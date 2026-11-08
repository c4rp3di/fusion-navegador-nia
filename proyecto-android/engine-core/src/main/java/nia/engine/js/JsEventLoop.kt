package nia.engine.js

import nia.engine.dom.DomDocument

/**
 * Event loop del motor JS (T025 v1, RES-0032): tasks (timers) y microtasks
 * con reloj controlable y orden reproducible.
 *
 * Dos colas sobre el motor existente: las microtasks ya viven en
 * JsRuntime.microtasks (Promise, await, queueMicrotask) y las tasks son
 * timers programados desde JS con handles monotonos. El reloj del loop es
 * VIRTUAL: avanza solo con advance(ms) (nada corre en hilos de tiempo
 * real), lo que hace el orden observable determinista y testeable.
 *
 * Orden del estandar HTML (reproducible): la fase sincrona y cada task
 * drenan las microtasks en vacio antes de la siguiente task; las tasks
 * vencidas corren por (deadline, orden de insercion); los intervalos son
 * fixed-rate (deadline += every) con tope de 10000 tasks por pump (paridad
 * con el tope de microtasks del drain). Un throw no capturado en una task
 * se reporta en el resultado y NO detiene el resto de tasks.
 *
 * La sesion se MANTIENE entre pumps: genv, cuotas y runners de async/await
 * vivos mientras el host avanza el reloj (un await asentado por el callback
 * de un setTimeout continua en el pump siguiente); end() corta la sesion.
 * El JsAbort (cuotas o interrupcion) aborta el pump y se reporta como
 * interrupted; la sesion se cierra con end().
 *
 * C5 v1 (R0280, RES-0121): evaluate(source, doc) abre la sesion con los
 * bindings DOM del motor (JsDomBindings) ademas de la API de timers: es
 * el camino del script de documento del contrato E4-C5, que corre al
 * inicio del documento y muere con end() (cancelacion al navegar); el
 * reloj sigue siendo virtual y solo avanza con advance().
 *
 * Exclusiones v1: un loop por sesion (el estado del interprete es global),
 * sin requestAnimationFrame, MessageChannel ni tasks de IO, sin
 * anidamiento, y el reloj no avanza solo (sin hilo de tiempo real).
 */
class JsEventLoop(private val limits: JsLimits = JsLimits()) {

    private class Timer(
        val id: Long,
        val cb: JsValue,
        val args: List<JsValue>,
        var deadline: Long,
        val every: Long,
        val repeating: Boolean
    )

    private val timers = ArrayList<Timer>()
    private var nextId: Long = 1
    private var genv: Env? = null

    /** Reloj virtual del loop en ms: SOLO avanza con advance(). */
    var now: Long = 0
        private set

    /** Cierto si quedan tasks pendientes (timers vivos). */
    val hasPendingTasks: Boolean get() = timers.isNotEmpty()

    /** Abre la sesion, corre la fase sincrona del programa y drena microtasks. */
    fun evaluate(source: String): JsEvalResult {
        val g = JsInterpreter.startLoopSession(limits)
        genv = g
        installTimerApi(g)
        return JsInterpreter.runInSession(source, g)
    }

    /**
     * C5 v1 (R0280, RES-0121, E4): abre la sesion con los bindings DOM
     * del motor (document sobre doc, JsDomBindings) ademas de la API de
     * timers; el script de documento corre con cuotas y su sesion muere
     * con end() (la cancelacion al navegar del contrato).
     */
    fun evaluate(source: String, doc: DomDocument): JsEvalResult {
        val g = JsInterpreter.startLoopSession(limits)
        genv = g
        JsDomBindings.install(g, doc)
        installTimerApi(g)
        return JsInterpreter.runInSession(source, g)
    }

    /** Corre mas codigo en la MISMA sesion (mismo genv y variables) y drena microtasks. */
    fun eval(source: String): JsEvalResult {
        val g = genv ?: return JsEvalResult(JsUndefined, true, JsStr("event loop session not started"), null)
        return JsInterpreter.runInSession(source, g)
    }

    /**
     * Avanza el reloj ms y corre las tasks vencidas: tras cada task se
     * drenan las microtasks en vacio antes de la siguiente (checkpoint del
     * estandar HTML). Un throw no capturado en una task se reporta en el
     * resultado y NO detiene el resto de tasks.
     */
    fun advance(ms: Long): JsEvalResult {
        if (genv == null) {
            return JsEvalResult(JsUndefined, true, JsStr("event loop session not started"), null)
        }
        now = now + ms
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        var aborted: JsAbortReason? = null
        var guard = 0
        while (aborted == null && guard < 10000) {
            val t = nextDue() ?: break
            try {
                val v = JsInterpreter.callFunction(t.cb, JsUndefined, t.args)
                if (v !is JsUndefined) last = v
            } catch (ex: JsAbort) {
                aborted = ex.reason
                break
            } catch (ex: Exception) {
                if (ex is JsThrown) {
                    if (thrown == null) thrown = ex.value
                } else {
                    throw ex
                }
            }
            if (t.repeating) {
                t.deadline = t.deadline + t.every
            } else {
                timers.remove(t)
            }
            try {
                val mv = JsInterpreter.drainInSession()
                if (mv != null) last = mv
            } catch (ex: JsAbort) {
                aborted = ex.reason
                break
            }
            guard++
        }
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /** Cierra la sesion: corta runners, limpia colas/registros y las cuotas. */
    fun end() {
        JsInterpreter.endLoopSession()
        timers.clear()
        genv = null
    }

    /** Siguiente task vencida: minima deadline, empates por orden de insercion (id). */
    private fun nextDue(): Timer? {
        var best: Timer? = null
        for (t in timers) {
            if (t.deadline > now) continue
            val b = best
            if (b == null || t.deadline < b.deadline || (t.deadline == b.deadline && t.id < b.id)) best = t
        }
        return best
    }

    private fun schedule(cb: JsValue, args: List<JsValue>, delay: Long, repeating: Boolean): Long {
        val t = Timer(nextId, cb, args, now + delay, delay, repeating)
        nextId = nextId + 1
        timers.add(t)
        return t.id
    }

    private fun clear(id: Long) {
        val it = timers.iterator()
        while (it.hasNext()) {
            if (it.next().id == id) it.remove()
        }
    }

    private fun installTimerApi(g: Env) {
        g.declare("setTimeout", JsBuiltins.fn("setTimeout") { args, _ ->
            val cb = JsBuiltins.a0(args)
            val delay = delayOf(args)
            val extra = if (args.size > 2) args.subList(2, args.size) else emptyList()
            JsNum(schedule(cb, extra, delay, false).toDouble())
        })
        g.declare("clearTimeout", JsBuiltins.fn("clearTimeout") { args, _ ->
            clear(handleOf(args))
            JsUndefined
        })
        g.declare("setInterval", JsBuiltins.fn("setInterval") { args, _ ->
            val cb = JsBuiltins.a0(args)
            val delay = delayOf(args)
            val extra = if (args.size > 2) args.subList(2, args.size) else emptyList()
            JsNum(schedule(cb, extra, delay, true).toDouble())
        })
        g.declare("clearInterval", JsBuiltins.fn("clearInterval") { args, _ ->
            clear(handleOf(args))
            JsUndefined
        })
        g.declare("queueMicrotask", JsBuiltins.fn("queueMicrotask") { args, _ ->
            val cb = JsBuiltins.a0(args)
            JsRuntime.microtasks.addLast {
                JsInterpreter.callFunction(cb, JsUndefined, emptyList())
            }
            JsUndefined
        })
    }

    /** Delay en ms: NaN y negativos a 0; suelo para positivos; tope 2^31-1. */
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
