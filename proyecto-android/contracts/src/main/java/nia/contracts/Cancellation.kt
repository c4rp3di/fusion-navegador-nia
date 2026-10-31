package nia.contracts

/** Reloj inyectable: los tests usan uno falso; producción usa el del sistema. */
interface WallClock {
    fun nowMillis(): Long
}

class SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Por qué se canceló una operación. */
sealed class CancelReason {
    object Explicit : CancelReason() {
        override fun toString(): String = "explicit"
    }

    data class Deadline(val deadlineMillis: Long) : CancelReason()
    data class Replaced(val by: String) : CancelReason()
}

/**
 * Cancelación cooperativa (maestro, sección 4): el token no interrumpe
 * nada por la fuerza; quien hace el trabajo pregunta al token en sus
 * puntos de paso. Los deadlines se comprueban explícitamente con
 * [CancellationToken.checkDeadline] para que el reloj sea controlable.
 */
class CancellationTokenSource(private val clock: WallClock = SystemWallClock()) {

    private val state = CancellationState(clock)

    val token: CancellationToken get() = CancellationToken(state)

    /**
     * Devuelve true solo si ESTA llamada fue la que canceló. Idempotente:
     * la primera razón gana; los listeners se invocan una única vez.
     */
    fun cancel(reason: CancelReason = CancelReason.Explicit): Boolean = state.cancel(reason)
}

internal class CancellationState(private val clock: WallClock) {
    private var reason: CancelReason? = null
    private val listeners = LinkedHashMap<Int, (CancelReason) -> Unit>()
    private var nextListenerId = 1

    @Synchronized fun isCancelled(): Boolean = reason != null

    @Synchronized fun reason(): CancelReason? = reason

    /** Si ya está cancelada, el listener se invoca de inmediato y no se registra. */
    @Synchronized fun addListener(listener: (CancelReason) -> Unit): Int {
        val current = reason
        if (current != null) {
            listener(current)
            return -1
        }
        val id = nextListenerId
        nextListenerId += 1
        listeners[id] = listener
        return id
    }

    @Synchronized fun removeListener(id: Int) {
        listeners.remove(id)
    }

    @Synchronized fun cancel(r: CancelReason): Boolean {
        val current = reason
        if (current != null) return false
        reason = r
        val toCall = listeners.values.toList()
        listeners.clear()
        for (listener in toCall) listener(r)
        return true
    }

    /** Razón ya producida, o la crea si el plazo venció. Null si sigue viva. */
    @Synchronized fun checkDeadline(deadlineMillis: Long): CancelReason? {
        reason?.let { return it }
        if (clock.nowMillis() >= deadlineMillis) {
            reason = CancelReason.Deadline(deadlineMillis)
            val toCall = listeners.values.toList()
            listeners.clear()
            for (listener in toCall) listener(reason!!)
            return reason
        }
        return null
    }
}

class CancellationToken internal constructor(private val state: CancellationState) {

    val isCancelled: Boolean get() = state.isCancelled()

    fun reason(): CancelReason? = state.reason()

    /** Suscripción: devuelve la función para darse de baja. */
    fun onCancel(listener: (CancelReason) -> Unit): () -> Unit {
        val id = state.addListener(listener)
        return { if (id > 0) state.removeListener(id) }
    }

    fun checkDeadline(deadlineMillis: Long): CancelReason? = state.checkDeadline(deadlineMillis)

    fun throwIfCancelled() {
        val r = state.reason()
        if (r != null) throw OperationCancelledException(r)
    }
}

class OperationCancelledException(val reason: CancelReason) :
    Exception("operación cancelada: $reason")
