package nia.engine.inspector

import nia.contracts.SystemWallClock
import nia.contracts.WallClock

/**
 * T008 v1 (R0036, RES-0023): backend de inspector versionado e
 * independiente de la UI (no es CDP/RDP). Eventos contextualizados
 * (sesion/pestana/frame/documento/generacion) con secuencia monotona,
 * secretos redactados EN LA INGESTA (el crudo no se persiste) y export
 * sanitizado.
 *
 * Limitaciones v1: buffer en memoria acotado (paginacion posterior), sin
 * conexion remota, sin snapshots DOM (T017) y sin cuerpos de red (P2).
 */

object InspectorProtocol {
    const val PROTOCOL_VERSION = 1
    const val MAX_EVENTS = 10000
}

enum class EventLevel { DEBUG, INFO, WARNING, ERROR }

/** Contexto obligatorio de cada evento: sin contexto no hay evento. */
data class EventContext(
    val sessionId: String,
    val tabId: String,
    val frameId: String,
    val documentId: String,
    val generation: Long
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId obligatorio" }
        require(tabId.isNotBlank()) { "tabId obligatorio" }
        require(frameId.isNotBlank()) { "frameId obligatorio" }
        require(documentId.isNotBlank()) { "documentId obligatorio" }
        require(generation > 0) { "generation obligatoria" }
    }
}

/** Redaccion determinista de secretos (maestro: DevTools redacta secretos). */
object SecretRedactor {
    const val REDACTED = "[redacted]"

    val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token"
    )

    private val BEARER = Regex("(?i)(bearer[ \t]+)[^\\s]+")
    private val SECRET_PARAMS = Regex("(?i)((?:token|passwd|password|secret|api[-_]?key)[ \t]*=)[^&\\s]*")
    private val USERINFO = Regex("://[^/@\\s]+@")

    fun redactText(text: String): String {
        var out = BEARER.replace(text) { m -> m.groupValues[1] + REDACTED }
        out = SECRET_PARAMS.replace(out) { m -> m.groupValues[1] + REDACTED }
        out = USERINFO.replace(out) { "://" + REDACTED + "@" }
        return out
    }

    fun redactUrl(url: String): String = redactText(url)

    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (SENSITIVE_HEADERS.contains(k.lowercase())) {
                out[k] = REDACTED
            } else {
                out[k] = redactText(v)
            }
        }
        return out
    }

    fun redactPayload(payload: Map<String, String>): Map<String, String> = redactHeaders(payload)
}

/** Evento del inspector: solo datos (no objetos vivos) y YA redactado. */
data class InspectorEvent(
    val seq: Long,
    val timestampMillis: Long,
    val context: EventContext,
    val kind: String,
    val level: EventLevel,
    val payload: Map<String, String>
)

/** Sesion del inspector: ingesta redactada, replay por seq y export sanitizado. */
class InspectorSession(private val clock: WallClock = SystemWallClock()) {

    private val events = ArrayList<InspectorEvent>()
    private var nextSeq = 1L

    /** Consola: el texto se redacta en la ingesta (el crudo no se persiste). */
    fun console(context: EventContext, level: EventLevel, text: String): InspectorEvent =
        record(context, "console", level, mapOf("text" to text))

    fun navigation(context: EventContext, url: String): InspectorEvent =
        record(context, "navigation", EventLevel.INFO, mapOf("url" to url))

    fun custom(
        context: EventContext,
        kind: String,
        level: EventLevel,
        payload: Map<String, String>
    ): InspectorEvent = record(context, kind, level, payload)

    private fun record(
        context: EventContext,
        kind: String,
        level: EventLevel,
        payload: Map<String, String>
    ): InspectorEvent {
        require(kind.isNotBlank()) { "kind obligatorio" }
        val redacted = SecretRedactor.redactPayload(payload)
        val event = InspectorEvent(
            nextSeq,
            clock.nowMillis(),
            context,
            kind,
            level,
            redacted
        )
        nextSeq += 1
        if (events.size >= InspectorProtocol.MAX_EVENTS) {
            events.removeAt(0)
        }
        events.add(event)
        return event
    }

    fun all(): List<InspectorEvent> = events.toList()

    /** Replay/paginacion: eventos con seq estrictamente mayor al dado. */
    fun eventsSince(seq: Long): List<InspectorEvent> = events.filter { it.seq > seq }

    /** Export sanitizado: una linea por evento con el payload ya redactado. */
    fun export(): String {
        val sb = StringBuilder()
        sb.append("# inspector NIA v" + InspectorProtocol.PROTOCOL_VERSION + "\n")
        for (e in events) {
            sb.append(
                e.seq.toString() + " " + e.kind + " " + e.level.name + " " +
                    e.context.sessionId + "/" + e.context.tabId + " "
            )
            val entries = e.payload.entries.toList()
            var i = 0
            while (i < entries.size) {
                val entry = entries[i]
                if (i > 0) sb.append(" ")
                sb.append(entry.key + "=" + entry.value)
                i += 1
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
