package nia.engine.js

/**
 * Service workers del motor (T034 v2, RES-0045): registro por scope con
 * lifecycle observable, intercepcion de fetch y CacheStorage en memoria
 * para el modo offline. Modelo cooperativo como los workers v1
 * (RES-0039): nada corre en tiempo real, el host bombea los eventos con
 * pump; el SW corre su script en un global AISLADO sobre la sesion unica
 * del motor (beginWorkerSession/makeWorkerGlobal de v1), con mensajes
 * string FIFO en ambas direcciones.
 *
 * Lifecycle: register(scope, script) corre el script y deja el SW en
 * INSTALLING; pump dispara UN evento por llamada: oninstall primero
 * (INSTALLING -> INSTALLED), onactivate despues (INSTALLED -> ACTIVE);
 * sin handler el estado avanza igual (la instalacion solo espera al
 * handler si existe). Solo un SW ACTIVE intercepa; con varios scopes que
 * casan, gana el prefijo mas largo. Un handler que no es funcion, un
 * throw no capturado en script/handler o un abort de cuota/interrupcion
 * (T021) TERMINAN el SW (lifecycle de Web Workers).
 *
 * Intercepcion: intercept(method, url) del host o de fetch JS
 * (JsFetchApi con serviceWorkers inyectado lo consulta ANTES del
 * transporte: offline real). El SW activo del scope recibe
 * onfetch(event) con event.request {method, url} y
 * event.respondWith(respuesta); la respuesta es un string (estado 200) o
 * un objeto {status, body}; sin respondWith el fetch pasa al transporte;
 * un throw del handler rechaza el fetch y TERMINA el SW.
 *
 * CacheStorage/Cache: sincronos en memoria (como indexedDB de T033 v2):
 * caches.open(name) crea o devuelve, put/match/delete/has/keys por
 * cache y open/has/delete/keys del storage; las respuestas se guardan
 * como {status, body} y match devuelve un objeto NUEVO (mutar la
 * respuesta no corrompe el cache).
 *
 * Exclusiones v2: sin hilos ni tiempos reales (el SW no tiene timers),
 * scope por prefijo sin normalizar, URL completa o path relativo (el
 * derivara de la navegacion), respondWith sincrono (sin promises en el
 * handler), sin push/sync/backgroundFetch del spec, una sesion de cuotas
 * por motor (paridad con v1) y los caches viven en memoria (la
 * persistencia en disco es la del storage, T033 v3).
 */
class JsServiceWorkers(private val limits: JsLimits = JsLimits()) {

    /** Estado del lifecycle del SW (T034 v2). */
    enum class SwState { INSTALLING, INSTALLED, ACTIVE, TERMINATED }

    /** Resultado de una intercepcion: pasa al transporte, respondida o rechazada. */
    sealed class Intercept {
        object None : Intercept()
        class Responded(val status: Int, val body: String) : Intercept()
        class Threw(val value: JsValue) : Intercept()
    }

    /** Un cache con nombre: entradas {status, body} por URL. */
    internal class Cache {
        val entries = LinkedHashMap<String, Pair<Int, String>>()
    }

    /** Handle del SW: identidad, colas y estado observable. */
    class ServiceWorker internal constructor(
        val id: Long,
        internal val genv: Env,
    ) {
        internal val inbox = ArrayDeque<String>()
        internal val outbox = ArrayDeque<String>()
        internal var abortReason: JsAbortReason? = null
        internal var responded = false
        internal var response: Pair<Int, String>? = null

        /** Estado del lifecycle: INSTALLING hasta terminar el ciclo o el SW. */
        var state: SwState = SwState.INSTALLING
            internal set

        /** Motivo del ultimo abort (cuota/interrupcion T021), si hubo. */
        fun lastAbort(): JsAbortReason? = abortReason

        /** Mensajes del host pendientes de entrega (solo se entregan en ACTIVE). */
        fun pendingMessages(): Int = inbox.size
    }

    private val workers = LinkedHashMap<String, ServiceWorker>()
    private val caches = LinkedHashMap<String, Cache>()
    private var nextId: Long = 1
    private var sessionOpen = false

    /** SWs registrados (los TERMINATED siguen hasta unregister/end). */
    fun registrations(): Int = workers.size

    /** Scopes registrados, en orden de insercion. */
    fun scopes(): List<String> = workers.keys.toList()

    /** Nombres de los caches abiertos. */
    fun cacheNames(): List<String> = caches.keys.toList()

    /** Entradas de un cache (observable del host); 0 si no existe. */
    fun cacheEntries(name: String): Int {
        val c = caches[name]
        return if (c != null) c.entries.size else 0
    }

    /** SWs vivos (no TERMINATED) del registro. */
    fun activeCount(): Int = workers.values.count { it.state != SwState.TERMINATED }

    /**
     * Registra un SW para el scope y corre su script: global aislado +
     * API del SW (postMessage/caches), sesion de motor abierta bajo
     * demanda. Un re-registro del mismo scope TERMINA el SW anterior
     * (simplificacion v2: sin cola de versiones del spec). Un throw no
     * capturado o un abort en el script dejan el SW TERMINADO ya en el
     * registro (lifecycle de Web Workers).
     */
    fun register(scope: String, script: String): ServiceWorker {
        val prev = workers[scope]
        if (prev != null && prev.state != SwState.TERMINATED) {
            doTerminate(prev)
        }
        if (!sessionOpen) {
            JsInterpreter.beginWorkerSession(limits)
            sessionOpen = true
        }
        val sw = ServiceWorker(nextId, JsInterpreter.makeWorkerGlobal())
        nextId = nextId + 1
        installSwApi(sw)
        workers[scope] = sw
        val r = JsInterpreter.runInSession(script, sw.genv)
        terminateFromError(sw, r.abortReason, r.thrown)
        return sw
    }

    /** Encola un mensaje hacia el SW: false si esta terminado. */
    fun postMessage(sw: ServiceWorker, message: String): Boolean {
        if (sw.state == SwState.TERMINATED) return false
        sw.inbox.addLast(message)
        return true
    }

    /** Saca (FIFO) un mensaje que el SW envio al host; null si no hay. */
    fun pollMessage(sw: ServiceWorker): String? = sw.outbox.removeFirstOrNull()

    /**
     * Bombea el siguiente evento del SW: UN paso del lifecycle por
     * llamada (INSTALLING -> INSTALLED con oninstall; INSTALLED -> ACTIVE
     * con onactivate) o, ya ACTIVE, los mensajes del host FIFO con tope
     * 10000. Abort de cuota/interrupcion o throw no capturado terminan el
     * SW; tras cada task se drenan los microtasks (paridad con v1).
     */
    fun pump(sw: ServiceWorker): JsEvalResult {
        if (sw.state == SwState.TERMINATED) {
            return JsEvalResult(JsUndefined, true, JsStr("worker terminado"), null)
        }
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        var aborted: JsAbortReason? = null

        fun runOne(body: () -> JsValue): Boolean {
            try {
                val v = body()
                if (v !is JsUndefined) last = v
            } catch (ex: JsAbort) {
                aborted = ex.reason
                return false
            } catch (ex: JsThrown) {
                if (thrown == null) thrown = ex.value
                return false
            }
            try {
                val mv = JsInterpreter.drainInSession()
                if (mv != null) last = mv
            } catch (ex: JsAbort) {
                aborted = ex.reason
                return false
            } catch (ex: JsThrown) {
                if (thrown == null) thrown = ex.value
                return false
            }
            return true
        }

        when (sw.state) {
            SwState.INSTALLING -> {
                runOne { fire(sw, "oninstall") }
                if (aborted == null && thrown == null) sw.state = SwState.INSTALLED
            }
            SwState.INSTALLED -> {
                runOne { fire(sw, "onactivate") }
                if (aborted == null && thrown == null) sw.state = SwState.ACTIVE
            }
            SwState.ACTIVE -> {
                var guard = 0
                while (aborted == null && thrown == null && guard < 10000) {
                    val msg = sw.inbox.removeFirstOrNull()
                    if (msg == null) break
                    if (!runOne { deliverMessage(sw, msg) }) break
                    guard = guard + 1
                }
            }
            SwState.TERMINATED -> {
            }
        }
        terminateFromError(sw, aborted, thrown != null)
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /**
     * Intercepcion de una peticion (host o fetch JS): busca el SW ACTIVE
     * cuyo scope casa y es mas largo (URL completa por prefijo o path
     * relativo contra el path de la URL), invoca onfetch con un evento
     * {request: {method, url}, respondWith} y devuelve la
     * respuesta comprometida; sin SW/handler o sin respondWith pasa al
     * transporte (None); un throw del handler rechaza el fetch y TERMINA
     * el SW. respondWith es sincrono (documentado): string = estado 200
     * o objeto {status, body}; un segundo respondWith lanza TypeError.
     */
    fun intercept(method: String, url: String): Intercept {
        var best: ServiceWorker? = null
        var bestLen = 0
        for ((scope, sw) in workers) {
            if (sw.state != SwState.ACTIVE) continue
            val len = scopeMatch(url, scope)
            if (len > bestLen) {
                best = sw
                bestLen = len
            }
        }
        val sw = best
        if (sw == null) return Intercept.None
        val handler = sw.genv.lookupEnv("onfetch")?.vars?.get("onfetch")
        if (handler == null || handler is JsUndefined) return Intercept.None
        if (handler !is JsFun) {
            val err = JsRuntime.makeErrorObj("TypeError", "onfetch no es una funcion")
            terminateFromError(sw, null, true)
            return Intercept.Threw(err)
        }
        val event = JsRuntime.newObj()
        val request = JsRuntime.newObj()
        request.props["method"] = JsProp.Value(JsStr(method))
        request.props["url"] = JsProp.Value(JsStr(url))
        event.props["request"] = JsProp.Value(request)
        sw.responded = false
        sw.response = null
        event.props["respondWith"] = JsProp.Value(JsBuiltins.fn("respondWith") { args, _ ->
            if (sw.responded) {
                throw JsRuntime.throwNative("TypeError", "respondWith ya fue llamado")
            }
            sw.response = parseResponse(JsBuiltins.a0(args))
            sw.responded = true
            JsUndefined
        })
        try {
            JsInterpreter.callFunction(handler, JsUndefined, listOf(event))
        } catch (ex: JsAbort) {
            sw.abortReason = ex.reason
            doTerminate(sw)
            throw ex
        } catch (ex: JsThrown) {
            terminateFromError(sw, null, true)
            return Intercept.Threw(ex.value)
        }
        val r = sw.response
        return if (sw.responded && r != null) Intercept.Responded(r.first, r.second) else Intercept.None
    }

    /**
     * Longitud del prefijo de url que casa el scope (0 = no casa): un
     * scope URL completa casa por prefijo; un scope relativo (que empieza
     * por '/') casa contra el path de la URL (el scope '/app' cubre
     * 'http://x/app/datos': prefijo del path, como los scopes del spec).
     * Gana el casamiento mas largo.
     */
    private fun scopeMatch(url: String, scope: String): Int {
        if (url.startsWith(scope)) return scope.length
        if (scope.startsWith("/") && !scope.startsWith("//")) {
            val start = pathStart(url)
            if (start >= 0 && url.startsWith(scope, start)) return start + scope.length
        }
        return 0
    }

    /** Indice del path dentro de la URL (tras '://'); -1 sin esquema o sin path. */
    private fun pathStart(url: String): Int {
        val scheme = url.indexOf("://")
        if (scheme < 0) return -1
        return url.indexOf('/', scheme + 3)
    }

    /** Termina el SW desde el host: false si ya estaba terminado. */
    fun terminate(sw: ServiceWorker): Boolean {
        if (sw.state == SwState.TERMINATED) return false
        doTerminate(sw)
        return true
    }

    /** Quita el registro del scope: termina su SW y lo borra del registro. */
    fun unregister(scope: String): Boolean {
        val sw = workers.remove(scope)
        if (sw == null) return false
        if (sw.state != SwState.TERMINATED) doTerminate(sw)
        return true
    }

    /** Termina todo, limpia los caches y cierra la sesion (register reabre). */
    fun end() {
        for (sw in workers.values.toList()) {
            if (sw.state != SwState.TERMINATED) doTerminate(sw)
        }
        workers.clear()
        caches.clear()
        if (sessionOpen) {
            JsInterpreter.endLoopSession()
            sessionOpen = false
        }
    }

    /** Llama al global handler del SW; sin handler no hace nada. */
    private fun fire(sw: ServiceWorker, name: String): JsValue {
        val handler = sw.genv.lookupEnv(name)?.vars?.get(name)
        if (handler == null || handler is JsUndefined) return JsUndefined
        if (handler !is JsFun) throw JsRuntime.throwNative("TypeError", name + " no es una funcion")
        return JsInterpreter.callFunction(handler, JsUndefined, emptyList())
    }

    /** Entrega un mensaje al global onmessage del SW; sin handler se descarta. */
    private fun deliverMessage(sw: ServiceWorker, msg: String): JsValue {
        val handler = sw.genv.lookupEnv("onmessage")?.vars?.get("onmessage")
        if (handler == null || handler is JsUndefined) return JsUndefined
        if (handler !is JsFun) throw JsRuntime.throwNative("TypeError", "onmessage no es una funcion")
        return JsInterpreter.callFunction(handler, JsUndefined, listOf(JsStr(msg)))
    }

    /** Respuesta comprometida: string (estado 200) u objeto {status, body}. */
    private fun parseResponse(v: JsValue): Pair<Int, String> = when (v) {
        is JsStr -> Pair(200, v.value)
        is JsObj -> {
            val st = (v.props["status"] as? JsProp.Value)?.v
            val bd = (v.props["body"] as? JsProp.Value)?.v
            Pair(
                if (st is JsNum) st.value.toInt() else 200,
                if (bd is JsStr) bd.value else ""
            )
        }
        else -> throw JsRuntime.throwNative("TypeError", "respondWith espera un string o un objeto {status, body}")
    }

    /** Termina el SW tras un abort o un throw no capturado (como Web Workers). */
    private fun terminateFromError(sw: ServiceWorker, aborted: JsAbortReason?, thrown: Boolean) {
        if (aborted != null) {
            sw.abortReason = aborted
        }
        if (aborted != null || thrown) {
            doTerminate(sw)
        }
    }

    private fun doTerminate(sw: ServiceWorker) {
        sw.state = SwState.TERMINATED
        sw.inbox.clear()
        if (activeCount() == 0 && sessionOpen) {
            JsInterpreter.endLoopSession()
            sessionOpen = false
        }
    }

    private fun installSwApi(sw: ServiceWorker) {
        val g = sw.genv
        g.declare("postMessage", JsBuiltins.fn("postMessage") { args, _ ->
            val v = JsBuiltins.a0(args)
            if (v !is JsStr) throw JsRuntime.throwNative("TypeError", "postMessage espera un string (v2)")
            sw.outbox.addLast(v.value)
            JsUndefined
        })
        g.declare("caches", cachesObject())
    }

    private fun cachesObject(): JsObj {
        val o = JsRuntime.newObj()
        o.props["open"] = JsProp.Value(JsBuiltins.fn("open") { args, _ ->
            cacheOf(JsRuntime.toStringJs(JsBuiltins.a0(args)))
        })
        o.props["has"] = JsProp.Value(JsBuiltins.fn("has") { args, _ ->
            JsBool(caches.containsKey(JsRuntime.toStringJs(JsBuiltins.a0(args))))
        })
        o.props["delete"] = JsProp.Value(JsBuiltins.fn("delete") { args, _ ->
            JsBool(caches.remove(JsRuntime.toStringJs(JsBuiltins.a0(args))) != null)
        })
        o.props["keys"] = JsProp.Value(JsBuiltins.fn("keys") { _, _ ->
            strArr(caches.keys.toList())
        })
        return o
    }

    private fun cacheOf(name: String): JsObj {
        val c = caches.getOrPut(name) { Cache() }
        val o = JsRuntime.newObj()
        o.props["put"] = JsProp.Value(JsBuiltins.fn("put") { args, _ ->
            c.entries[JsRuntime.toStringJs(JsBuiltins.a0(args))] = parseResponse(JsBuiltins.a1(args))
            JsUndefined
        })
        o.props["match"] = JsProp.Value(JsBuiltins.fn("match") { args, _ ->
            val r = c.entries[JsRuntime.toStringJs(JsBuiltins.a0(args))]
            if (r == null) JsNull else responseObj(r.first, r.second)
        })
        o.props["delete"] = JsProp.Value(JsBuiltins.fn("delete") { args, _ ->
            JsBool(c.entries.remove(JsRuntime.toStringJs(JsBuiltins.a0(args))) != null)
        })
        o.props["has"] = JsProp.Value(JsBuiltins.fn("has") { args, _ ->
            JsBool(c.entries.containsKey(JsRuntime.toStringJs(JsBuiltins.a0(args))))
        })
        o.props["keys"] = JsProp.Value(JsBuiltins.fn("keys") { _, _ ->
            strArr(c.entries.keys.toList())
        })
        return o
    }

    private fun responseObj(status: Int, body: String): JsObj {
        val o = JsRuntime.newObj()
        o.props["status"] = JsProp.Value(JsNum(status.toDouble()))
        o.props["body"] = JsProp.Value(JsStr(body))
        return o
    }

    private fun strArr(items: List<String>): JsObj {
        val arr = JsRuntime.newArr()
        for (item in items) arr.elems.add(JsStr(item))
        return arr
    }
}
