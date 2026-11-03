package nia.engine.js

import nia.engine.dom.DomDocument
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession

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
