package nia.engine.broker

import java.util.UUID

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.IpcEnvelope
import nia.contracts.IpcKind
import nia.contracts.IpcMessages
import nia.contracts.Outcome

/**
 * T006 v1 (R0036, RES-0023): broker/renderer/IPC en proceso puro sobre los
 * contratos de :contracts. El broker es el dueno de la autorizacion: un
 * renderer solo puede hacer lo que su contexto ASIGNADO concede (por
 * contexto, nunca por origin declarado en el mensaje). La muerte del
 * renderer es recuperable: los requests en vuelo se resuelven con
 * PROCESS_GONE y el broker puede respawnear un contexto nuevo con las
 * mismas capacidades.
 *
 * Limitaciones v1: sin procesos OS reales (el aislamiento Android con
 * Binder/isolatedProcess llega en fase posterior; v1 prueba el protocolo,
 * la autorizacion y la recuperacion en JVM puro), sin streams/FD para
 * payloads grandes (el limite del sobre se conserva), un solo request en
 * vuelo por contexto y sin backpressure.
 */

/** Eventos del broker hacia el host (en proceso; el sobre IPC no lleva callbacks). */
sealed class BrokerEvent {
    data class RendererStarted(val tabId: String, val contextId: String) : BrokerEvent()
    data class RendererCrashed(
        val tabId: String,
        val contextId: String,
        val pendingRequests: Int
    ) : BrokerEvent()
    data class RendererRespawned(
        val tabId: String,
        val contextId: String,
        val generation: Long
    ) : BrokerEvent()
    data class RendererClosed(val tabId: String, val contextId: String) : BrokerEvent()
}

/** Operaciones que el broker conoce en v1; conceder = autorizar por contexto. */
object BrokerOps {
    const val NAVIGATE = "tab.navigate"
    const val RENDER = "tab.render"
    const val SNAPSHOT = "tab.snapshot"
    const val STORAGE_READ = "storage.read"
    const val NETWORK_FETCH = "network.fetch"

    val KNOWN = setOf(NAVIGATE, RENDER, SNAPSHOT, STORAGE_READ, NETWORK_FETCH)

    /** Operaciones cuyo payload es el tabId asignado del contexto. */
    val TAB_SCOPED = setOf(NAVIGATE, SNAPSHOT)
}

internal class ContextState(
    val contextId: String,
    val tabId: String,
    val operations: Set<String>
) {
    var generation = 1L
    var connected = false
    var alive = true
    var closed = false
    var inFlight = 0
}

/**
 * Renderer: handle del lado del contenido hacia su contexto asignado. No
 * lleva credenciales ni decide autorizacion: solo envia sobres.
 */
class Renderer internal constructor(
    internal val broker: Broker,
    val contextId: String,
    val tabId: String,
    val generation: Long
) {
    fun handshake(): Outcome<IpcEnvelope> =
        broker.dispatch(IpcMessages.handshake(contextId, generation))

    fun request(operation: String, payload: ByteArray? = null): Outcome<IpcEnvelope> =
        broker.dispatch(IpcMessages.request(contextId, generation, operation, payload))

    fun close(): Outcome<IpcEnvelope> =
        broker.dispatch(IpcMessages.close(contextId, generation))
}

/**
 * Broker: valida cada sobre contra el contexto ASIGNADO y ejecuta la
 * operacion. Orden de denegacion: contexto desconocido o cerrado ->
 * DENIED; renderer muerto -> PROCESS_GONE; generacion caducada ->
 * STALE_HANDLE; operacion no concedida o de otra pestana -> DENIED;
 * operacion desconocida -> UNSUPPORTED; falta handshake -> DENIED.
 */
class Broker(
    private val onEvent: (BrokerEvent) -> Unit = {},
    /** Hook del host para tab.navigate: permite, p. ej., provocar un crash. */
    private val navigateHook: (String) -> Outcome<Unit> = { Outcome.success(Unit) }
) {
    private val contexts = LinkedHashMap<String, ContextState>()

    /** Asigna un contexto nuevo al renderer de una pestana (generacion 1). */
    fun attachRenderer(tabId: String, operations: Set<String>): Renderer {
        require(tabId.isNotBlank()) { "tabId obligatorio" }
        require(operations.isNotEmpty()) { "un contexto necesita operaciones concedidas" }
        val state = ContextState("ctx-" + UUID.randomUUID(), tabId, operations.toSet())
        contexts[state.contextId] = state
        onEvent(BrokerEvent.RendererStarted(tabId, state.contextId))
        return Renderer(this, state.contextId, tabId, state.generation)
    }

    /** Muerte del renderer: invalida la generacion y emite RendererCrashed. */
    fun crash(contextId: String): Boolean {
        val state = contexts[contextId] ?: return false
        if (!state.alive || state.closed) return false
        state.alive = false
        state.generation += 1
        val pending = state.inFlight
        state.inFlight = 0
        onEvent(BrokerEvent.RendererCrashed(state.tabId, contextId, pending))
        return true
    }

    /** Contexto nuevo para la misma pestana y capacidades (solo si el anterior murio o cerro). */
    fun respawn(renderer: Renderer): Renderer {
        val state = contexts[renderer.contextId]
        require(state != null) { "contexto desconocido" }
        require(state.tabId == renderer.tabId) { "respawn solo para la misma pestana" }
        require(!state.alive || state.closed) { "respawn solo tras crash o close" }
        val nuevo = attachRenderer(renderer.tabId, state.operations)
        onEvent(BrokerEvent.RendererRespawned(renderer.tabId, nuevo.contextId, nuevo.generation))
        return nuevo
    }

    /** Caduca la generacion viva SIN matar al renderer (p. ej. navegacion externa). */
    fun invalidate(renderer: Renderer): Long {
        val state = contexts[renderer.contextId]
        require(state != null) { "contexto desconocido" }
        require(state.alive && !state.closed) { "solo contextos vivos se invalidan" }
        state.generation += 1
        return state.generation
    }

    /** Valida y ejecuta un sobre; el RESULT vuelve como IpcEnvelope o Failure. */
    fun dispatch(envelope: IpcEnvelope): Outcome<IpcEnvelope> {
        val state = contexts[envelope.contextId]
            ?: return denied("contexto desconocido: autoriza con attachRenderer")
        if (state.closed) return denied("contexto cerrado")
        if (!state.alive) return processGone()
        if (envelope.generation != state.generation) {
            return Outcome.failure(
                ErrorInfo(
                    ErrorCode.STALE_HANDLE,
                    "la generacion del contexto caduco; pide un contexto nuevo"
                )
            )
        }
        if (envelope.kind == IpcKind.HANDSHAKE) {
            state.connected = true
            return Outcome.success(IpcMessages.result(envelope, "handshake", "connected".toByteArray()))
        }
        if (envelope.kind == IpcKind.CLOSE) {
            state.closed = true
            state.connected = false
            onEvent(BrokerEvent.RendererClosed(state.tabId, state.contextId))
            return Outcome.success(IpcMessages.result(envelope, "close", "closed".toByteArray()))
        }
        if (envelope.kind != IpcKind.REQUEST) {
            return denied("solo REQUEST, HANDSHAKE o CLOSE cruzan hacia el broker")
        }
        if (!state.connected) return denied("falta handshake antes de operar")
        if (!state.operations.contains(envelope.operation)) {
            return denied("operacion no concedida a este contexto: " + envelope.operation)
        }
        if (!BrokerOps.KNOWN.contains(envelope.operation)) {
            return Outcome.failure(
                ErrorInfo(
                    ErrorCode.UNSUPPORTED,
                    "operacion desconocida para el broker: " + envelope.operation
                )
            )
        }
        if (BrokerOps.TAB_SCOPED.contains(envelope.operation)) {
            val target = envelope.payload?.toString(Charsets.UTF_8) ?: ""
            if (target != state.tabId) {
                return denied("la operacion apunta a una pestana fuera del contexto asignado")
            }
        }
        state.inFlight += 1
        val result = runOperation(state, envelope)
        if (state.inFlight > 0) state.inFlight -= 1
        if (!state.alive || state.closed) return processGone()
        return result
    }

    private fun runOperation(state: ContextState, envelope: IpcEnvelope): Outcome<IpcEnvelope> {
        return when (envelope.operation) {
            BrokerOps.NAVIGATE -> {
                val hook = navigateHook(state.tabId)
                if (hook is Outcome.Failure) return Outcome.failure(hook.error)
                Outcome.success(
                    IpcMessages.result(envelope, envelope.operation, "navigation-started".toByteArray())
                )
            }
            else ->
                Outcome.success(IpcMessages.result(envelope, envelope.operation, "ack".toByteArray()))
        }
    }

    private fun denied(safe: String): Outcome<IpcEnvelope> =
        Outcome.failure(ErrorInfo(ErrorCode.DENIED, safe))

    private fun processGone(): Outcome<IpcEnvelope> =
        Outcome.failure(
            ErrorInfo(ErrorCode.PROCESS_GONE, "el renderer murio; respawnea y reintenta")
        )
}
