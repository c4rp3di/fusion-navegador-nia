package nia.engine.js

/**
 * Cuotas de ejecucion de JavaScript (T021, RES-0029).
 *
 * Toda evaluacion corre dentro de una sesion de cuotas: cada sentencia y
 * cada expresion que el interprete despacha son un safepoint (tick cuenta
 * un paso contra el tope de pasos) y cada objeto o array creado cuenta una
 * asignacion contra el tope de heap (alloc desde JsRuntime.newObj/newArr).
 * Superar un tope aborta la evaluacion con JsAbort, que NO es JsThrown:
 * el try/catch de JavaScript no puede capturarlo y siempre llega al punto
 * de entrada, que lo reporta como evaluacion interrumpida.
 *
 * Una sesion por evaluacion: begin abre (fija topes y resetea contadores y
 * banderas) y end cierra. El flag de interrupcion es volatile para que otro
 * hilo pueda pedir el corte: el hilo que ejecuta JS aborta en el siguiente
 * safepoint (la UI pide la interrupcion y nunca se bloquea esperando).
 */

/** Motivo del abort de una evaluacion con cuotas (T021; DEBUG_PAUSE: T028). */
enum class JsAbortReason { INTERRUPTED, STEP_QUOTA, HEAP_QUOTA, DEBUG_PAUSE }

/**
 * Abort de cuotas (T021): interno del motor y NO es JsThrown, para que el
 * codigo JavaScript no pueda capturarlo con try/catch; siempre llega al
 * punto de entrada de la evaluacion.
 */
class JsAbort(val reason: JsAbortReason) : RuntimeException(reason.name, null, false, false) {
    override fun toString(): String = "JsAbort(" + reason.name + ")"
}

/** Topes de una evaluacion: pasos (sentencias/expresiones) y heap (objetos). */
class JsLimits(val maxSteps: Long = 100_000_000L, val maxAllocs: Long = 10_000_000L)

/**
 * Sesion de cuotas de la evaluacion en curso (T021). Los safepoints son
 * baratos (una lectura volatile y un incremento); fuera de sesion son
 * no-op, asi que el setup del interprete y lo que corre fuera de evaluate
 * no cuenta contra la cuota.
 */
object JsQuotas {

    @Volatile
    internal var active: Boolean = false

    @Volatile
    internal var interruptRequested: Boolean = false

    internal var maxSteps: Long = 0L
    internal var maxAllocs: Long = 0L
    internal var steps: Long = 0L
    internal var allocs: Long = 0L

    /** Abre la sesion de la evaluacion: fija topes y resetea contadores. */
    internal fun begin(limits: JsLimits) {
        maxSteps = limits.maxSteps
        maxAllocs = limits.maxAllocs
        steps = 0L
        allocs = 0L
        interruptRequested = false
        active = true
    }

    /** Cierra la sesion de la evaluacion. */
    internal fun end() {
        active = false
        interruptRequested = false
    }

    /**
     * Safepoint de sentencia/expresion (T021): cuenta un paso y aborta con
     * STEP_QUOTA al superar el tope o con INTERRUPTED si otro hilo pidio
     * la interrupcion. Fuera de sesion no hace nada.
     */
    internal fun tick() {
        if (!active) return
        steps = steps + 1L
        if (steps > maxSteps) throw JsAbort(JsAbortReason.STEP_QUOTA)
        if (interruptRequested) throw JsAbort(JsAbortReason.INTERRUPTED)
    }

    /**
     * Safepoint de creacion de objeto/array (T021): cuenta una asignacion
     * contra el tope de heap y aborta con HEAP_QUOTA (o INTERRUPTED).
     */
    internal fun alloc() {
        if (!active) return
        allocs = allocs + 1L
        if (allocs > maxAllocs) throw JsAbort(JsAbortReason.HEAP_QUOTA)
        if (interruptRequested) throw JsAbort(JsAbortReason.INTERRUPTED)
    }

    /**
     * Pide la interrupcion de la evaluacion en curso (T021): conmuta el
     * flag volatile y el hilo que ejecuta JS aborta en el siguiente
     * safepoint. Sin sesion abierta no hace nada (no envenena la
     * siguiente evaluacion).
     */
    internal fun interrupt() {
        if (active) interruptRequested = true
    }
}
