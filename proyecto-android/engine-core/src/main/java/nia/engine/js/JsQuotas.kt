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
 *
 * Sesion POR HILO (T-CONC-1): el estado ya no es un singleton plano. Cada
 * hilo (dos pestanas del pool de navegacion, un worker, un plugin) lleva
 * sus propias sesiones en una pila ThreadLocal, asi que el begin/end de un
 * hilo JAMAS pisa los contadores ni desactiva la sesion de otro (antes, el
 * end del primero que terminaba dejaba al resto sin cuotas: pasos y heap
 * sin limite). Las sesiones anidadas en el MISMO hilo se apilan LIFO: al
 * cerrar la interior se restaura la exterior. interrupt() consulta el
 * registro global de sesiones vivas, asi que el corte desde la UI sigue
 * llegando al hilo que ejecuta JS; sin sesiones vivas es un no-op (no
 * envenena la siguiente evaluacion).
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

    /** Sesion de un hilo: topes fijos, contadores propios y bandera volatile. */
    internal class Sesion(val maxSteps: Long, val maxAllocs: Long) {
        @Volatile
        var interruptRequested: Boolean = false
        var steps: Long = 0L
        var allocs: Long = 0L
    }

    // Pila de sesiones del hilo: begin empuja y end saca. Dos pestanas del
    // pool de navegacion llevan pilas independientes; dentro de un mismo
    // hilo, una evaluacion anidada (filtro de plugin durante un fetch de
    // la pagina) apila la suya y al cerrar se restaura la exterior.
    private val pilaHilo: ThreadLocal<ArrayDeque<Sesion>> =
        ThreadLocal.withInitial { ArrayDeque<Sesion>() }

    // Registro global de sesiones vivas: interrupt() se llama desde OTRO
    // hilo (la UI pide el Stop) y debe alcanzar la sesion del hilo que
    // ejecuta JS. begin da de alta y end de baja; adoptar suma otra
    // referencia (la del runner que la hereda), asi la sesion sigue
    // alcanzable para interrupt mientras CUALQUIER hilo la use.
    private val vivas =
        java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Sesion, Int>())

    private fun registrar(s: Sesion) {
        vivas.merge(s, 1) { a, b -> a + b }
    }

    private fun darDeBaja(s: Sesion) {
        val n = vivas[s] ?: return
        if (n <= 1) vivas.remove(s) else vivas[s] = n - 1
    }

    /** Abre la sesion de la evaluacion en ESTE hilo: fija topes y resetea contadores. */
    internal fun begin(limits: JsLimits) {
        val s = Sesion(limits.maxSteps, limits.maxAllocs)
        pilaHilo.get().addLast(s)
        registrar(s)
    }

    /** Cierra la sesion mas reciente de ESTE hilo (LIFO) y restaura la exterior si la habia. */
    internal fun end() {
        val s = pilaHilo.get().removeLastOrNull() ?: return
        darDeBaja(s)
    }

    /** Sesion viva del hilo llamador (la mas interior de su pila). */
    internal fun sesionActual(): Sesion? = pilaHilo.get().lastOrNull()

    /**
     * Adopta en ESTE hilo la sesion capturada en otro (los runners de
     * async/await y generators corren en hilo propio y heredan la sesion
     * del hilo que los lanzo: sin esto su safepoint seria no-op y un
     * bucle infinito dentro del runner JAMAS abortaria por cuotas).
     */
    internal fun adoptar(s: Sesion?) {
        if (s == null) return
        pilaHilo.get().addLast(s)
        registrar(s)
    }

    /**
     * Safepoint de sentencia/expresion (T021): cuenta un paso y aborta con
     * STEP_QUOTA al superar el tope o con INTERRUPTED si otro hilo pidio
     * la interrupcion. Fuera de sesion no hace nada.
     */
    internal fun tick() {
        val s = pilaHilo.get().lastOrNull() ?: return
        s.steps = s.steps + 1L
        if (s.steps > s.maxSteps) throw JsAbort(JsAbortReason.STEP_QUOTA)
        if (s.interruptRequested) throw JsAbort(JsAbortReason.INTERRUPTED)
    }

    /**
     * Safepoint de creacion de objeto/array (T021): cuenta una asignacion
     * contra el tope de heap y aborta con HEAP_QUOTA (o INTERRUPTED).
     */
    internal fun alloc() {
        val s = pilaHilo.get().lastOrNull() ?: return
        s.allocs = s.allocs + 1L
        if (s.allocs > s.maxAllocs) throw JsAbort(JsAbortReason.HEAP_QUOTA)
        if (s.interruptRequested) throw JsAbort(JsAbortReason.INTERRUPTED)
    }

    /**
     * Pide la interrupcion de las evaluaciones en curso (T021): conmuta el
     * flag volatile de cada sesion viva y el hilo que ejecuta JS aborta en
     * el siguiente safepoint. Sin sesiones vivas no hace nada (no envenena
     * la siguiente evaluacion).
     */
    internal fun interrupt() {
        synchronized(vivas) {
            for (s in vivas.keys) s.interruptRequested = true
        }
    }
}
