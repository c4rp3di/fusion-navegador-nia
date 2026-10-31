package nia.contracts

import java.util.UUID

/**
 * Códigos de error cerrados (maestro, sección 4). No se inventan códigos
 * ad-hoc: se mapea a uno de estos o se pide RFC.
 */
enum class ErrorCode {
    UNSUPPORTED,
    INVALID_INPUT,
    DENIED,
    ORIGIN_VIOLATION,
    CANCELLED,
    DEADLINE,
    QUOTA,
    STALE_HANDLE,
    PROCESS_GONE,
    NETWORK,
    TLS,
    PARSE,
    INTERNAL
}

/** ¿Tiene sentido reintentar la misma operación? */
enum class Retryability { NONE, RETRY_LATER, RETRY_SAME }

/**
 * Política por defecto: solo se reintenta lo que puede cambiar por sí solo
 * con el tiempo. TLS y DENIED no se reintentan tal cual: requieren decisión.
 */
fun ErrorCode.defaultRetryability(): Retryability = when (this) {
    ErrorCode.NETWORK, ErrorCode.DEADLINE, ErrorCode.QUOTA, ErrorCode.PROCESS_GONE, ErrorCode.INTERNAL -> Retryability.RETRY_LATER
    else -> Retryability.NONE
}

object Tracing {
    fun newTraceId(): String = "trace-" + UUID.randomUUID()
}

/**
 * Información de error que cruza fronteras de proceso. [safeMessage] es lo
 * único que se muestra al usuario: NUNCA se construye con datos de [cause]
 * (secretos, URLs internas, detalles de sistema).
 */
data class ErrorInfo(
    val code: ErrorCode,
    val safeMessage: String,
    val retryability: Retryability = code.defaultRetryability(),
    val traceId: String = Tracing.newTraceId(),
    /** Detalle técnico; NO se serializa fuera del proceso. */
    val cause: Throwable? = null
) {
    init {
        require(safeMessage.isNotBlank()) { "safeMessage es obligatorio" }
        require(traceId.isNotBlank()) { "traceId es obligatorio" }
    }
}

/**
 * El `Result` del maestro (Success | Error). Se llama Outcome para no
 * colisionar con kotlin.Result de la stdlib.
 */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val error: ErrorInfo) : Outcome<Nothing>()

    val isFailure: Boolean get() = this is Failure

    companion object {
        /** Fábricas canónicas (R0007): crear resultados con Outcome.success/failure, no con las subclases a mano. */
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: ErrorInfo): Outcome<Nothing> = Failure(error)
    }
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (ErrorInfo) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

val <T> Outcome<T>.valueOrNull: T? get() = (this as? Outcome.Success)?.value
