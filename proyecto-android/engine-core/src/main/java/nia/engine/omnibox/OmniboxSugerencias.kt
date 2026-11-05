package nia.engine.omnibox

import nia.engine.bookmarks.BookmarkManager

/**
 * OmniboxSugerencias (T072 v1, RES-0075, R0155, ADR-022): sugerencias del
 * omnibox SIEMPRE locales, sin red ni autocompletado remoto ni telemetria.
 * Marcadores primero (BookmarkManager.buscar), historial del llamador en
 * orden mas-reciente-primero y silenciado en incognito (ADR-019); el orden
 * es coincidencia exacta, prefijo y resto (subcadena), con dedup por URL
 * (gana el marcador) y tope pequeno. Espejo del gemelo TS validado offline
 * (2 corridas identicas, huella 9822.26f4.982, 34 escenarios).
 */
enum class Origen { MARCADOR, HISTORIAL }

data class Sugerencia(val titulo: String, val url: String, val origen: Origen)

object OmniboxSugerencias {

    fun sugerencias(
        texto: String,
        marcadores: BookmarkManager?,
        historial: List<String>,
        incognito: Boolean,
        tope: Int = 8
    ): List<Sugerencia> {
        val q = texto.trim()
        if (q.isEmpty() || tope <= 0) return emptyList()
        val candidatos = ArrayList<Sugerencia>()
        if (marcadores != null) {
            for (b in marcadores.buscar(q)) {
                candidatos.add(Sugerencia(b.titulo, b.url, Origen.MARCADOR))
            }
        }
        val min = q.lowercase()
        if (!incognito) {
            for (u in historial) {
                if (u.lowercase().contains(min)) {
                    candidatos.add(Sugerencia(u, u, Origen.HISTORIAL))
                }
            }
        }
        val vistos = HashSet<String>()
        val exactas = ArrayList<Sugerencia>()
        val prefijos = ArrayList<Sugerencia>()
        val resto = ArrayList<Sugerencia>()
        for (c in candidatos) {
            if (!vistos.add(c.url)) continue
            val t = c.titulo.lowercase()
            val u = c.url.lowercase()
            when {
                t == min || u == min -> exactas.add(c)
                t.startsWith(min) || u.startsWith(min) -> prefijos.add(c)
                else -> resto.add(c)
            }
        }
        val salida = ArrayList<Sugerencia>(exactas.size + prefijos.size + resto.size)
        salida.addAll(exactas)
        salida.addAll(prefijos)
        salida.addAll(resto)
        return if (salida.size > tope) salida.subList(0, tope).toList() else salida
    }
}
