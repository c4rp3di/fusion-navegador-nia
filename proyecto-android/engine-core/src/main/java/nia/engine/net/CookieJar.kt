package nia.engine.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import nia.contracts.WallClock

/** Contexto de navegacion de la peticion (T026 v2, R0060): SameSite. */
enum class SiteContext { SAME_SITE, TOP_LEVEL_CROSS_SITE, EMBEDDED_CROSS_SITE }

/**
 * T026 v1 (R0052, RES-0031): cookies RFC 6265 para el motor de red.
 *
 * CookieJar es un almacena por sesion de motor (la persistencia en disco
 * llega con las particiones de storage, T033). Todo sigue el RFC 6265:
 *
 * - parseSetCookie (5.2): una cookie invalida devuelve null y se descarta
 *   en silencio; el atributo Domain sin valor o con un valor vacio tambien
 *   descarta la cookie; Expires ilegible se ignora (queda como cookie de
 *   sesion); Max-Age <= 0 marca la cookie como caducada (centinela -1).
 * - storeFromResponse (5.3): sin atributo Domain la cookie es host-only
 *   del host exacto; con Domain, el host debe hacer domain-match (una
 *   cookie de otro dominio se RECHAZA, nunca se almacena); Secure exige
 *   transporte seguro; reemplaza la cookie con misma (nombre, dominio,
 *   ruta, host-only).
 * - cookieHeader (5.4): domain-match + path-match + Secure solo sobre
 *   https, caducadas fuera, orden por longitud de ruta (la mas larga
 *   primero) y luego por momento de creacion.
 *
 * Limitaciones v1: SameSite se almacena pero no se aplica (exige contexto
 * de navegacion y sitio top-level, con el cableado del broker); sin
 * particiones (T033); una sola cookie por cabecera Set-Cookie (el
 * transporte une las repetidas con coma, y partir por comas romperia
 * los Expires: el que llama entrega una cookie por cabecera).
 *
 * v2 (R0060, RES-0035): SameSite aplicado. La sobrecarga de
 * cookieHeader con SiteContext filtra con sameSiteAllowed (strict
 * solo same-site, lax acepta ademas navegacion top-level, none
 * siempre, sin atributo o desconocido lax); la API de 3 argumentos
 * queda intacta (sin contexto, sin filtro).
 */
data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtMillis: Long?,
    val sameSite: String?,
    val httpOnly: Boolean,
    val secure: Boolean,
    val hostOnly: Boolean,
    val createdAtMillis: Long
)

class CookieJar(private val clock: WallClock) {

    private val cookies = mutableListOf<Cookie>()

    fun count(): Int = cookies.size

    fun all(): List<Cookie> = cookies.toList()

    /**
     * Almacena la cookie de una respuesta hacia [host]. El que llama
     * entrega UNA cabecera Set-Cookie por llamada. Devuelve false si la
     * cookie es invalida, el dominio no matchea o Secure va sin https.
     */
    fun storeFromResponse(host: String, isSecureTransport: Boolean, setCookie: String?): Boolean {
        if (setCookie == null) return false
        val now = clock.nowMillis()
        val parsed = parseSetCookie(setCookie, now) ?: return false
        val cookie: Cookie
        if (parsed.domain.isEmpty()) {
            cookie = parsed.copy(domain = host.lowercase(), hostOnly = true)
        } else {
            if (!domainMatch(host.lowercase(), parsed)) return false
            cookie = parsed
        }
        if (cookie.secure && !isSecureTransport) return false
        val prev = cookies.indexOfFirst {
            it.name == cookie.name && it.domain == cookie.domain &&
                it.path == cookie.path && it.hostOnly == cookie.hostOnly
        }
        if (prev >= 0) cookies.removeAt(prev)
        cookies.add(cookie)
        return true
    }

    /**
     * Cabecera Cookie para una peticion hacia (host, path): las cookies
     * caducadas fuera, ordenadas por longitud de ruta y creacion.
     */
    fun cookieHeader(host: String, path: String, isSecureTransport: Boolean): String {
        val now = clock.nowMillis()
        return cookies
            .filter { c ->
                (c.expiresAtMillis == null || (c.expiresAtMillis > 0 && c.expiresAtMillis > now)) &&
                    domainMatch(host.lowercase(), c) &&
                    pathMatch(path, c.path) &&
                    (!c.secure || isSecureTransport)
            }
            .sortedWith(compareByDescending<Cookie> { it.path.length }.thenBy { it.createdAtMillis })
            .joinToString("; ") { it.name + "=" + it.value }
    }

    /**
     * T026 v2 (R0060, RES-0035): cookieHeader con SameSite aplicado.
     * La sobrecarga de 3 argumentos queda sin filtro (compatibilidad).
     */
    fun cookieHeader(host: String, path: String, isSecureTransport: Boolean, siteContext: SiteContext): String {
        val now = clock.nowMillis()
        return cookies
            .filter { c ->
                sameSiteAllowed(c.sameSite, siteContext) &&
                    (c.expiresAtMillis == null || (c.expiresAtMillis > 0 && c.expiresAtMillis > now)) &&
                    domainMatch(host.lowercase(), c) &&
                    pathMatch(path, c.path) &&
                    (!c.secure || isSecureTransport)
            }
            .sortedWith(compareByDescending<Cookie> { it.path.length }.thenBy { it.createdAtMillis })
            .joinToString("; ") { it.name + "=" + it.value }
    }

    companion object {

        private val IPV4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
        private val RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME

        /** RFC 6265 5.2: cookie invalida -> null (se descarta en silencio). */
        fun parseSetCookie(value: String, nowMillis: Long): Cookie? {
            val parts = value.split(';')
            if (parts.isEmpty()) return null
            val nv = parts[0]
            val eq = nv.indexOf('=')
            if (eq <= 0) return null
            val name = nv.substring(0, eq).trim()
            val cookieValue = nv.substring(eq + 1).trim()
            if (name.isEmpty()) return null
            var domain = ""
            var path = "/"
            var expiresAt: Long? = null
            var sameSite: String? = null
            var httpOnly = false
            var secure = false
            for (i in 1 until parts.size) {
                val p = parts[i]
                val e = p.indexOf('=')
                val k = (if (e < 0) p else p.substring(0, e)).trim().lowercase()
                val v = if (e < 0) "" else p.substring(e + 1).trim()
                when (k) {
                    "domain" -> {
                        val d = v.lowercase().removePrefix(".")
                        if (d.isEmpty()) return null
                        domain = d
                    }
                    "path" -> if (v.startsWith("/")) path = v
                    "expires" -> {
                        try {
                            expiresAt = ZonedDateTime.parse(v, RFC1123).toInstant().toEpochMilli()
                        } catch (_: java.time.format.DateTimeParseException) {
                            // fecha ilegible: se ignora el atributo (cookie de sesion)
                        }
                    }
                    "max-age" -> {
                        val n = v.toLongOrNull()
                        if (n != null) expiresAt = if (n <= 0) -1 else nowMillis + n * 1000
                    }
                    "samesite" -> sameSite = v.lowercase()
                    "httponly" -> httpOnly = true
                    "secure" -> secure = true
                }
            }
            val hostOnly = domain.isEmpty()
            return Cookie(name, cookieValue, domain, path, expiresAt, sameSite, httpOnly, secure, hostOnly, nowMillis)
        }

        /** RFC 6265 5.1.3: domain-match; host-only exige igualdad exacta. */
        fun domainMatch(host: String, cookie: Cookie): Boolean {
            if (cookie.hostOnly) return host == cookie.domain
            if (host == cookie.domain) return true
            return host.endsWith("." + cookie.domain) && !IPV4.matches(host)
        }

        /** RFC 6265 5.1.4: path-match. */
        fun pathMatch(requestPath: String, cookiePath: String): Boolean {
            val rp = if (requestPath.isEmpty()) "/" else requestPath
            val cp = if (cookiePath.isEmpty()) "/" else cookiePath
            if (rp == cp) return true
            if (rp.startsWith(cp)) {
                if (cp.endsWith("/")) return true
                if (rp[cp.length] == '/') return true
            }
            return false
        }

        /**
         * T026 v2 (R0060): strict solo same-site; lax acepta ademas
         * navegacion top-level cross-site; none siempre; sin atributo
         * o valor desconocido se trata como lax.
         */
        fun sameSiteAllowed(sameSite: String?, siteContext: SiteContext): Boolean {
            return when (sameSite) {
                "strict" -> siteContext == SiteContext.SAME_SITE
                "none" -> true
                else -> siteContext == SiteContext.SAME_SITE || siteContext == SiteContext.TOP_LEVEL_CROSS_SITE
            }
        }
    }
}
