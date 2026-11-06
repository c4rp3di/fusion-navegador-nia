package nia.engine.history

import java.io.File

import nia.contracts.WallClock

/**
 * T074 v1 (R0178, RES-0082): una visita del historial, SOLO del perfil
 * NORMAL (el incognito JAMAS se registra: D-001/ADR-019). La fecha es
 * epochMillis y visitas cuenta cuantas veces se cargo la misma URL.
 */
data class EntradaHistorial(
    val id: Long,
    val url: String,
    val titulo: String,
    val visitas: Int,
    val fecha: Long
)

/**
 * HistorialManager puro JVM (T074 v1): historial global del perfil
 * NORMAL, separado del session history de cada pestana (el
 * back/forward vive en el NavController de cada pestana, T071).
 *
 * Redaccion por diseno: la URL se guarda SIEMPRE sin query ni
 * fragmento (lo sensible de una URL viaja en la query: tokens,
 * busquedas y sesiones) y las paginas internas (about:, data:) o de
 * otro esquema jamas se registran; el incognito no llega aqui
 * porque el shell solo registra las pestanas NORMAL.
 *
 * Presupuesto explicito: tope de entradas unicas con desalojo de la
 * de fecha mas vieja (sin memoria ilimitada).
 *
 * Persistencia tabulada por linea (una linea por entrada, escapada
 * como los marcadores de T060): el estado NUNCA entra roto,
 * importar parsea a estructuras temporales y solo hace el swap si
 * TODO vale, y cada mutacion guarda. Un archivo corrupto deja el
 * estado vivo intacto. Esquema version 1 (la migracion de T007
 * llega si cambia).
 */
class HistoryManager(
    private val reloj: WallClock,
    private val archivo: File? = null,
    private val maxEntradas: Int = MAX_ENTRADAS
) {

    private val entradas = LinkedHashMap<String, EntradaHistorial>()
    private var siguienteId = 1L

    init {
        val texto = leer()
        if (texto != null) {
            importar(texto)
        }
    }

    /** Cuantas entradas unicas hay. */
    fun contar(): Int = entradas.size

    /** Todas las entradas, la mas reciente primero. */
    fun entradas(): List<EntradaHistorial> =
        entradas.values.sortedByDescending { it.fecha }

    /** Registra una visita (dedup por URL normalizada); false si la
     * URL no es registrable: interna (about:, data:) o no http/https. */
    fun registrar(url: String, titulo: String): Boolean {
        val limpia = normaliza(url) ?: return false
        val ahora = reloj.nowMillis()
        val tituloFinal = (if (titulo.isEmpty()) limpia else titulo).take(MAX_CAMPO)
        val previa = entradas[limpia]
        if (previa != null) {
            entradas[limpia] =
                EntradaHistorial(previa.id, limpia, tituloFinal, previa.visitas + 1, ahora)
        } else {
            desalojaSiLleno()
            entradas[limpia] = EntradaHistorial(siguienteId, limpia, tituloFinal, 1, ahora)
            siguienteId += 1
        }
        guardar()
        return true
    }

    /** Busca en URL y titulo, case-insensitive; recientes primero. */
    fun buscar(q: String): List<EntradaHistorial> {
        val n = q.lowercase()
        return entradas().filter {
            it.url.lowercase().contains(n) || it.titulo.lowercase().contains(n)
        }
    }

    /** Borra la entrada con ese id; false si no existe. */
    fun borrar(id: Long): Boolean {
        val clave = entradas.entries.firstOrNull { it.value.id == id }?.key
        if (clave == null) {
            return false
        }
        entradas.remove(clave)
        guardar()
        return true
    }

    /** Borra las entradas con fecha en [desde, hasta]; devuelve cuantas. */
    fun borrarRango(desde: Long, hasta: Long): Int {
        val claves = entradas.entries
            .filter { it.value.fecha >= desde && it.value.fecha <= hasta }
            .map { it.key }
        for (c in claves) {
            entradas.remove(c)
        }
        if (claves.isNotEmpty()) {
            guardar()
        }
        return claves.size
    }

    /** Borra TODO el historial (Fire total del perfil NORMAL, T045):
     * devuelve cuantas murieron y el gestor queda listo. */
    fun limpiar(): Int {
        val n = entradas.size
        entradas.clear()
        guardar()
        return n
    }

    /** Exporta el estado completo (esquema version 1). */
    fun exportar(): String {
        val sb = StringBuilder()
        sb.append("nia-history\t1")
        for (e in entradas()) {
            sb.append('\n')
            sb.append("H\t")
            sb.append(e.id)
            sb.append('\t')
            sb.append(e.visitas)
            sb.append('\t')
            sb.append(e.fecha)
            sb.append('\t')
            sb.append(escapa(e.url))
            sb.append('\t')
            sb.append(escapa(e.titulo))
        }
        return sb.toString()
    }

    /** Importa un export: SOLO hace swap si TODO parsea; un archivo
     * corrupto deja el estado vivo intacto. */
    fun importar(texto: String): Boolean {
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-history\t1") {
            return false
        }
        val tmp = LinkedHashMap<String, EntradaHistorial>()
        var maxId = 0L
        for (i in 1 until lineas.size) {
            val p = lineas[i].split('\t')
            if (p.size != 6 || p[0] != "H") {
                return false
            }
            val id = p[1].toLongOrNull()
            val visitas = p[2].toIntOrNull()
            val fecha = p[3].toLongOrNull()
            if (id == null || visitas == null || fecha == null) {
                return false
            }
            if (id < 1L || visitas < 1 || fecha < 0L) {
                return false
            }
            val url = desescapa(p[4])
            val titulo = desescapa(p[5])
            if (url == null || titulo == null) {
                return false
            }
            if (normaliza(url) != url) {
                return false
            }
            if (titulo.isEmpty() || titulo.length > MAX_CAMPO) {
                return false
            }
            if (tmp.containsKey(url)) {
                return false
            }
            tmp[url] = EntradaHistorial(id, url, titulo, visitas, fecha)
            if (id > maxId) {
                maxId = id
            }
        }
        entradas.clear()
        entradas.putAll(tmp)
        siguienteId = maxId + 1L
        aplicarPresupuesto()
        guardar()
        return true
    }

    companion object {
        const val MAX_ENTRADAS = 500
        const val MAX_CAMPO = 64
        private const val DIA_MILLIS = 86400000L

        /** Dia UTC de una fecha, para agrupar por dia en el shell. */
        fun diaDe(fecha: Long): Long = fecha / DIA_MILLIS
    }

    /** Normaliza la URL para el historial: sin fragmento ni query y
     * solo http/https; null si no es registrable. */
    private fun normaliza(url: String): String? {
        val sinFragmento = url.substringBefore('#')
        val sinQuery = sinFragmento.substringBefore('?')
        if (!sinQuery.startsWith("http://") && !sinQuery.startsWith("https://")) {
            return null
        }
        return sinQuery
    }

    private fun desalojaSiLleno() {
        while (entradas.size >= maxEntradas) {
            if (!expulsaLaMasVieja()) {
                return
            }
        }
    }

    private fun aplicarPresupuesto() {
        while (entradas.size > maxEntradas) {
            if (!expulsaLaMasVieja()) {
                return
            }
        }
    }

    private fun expulsaLaMasVieja(): Boolean {
        val vieja = entradas.entries.minByOrNull { it.value.fecha }
        if (vieja == null) {
            return false
        }
        entradas.remove(vieja.key)
        return true
    }

    private fun guardar() {
        val f = archivo ?: return
        try {
            f.writeText(exportar())
        } catch (e: Exception) {
            // Sin disco: el historial sigue vivo en memoria.
        }
    }

    private fun leer(): String? {
        val f = archivo ?: return null
        if (!f.isFile) {
            return null
        }
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun escapa(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch == '\\') sb.append("\\\\")
            else if (ch == '\t') sb.append("\\t")
            else if (ch == '\n') sb.append("\\n")
            else if (ch == '\r') sb.append("\\r")
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun desescapa(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') {
                sb.append(ch)
                i += 1
                continue
            }
            if (i + 1 >= s.length) {
                return null
            }
            when (s[i + 1]) {
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
