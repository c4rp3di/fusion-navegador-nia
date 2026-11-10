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
 * v4 (R0314, RES-0128): Set-Cookie multiples. El transporte une las
 * cabeceras repetidas con coma; storeFromResponse parte el valor por
 * comas SOLO donde empieza otra cookie (token= tras la coma), de modo
 * que los Expires con coma en la fecha quedan intactos (cookie-octet de
 * RFC 6265 excluye la coma: el corte es seguro), y guarda las cookies
 * validas descartando en silencio las invalidas.
 *
 * Limitaciones v1: SameSite se almacena pero no se aplica (exige contexto
 * de navegacion y sitio top-level, con el cableado del broker); sin
 * particiones (T033).
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

    /** T044 v1 (R0104, RES-0056): borra todas las cookies; devuelve cuantas. */
    fun clear(): Int {
        val removed = cookies.size
        cookies.clear()
        return removed
    }

    /** T085 v1 (RES-0098): conteo de las cookies del sitio: las que
     *  hacen domain-match con el host (RFC 6265 5.1.3); cuenta todas
     *  las almacenadas del dominio, tambien las caducadas (el conteo
     *  es del almacen, no de la peticion). */
    fun contarDe(host: String): Int {
        val h = host.lowercase()
        return cookies.count { domainMatch(h, it) }
    }

    /** T085 v1: borra SOLO las cookies del sitio (domain-match con
     *  el host); los demas dominios quedan intactos. */
    fun borrarDe(host: String): Int {
        val h = host.lowercase()
        val prev = cookies.size
        cookies.removeAll { domainMatch(h, it) }
        return prev - cookies.size
    }

    /**
     * Almacena las cookies de una respuesta hacia [host]. El valor puede
     * llevar VARIAS cookies unidas por coma (el transporte une las
     * cabeceras Set-Cookie repetidas): se parte por comas solo donde
     * empieza otra cookie y cada una se guarda o descarta en silencio.
     * Devuelve true si ALGUNA se guardo; false si todas son invalidas,
     * el dominio no matchea o Secure va sin https.
     */
    fun storeFromResponse(host: String, isSecureTransport: Boolean, setCookie: String?): Boolean {
        if (setCookie == null) return false
        // OJO: any cortocircuitaria en la primera guardada y perderia el resto
        var storedAny = false
        for (valor in splitSetCookie(setCookie)) {
            if (storeOne(host, isSecureTransport, valor)) storedAny = true
        }
        return storedAny
    }

    /** T026 v4: guarda UNA cookie ya partida; la logica 5.3 de siempre. */
    private fun storeOne(host: String, isSecureTransport: Boolean, valor: String): Boolean {
        val now = clock.nowMillis()
        val parsed = parseSetCookie(valor, now) ?: return false
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

    /** T101 v1 (RES-0099): borra la cookie con identidad compuesta
     *  (nombre, dominio, ruta, host-only) - la MISMA identidad del
     *  reemplazo de storeFromResponse (RFC 6265 5.3) - y devuelve si
     *  existia; el resto de cookies queda intacto. */
    fun borrarUna(nombre: String, dominio: String, ruta: String, hostOnly: Boolean): Boolean {
        val idx = cookies.indexOfFirst {
            it.name == nombre && it.domain == dominio &&
                it.path == ruta && it.hostOnly == hostOnly
        }
        if (idx < 0) return false
        cookies.removeAt(idx)
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

        /** T026 v4 (R0314, RES-0128): parte un valor Set-Cookie (posiblemente
         *  varias cookies unidas por coma) SOLO donde tras la coma empieza
         *  otra cookie (token=); los Expires con coma en la fecha quedan
         *  intactos porque la fecha no empieza por token=. */
        private val TOKEN_IGUAL = Regex("^[A-Za-z0-9!#$%&'*+\\-.^_`|~]+=")

        fun splitSetCookie(joined: String): List<String> {
            val out = mutableListOf<String>()
            val b = StringBuilder()
            var i = 0
            while (i < joined.length) {
                val c = joined[i]
                if (c == ',' && TOKEN_IGUAL.containsMatchIn(joined.drop(i + 1).trim())) {
                    out.add(b.toString().trim())
                    b.clear()
                } else {
                    b.append(c)
                }
                i++
            }
            val last = b.toString().trim()
            if (last.isNotEmpty()) out.add(last)
            return out
        }

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
