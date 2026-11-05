package nia.engine.omnibox

import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser

/**
 * OmniboxInput (T072 v1, RES-0075, R0155, ADR-022): resolucion del texto del
 * omnibox con reglas explicitas y deterministas, en orden, la primera que
 * aplica (todo testeable en JVM puro): (1) vacio o espacios, sin accion;
 * (2) esquema http/https explicito, navegar tal cual validando con el
 * UrlParser verificado de T010 (los errores de parseo se avisan, nunca
 * busqueda silenciosa); (3) esquema explicito NO soportado, aviso honesto
 * sin fingir navegacion ni busqueda; (4) sin esquema pero parece dominio
 * (punto, localhost, IP o IPv6 literal, sin espacios), navegar con https
 * implicito; (5) todo lo demas, busqueda con la query codificada en UTF-8.
 *
 * Fallback DNS del ADR-022: la resolucion marca dominioImplicito=true en la
 * regla 4 para que el shell OFREZCA la busqueda tras confirmacion si la
 * navegacion falla de DNS; nunca en silencio y nunca desde aqui.
 *
 * Espejo del gemelo TS validado offline (2 corridas identicas, huella
 * 9822.26f4.982, 34 escenarios): OmniboxInputTest y OmniboxSugerenciasTest.
 */
object OmniboxInput {

    /** Motor por defecto de v1: DuckDuckGo HTML, sin JS (ADR-022). */
    const val BASE_BUSQUEDA_DEFECTO = "https://html.duckduckgo.com/html/?q="

    private val ESQUEMAS_CONOCIDOS = setOf(
        "about", "file", "chrome", "javascript", "data", "view-source", "mailto",
        "ftp", "ws", "wss", "blob", "tel", "geo", "intent"
    )

    private val RE_ESQUEMA = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")

    sealed class Resolucion {
        object Vacio : Resolucion()
        data class Navegar(val url: String, val dominioImplicito: Boolean) : Resolucion()
        data class Buscar(val url: String, val query: String) : Resolucion()
        data class EsquemaNoSoportado(val esquema: String) : Resolucion()
        data class ErrorDeParseo(val motivo: String) : Resolucion()
    }

    fun resolver(texto: String, baseBusqueda: String = BASE_BUSQUEDA_DEFECTO): Resolucion {
        val t = texto.trim()
        if (t.isEmpty()) return Resolucion.Vacio
        val esquema = esquemaExplicito(t)
        if (esquema == "http" || esquema == "https") {
            return try {
                UrlParser.parse(t)
                Resolucion.Navegar(t, false)
            } catch (e: UrlParseException) {
                Resolucion.ErrorDeParseo(e.reason)
            }
        }
        if (esquema != null) return Resolucion.EsquemaNoSoportado(esquema)
        if (!pareceDominio(t)) return Resolucion.Buscar(baseBusqueda + codificarQuery(t), t)
        val implicita = "https://" + t
        return try {
            UrlParser.parse(implicita)
            Resolucion.Navegar(implicita, true)
        } catch (e: UrlParseException) {
            Resolucion.ErrorDeParseo(e.reason)
        }
    }

    /**
     * Esquema explicito reconocido (en minusculas) o null: http/https, un
     * esquema conocido, o cualquiera seguido de doble barra; un host:puerto
     * NO es un esquema.
     */
    fun esquemaExplicito(texto: String): String? {
        val i = texto.indexOf(':')
        if (i <= 0) return null
        val candidato = texto.substring(0, i)
        if (!RE_ESQUEMA.matches(candidato)) return null
        val min = candidato.lowercase()
        if (min == "http" || min == "https") return min
        if (texto.substring(i + 1).startsWith("//")) return min
        return if (min in ESQUEMAS_CONOCIDOS) min else null
    }

    /**
     * Heuristica deliberadamente simple del ADR-022: sin espacios ni
     * controles, sin arroba (correo), localhost, literal IPv6 entre
     * corchetes, IP o host con punto.
     */
    fun pareceDominio(texto: String): Boolean {
        if (texto.any { it <= ' ' }) return false
        val autoridad = texto.substringBefore('/').substringBefore('?').substringBefore('#')
        if (autoridad.contains('@')) return false
        if (autoridad.startsWith("[")) return true
        val host = autoridad.substringBefore(':')
        if (host == "localhost") return true
        if (esIpv4Literal(host)) return true
        return host.contains('.')
    }

    private fun esIpv4Literal(s: String): Boolean =
        s.contains('.') && s.split('.').all { p -> p.isNotEmpty() && p.all(Char::isDigit) }

    /** Query UTF-8 percent-codificada (RFC 3986, espacio como %20). */
    fun codificarQuery(q: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < q.length) {
            val c = q[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < q.length && q[i + 1].isLowSurrogate()) {
                cp = Character.toCodePoint(c, q[i + 1])
                i++
            } else {
                cp = c.code
            }
            i++
            if (cp in 0x41..0x5A || cp in 0x61..0x7A || cp in 0x30..0x39 ||
                cp == '-'.code || cp == '.'.code || cp == '_'.code || cp == '~'.code
            ) {
                sb.appendCodePoint(cp)
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append(HEX[v ushr 4])
                    sb.append(HEX[v and 15])
                }
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
