package nia.engine.js

/**
 * Workers dedicados del motor (T034 v1, RES-0039): cada worker corre su
 * script en un global AISLADO sobre la sesion de motor compartida, con
 * reloj virtual propio (nada corre en tiempo real: el host decide los
 * pumps), mensajes string FIFO en ambas direcciones y lifecycle
 * observable.
 *
 * Modelo cooperativo de UN motor: las cuotas (T021) y los runners de
 * async/await son del MOTOR, no de cada worker; el host bombea UN worker
 * a la vez con pump(w, ms), asi que la ejecucion es determinista. Un
 * worker que agota la cuota o recibe una interrupcion TERMINA con el
 * motivo registrado; un throw no capturado en su script, en onmessage o
 * en un timer tambien lo termina (lifecycle de Web Workers: el error del
 * worker lo mata).
 *
 * Mensajes: SOLO strings en v1 (como las claves y valores de storage). El
 * host envia con postMessage(worker, msg) y se entrega en el siguiente
 * pump como task inmediata (FIFO, antes que los timers, sin depender del
 * reloj) llamando al global onmessage del worker; el worker envia con
 * postMessage(msg) y el host recibe sondeando pollMessage(worker). Sin
 * onmessage el mensaje se descarta; un onmessage que no es funcion o un
 * postMessage del worker sin string lanzan TypeError.
 *
 * Timers del worker: setTimeout/setInterval/clearTimeout/clearInterval
 * sobre el reloj VIRTUAL del worker (avanza solo con pump); los
 * intervalos son fixed-rate con tope de 10000 tasks por pump (paridad
 * con el event loop T025).
 *
 * Exclusiones v1: sin hilos propios, sin SharedArrayBuffer, sin
 * transferables, mensajes solo string, sesion de cuotas compartida por
 * motor (documentada), sin eventos error/MessageEvent (el host sondea),
 * service workers/offline en v2.
 */
class JsWorkers(private val limits: JsLimits = JsLimits()) {

    /** Estado del lifecycle del worker (T034): RUNNING hasta terminar. */
    enum class WorkerState { RUNNING, TERMINATED }

    private class Timer(
        val id: Long,
        var deadline: Long,
        val every: Long,
        val repeating: Boolean,
        val cb: JsValue,
        val args: List<JsValue>,
    )

    /** Handle de un worker: identidad, colas y estado observable. */
    class Worker internal constructor(
        val id: Long,
        internal val genv: Env,
    ) {
        internal val timers = ArrayList<Timer>()
        internal val inbox = ArrayDeque<String>()
        internal val outbox = ArrayDeque<String>()
        internal var now: Long = 0
        internal var state: WorkerState = WorkerState.RUNNING
        internal var abortReason: JsAbortReason? = null

        /** Cierto mientras el worker no haya terminado. */
        val running: Boolean get() = state == WorkerState.RUNNING

        /** Motivo del ultimo abort (cuota/interrupcion T021), si hubo. */
        fun lastAbort(): JsAbortReason? = abortReason

        /** Mensajes del host pendientes de entrega. */
        fun pendingMessages(): Int = inbox.size

        /** Timers vivos del worker. */
        fun pendingTimers(): Int = timers.size
    }

    private val workers = LinkedHashMap<Long, Worker>()
    private var nextId: Long = 1
    private var nextTimerId: Long = 1
    private var sessionOpen = false

    /** Workers activos (RUNNING) del manager. */
    fun activeCount(): Int = workers.values.count { it.state == WorkerState.RUNNING }

    /** Cierto si al worker le queda trabajo: mensajes sin entregar o timers vivos. */
    fun hasPendingWork(w: Worker): Boolean =
        w.state == WorkerState.RUNNING && (w.inbox.isNotEmpty() || w.timers.isNotEmpty())

    /**
     * Crea un worker y corre su script: global aislado + API del worker,
     * sesion de motor abierta bajo demanda (beginWorkerSession). Un throw
     * no capturado o un abort de cuotas/interrupcion en el script
     * TERMINAN el worker en el spawn (lifecycle de Web Workers).
     */
    fun spawn(source: String): Worker {
        if (!sessionOpen) {
            JsInterpreter.beginWorkerSession(limits)
            sessionOpen = true
        }
        val w = Worker(nextId, JsInterpreter.makeWorkerGlobal())
        nextId = nextId + 1
        installWorkerApi(w)
        workers[w.id] = w
        val r = JsInterpreter.runInSession(source, w.genv)
        terminateFromError(w, r.abortReason, r.thrown)
        return w
    }

    /** Encola un mensaje hacia el worker: false si esta terminado. */
    fun postMessage(w: Worker, message: String): Boolean {
        if (w.state != WorkerState.RUNNING) return false
        w.inbox.addLast(message)
        return true
    }

    /** Saca (FIFO) un mensaje que el worker envio al host; null si no hay. */
    fun pollMessage(w: Worker): String? = w.outbox.removeFirstOrNull()

    /**
     * Bombea el worker ms de su reloj virtual: fase 1 entrega los mensajes
     * del host (tasks inmediatas, FIFO, sin depender del reloj) y fase 2
     * corre los timers vencidos por (deadline, orden de insercion), con
     * drenaje de microtasks tras cada task y tope de 10000 tasks. Abort de
     * cuota/interrupcion o throw no capturado terminan el worker.
     */
    fun pump(w: Worker, ms: Long): JsEvalResult {
        if (w.state != WorkerState.RUNNING) {
            return JsEvalResult(JsUndefined, true, JsStr("worker terminado"), null)
        }
        w.now = w.now + ms
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

        while (aborted == null && w.inbox.isNotEmpty()) {
            val msg = w.inbox.removeFirst()
            if (!runOne { deliverMessage(w, msg) }) break
        }
        var guard = 0
        while (aborted == null && guard < 10000) {
            val t = nextDue(w) ?: break
            if (!runOne { JsInterpreter.callFunction(t.cb, JsUndefined, t.args) }) break
            if (t.repeating) {
                t.deadline = t.deadline + t.every
            } else {
                w.timers.remove(t)
            }
            guard = guard + 1
        }
        terminateFromError(w, aborted, thrown != null)
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /** Termina el worker desde el host: false si ya estaba terminado. */
    fun terminate(w: Worker): Boolean {
        if (w.state != WorkerState.RUNNING) return false
        doTerminate(w)
        return true
    }

    /** Termina todos los workers y cierra la sesion del motor (spawn reabre). */
    fun end() {
        for (w in workers.values.toList()) {
            if (w.state == WorkerState.RUNNING) doTerminate(w)
        }
        workers.clear()
        if (sessionOpen) {
            JsInterpreter.endLoopSession()
            sessionOpen = false
        }
    }

    /** Entrega un mensaje al global onmessage del worker; sin handler se descarta. */
    private fun deliverMessage(w: Worker, msg: String): JsValue {
        val handler = w.genv.lookupEnv("onmessage")?.vars?.get("onmessage")
        if (handler == null || handler is JsUndefined) return JsUndefined
        if (handler !is JsFun) throw JsRuntime.throwNative("TypeError", "onmessage no es una funcion")
        return JsInterpreter.callFunction(handler, JsUndefined, listOf(JsStr(msg)))
    }

    /** Termina el worker tras un abort o un throw no capturado (como Web Workers). */
    private fun terminateFromError(w: Worker, aborted: JsAbortReason?, thrown: Boolean) {
        if (aborted != null) {
            w.abortReason = aborted
        }
        if (aborted != null || thrown) {
            doTerminate(w)
        }
    }

    private fun doTerminate(w: Worker) {
        w.state = WorkerState.TERMINATED
        w.timers.clear()
        w.inbox.clear()
        if (activeCount() == 0 && sessionOpen) {
            JsInterpreter.endLoopSession()
            sessionOpen = false
        }
    }

    private fun installWorkerApi(w: Worker) {
        val g = w.genv
        g.declare("postMessage", JsBuiltins.fn("postMessage") { args, _ ->
            val v = JsBuiltins.a0(args)
            if (v !is JsStr) throw JsRuntime.throwNative("TypeError", "postMessage espera un string (v1)")
            w.outbox.addLast(v.value)
            JsUndefined
        })
        g.declare("setTimeout", JsBuiltins.fn("setTimeout") { args, _ ->
            val cb = JsBuiltins.a0(args)
            val delay = delayOf(args)
            val extra = if (args.size > 2) args.subList(2, args.size) else emptyList()
            JsNum(schedule(w, cb, extra, delay, false).toDouble())
        })
        g.declare("clearTimeout", JsBuiltins.fn("clearTimeout") { args, _ ->
            clear(w, handleOf(args))
            JsUndefined
        })
        g.declare("setInterval", JsBuiltins.fn("setInterval") { args, _ ->
            val cb = JsBuiltins.a0(args)
            val delay = delayOf(args)
            val extra = if (args.size > 2) args.subList(2, args.size) else emptyList()
            JsNum(schedule(w, cb, extra, delay, true).toDouble())
        })
        g.declare("clearInterval", JsBuiltins.fn("clearInterval") { args, _ ->
            clear(w, handleOf(args))
            JsUndefined
        })
    }

    private fun schedule(w: Worker, cb: JsValue, args: List<JsValue>, delay: Long, repeating: Boolean): Long {
        val id = nextTimerId
        nextTimerId = nextTimerId + 1
        w.timers.add(Timer(id, w.now + delay, delay, repeating, cb, args))
        return id
    }

    private fun clear(w: Worker, id: Long) {
        w.timers.removeAll { it.id == id }
    }

    /** Siguiente timer vencido: minima deadline, empates por orden de insercion (id). */
    private fun nextDue(w: Worker): Timer? {
        var best: Timer? = null
        for (t in w.timers) {
            if (t.deadline > w.now) continue
            val b = best
            if (b == null || t.deadline < b.deadline || (t.deadline == b.deadline && t.id < b.id)) {
                best = t
            }
        }
        return best
    }

    /** Delay en ms: NaN y negativos a 0; tope 2^31-1 (paridad con T025). */
    private fun delayOf(args: List<JsValue>): Long {
        if (args.size < 2) return 0
        val n = JsRuntime.toNumber(args[1])
        if (n.isNaN() || n < 0) return 0
        if (n > 2147483647.0) return 2147483647L
        return n.toLong()
    }

    private fun handleOf(args: List<JsValue>): Long {
        if (args.isEmpty()) return -1
        val n = JsRuntime.toNumber(args[0])
        if (n.isNaN() || n < 0) return -1
        return n.toLong()
    }
}
