package nia.engine.net

import nia.contracts.CancellationToken
import nia.contracts.CancellationTokenSource
import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome
import nia.contracts.SystemWallClock
import nia.contracts.WallClock

/**
 * T011 v1 (R0039, RES-0026): fetch/TLS/cancelacion. FetchManager es el
 * dueno del ciclo de vida de una peticion y es AGNOSTICO del transporte:
 * el transporte real (TLS incluido) se inyecta como FetchTransport y en
 * v1 es un Stub en los tests (la pila real llega con el adaptador de
 * Android en una fase posterior). Todo lo que puede salir mal se mapea a
 * los codigos cerrados de :contracts:
 *
 * - TLS invalido (el transporte no valida el certificado) -> TLS, no
 *   reintentable, mensaje seguro que NUNCA repite la URL.
 * - Fallo de DNS o de red -> NETWORK, reintentable mas tarde.
 * - Cancelacion cooperativa (contratos): deadline comprobado con el
 *   reloj inyectable antes de llamar al transporte y cancelacion
 *   explicita observada despues; ambas -> CANCELLED y la respuesta se
 *   descarta aunque el transporte la devuelva.
 * - Respuestas stale: invalidate() sube la generacion (navegacion); una
 *   respuesta de una generacion anterior -> STALE_HANDLE. Si hay
 *   cancelacion Y stale, gana la cancelacion (se informa de por que).
 *
 * Limitaciones v1: transporte sincrono (el adaptador real sera
 * asincrono sobre esta misma frontera), sin redireccion, sin
 * descompresion, sin pool de conexiones, sin cookies/CORS/cache (T026),
 * y sin cableado por broker (el codec de payloads de NETWORK_FETCH llega
 * en la v2 junto al modulo de red del broker).
 *
 * v2 (R0060, RES-0035): cache HTTP opcional. Con un HttpCache
 * inyectado, los GET frescos se sirven de cache sin tocar el
 * transporte, los caducados se revalidan con If-None-Match o
 * If-Modified-Since (un 304 revive la entrada guardada) y los 200
 * se almacenan; una peticion con cache-control no-cache salta el
 * cache. Sin cache inyectado el comportamiento es exactamente el
 * de v1.
 *
 * v3 (R0242, T052): filtro de red opcional ANTES de cache y
 * transporte. Con un RequestFilter inyectado, cada fetch consulta el
 * filtro primero con el RequestContext del llamador (o uno por
 * defecto, tipo "otro" sin iniciador); Block -> DENIED sin tocar ni
 * cache ni transporte. Sin filtro el comportamiento es exactamente
 * el de v2. El consult previo al service worker vive en JsFetchApi.
 *
 * v4 (R0271, C3): respuesta sustituta. Un FilterDecision.Substitute
 * se sirve SIN tocar cache ni transporte: no-store, sin cookies, el
 * CORS lo calcula el host (refleja el origen del iniciador solo en
 * cross-origin explicito) y el Range se honra con 206 parcial o
 * DENIED (rango invalido); la sustitucion NO cuenta en delivered.
 */

data class FetchRequest(
    val id: Long,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?
)

data class FetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val seguridad: TlsInfo? = null
)

/** Fallos que reporta el transporte; el manager los mapea a ErrorCode. */
enum class TransportFailure { TLS_INVALID, DNS, NETWORK }

sealed class TransportResult {
    data class Success(val response: FetchResponse) : TransportResult()
    data class Failure(val kind: TransportFailure) : TransportResult()
}

interface FetchTransport {
    fun fetch(request: FetchRequest, token: CancellationToken): TransportResult
}

class FetchManager(
    private val transport: FetchTransport,
    private val clock: WallClock = SystemWallClock(),
    private val cache: HttpCache? = null,
    private val filter: RequestFilter? = null
) {
    private val methods = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
    private var generation = 0L
    private var nextId = 0L
    private var delivered = 0

    /** Peticion nueva con id monotomico; el cuerpo y cabeceras llegan aparte. */
    fun newRequest(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): FetchRequest {
        nextId += 1
        return FetchRequest(nextId, method, url, headers, body)
    }

    /** Nueva generacion: las respuestas de generaciones anteriores son stale. */
    fun invalidate(): Long {
        generation += 1
        return generation
    }

    fun deliveredCount(): Int = delivered

    /**
     * Ejecuta una peticion con cancelacion cooperativa: el token externo
     * manda (el interno se crea solo si no se pasa ninguno) y el deadline
     * (si deadlineMillis > 0) se comprueba ANTES de llamar al transporte.
     * T052 v1: con filter inyectado, la peticion se consulta ANTES de
     * cache y transporte con el context del llamador (o uno por defecto);
     * Block -> DENIED sin tocar la red.
     */
    fun fetch(
        request: FetchRequest,
        token: CancellationToken? = null,
        deadlineMillis: Long = -1,
        context: RequestContext? = null
    ): Outcome<FetchResponse> {
        if (!methods.contains(request.method)) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "metodo no soportado"))
        }
        val lower = request.url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Outcome.failure(ErrorInfo(ErrorCode.UNSUPPORTED, "esquema no soportado"))
        }
        val f = filter
        if (f != null) {
            val ctx = context ?: RequestContext.of(request.url, "otro", "")
            val decision = f.decide(ctx)
            if (decision is FilterDecision.Block) {
                return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "peticion bloqueada por un modulo"))
            }
            if (decision is FilterDecision.Substitute) {
                return respuestaSustituta(request, ctx, decision)
            }
        }
        val g = generation
        val t = token ?: CancellationTokenSource(clock).token
        if (deadlineMillis > 0) {
            val r = t.checkDeadline(deadlineMillis)
            if (r != null) {
                return Outcome.failure(ErrorInfo(ErrorCode.CANCELLED, "operacion cancelada: " + r))
            }
        }
        var effective = request
        if (cache != null && request.method == "GET" && !requestCacheNoCache(request)) {
            when (val lu = cache.lookup(request.method, request.url)) {
                is HttpCache.Lookup.Fresh -> {
                    delivered += 1
                    return Outcome.success(FetchResponse(lu.entry.status, lu.entry.headers, lu.entry.body))
                }
                is HttpCache.Lookup.Stale -> {
                    val conditional = cache.revalidationHeaders(lu.entry)
                    if (conditional.isNotEmpty()) {
                        effective = request.copy(headers = request.headers + conditional)
                    }
                }
                HttpCache.Lookup.Miss -> {
                }
            }
        }
        val result = transport.fetch(effective, t)
        val cr = t.reason()
        if (cr != null) {
            return Outcome.failure(ErrorInfo(ErrorCode.CANCELLED, "operacion cancelada: " + cr))
        }
        if (generation != g) {
            return Outcome.failure(ErrorInfo(ErrorCode.STALE_HANDLE, "respuesta descartada: generacion anterior"))
        }
        return when (result) {
            is TransportResult.Success -> {
                if (cache != null && request.method == "GET") {
                    if (result.response.status == 304) {
                        val served = cache.serveRevalidated(request.url, result.response)
                        if (served != null) {
                            delivered += 1
                            return Outcome.success(served)
                        }
                    } else if (result.response.status == 200) {
                        cache.store(request.url, result.response)
                    }
                }
                delivered += 1
                Outcome.success(result.response)
            }
            is TransportResult.Failure -> when (result.kind) {
                TransportFailure.TLS_INVALID ->
                    Outcome.failure(ErrorInfo(ErrorCode.TLS, "TLS invalido: conexion rechazada"))
                TransportFailure.DNS ->
                    Outcome.failure(ErrorInfo(ErrorCode.NETWORK, "fallo de red: DNS"))
                TransportFailure.NETWORK ->
                    Outcome.failure(ErrorInfo(ErrorCode.NETWORK, "fallo de red"))
            }
        }
    }

    /** La peticion pide explicitamente no-cache (cache-control o pragma). */
    private fun requestCacheNoCache(request: FetchRequest): Boolean {
        for ((k, v) in request.headers) {
            val lk = k.lowercase()
            if (lk == "cache-control") {
                for (part in v.split(',')) {
                    if (part.trim().lowercase() == "no-cache") return true
                }
            } else if (lk == "pragma") {
                if (v.lowercase().contains("no-cache")) return true
            }
        }
        return false
    }

    /**
     * C3 v1 (R0271, RES-0119): respuesta sustituta del filtro. Se
     * sirve ANTES de cache y transporte: no-store (NUNCA puebla la
     * cache de la URL original), sin Set-Cookie, CORS calculado por
     * el HOST reflejando el origen del iniciador solo en cross-origin
     * explicito y Range honrado con 206 parcial o DENIED (rango
     * invalido documentado); delivered NO cuenta la sustitucion.
     */
    private fun respuestaSustituta(
        request: FetchRequest,
        ctx: RequestContext?,
        decision: FilterDecision.Substitute
    ): Outcome<FetchResponse> {
        val bytes = decision.bytes
        val total = bytes.size
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = decision.contentType
        headers["Accept-Ranges"] = "bytes"
        headers["Cache-Control"] = "no-store"
        if (ctx != null && ctx.initiatorUrl.isNotEmpty() && ctx.thirdParty == true) {
            val origen = origenDe(ctx.initiatorUrl)
            if (origen.isNotEmpty()) headers["Access-Control-Allow-Origin"] = origen
        }
        var desde = 0
        var hasta = total - 1
        var status = 200
        val range = headerValue(request.headers, "Range")
        if (range != null) {
            val m = Regex("^bytes=(\\d+)-(\\d*)$").find(range.trim())
            if (m == null) {
                return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "rango invalido"))
            }
            desde = m.groupValues[1].toInt()
            if (m.groupValues[2].isNotEmpty()) hasta = m.groupValues[2].toInt()
            if (desde > hasta || desde >= total) {
                return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "rango invalido"))
            }
            if (hasta > total - 1) hasta = total - 1
            status = 206
            headers["Content-Range"] = "bytes " + desde + "-" + hasta + "/" + total
        }
        val cuerpoGet = bytes.sliceArray(desde..hasta)
        headers["Content-Length"] = cuerpoGet.size.toString()
        val cuerpo = if (request.method == "HEAD") ByteArray(0) else cuerpoGet
        return Outcome.success(FetchResponse(status, headers, cuerpo))
    }

    /** Origen crudo del iniciador (tras "://", sin userinfo ni ruta). */
    private fun origenDe(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        var s = url.substring(i + 3)
        val corte = s.indexOfAny(charArrayOf('/', '?', '#'))
        if (corte >= 0) s = s.substring(0, corte)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        return s
    }

    /** Valor de una cabecera sin importar mayusculas; null si no esta. */
    private fun headerValue(headers: Map<String, String>, nombre: String): String? {
        for ((k, v) in headers) {
            if (k.equals(nombre, ignoreCase = true)) return v
        }
        return null
    }
}
