package nia.engine.profile

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * C9 v1 (R0253, RES-0113, ADR-005): ajustes de los modulos POR PERFIL.
 * Almacen clave/valor por modulo (id -> clave -> valor) con el que el
 * host materializa el contrato de ajustes del lado modulo (E4): la
 * convencion configPath -> clave plana (p.ej. "general.enabled") se
 * guarda tal cual; la validacion todo-o-nada del esquema es del lado
 * modulo y NO vive aqui (el host solo consulta "general.enabled").
 *
 * Regla dura (E4, ADR-019): el perfil INCOGNITO monta ConfigStore(null)
 * y NADA persiste; el perfil NORMAL monta ConfigStore(File(dir,
 * "modulos-config.tsv")) con write-through best-effort (patron
 * modulos.tsv del gestor): cada mutacion reescribe el TSV y las lineas
 * corruptas se ignoran al cargar. La memoria viva es la fuente de
 * verdad de la sesion; un fallo de disco JAMAS rompe la navegacion.
 *
 * Limites explicitos (nada de memoria ilimitada): MAX_MODULOS modulos,
 * MAX_CLAVES claves por modulo, id y clave de 1..MAX_CAMPO chars en
 * [a-zA-Z0-9._-] y valor de 0..MAX_VALOR chars sin tab ni saltos de
 * linea (formato TSV seguro). Fuera de limites escribir responde
 * INVALID_INPUT SIN tocar el estado vivo.
 *
 * enabled(id): el ajuste maestro del modulo. Ausencia o cualquier
 * valor distinto del "false" exacto significa habilitado
 * (compatibilidad: un modulo sin ajustes se comporta igual que antes
 * de C9; solo el usuario apagandolo escribe "false").
 */
class ConfigStore(private val almacen: File?) {

    companion object {
        /** Tope de modulos con config viva. */
        const val MAX_MODULOS = 32

        /** Tope de claves por modulo. */
        const val MAX_CLAVES = 64

        /** Longitud maxima de id y clave. */
        const val MAX_CAMPO = 64

        /** Longitud maxima del valor. */
        const val MAX_VALOR = 512

        /** Ajuste maestro del modulo (contrato E4/E2, lado host). */
        const val CLAVE_ENABLED = "general.enabled"
    }

    private val modulos = LinkedHashMap<String, LinkedHashMap<String, String>>()

    init {
        cargar()
    }

    /** Escribe un par validado con write-through best-effort. */
    fun escribir(id: String, clave: String, valor: String): Outcome<Unit> {
        if (!campoValido(id)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "id de modulo invalido"))
        }
        if (!campoValido(clave)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "clave invalida"))
        }
        if (!valorValido(valor)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "valor invalido"))
        }
        val pares = modulos[id]
        if (pares == null) {
            if (modulos.size >= MAX_MODULOS) {
                return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "demasiados modulos con ajustes"))
            }
            modulos[id] = LinkedHashMap()
        } else if (!pares.containsKey(clave) && pares.size >= MAX_CLAVES) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "demasiadas claves para el modulo"))
        }
        modulos[id]!!.put(clave, valor)
        persistir()
        return Outcome.success(Unit)
    }

    /** Lee un par; null si no existe. */
    fun leer(id: String, clave: String): String? = modulos[id]?.get(clave)

    /** Copia de los pares vivos del modulo; mapa vacio sin config. */
    fun entradas(id: String): Map<String, String> = LinkedHashMap(modulos[id] ?: emptyMap())

    /** Ajuste maestro: solo el "false" exacto deshabilita. */
    fun enabled(id: String): Boolean = leer(id, CLAVE_ENABLED) != "false"

    /** Borra la config del modulo; devuelve cuantas claves murieron. */
    fun borrar(id: String): Int {
        val pares = modulos.remove(id) ?: return 0
        persistir()
        return pares.size
    }

    /** Borra TODO; devuelve cuantos pares murieron (write-through). */
    fun limpiar(): Int {
        var n = 0
        for (pares in modulos.values) n += pares.size
        modulos.clear()
        persistir()
        return n
    }

    /** Carga el TSV: lineas corruptas o fuera de limites se ignoran. */
    private fun cargar() {
        val archivo = almacen ?: return
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
            val clave = partes[1]
            val valor = partes[2]
            if (!campoValido(id) || !campoValido(clave) || !valorValido(valor)) continue
            val pares = modulos[id]
            if (pares == null) {
                if (modulos.size >= MAX_MODULOS) continue
                modulos[id] = LinkedHashMap()
            } else if (!pares.containsKey(clave) && pares.size >= MAX_CLAVES) continue
            modulos[id]!!.put(clave, valor)
        }
    }

    /** Reescribe el TSV completo; sin almacen (incognito) no toca nada. */
    private fun persistir() {
        val archivo = almacen ?: return
        val sb = StringBuilder()
        for ((id, pares) in modulos) {
            for ((clave, valor) in pares) {
                sb.append(id).append('\t').append(clave).append('\t').append(valor).append('\n')
            }
        }
        try {
            archivo.parentFile?.mkdirs()
            archivo.writeText(sb.toString())
        } catch (noEscribible: Exception) {
            // best-effort: la memoria viva manda
        }
    }

    private fun campoValido(s: String): Boolean =
        s.isNotEmpty() && s.length <= MAX_CAMPO &&
            s.all { c ->
                c == '.' || c == '-' || c == '_' ||
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9')
            }

    private fun valorValido(v: String): Boolean =
        v.length <= MAX_VALOR && !v.contains('\t') && !v.contains('\n') && !v.contains('\r')
}
