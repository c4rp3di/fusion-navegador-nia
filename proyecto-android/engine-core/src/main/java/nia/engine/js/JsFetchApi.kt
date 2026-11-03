package nia.engine.js

import nia.contracts.CancellationToken
import nia.contracts.CancellationTokenSource
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.SystemWallClock
import nia.contracts.WallClock
import nia.engine.net.FetchManager
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport

/**
 * Fetch JS (T027 v1, RES-0033): expone fetch(url, opts) y AbortController
 * sobre el FetchManager del motor (T011) con el FetchTransport INYECTABLE
 * (los tests usan un falso; el real ya esta probado en HttpTransportTest).
 *
 * fetch(url, opts) devuelve una Promise que se asienta en la PROPIA
 * llamada: el transporte de T011 es bloqueante, asi que la peticion corre
 * dentro de la llamada y la promise queda asentada antes de volver (el
 * drenaje de microtasks la entrega al then/await). v1 NO paraleliza: el
 * event loop (T025) la integrara con timers en una fase posterior.
 *
 * opts: method (GET/HEAD/POST/PUT/DELETE/OPTIONS), headers (objeto),
 * body (string), timeoutMs (deadline = reloj + timeoutMs; no positivo =
 * deadline ya vencido -> AbortError sin llamar al transporte) y signal
 * (la de un AbortController).
 *
 * Rechazos tipificados sobre los ErrorCode cerrados de :contracts:
 * CANCELLED -> AbortError (abort o deadline), NETWORK -> NetworkError,
 * TLS -> TlsError, UNSUPPORTED/INVALID_INPUT -> TypeError, resto ->
 * FetchError. La cancelacion observada tras el transporte descarta la
 * respuesta (semantica cooperativa de T011): la promise se rechaza
 * aunque el transporte haya devuelto exito.
 *
 * AbortController (signal + abort()) mapea a un CancellationTokenSource
 * del mismo reloj inyectable: abort() conmuta signal.aborted y cancela
 * por cooperacion (nadie interrumpe a la fuerza).
 *
 * Exclusiones v1: sin multipart/form-data, sin streams/blob (solo
 * text()), sin credenciales/cookies (T026 v2), sin XMLHttpRequest, y la
 * API es propia del motor (las expectativas de los tests vienen del
 * diseno, no de un oraculo del estandar).
 */
class JsFetchApi(
    transport: FetchTransport,
    private val clock: WallClock = SystemWallClock()
) {

    private val manager = FetchManager(transport, clock)

    /** Signals vivas: objeto signal JS -> CancellationTokenSource (T027). */
    private val signals = HashMap<JsObj, CancellationTokenSource>()

    /** Crea un controlador { signal, abort() } sobre un token cooperativo. */
    fun makeController(): JsObj {
        val cts = CancellationTokenSource(clock)
        val signal = JsRuntime.newObj()
        signal.props["aborted"] = JsProp.Value(JsBool(false))
        signals[signal] = cts
        val controller = JsRuntime.newObj()
        controller.props["signal"] = JsProp.Value(signal)
        controller.props["abort"] = JsProp.Value(JsBuiltins.fn("abort") { _, _ ->
            cts.cancel()
            signal.props["aborted"] = JsProp.Value(JsBool(true))
            JsUndefined
        })
        return controller
    }

    /** Instala fetch y AbortController en la sesion JS. */
    fun install(genv: Env) {
        genv.declare("fetch", JsBuiltins.fn("fetch") { args, _ ->
            val p = JsInterpreter.nativePromise()
            val url = JsRuntime.toStringJs(JsBuiltins.a0(args))
            var method = "GET"
            var body: ByteArray? = null
            var deadline = -1L
            var token: CancellationToken? = null
            var headers = HashMap<String, String>()
            val optsV = if (args.size > 1) args[1] else JsUndefined
            if (optsV is JsObj) {
                val mV = (optsV.props["method"] as? JsProp.Value)?.v
                if (mV != null && mV !is JsUndefined) method = JsRuntime.toStringJs(mV).uppercase()
                val hV = (optsV.props["headers"] as? JsProp.Value)?.v
                if (hV is JsObj) {
                    for ((k, e) in hV.props) {
                        val v = (e as? JsProp.Value)?.v
                        if (v != null && v !is JsUndefined) headers[k] = JsRuntime.toStringJs(v)
                    }
                }
                val bV = (optsV.props["body"] as? JsProp.Value)?.v
                if (bV != null && bV !is JsUndefined) body = JsRuntime.toStringJs(bV).toByteArray()
                val tV = (optsV.props["timeoutMs"] as? JsProp.Value)?.v
                if (tV != null && tV !is JsUndefined) {
                    val n = JsRuntime.toNumber(tV)
                    deadline = if (!n.isNaN() && n > 0) clock.nowMillis() + n.toLong() else clock.nowMillis()
                }
                val sV = (optsV.props["signal"] as? JsProp.Value)?.v
                if (sV is JsObj) {
                    val cts = signals[sV]
                    if (cts != null) token = cts.token
                }
            }
            if (token != null && token.isCancelled) {
                JsInterpreter.settleNativePromise(
                    p,
                    false,
                    JsRuntime.makeErrorObj("AbortError", "operacion abortada")
                )
                return@fn p
            }
            val req = manager.newRequest(method, url, headers, body)
            when (val out = manager.fetch(req, token, deadline)) {
                is Outcome.Success ->
                    JsInterpreter.settleNativePromise(p, true, responseOf(out.value))
                is Outcome.Failure ->
                    JsInterpreter.settleNativePromise(p, false, errorOf(out.error.code, out.error.safeMessage))
            }
            p
        })
        genv.declare("AbortController", JsNative("AbortController", construct = { _ -> makeController() }))
    }

    /** Response {ok, status, statusText, headers.get(name), text()}. */
    private fun responseOf(r: FetchResponse): JsObj {
        val o = JsRuntime.newObj()
        o.props["ok"] = JsProp.Value(JsBool(r.status in 200..299))
        o.props["status"] = JsProp.Value(JsNum(r.status.toDouble()))
        o.props["statusText"] = JsProp.Value(JsStr(statusTextOf(r.status)))
        val headers = JsRuntime.newObj()
        headers.props["get"] = JsProp.Value(JsBuiltins.fn("get") { args, _ ->
            val name = JsRuntime.toStringJs(JsBuiltins.a0(args)).lowercase()
            var found: String? = null
            for ((k, v) in r.headers) {
                if (k.lowercase() == name) {
                    found = v
                    break
                }
            }
            if (found == null) JsNull else JsStr(found)
        })
        o.props["headers"] = JsProp.Value(headers)
        o.props["text"] = JsProp.Value(JsBuiltins.fn("text") { _, _ ->
            JsStr(String(r.body, Charsets.UTF_8))
        })
        return o
    }

    /** Rechazo tipificado a partir del ErrorCode cerrado de :contracts. */
    private fun errorOf(code: ErrorCode, msg: String): JsObj = JsRuntime.makeErrorObj(
        when (code) {
            ErrorCode.CANCELLED -> "AbortError"
            ErrorCode.NETWORK -> "NetworkError"
            ErrorCode.TLS -> "TlsError"
            ErrorCode.UNSUPPORTED, ErrorCode.INVALID_INPUT -> "TypeError"
            else -> "FetchError"
        },
        msg
    )

    /** statusText del subconjunto habitual; vacio para los demas (v1). */
    private fun statusTextOf(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }
}
