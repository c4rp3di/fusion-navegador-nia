package nia.contracts

import java.util.UUID

@JvmInline value class MessageId(val value: String)

enum class IpcKind { HANDSHAKE, REQUEST, RESULT, EVENT, CANCEL, CLOSE }

object Ipc {
    /** Versión del sobre. Un bump es un RFC (maestro, sección 4). */
    const val PROTOCOL_VERSION = 1

    /** Payloads mayores que esto van por stream/FD, nunca dentro del sobre. */
    const val MAX_INLINED_PAYLOAD_BYTES = 1 shl 20
}

/**
 * Sobre IPC entre procesos: no hay objetos vivos ni callbacks
 * cross-process. `contextId` es el contexto ASIGNADO por el broker;
 * no es un origin confiado. `generation` caduca igual que un handle.
 *
 * Nota: la igualdad generada para ByteArray compara identidad; dos
 * sobres con el mismo contenido de payload no son `equals`. Los
 * messageId existen precisamente para identificar mensajes.
 */
data class IpcEnvelope(
    val messageId: MessageId,
    val kind: IpcKind,
    val operation: String,
    val contextId: String,
    val generation: Long,
    val correlationId: MessageId? = null,
    val payload: ByteArray? = null,
    val protocolVersion: Int = Ipc.PROTOCOL_VERSION
) {
    init {
        require(protocolVersion == Ipc.PROTOCOL_VERSION) {
            "protocolVersion $protocolVersion no soportado (actual: ${Ipc.PROTOCOL_VERSION})"
        }
        require(operation.isNotBlank()) { "operation es obligatoria" }
        require(contextId.isNotBlank()) { "contextId es obligatorio (contexto asignado, no origin)" }
        require(generation > 0) { "generation es obligatoria" }
        when (kind) {
            IpcKind.REQUEST, IpcKind.CANCEL ->
                require(correlationId == null) { "$kind no lleva correlationId" }
            IpcKind.RESULT ->
                require(correlationId != null) { "RESULT necesita el correlationId del REQUEST" }
            IpcKind.HANDSHAKE, IpcKind.EVENT, IpcKind.CLOSE -> {}
        }
        payload?.let {
            require(it.size <= Ipc.MAX_INLINED_PAYLOAD_BYTES) {
                "payload de ${it.size} bytes: usa stream/FD, no el sobre"
            }
        }
    }
}

/** Constructores canónicos de sobres. */
object IpcMessages {

    fun newMessageId(): MessageId = MessageId("msg-" + UUID.randomUUID())

    fun handshake(contextId: String, generation: Long, operation: String = "handshake"): IpcEnvelope =
        IpcEnvelope(newMessageId(), IpcKind.HANDSHAKE, operation, contextId, generation)

    fun request(contextId: String, generation: Long, operation: String, payload: ByteArray? = null): IpcEnvelope =
        IpcEnvelope(newMessageId(), IpcKind.REQUEST, operation, contextId, generation, null, payload)

    fun result(forRequest: IpcEnvelope, operation: String, payload: ByteArray? = null): IpcEnvelope =
        IpcEnvelope(
            newMessageId(), IpcKind.RESULT, operation,
            forRequest.contextId, forRequest.generation, forRequest.messageId, payload
        )

    fun event(contextId: String, generation: Long, operation: String, payload: ByteArray? = null): IpcEnvelope =
        IpcEnvelope(newMessageId(), IpcKind.EVENT, operation, contextId, generation, null, payload)

    fun cancel(forRequest: IpcEnvelope): IpcEnvelope =
        IpcEnvelope(newMessageId(), IpcKind.CANCEL, "cancel", forRequest.contextId, forRequest.generation)

    fun close(contextId: String, generation: Long): IpcEnvelope =
        IpcEnvelope(newMessageId(), IpcKind.CLOSE, "close", contextId, generation)
}
