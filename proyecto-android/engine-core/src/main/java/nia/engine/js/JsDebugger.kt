package nia.engine.js

import nia.engine.dom.DomDocument
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Snapshot de una pausa del debugger (T028, RES-0034): solo datos, sin
 * objetos vivos del motor. Los scopes van del mas interno al global con
 * el dump canonico de JsRuntime por binding (nombres ordenados); los
 * scopes vacios se omiten.
 */
class DebugSnapshot(
    val line: Int,
    val reason: String,
    val scopes: List<String>,
    val depth: Int
) {
    override fun toString(): String =
        "DebugSnapshot(line=" + line + ",reason=" + reason + ",depth=" + depth +
            ",scopes=" + scopes.joinToString(";") + ")"
}

/**
 * Debugger de JavaScript (T028 v1, RES-0034): pausa/paso/scopes sin
 * congelar la UI.
 *
 * Mecanica: pausa cooperativa sobre los safepoints por sentencia de las
 * cuotas (T021). El hook interno debugHook de JsInterpreter observa cada
 * sentencia ejecutada (junto al tick); al pausar se lanza JsAbort con el
 * motivo DEBUG_PAUSE, que NO es JsThrown: el try/catch de JavaScript no
 * puede capturarlo y siempre llega al punto de entrada (la UI pide la
 * pausa y nunca se bloquea esperando). El snapshot se captura ANTES de
 * lanzar (JsAbort solo transporta el motivo): linea 1-based del inicio
 * de la sentencia en la fuente, scopes de la cadena de entornos con el
 * dump canonico de cada binding y profundidad de pila (fronteras
 * fnScope de la cadena de Env).
 *
 * Con una InspectorSession (T008) la pausa emite un evento custom de kind
 * debugger con payload de linea, motivo, profundidad y scopes, redactado
 * en la ingesta por SecretRedactor.
 *
 * Exclusiones v1: sin reanudacion real (la pila de ejecucion no se
 * conserva: continuar = re-evaluar), sin step-in/over/out granular
 * (solo presupuesto de pasos), sin breakpoints condicionales ni
 * watchpoints, sin UI (la integracion con DevTools llega en otra fase).
 */
class JsDebugger(
    private val source: String,
    private val session: InspectorSession? = null,
    private val context: EventContext? = null
) {

    init {
        if (session != null) {
            require(context != null) { "context obligatorio con sesion de inspector" }
        }
    }

    private val lineStarts: IntArray = computeLineStarts(source)
    private val breakpoints = HashSet<Int>()
    private var stepBudget: Long = -1L
    private var steps: Long = 0L

    /** Snapshot de la ultima pausa; null si la evaluacion no se pauso. */
    var lastSnapshot: DebugSnapshot? = null
        private set

    /** Fija un breakpoint en la linea 1-based dada. */
    fun breakOn(line: Int): JsDebugger {
        breakpoints.add(line)
        return this
    }

    /** Presupuesto de pasos: pausa antes de la sentencia n+1 ejecutada. */
    fun stepBudget(n: Long): JsDebugger {
        stepBudget = n
        return this
    }

    fun evaluate(limits: JsLimits = JsLimits()): JsEvalResult =
        install { JsInterpreter.evaluate(source, limits) }

    fun evaluateWithDom(doc: DomDocument, limits: JsLimits = JsLimits()): JsEvalResult =
        install { JsInterpreter.evaluateWithDom(source, doc, limits) }

    /** Instala el hook, corre la evaluacion y lo desmonta siempre. */
    private fun <T> install(body: () -> T): T {
        steps = 0L
        val previous = JsInterpreter.debugHook
        JsInterpreter.debugHook = { s, env -> onStatement(s, env) }
        try {
            return body()
        } finally {
            JsInterpreter.debugHook = previous
        }
    }

    /** Safepoint por sentencia (T028): lo llama runStatement junto al tick de T021. */
    private fun onStatement(s: JsStmt, env: Env) {
        steps = steps + 1L
        val line = lineOf(s.start)
        val hitBp = breakpoints.contains(line)
        val hitStep = stepBudget > 0L && steps > stepBudget
        if (!hitBp && !hitStep) return
        pause(line, if (hitBp) "breakpoint" else "step", env)
    }

    /** Captura el snapshot, emite el evento del inspector y aborta con DEBUG_PAUSE. */
    private fun pause(line: Int, reason: String, env: Env) {
        val scopes = ArrayList<String>()
        var depth = 0
        var e: Env? = env
        while (e != null) {
            if (e.fnScope) depth = depth + 1
            if (e.vars.isNotEmpty()) {
                val sb = StringBuilder()
                for (n in e.vars.keys.sorted()) {
                    if (sb.isNotEmpty()) sb.append(",")
                    sb.append(n).append("=").append(JsRuntime.dumpValue(e.vars[n] ?: JsUndefined))
                }
                scopes.add(sb.toString())
            }
            e = e.parent
        }
        val snap = DebugSnapshot(line, reason, scopes, depth)
        lastSnapshot = snap
        if (session != null && context != null) {
            val payload = LinkedHashMap<String, String>()
            payload["line"] = line.toString()
            payload["reason"] = reason
            payload["depth"] = depth.toString()
            payload["scopes"] = scopes.joinToString(";")
            session.custom(context, "debugger", EventLevel.DEBUG, payload)
        }
        throw JsAbort(JsAbortReason.DEBUG_PAUSE)
    }

    /** Linea 1-based del offset dado sobre los inicios de linea precomputados. */
    private fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    companion object {

        /** Inicios de linea: offset 0 y cada posicion tras un salto de linea. */
        internal fun computeLineStarts(src: String): IntArray {
            val starts = ArrayList<Int>()
            starts.add(0)
            for (i in src.indices) {
                if (src[i] == '\n') starts.add(i + 1)
            }
            return starts.toIntArray()
        }
    }
}

/**
 * Estado de una sesion de debug (T028 v2): RUNNING mientras evalua,
 * PAUSED con el hilo de JS parado en un safepoint y FINISHED con el
 * resultado disponible.
 */
enum class DebugState { RUNNING, PAUSED, FINISHED }

/** Politica del tramo en curso tras la ultima pausa (T028 v2). */
internal enum class DebugPolicy { RUN, STEP_IN, STEP_OVER, STEP_OUT }

/** Comandos que la UI envia a la sesion de debug (T028 v2). */
internal sealed class DebugCmd {
    object Resume : DebugCmd()
    object StepIn : DebugCmd()
    object StepOver : DebugCmd()
    object StepOut : DebugCmd()
    object Stop : DebugCmd()
    class Inspect(val expr: String, val reply: LinkedBlockingQueue<String>) : DebugCmd()
}

/**
 * Sesion de debug con REANUDACION REAL (T028 v2, RES-0037): el programa
 * corre en un hilo de debug propio y al pausar NO se aborta: se publica
 * el snapshot y el hilo de JS SE BLOQUEA en la cola de comandos. La pila
 * de Kotlin vive, asi que resume/step-in/step-over/step-out continuan
 * desde el punto exacto sin re-evaluar (la limitacion fundamental de v1).
 * Los comandos de la UI no bloquean; inspect evalua en el entorno vivo.
 *
 * Mecanica: sobre el mismo hook de safepoints por sentencia de v1 (T021).
 * El hook solo actua en el hilo de la sesion: los callbacks de
 * async/generators corren en runners con handoff estricto y bloquearlos
 * deadlockearia (exclusion documentada: solo el flujo principal pausa).
 *
 * Exclusiones v2: sin breakpoints condicionales ni watchpoints (v3), sin
 * pausa dentro de runners async/generators, inspect puede mutar el
 * entorno (efectos de un REPL real, documentado), una sesion por
 * programa (sin reattach) y sin UI Android (fase posterior).
 */
class DebugSession(
    private val source: String,
    private val session: InspectorSession? = null,
    private val context: EventContext? = null
) {

    init {
        if (session != null) {
            require(context != null) { "context obligatorio con sesion de inspector" }
        }
    }

    private val lineStarts: IntArray = JsDebugger.computeLineStarts(source)
    private val breakpoints = HashSet<Int>()
    private val inbox = LinkedBlockingQueue<DebugCmd>()

    @Volatile
    var state: DebugState = DebugState.RUNNING
        private set

    /** Snapshot de la ultima pausa; null si todavia no se ha pausado. */
    @Volatile
    var lastSnapshot: DebugSnapshot? = null
        private set

    /** Resultado de la evaluacion; disponible al llegar a FINISHED. */
    @Volatile
    var result: JsEvalResult? = null
        private set

    @Volatile
    private var policy: DebugPolicy = DebugPolicy.RUN

    @Volatile
    private var targetDepth: Int = 0

    @Volatile
    private var pauseRequested: Boolean = false

    @Volatile
    private var sessionThread: Thread? = null

    /** Fija un breakpoint en la linea 1-based dada. */
    fun breakOn(line: Int): DebugSession {
        breakpoints.add(line)
        return this
    }

    /**
     * Lanza el hilo de debug que evalua el programa (doc opcional para
     * los bindings DOM). El hook se monta solo durante la evaluacion.
     */
    fun start(doc: DomDocument? = null, limits: JsLimits = JsLimits()): DebugSession {
        val body = Runnable {
            val previous = JsInterpreter.debugHook
            JsInterpreter.debugHook = { s, env -> onStatement(s, env) }
            try {
                val r = if (doc != null) {
                    JsInterpreter.evaluateWithDom(source, doc, limits)
                } else {
                    JsInterpreter.evaluate(source, limits)
                }
                result = r
            } finally {
                JsInterpreter.debugHook = previous
                state = DebugState.FINISHED
            }
        }
        val t = Thread(body)
        t.isDaemon = true
        sessionThread = t
        t.start()
        return this
    }

    /** Reanuda la ejecucion desde el punto exacto de la pausa. */
    fun resume() { send(DebugCmd.Resume) }

    /** Pausa en la siguiente sentencia ejecutada (entra en las llamadas). */
    fun stepIn() { send(DebugCmd.StepIn) }

    /** Siguiente sentencia del mismo nivel o menos profunda. */
    fun stepOver() { send(DebugCmd.StepOver) }

    /** Siguiente sentencia del llamador (fuera de la funcion actual). */
    fun stepOut() { send(DebugCmd.StepOut) }

    /** Pide pausa en el siguiente safepoint; no bloquea al que llama. */
    fun pause() { pauseRequested = true }

    /** Termina la evaluacion: pausada aborta con INTERRUPTED, corriendo interrumpe. */
    fun stop() {
        if (state == DebugState.PAUSED) {
            inbox.add(DebugCmd.Stop)
        } else {
            JsInterpreter.interrupt()
        }
    }

    /**
     * Evalua una expresion en el entorno PAUSADO: la evalua el propio
     * hilo de JS (handoff intacto) y devuelve el dump canonico; en un
     * throw de JavaScript devuelve "uncaught <dump>". Fuera de PAUSED o
     * si la sesion se reanuda antes, devuelve null.
     */
    fun inspect(expr: String, timeoutMs: Long = 5000L): String? {
        if (state != DebugState.PAUSED) return null
        val reply = LinkedBlockingQueue<String>()
        inbox.add(DebugCmd.Inspect(expr, reply))
        return reply.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Espera a la pausa (tests/UI); null si agota el tiempo. */
    fun awaitPause(timeoutMs: Long = 5000L): DebugSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = lastSnapshot
            if (state == DebugState.PAUSED && s != null) return s
            Thread.sleep(5)
        }
        return null
    }

    /** Espera a que la evaluacion termine; false si agota el tiempo. */
    fun awaitDone(timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state == DebugState.FINISHED) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Encola un comando si la sesion esta pausada; si no, no-op. */
    private fun send(cmd: DebugCmd) {
        if (state == DebugState.PAUSED) inbox.add(cmd)
    }

    /** Safepoint por sentencia (T028 v2): solo actua el hilo de la sesion. */
    private fun onStatement(s: JsStmt, env: Env) {
        if (Thread.currentThread() !== sessionThread) return
        val line = lineOf(s.start)
        val depth = depthOf(env)
        val hitBp = breakpoints.contains(line)
        val wantPause = pauseRequested
        val hit = when (policy) {
            DebugPolicy.RUN -> hitBp || wantPause
            DebugPolicy.STEP_IN -> true
            DebugPolicy.STEP_OVER -> depth <= targetDepth
            DebugPolicy.STEP_OUT -> depth < targetDepth
        }
        if (!hit) return
        val reason = if (hitBp) "breakpoint" else if (wantPause) "pause" else "step"
        pauseRequested = false
        park(env, line, depth, reason)
    }

    /** Publica el snapshot y bloquea el hilo de JS hasta el proximo comando. */
    private fun park(env: Env, line: Int, depth: Int, reason: String) {
        val snap = capture(env, line, depth, reason)
        lastSnapshot = snap
        state = DebugState.PAUSED
        emitEvent(snap)
        while (true) {
            val cmd = inbox.take()
            when (cmd) {
                is DebugCmd.Resume -> { policy = DebugPolicy.RUN; state = DebugState.RUNNING; return }
                is DebugCmd.StepIn -> { policy = DebugPolicy.STEP_IN; state = DebugState.RUNNING; return }
                is DebugCmd.StepOver -> { policy = DebugPolicy.STEP_OVER; targetDepth = depth; state = DebugState.RUNNING; return }
                is DebugCmd.StepOut -> { policy = DebugPolicy.STEP_OUT; targetDepth = depth; state = DebugState.RUNNING; return }
                is DebugCmd.Stop -> { state = DebugState.RUNNING; throw JsAbort(JsAbortReason.INTERRUPTED) }
                is DebugCmd.Inspect -> cmd.reply.offer(inspectIn(cmd.expr, env))
            }
        }
    }

    /** Evalua la expresion en el env pausado; la llama el hilo de JS. */
    private fun inspectIn(expr: String, env: Env): String =
        try {
            JsRuntime.dumpValue(JsInterpreter.debugEvalExpression(expr, env))
        } catch (ex: JsThrown) {
            "uncaught " + JsRuntime.dumpValue(ex.value)
        } catch (ex: JsAbort) {
            throw ex
        } catch (ex: Exception) {
            "error " + (ex.message ?: "inesperado")
        }

    /** Captura scopes/profundidad como v1: cadena de Env, dump canonico. */
    private fun capture(env: Env, line: Int, depth: Int, reason: String): DebugSnapshot {
        val scopes = ArrayList<String>()
        var e: Env? = env
        while (e != null) {
            if (e.vars.isNotEmpty()) {
                val sb = StringBuilder()
                for (n in e.vars.keys.sorted()) {
                    if (sb.isNotEmpty()) sb.append(",")
                    sb.append(n).append("=").append(JsRuntime.dumpValue(e.vars[n] ?: JsUndefined))
                }
                scopes.add(sb.toString())
            }
            e = e.parent
        }
        return DebugSnapshot(line, reason, scopes, depth)
    }

    /** Evento del inspector en la pausa (redactado en la ingesta, como v1). */
    private fun emitEvent(snap: DebugSnapshot) {
        if (session != null && context != null) {
            val payload = LinkedHashMap<String, String>()
            payload["line"] = snap.line.toString()
            payload["reason"] = snap.reason
            payload["depth"] = snap.depth.toString()
            payload["scopes"] = snap.scopes.joinToString(";")
            session.custom(context, "debugger", EventLevel.DEBUG, payload)
        }
    }

    /** Profundidad de pila: fronteras fnScope de la cadena de Env. */
    private fun depthOf(env: Env): Int {
        var d = 0
        var e: Env? = env
        while (e != null) {
            if (e.fnScope) d = d + 1
            e = e.parent
        }
        return d
    }

    /** Linea 1-based del offset dado (misma busqueda binaria que v1). */
    private fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}
