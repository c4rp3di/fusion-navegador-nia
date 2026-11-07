package nia.engine.bookmarks

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome
import nia.contracts.valueOrNull

/**
 * T099 v1 (RES-0102): import/export de marcadores en formato Netscape
 * bookmarks HTML (el que producen Firefox y Chrome).
 *
 * La importacion parsea TODO el HTML a entradas temporales validadas
 * ANTES de tocar la coleccion: un archivo malformado o que exceda los
 * limites devuelve PARSE/QUOTA tipificado y el BookmarkManager queda
 * intacto (el fallo no altera la coleccion). La mezcla es un paso
 * posterior que deduplica por url y reutiliza carpetas por nombre bajo
 * el mismo padre.
 *
 * Exclusiones v1: solo titulo, url y carpeta (ADD_DATE/ICON se
 * ignoran al importar y no se emiten al exportar), los pins no viajan
 * en el HTML, el merge es por url exacta (no por titulo) y el parser
 * tolera listas sin cerrar (los navegadores tambien).
 */

/** Entrada temporal validada: titulo, url http/https y ruta de carpetas (vacia = raiz). */
data class EntradaHtml(val titulo: String, val url: String, val ruta: List<String>)

/** Salida del parseo: entradas validas y cuantas se descartaron. */
data class ImportHtml(val entradas: List<EntradaHtml>, val rechazados: Int)

/** Contadores de la mezcla sobre la coleccion viva. */
data class ResultadoMezcla(
    val agregados: Int,
    val duplicados: Int,
    val carpetas: Int,
    val fallidos: Int
)

/**
 * Parser y escritor Netscape HTML puro JVM: sin Android, sin red y
 * sin estado propio. Los safeMessage nunca repiten contenido crudo
 * del HTML de entrada.
 */
object MarcadoresHtml {

    /** Techo de tamano del HTML aceptado (256 KiB). */
    const val MAX_BYTES = 262144

    /** Techo de marcadores leidos (validos y rechazados). */
    const val MAX_ENTRADAS = 500

    /** Techo de carpetas anidadas. */
    const val MAX_PROFUNDIDAD = 8

    private val TOKEN_RE = Regex(
        "(?is)(?:<dt>\\s*)?<h3[^>]*>(.*?)</h3>" +
            "|(?:<dt>\\s*)?<a\\b[^>]*?\\shref=([\"'])(.*?)\\2[^>]*>(.*?)</a\\s*>" +
            "|<(dl)\\b[^>]*>" +
            "|</(dl)\\b[^>]*>"
    )
    private val URL_RE = Regex("^https?://\\S+$")

    /**
     * Parsea TODO a temporales: cada DT/A valido produce una
     * EntradaHtml con la ruta de H3 abiertos; los de esquema no
     * http/https o con titulo vacio o mayor que MAX_CAMPO se cuentan
     * en rechazados. Un H3 con nombre vacio o largo, o un limite
     * excedido, es fallo tipificado SIN tocar nada.
     */
    fun parsear(html: String): Outcome<ImportHtml> {
        if (html.length > MAX_BYTES) {
            return fallo(ErrorCode.QUOTA, "html demasiado grande: " + html.length)
        }
        val entradas = ArrayList<EntradaHtml>()
        var rechazados = 0
        var vistos = 0
        val ruta = ArrayDeque<String>()
        var pendiente: String? = null
        var abrioLista = false
        for (m in TOKEN_RE.findAll(html)) {
            val gH3 = m.groups[1]
            val gHref = m.groups[3]
            val gAbre = m.groups[5]
            if (gH3 != null) {
                val nombre = decodificar(gH3.value).trim()
                if (nombre.isEmpty() || nombre.length > BookmarkManager.MAX_CAMPO) {
                    return fallo(ErrorCode.INVALID_INPUT, "carpeta no valida en el html")
                }
                pendiente = nombre
            } else if (gHref != null) {
                vistos += 1
                if (vistos > MAX_ENTRADAS) {
                    return fallo(ErrorCode.QUOTA, "demasiadas entradas en el html")
                }
                val url = decodificar(gHref.value).trim()
                val titulo = decodificar(m.groups[4]?.value ?: "").trim()
                if (!URL_RE.matches(url) || titulo.isEmpty() || titulo.length > BookmarkManager.MAX_CAMPO) {
                    rechazados += 1
                } else {
                    entradas.add(EntradaHtml(titulo, url, ArrayList(ruta)))
                }
            } else if (gAbre != null) {
                abrioLista = true
                val p = pendiente
                if (p != null) {
                    if (ruta.size >= MAX_PROFUNDIDAD) {
                        return fallo(ErrorCode.QUOTA, "profundidad excesiva en el html")
                    }
                    ruta.addLast(p)
                    pendiente = null
                }
            } else {
                if (ruta.isNotEmpty()) ruta.removeLast()
            }
        }
        if (!abrioLista) {
            return fallo(ErrorCode.PARSE, "html sin listas de marcadores")
        }
        return Outcome.success(ImportHtml(entradas, rechazados))
    }

    /** Escribe la coleccion viva como Netscape HTML (sin pins). */
    fun escribir(manager: BookmarkManager): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-\">\n")
        sb.append("<TITLE>Marcadores</TITLE>\n")
        sb.append("<H1>Marcadores</H1>\n")
        sb.append("<DL><p>\n")
        escribirHijos(manager, "root", sb)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun escribirHijos(manager: BookmarkManager, carpeta: String, sb: StringBuilder) {
        val hijos = manager.hijos(carpeta).valueOrNull ?: return
        for (id in hijos) {
            val bm = manager.marcadorDe(id)
            if (bm != null) {
                sb.append("<DT><A HREF=\"").append(escapar(bm.url)).append("\">")
                sb.append(escapar(bm.titulo)).append("</A>\n")
            } else {
                val cf = manager.carpetaDe(id) ?: continue
                sb.append("<DT><H3>").append(escapar(cf.nombre)).append("</H3>\n")
                sb.append("<DL><p>\n")
                escribirHijos(manager, cf.id, sb)
                sb.append("</DL><p>\n")
            }
        }
    }

    /**
     * Mezcla las entradas validadas en la coleccion viva: deduplica
     * por url exacta contra lo ya instalado, reutiliza carpetas por
     * nombre bajo el mismo padre y crea las que falten. Un add que
     * falle (defensa: todo fue pre-validado) cuenta en fallidos sin
     * detener la mezcla.
     */
    fun mezclar(manager: BookmarkManager, importacion: ImportHtml): ResultadoMezcla {
        val existentes = HashSet<String>()
        recolectarUrls(manager, "root", existentes)
        var agregados = 0
        var duplicados = 0
        var carpetas = 0
        var fallidos = 0
        for (e in importacion.entradas) {
            if (existentes.contains(e.url)) {
                duplicados += 1
                continue
            }
            val destino = asegurarRuta(manager, e.ruta)
            carpetas += destino.second
            when (val r = manager.add(e.titulo, e.url, destino.first)) {
                is Outcome.Success -> {
                    existentes.add(e.url)
                    agregados += 1
                }
                is Outcome.Failure -> fallidos += 1
            }
        }
        return ResultadoMezcla(agregados, duplicados, carpetas, fallidos)
    }

    /** Id de la carpeta destino creando la ruta que falte, y cuantas creo. */
    private fun asegurarRuta(manager: BookmarkManager, ruta: List<String>): Pair<String, Int> {
        var padre = "root"
        var creadas = 0
        for (nombre in ruta) {
            val existente = hijoCarpetaPorNombre(manager, padre, nombre)
            if (existente != null) {
                padre = existente
                continue
            }
            val nueva = manager.addCarpeta(nombre, padre).valueOrNull ?: return Pair(padre, creadas)
            padre = nueva.id
            creadas += 1
        }
        return Pair(padre, creadas)
    }

    /** Carpeta hija directa con ese nombre o null. */
    private fun hijoCarpetaPorNombre(manager: BookmarkManager, padre: String, nombre: String): String? {
        val hijos = manager.hijos(padre).valueOrNull ?: return null
        for (id in hijos) {
            val cf = manager.carpetaDe(id)
            if (cf != null && cf.nombre == nombre) return id
        }
        return null
    }

    /** Recoleccion recursiva de las urls ya instaladas. */
    private fun recolectarUrls(manager: BookmarkManager, carpeta: String, out: HashSet<String>) {
        val hijos = manager.hijos(carpeta).valueOrNull ?: return
        for (id in hijos) {
            val bm = manager.marcadorDe(id)
            if (bm != null) {
                out.add(bm.url)
            } else {
                val cf = manager.carpetaDe(id) ?: continue
                recolectarUrls(manager, cf.id, out)
            }
        }
    }

    private fun decodificar(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun escapar(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun <T> fallo(code: ErrorCode, mensaje: String): Outcome<T> =
        Outcome.failure(ErrorInfo(code, mensaje))
}
