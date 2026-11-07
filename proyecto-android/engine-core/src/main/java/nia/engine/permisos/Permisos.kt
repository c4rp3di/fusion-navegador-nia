package nia.engine.permisos

import java.io.File

/**
 * T089 v1 (RES-0109): broker de PERMISOS POR SITIO. Un permiso
 * (camara, microfono, geolocalizacion, notificaciones, portapapeles)
 * solo se puede solicitar si el shell registro el backend REAL con
 * marcarDisponible: una API sin backend JAMAS se anuncia habilitada
 * (se deniega tipificada NO_DISPONIBLE, sin prompt). La identidad
 * del sitio es la clave de ORIGEN (scheme://host:puerto efectivo)
 * que calcula el shell con UrlParser/Origin del motor: NUNCA la
 * URL completa. Las decisiones son PERMITIDO/DENEGADO con ambito
 * SESION (vive mientras la sesion del perfil) o PERMANENTE
 * (persiste en permisos.tsv); sin decision la solicitud pide
 * prompt y la UI privilegiada responde con la GENERACION de la
 * solicitud: si la generacion cambio (revocacion o fin de sesion)
 * la respuesta es STALE y NO se aplica. Revocar borra la decision
 * y sube la generacion: el acceso se corta EN SEGUIDA porque esta
 * tabla es la unica fuente (nada queda cacheado). El background
 * JAMAS pregunta (denegado tipificado BACKGROUND), mismo caracter
 * que las denegaciones tipificadas del Broker de autorizacion
 * (T006). Doble nivel: el nivel ANDROID lo declara el shell al
 * registrar el backend (solo si el permiso del sistema esta
 * concedido); el nivel WEB es esta tabla por origen.
 */
enum class TipoPermiso { CAMARA, MICROFONO, GEOLOCALIZACION, NOTIFICACIONES, PORTAPAPELES }

/** Ambito de una decision: SESION vive hasta el fin de sesion, PERMANENTE persiste. */
enum class AmbitoPermiso { SESION, PERMANENTE }

/** Fallos tipificados de una solicitud denegada (patron del Broker T006). */
enum class FalloPermiso { NO_DISPONIBLE, ORIGEN_INVALIDO, BACKGROUND }

/** Respuesta SIEMPRE tipificada; prompt solo en primer plano y con backend. */
data class ResultadoPermiso(
    val concedido: Boolean,
    val fallo: FalloPermiso?,
    val prompt: Boolean,
    val ambito: AmbitoPermiso?,
    val generacion: Long
)

/** Decision guardada, para el dialogo del shell. */
data class FilaPermiso(
    val clave: String,
    val tipo: TipoPermiso,
    val permitido: Boolean,
    val ambito: AmbitoPermiso
)

/**
 * Registro de decisiones por sitio (patron de SitiosManager):
 * export/import tabulado version 1 ("nia-permisos<TAB>1" + lineas
 * "P<TAB>clave<TAB>TIPO<TAB>PERMITIDO|DENEGADO", SOLO permanentes;
 * la clave de origen nunca trae tab ni saltos porque claveValida
 * los rechaza, asi que el TSV es seguro SIN escape), import
 * TODO-o-NADA (un fallo no toca el estado vivo), archivo opcional
 * (sin disco el estado vive en memoria) y un corrupto se ignora.
 * Limites honestos: MAX_CLAVE por clave y MAX_DECISIONES con
 * expulsion de la mas vieja (re-fijar rejuvenece).
 */
class GestorPermisos(private val archivo: File? = null) {

    private class Entrada(val permitido: Boolean, val ambito: AmbitoPermiso, val sesion: Long)

    companion object {
        const val MAX_CLAVE = 200
        const val MAX_DECISIONES = 200
        const val CABECERA = "nia-permisos\t1"
    }

    private val decisiones = LinkedHashMap<String, LinkedHashMap<TipoPermiso, Entrada>>()
    private val disponibles = HashSet<TipoPermiso>()
    private var generacion = 0L
    private var sesionActual = 0L

    /** El shell registra SOLO backends reales; sin registro no hay API. */
    @Synchronized
    fun marcarDisponible(tipo: TipoPermiso, disponible: Boolean) {
        if (disponible) disponibles.add(tipo) else disponibles.remove(tipo)
    }

    /** Cierto si el backend real del tipo esta registrado. */
    @Synchronized
    fun disponible(tipo: TipoPermiso): Boolean = disponibles.contains(tipo)

    /** Cuantas decisiones hay guardadas (de sesion y permanentes). */
    @Synchronized
    fun total(): Int = contarTodos()

    /** Las decisiones guardadas en orden (la mas vieja primero). */
    @Synchronized
    fun listar(): List<FilaPermiso> {
        val filas = ArrayList<FilaPermiso>()
        for ((clave, porTipo) in decisiones) {
            for ((tipo, e) in porTipo) {
                filas.add(FilaPermiso(clave, tipo, e.permitido, e.ambito))
            }
        }
        return filas
    }

    /**
     * Estado de una solicitud, SIN preguntar jamas en background y
     * sin anunciar APIs sin backend. Con decision vigente devuelve
     * PERMITIDO/DENEGADO sin prompt; sin decision pide prompt con
     * la generacion actual para que la UI responda con ella.
     */
    @Synchronized
    fun solicitar(clave: String, tipo: TipoPermiso, primerPlano: Boolean): ResultadoPermiso {
        if (!disponible(tipo)) {
            return ResultadoPermiso(false, FalloPermiso.NO_DISPONIBLE, false, null, generacion)
        }
        if (!claveValida(clave)) {
            return ResultadoPermiso(false, FalloPermiso.ORIGEN_INVALIDO, false, null, generacion)
        }
        if (!primerPlano) {
            return ResultadoPermiso(false, FalloPermiso.BACKGROUND, false, null, generacion)
        }
        val e = decisiones[clave]?.get(tipo)
        if (e != null) {
            if (e.ambito == AmbitoPermiso.PERMANENTE || e.sesion == sesionActual) {
                return ResultadoPermiso(e.permitido, null, false, e.ambito, generacion)
            }
            val porTipo = decisiones[clave]
            if (porTipo != null) {
                porTipo.remove(tipo)
                if (porTipo.isEmpty()) decisiones.remove(clave)
            }
        }
        return ResultadoPermiso(false, null, true, null, generacion)
    }

    /**
     * Aplica la respuesta de la UI privilegiada: SOLO si la
     * generacion sigue siendo la de la solicitud (si cambio, la
     * respuesta es STALE y no se aplica), el backend sigue
     * registrado y la clave es un origen valido.
     */
    @Synchronized
    fun registrar(clave: String, tipo: TipoPermiso, permitido: Boolean, ambito: AmbitoPermiso, generacionRespuesta: Long): Boolean {
        if (generacionRespuesta != generacion) return false
        if (!disponible(tipo)) return false
        if (!claveValida(clave)) return false
        var porTipo = decisiones[clave]
        if (porTipo == null) {
            porTipo = LinkedHashMap()
            decisiones[clave] = porTipo
        }
        porTipo.remove(tipo)
        porTipo[tipo] = Entrada(permitido, ambito, sesionActual)
        expulsarSiHaceFalta()
        if (ambito == AmbitoPermiso.PERMANENTE) guardar()
        return true
    }

    /** Revoca la decision del sitio: vuelve a preguntar y corta el acceso EN SEGUIDA. */
    @Synchronized
    fun revocar(clave: String, tipo: TipoPermiso): Boolean {
        val porTipo = decisiones[clave] ?: return false
        val had = porTipo.remove(tipo) != null
        if (porTipo.isEmpty()) decisiones.remove(clave)
        if (had) {
            generacion += 1
            guardar()
        }
        return had
    }

    /** Revoca TODAS las decisiones; devuelve cuantas habia. */
    @Synchronized
    fun revocarTodo(): Int {
        val n = contarTodos()
        if (n > 0) {
            decisiones.clear()
            generacion += 1
            guardar()
        }
        return n
    }

    /**
     * Fin de la sesion del perfil: las decisiones de SESION mueren
     * (vuelven a preguntar) y la generacion invalida las respuestas
     * de dialogos en vuelo. Devuelve cuantas murieron.
     */
    @Synchronized
    fun finDeSesion(): Int {
        var n = 0
        val claves = ArrayList(decisiones.keys)
        for (clave in claves) {
            val porTipo = decisiones[clave] ?: continue
            val tipos = ArrayList(porTipo.keys)
            for (t in tipos) {
                val e = porTipo[t]
                if (e != null && e.ambito == AmbitoPermiso.SESION) {
                    porTipo.remove(t)
                    n += 1
                }
            }
            if (porTipo.isEmpty()) decisiones.remove(clave)
        }
        if (n > 0) generacion += 1
        return n
    }

    /** Export tabulado version 1: SOLO decisiones permanentes. */
    @Synchronized
    fun exportar(): String {
        val sb = StringBuilder()
        sb.append(CABECERA)
        for ((clave, porTipo) in decisiones) {
            for ((tipo, e) in porTipo) {
                if (e.ambito != AmbitoPermiso.PERMANENTE) continue
                sb.append('\n')
                sb.append("P\t")
                sb.append(clave)
                sb.append('\t')
                sb.append(tipo.name)
                sb.append('\t')
                sb.append(if (e.permitido) "PERMITIDO" else "DENEGADO")
            }
        }
        return sb.toString()
    }

    /** Import TODO-o-NADA: un fallo deja el estado vivo intacto. */
    @Synchronized
    fun importar(texto: String): Boolean {
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != CABECERA) return false
        val tmp = LinkedHashMap<String, LinkedHashMap<TipoPermiso, Entrada>>()
        var n = 0
        for (i in 1 until lineas.size) {
            if (lineas[i].isEmpty()) return false
            val partes = lineas[i].split('\t')
            if (partes.size != 4 || partes[0] != "P") return false
            if (!claveValida(partes[1])) return false
            val tipo = tipoDe(partes[2]) ?: return false
            if (partes[3] != "PERMITIDO" && partes[3] != "DENEGADO") return false
            var porTipo = tmp[partes[1]]
            if (porTipo == null) {
                porTipo = LinkedHashMap()
                tmp[partes[1]] = porTipo
            }
            if (porTipo.containsKey(tipo)) return false
            porTipo[tipo] = Entrada(partes[3] == "PERMITIDO", AmbitoPermiso.PERMANENTE, 0L)
            n += 1
            if (n > MAX_DECISIONES) return false
        }
        decisiones.clear()
        decisiones.putAll(tmp)
        guardar()
        return true
    }

    /** Recarga el archivo: un corrupto se ignora (queda el estado vivo). */
    @Synchronized
    fun recargar(): Boolean {
        val texto = leer() ?: return false
        return importar(texto)
    }

    private fun tipoDe(nombre: String): TipoPermiso? {
        for (t in TipoPermiso.values()) {
            if (t.name == nombre) return t
        }
        return null
    }

    private fun claveValida(clave: String): Boolean {
        if (clave.isEmpty() || clave.length > MAX_CLAVE) return false
        if (clave.contains('\t') || clave.contains('\n') || clave.contains('\r')) return false
        return true
    }

    private fun contarTodos(): Int {
        var n = 0
        for ((_, porTipo) in decisiones) {
            n += porTipo.size
        }
        return n
    }

    private fun expulsarSiHaceFalta() {
        var n = contarTodos()
        while (n > MAX_DECISIONES) {
            val primeraClave = decisiones.keys.firstOrNull() ?: return
            val porTipo = decisiones[primeraClave] ?: return
            val tipo = porTipo.keys.firstOrNull() ?: return
            porTipo.remove(tipo)
            if (porTipo.isEmpty()) decisiones.remove(primeraClave)
            n -= 1
        }
    }

    private fun guardar() {
        val f = archivo ?: return
        try {
            f.writeText(exportar())
        } catch (e: Exception) {
            // Sin disco: las decisiones siguen vivas en memoria.
        }
    }

    private fun leer(): String? {
        val f = archivo ?: return null
        if (!f.isFile) return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }
}
