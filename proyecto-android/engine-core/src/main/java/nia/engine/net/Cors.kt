package nia.engine.net

/**
 * T026 v1 (R0052, RES-0031): CORS (Fetch, subconjunto practico) para el
 * motor de red.
 *
 * - isSimple: peticion simple (sin preflight) = metodo GET/HEAD/POST con
 *   cabeceras simples (accept, accept-language, content-language y
 *   content-type limitado a application/x-www-form-urlencoded,
 *   multipart/form-data o text/plain).
 * - requestHeadersNeedingPreflight: cabeceras de la peticion que obligan
 *   a preflight (no simples), en minusculas.
 * - evaluate: la respuesta de una peticion simple o tras preflight.
 *   Origin vacio = same-origin: siempre ok. Si no, exige
 *   Access-Control-Allow-Origin coincidente o '*'.
 * - evaluatePreflight: la respuesta del OPTIONS: ACAO, ACAM (metodo) y
 *   ACAH (cabeceras pedidas, content-type excluido de la exigencia
 *   porque ya viaja evaluado como cabecera simple).
 * - preflightMaxAge: Access-Control-Max-Age saneado, tope 24 h,
 *   ilegible -> null.
 * - isForbiddenHeader: cabeceras que el navegador NUNCA deja enviar por
 *   JS (host, connection, cookie, authorization).
 *
 * Limitaciones v1: sin credenciales cross-site (Allow-Credentials y el
 * envio de cookies en peticiones CORS llegan con el cableado del broker,
 * v2), sin cache real de preflights (solo se informa el Max-Age) y sin
 * Vary/Origin null avanzados.
 */
object Cors {

    data class Decision(val ok: Boolean, val reason: String)

    private val SIMPLE_METHODS = setOf("GET", "HEAD", "POST")
    private val SIMPLE_TYPES = setOf(
        "application/x-www-form-urlencoded",
        "multipart/form-data",
        "text/plain"
    )
    private val SIMPLE_HEADERS = setOf(
        "accept",
        "accept-language",
        "content-language",
        "content-type"
    )
    private val FORBIDDEN_HEADERS = setOf(
        "host",
        "connection",
        "cookie",
        "authorization"
    )
    private const val MAX_AGE_CAP = 86400L

    fun isSimple(method: String, headers: Map<String, String>): Boolean {
        if (!SIMPLE_METHODS.contains(method)) return false
        for ((k, v) in headers) {
            val lk = k.lowercase()
            if (lk == "content-type") {
                if (!SIMPLE_TYPES.contains(contentTypeOf(v))) return false
            } else if (!SIMPLE_HEADERS.contains(lk)) {
                return false
            }
        }
        return true
    }

    fun requestHeadersNeedingPreflight(headers: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for ((k, v) in headers) {
            val lk = k.lowercase()
            if (lk == "content-type") {
                if (!SIMPLE_TYPES.contains(contentTypeOf(v))) out.add(lk)
            } else if (!SIMPLE_HEADERS.contains(lk)) {
                out.add(lk)
            }
        }
        return out
    }

    /** Origin vacio = same-origin: sin CORS que evaluar. */
    fun evaluate(origin: String, responseHeaders: Map<String, String>): Decision {
        if (origin.isEmpty()) return Decision(true, "same-origin")
        val acao = headerValue(responseHeaders, "access-control-allow-origin")
            ?: return Decision(false, "sin-access-control-allow-origin")
        if (acao.trim() == "*") return Decision(true, "allow-origin-*")
        if (acao.trim() != origin) return Decision(false, "allow-origin-no-coincide")
        return Decision(true, "allow-origin")
    }

    fun evaluatePreflight(
        origin: String,
        method: String,
        requestHeaders: List<String>,
        responseHeaders: Map<String, String>
    ): Decision {
        val acao = headerValue(responseHeaders, "access-control-allow-origin")
            ?: return Decision(false, "sin-access-control-allow-origin")
        val trimmedOrigin = acao.trim()
        if (trimmedOrigin != "*" && trimmedOrigin != origin) {
            return Decision(false, "allow-origin-no-coincide")
        }
        val acam = headerValue(responseHeaders, "access-control-allow-methods")
            ?: return Decision(false, "sin-access-control-allow-methods")
        val methods = acam.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (!methods.contains(method.uppercase())) {
            return Decision(false, "metodo-no-permitido")
        }
        if (requestHeaders.isNotEmpty()) {
            val acah = headerValue(responseHeaders, "access-control-allow-headers")
                ?: return Decision(false, "sin-access-control-allow-headers")
            val allowed = acah.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            for (h in requestHeaders) {
                if (h != "content-type" && !allowed.contains(h)) {
                    return Decision(false, "cabecera-no-permitida:" + h)
                }
            }
        }
        return Decision(true, "preflight-ok")
    }

    fun preflightMaxAge(responseHeaders: Map<String, String>): Long? {
        val raw = headerValue(responseHeaders, "access-control-max-age") ?: return null
        val n = raw.trim().toLongOrNull() ?: return null
        return n.coerceIn(0, MAX_AGE_CAP)
    }

    fun isForbiddenHeader(name: String): Boolean =
        FORBIDDEN_HEADERS.contains(name.lowercase())

    /** Content-Type en minusculas sin parametros ("; charset=..."). */
    private fun contentTypeOf(value: String): String =
        value.lowercase().split(';')[0].trim()

    /** Busque case-insensitive (HTTP/1.1: nombres de cabecera sin mayusculas obligatorias). */
    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.lowercase() == name }?.value
}
