package nia.engine.modules

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

import nia.engine.net.FetchManager
import nia.engine.net.FetchTransport

import nia.engine.profile.ConfigStore

/**
 * C7+C8 v1 (R0255, RES-0114, E4/ADR-005): listas del host para los
 * modulos. El modulo JAMAS descarga (sin red directa, E4): el HOST
 * trae las listas con FetchManager y cuotas por modulo y la
 * denegacion por cuota se DISTINGUE del error de red en el informe.
 *
 * Fuentes por modulo (id -> url -> intervaloHoras) con TSV
 * write-through best-effort (patron modulos.tsv/ConfigStore): cada
 * mutacion reescribe listas.tsv y las lineas corruptas se ignoran al
 * cargar. Solo https SIN credenciales (userinfo rechazado). El
 * intervalo por defecto (24 h) es la propuesta A MEDIR del E4: el
 * modulo dice cada cuanto le GUSTARIA y el HOST decide cuando.
 *
 * C8: el estado de actualizacion vive en la config del PERFIL (C9:
 * listas.<nombre>.actualizada = epoch millis y listas.<nombre>.bytes);
 * sin config (sin perfil vivo) no hay planificacion persistida. El
 * contenido descargado se escribe bajo contenido/<id>/<nombre>.txt y
 * alimentara al matcher del host (T051): el entry del modulo NO puede
 * leerlo hoy (api-version 1, E4).
 *
 * Limites v1 (A MEDIR; el tope de 80 MB / 500.000 reglas propuesto
 * por el modulo NO esta aprobado): MAX_LISTAS_POR_MODULO fuentes,
 * MAX_BYTES_LISTA por lista (comprobado TRAS la descarga: el
 * transporte sincrono v1 no acota en vuelo) y MAX_MODULOS_CON_LISTAS.
 * Sobre el tope la lista se DESCARTA (ni contenido ni estado) y el
 * informe lo separa como cuota, no como error de red.
 */
data class ListaFuente(val url: String, val intervaloHoras: Int)

/** Resultado por fuente: Ok/Cuota/Fallo distinguibles (E4). */
sealed class ListaResultado {
    data class Ok(val nombre: String, val url: String, val bytes: Int) : ListaResultado()
    data class Cuota(val nombre: String, val url: String, val motivo: String) : ListaResultado()
    data class Fallo(val nombre: String, val url: String, val motivo: String) : ListaResultado()
}

/** Informe de una tanda: conteos por tipo y resumen para el shell. */
class ListasInforme(val resultados: List<ListaResultado>) {
    val ok: List<ListaResultado.Ok> = resultados.filterIsInstance<ListaResultado.Ok>()
    val cuota: List<ListaResultado.Cuota> = resultados.filterIsInstance<ListaResultado.Cuota>()
    val fallo: List<ListaResultado.Fallo> = resultados.filterIsInstance<ListaResultado.Fallo>()

    fun resumen(): String =
        ok.size.toString() + " ok, " + cuota.size + " por cuota, " + fallo.size + " con fallo"
}

class ListasModulos(private val almacen: File?) {

    companion object {
        /** Tope de fuentes por modulo (A MEDIR). */
        const val MAX_LISTAS_POR_MODULO = 8

        /** Tope de bytes por lista (A MEDIR; 80 MB propuesto NO aprobado). */
        const val MAX_BYTES_LISTA = 2 * 1024 * 1024

        /** Tope de modulos con listas registradas. */
        const val MAX_MODULOS_CON_LISTAS = 32

        /** Longitud maxima de la URL. */
        const val MAX_URL = 512

        /** Intervalo por defecto: la propuesta A MEDIR del E4 (24 h). */
        const val INTERVALO_DEFECTO_HORAS = 24

        const val INTERVALO_MIN_HORAS = 1

        const val INTERVALO_MAX_HORAS = 720

        /** Nombre estable de archivo de una fuente (djb2 hex de la url). */
        fun nombreDe(url: String): String {
            var h = 5381
            for (c in url) {
                h = h * 33 + c.code
            }
            return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        }
    }

    private val modulos = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    init {
        cargar()
    }

    /** Registra una fuente validada con write-through; re-registrar
     *  la misma url actualiza su intervalo. */
    fun registrar(
        id: String,
        url: String,
        intervaloHoras: Int = INTERVALO_DEFECTO_HORAS
    ): Outcome<Unit> {
        if (!idValido(id)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "id de modulo invalido"))
        }
        if (!urlValida(url)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "la lista debe ser https sin credenciales"))
        }
        if (intervaloHoras < INTERVALO_MIN_HORAS || intervaloHoras > INTERVALO_MAX_HORAS) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "intervalo fuera de rango"))
        }
        val pares = modulos[id]
        if (pares == null) {
            if (modulos.size >= MAX_MODULOS_CON_LISTAS) {
                return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "demasiados modulos con listas"))
            }
            modulos[id] = LinkedHashMap()
        } else if (!pares.containsKey(url) && pares.size >= MAX_LISTAS_POR_MODULO) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "demasiadas listas para el modulo"))
        }
        modulos[id]!!.put(url, intervaloHoras)
        persistir()
        return Outcome.success(Unit)
    }

    /** Copia de las fuentes del modulo en orden de registro. */
    fun fuentes(id: String): List<ListaFuente> {
        val pares = modulos[id] ?: return emptyList()
        return pares.map { (url, intervalo) -> ListaFuente(url, intervalo) }
    }

    /** Quita una fuente; 1 si existia, 0 si no (write-through). */
    fun quitar(id: String, url: String): Int {
        val pares = modulos[id] ?: return 0
        if (pares.remove(url) == null) return 0
        if (pares.isEmpty()) modulos.remove(id)
        persistir()
        return 1
    }

    /** Nombres de las fuentes vencidas a ahoraMillis (planificador C8). */
    fun haceFaltaActualizar(id: String, ahoraMillis: Long, config: ConfigStore?): List<String> {
        val pares = modulos[id] ?: return emptyList()
        val vencidas = ArrayList<String>()
        for ((url, intervalo) in pares) {
            val nombre = nombreDe(url)
            if (vencida(id, nombre, intervalo, ahoraMillis, config)) {
                vencidas.add(nombre)
            }
        }
        return vencidas
    }

    /** Descarga TODAS las fuentes del modulo (accion del usuario). */
    fun actualizarAhora(
        id: String,
        transport: FetchTransport,
        config: ConfigStore?,
        ahoraMillis: Long
    ): ListasInforme = actualizar(id, transport, config, ahoraMillis, false)

    /** Descarga SOLO las fuentes vencidas (planificador C8: el host
     *  decide cuando mirar; sin disparos en nombre del modulo). */
    fun actualizarVencidas(
        id: String,
        transport: FetchTransport,
        config: ConfigStore?,
        ahoraMillis: Long
    ): ListasInforme = actualizar(id, transport, config, ahoraMillis, true)

    private fun actualizar(
        id: String,
        transport: FetchTransport,
        config: ConfigStore?,
        ahoraMillis: Long,
        soloVencidas: Boolean
    ): ListasInforme {
        val pares = modulos[id]
        if (pares == null || pares.isEmpty()) return ListasInforme(emptyList())
        val manager = FetchManager(transport)
        val resultados = ArrayList<ListaResultado>()
        for ((url, intervalo) in pares) {
            val nombre = nombreDe(url)
            if (soloVencidas && !vencida(id, nombre, intervalo, ahoraMillis, config)) continue
            val out = manager.fetch(manager.newRequest("GET", url))
            when (out) {
                is Outcome.Failure -> resultados.add(ListaResultado.Fallo(nombre, url, out.error.safeMessage))
                is Outcome.Success -> {
                    val resp = out.value
                    if (resp.status != 200) {
                        resultados.add(ListaResultado.Fallo(nombre, url, "estado " + resp.status))
                    } else if (resp.body.size > MAX_BYTES_LISTA) {
                        resultados.add(
                            ListaResultado.Cuota(
                                nombre, url,
                                "lista de " + resp.body.size + " bytes sobre el tope de " + MAX_BYTES_LISTA
                            )
                        )
                    } else if (!guardarContenido(id, nombre, resp.body)) {
                        resultados.add(ListaResultado.Fallo(nombre, url, "no se pudo guardar la lista en disco"))
                    } else {
                        if (config != null) {
                            config.escribir(id, "listas." + nombre + ".actualizada", ahoraMillis.toString())
                            config.escribir(id, "listas." + nombre + ".bytes", resp.body.size.toString())
                        }
                        resultados.add(ListaResultado.Ok(nombre, url, resp.body.size))
                    }
                }
            }
        }
        return ListasInforme(resultados)
    }

    private fun vencida(
        id: String,
        nombre: String,
        intervaloHoras: Int,
        ahoraMillis: Long,
        config: ConfigStore?
    ): Boolean {
        if (config == null) return true
        val previo = config.leer(id, "listas." + nombre + ".actualizada") ?: return true
        val epoch = previo.toLongOrNull() ?: return true
        return ahoraMillis - epoch >= intervaloHoras * 3_600_000L
    }

    /** Escribe el contenido bajo contenido/<id>/<nombre>.txt; sin
     *  almacen (memoria) cuenta como Ok sin tocar disco. */
    private fun guardarContenido(id: String, nombre: String, cuerpo: ByteArray): Boolean {
        val raiz = almacen ?: return true
        return try {
            val destino = File(File(File(raiz, "contenido"), id), nombre + ".txt")
            destino.parentFile?.mkdirs()
            destino.writeBytes(cuerpo)
            true
        } catch (noEscribible: Exception) {
            false
        }
    }

    /** Carga el TSV: lineas corruptas o fuera de limites se ignoran. */
    private fun cargar() {
        val archivo = archivoRuta() ?: return
        val texto = try {
            archivo.readText()
        } catch (noLegible: Exception) {
            return
        }
        for (linea in texto.split('\n')) {
            if (linea.isEmpty()) continue
            val partes = linea.split('\t')
            if (partes.size != 3) continue
            val id = partes[0]
            val url = partes[1]
            val intervalo = partes[2].toIntOrNull() ?: continue
            if (!idValido(id) || !urlValida(url)) continue
            if (intervalo < INTERVALO_MIN_HORAS || intervalo > INTERVALO_MAX_HORAS) continue
            val pares = modulos[id]
            if (pares == null) {
                if (modulos.size >= MAX_MODULOS_CON_LISTAS) continue
                modulos[id] = LinkedHashMap()
            } else if (!pares.containsKey(url) && pares.size >= MAX_LISTAS_POR_MODULO) continue
            modulos[id]!!.put(url, intervalo)
        }
    }

    /** Reescribe el TSV completo; sin almacen no toca nada. */
    private fun persistir() {
        val archivo = archivoRuta() ?: return
        val sb = StringBuilder()
        for ((id, pares) in modulos) {
            for ((url, intervalo) in pares) {
                sb.append(id).append('\t').append(url).append('\t').append(intervalo).append('\n')
            }
        }
        try {
            archivo.parentFile?.mkdirs()
            archivo.writeText(sb.toString())
        } catch (noEscribible: Exception) {
            // best-effort: la memoria viva manda
        }
    }

    private fun archivoRuta(): File? = almacen?.let { File(it, "listas.tsv") }

    private fun idValido(s: String): Boolean =
        s.isNotEmpty() && s.length <= 64 &&
            s.all { c ->
                c == '.' || c == '-' || c == '_' ||
                    (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
            }

    /** Solo https, autoridad no vacia y SIN credenciales (userinfo). */
    private fun urlValida(url: String): Boolean {
        if (url.length < 9 || url.length > MAX_URL) return false
        if (!url.startsWith("https://")) return false
        if (url.contains(' ') || url.contains('\t') || url.contains('\n')) return false
        val resto = url.substring("https://".length)
        val corte = resto.indexOf('/')
        val autoridad = if (corte < 0) resto else resto.substring(0, corte)
        if (autoridad.isEmpty()) return false
        if (autoridad.contains('@')) return false
        return true
    }
}
