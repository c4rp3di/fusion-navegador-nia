package nia.engine.url

/** Error de parseo de URL (T010). El motivo es estructural, sin datos sensibles. */
class UrlParseException(val reason: String) : Exception("URL invalida: $reason")

/**
 * URL absoluta parseada (T010 v1): subconjunto practico del algoritmo WHATWG.
 *
 * Alceance v1: esquemas especiales (http, https, ws, wss, ftp, file), userinfo
 * opcional, host (dominio ASCII, IPv4 o IPv6 entre corchetes), puerto, ruta
 * normalizada (segmentos `.` y `..`), query y fragmento.
 *
 * Fuera de alcance v1: referencias relativas (requieren base), IDNA/punycode
 * (se rechazan dominios no-ASCII) y esquemas no especiales.
 */
data class Url(
    val scheme: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?
) {
    /** Puerto efectivo: el explicito o el por defecto del esquema. */
    val effectivePort: Int get() = port ?: UrlParser.defaultPort(scheme) ?: -1

    /** Serializacion canonica (userinfo incluido). */
    override fun toString(): String = buildString {
        append(scheme)
        append("://")
        if (username.isNotEmpty()) {
            append(username)
            if (password.isNotEmpty()) {
                append(':')
                append(password)
            }
            append('@')
        }
        append(host)
        if (port != null) {
            append(':')
            append(port)
        }
        append(path)
        if (query != null) {
            append('?')
            append(query)
        }
        if (fragment != null) {
            append('#')
            append(fragment)
        }
    }
}

object UrlParser {

    private val SPECIAL_SCHEMES = mapOf(
        "http" to 80,
        "https" to 443,
        "ws" to 80,
        "wss" to 443,
        "ftp" to 21
    )

    private const val FILE_SCHEME = "file"

    private val FORBIDDEN_HOST = setOf(' ', '#', '/', '?', ':', '@', '[', '\\', ']', '<', '>', '|', '^')

    fun defaultPort(scheme: String): Int? = SPECIAL_SCHEMES[scheme]

    private fun isSpecial(scheme: String): Boolean =
        scheme == FILE_SCHEME || SPECIAL_SCHEMES.containsKey(scheme)

    fun parse(input: String): Url {
        val raw = clean(input)
        if (raw.isEmpty()) throw UrlParseException("URL vacia")
        val colonIdx = raw.indexOf(':')
        if (colonIdx <= 0) throw UrlParseException("se esperaba esquema absoluto")
        val scheme = raw.substring(0, colonIdx).lowercase()
        if (!isScheme(scheme)) throw UrlParseException("esquema invalido: $scheme")
        if (!isSpecial(scheme)) throw UrlParseException("esquema no especial no soportado v1: $scheme")
        var rest = raw.substring(colonIdx + 1)
        if (!rest.startsWith("//")) throw UrlParseException("se esperaba // tras el esquema $scheme")
        rest = rest.substring(2)

        var endAuth = rest.length
        for (i in rest.indices) {
            val ch = rest[i]
            if (ch == '/' || ch == '?' || ch == '#') {
                endAuth = i
                break
            }
        }
        val authority = rest.substring(0, endAuth)
        val after = rest.substring(endAuth)

        var username = ""
        var password = ""
        var hostPort = authority
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            hostPort = authority.substring(at + 1)
            val userinfo = authority.substring(0, at)
            val c = userinfo.indexOf(':')
            if (c >= 0) {
                username = userinfo.substring(0, c)
                password = userinfo.substring(c + 1)
            } else {
                username = userinfo
            }
        }

        if (hostPort.isEmpty() && scheme != FILE_SCHEME) throw UrlParseException("host vacio")

        var host = ""
        var port: Int? = null
        if (hostPort.isNotEmpty()) {
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) throw UrlParseException("IPv6 sin cerrar")
                host = hostPort.substring(0, close + 1)
                port = parsePort(hostPort.substring(close + 1))
            } else {
                val c = hostPort.indexOf(':')
                if (c >= 0) {
                    host = hostPort.substring(0, c)
                    port = parsePort(hostPort.substring(c))
                } else {
                    host = hostPort
                }
            }
        }
        val normHost = if (host.isEmpty()) "" else parseHost(host)

        val defaultP = defaultPort(scheme)
        val effPort = if (port != null && port == defaultP) null else port

        var working = after
        var fragment: String? = null
        val hash = working.indexOf('#')
        if (hash >= 0) {
            fragment = working.substring(hash + 1)
            working = working.substring(0, hash)
        }
        var query: String? = null
        val question = working.indexOf('?')
        if (question >= 0) {
            query = working.substring(question + 1)
            working = working.substring(0, question)
        }
        var path = working
        if (path.isEmpty()) path = "/"
        path = normalizePath(path)

        return Url(scheme, username, password, normHost, effPort, path, query, fragment)
    }

    /** Limpieza previa del algoritmo WHATWG: espacios C0 en los extremos y tabulaciones/saltos en medio. */
    private fun clean(input: String): String {
        val trimmed = input.trim { it <= ' ' }
        val sb = StringBuilder()
        for (ch in trimmed) {
            if (ch != '\t' && ch != '\n' && ch != '\r') sb.append(ch)
        }
        return sb.toString()
    }

    private fun isScheme(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!s[0].isLetter()) return false
        for (ch in s) {
            if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') return false
        }
        return true
    }

    /** tail es "" o ":123"; ":" solo se admite como puerto vacio (sin puerto). */
    private fun parsePort(tail: String): Int? {
        if (tail.isEmpty()) return null
        if (!tail.startsWith(":")) throw UrlParseException("se esperaba :puerto tras el host")
        val digits = tail.substring(1)
        if (digits.isEmpty()) return null
        for (ch in digits) {
            if (ch !in '0'..'9') throw UrlParseException("puerto no numerico")
        }
        if (digits.length > 5) throw UrlParseException("puerto fuera de rango")
        val v = digits.toInt()
        if (v > 65535) throw UrlParseException("puerto fuera de rango")
        return v
    }

    private fun normalizePath(path: String): String {
        if (!path.startsWith("/")) throw UrlParseException("ruta no absoluta")
        val segments = path.substring(1).split('/')
        val out = mutableListOf<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> {}

                ".." -> { if (out.isNotEmpty()) out.removeAt(out.size - 1) }
                else -> out.add(seg)
            }
        }
        val trailing = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
        val sb = StringBuilder()
        for (s in out) {
            sb.append('/').append(s)
        }
        if (sb.isEmpty()) {
            sb.append('/')
        } else if (trailing && sb[sb.length - 1] != '/') {
            sb.append('/')
        }
        return sb.toString()
    }

    private fun parseHost(raw: String): String {
        if (raw.isEmpty()) throw UrlParseException("host vacio")
        if (raw.startsWith("[")) {
            if (!raw.endsWith("]")) throw UrlParseException("IPv6 sin cerrar")
            val inner = raw.substring(1, raw.length - 1)
            if (!parseIpv6(inner)) throw UrlParseException("IPv6 mal formado")
            return raw.lowercase()
        }
        validatePercent(raw)
        for (ch in raw) {
            val c = ch.code
            if (c <= 0x20 || c == 0x7F) throw UrlParseException("caracter de control en host")
            if (FORBIDDEN_HOST.contains(ch)) throw UrlParseException("caracter prohibido en host")
            if (c > 0x7E) throw UrlParseException("dominio no ASCII no soportado v1 (IDNA pendiente)")
        }
        val lower = raw.lowercase()
        if (isIpv4Candidate(lower)) {
            return parseIpv4(lower)
        }
        return lower
    }

    private fun validatePercent(s: String) {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 >= s.length || !isHexChar(s[i + 1]) || !isHexChar(s[i + 2])) {
                    throw UrlParseException("secuencia % invalida en host")
                }
                i += 3
            } else {
                i += 1
            }
        }
    }

    private fun isHexChar(ch: Char): Boolean =
        (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')

    /** v1: solo se consideran IPv4 los 4 grupos decimales completos. */
    private fun isIpv4Candidate(s: String): Boolean {
        if (!s.contains('.')) return false
        val parts = s.split('.')
        if (parts.any { it.isEmpty() }) return false
        return parts.all { p -> p.all(Char::isDigit) }
    }

    private fun parseIpv4(s: String): String {
        val parts = s.split('.')
        if (parts.size != 4) throw UrlParseException("IPv4 incompleto no soportado v1")
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) throw UrlParseException("IPv4 mal formado")
            if (p.toInt() > 255) throw UrlParseException("IPv4 fuera de rango")
        }
        return s
    }

    /** Validacion basica RFC 4291 (incluye IPv4 embebido en los ultimos 32 bits). */
    private fun parseIpv6(raw: String): Boolean {
        var s = raw
        val lastColon = s.lastIndexOf(':')
        if (lastColon >= 0 && s.substring(lastColon + 1).contains('.')) {
            val tail = s.substring(lastColon + 1)
            val p = tail.split('.')
            if (p.size != 4) return false
            for (part in p) {
                if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return false
                if (part.toInt() > 255) return false
            }
            s = s.substring(0, lastColon) + ":0:0"
        }
        if (s.isEmpty()) return false
        val hasDouble = s.contains("::")
        val left: List<String>
        val right: List<String>
        if (hasDouble) {
            val sides = s.split("::", limit = 2)
            if (sides.size != 2) return false
            left = if (sides[0].isEmpty()) emptyList() else sides[0].split(':')
            right = if (sides[1].isEmpty()) emptyList() else sides[1].split(':')
        } else {
            left = s.split(':')
            right = emptyList()
        }
        for (g in left) {
            if (!isIpv6Group(g)) return false
        }
        for (g in right) {
            if (!isIpv6Group(g)) return false
        }
        if (hasDouble) {
            return left.size + right.size < 8
        }
        return left.size + right.size == 8
    }

    private fun isIpv6Group(g: String): Boolean {
        if (g.isEmpty() || g.length > 4) return false
        for (ch in g) {
            if (!((ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F'))) return false
        }
        return true
    }
}
