package nia.contracts

import java.util.UUID

/**
 * Identidades tipadas (maestro, sección 4). Son opacas: se crean con [Ids]
 * o se reconstruyen desde su forma serializada con las funciones parse*.
 * Un id NO es un handle: la generación (invalidación al navegar) vive en
 * [Handle]; los ids no caducan por sí mismos.
 */
@JvmInline value class ProfileId(val value: String)
@JvmInline value class SessionId(val value: String)
@JvmInline value class TabId(val value: String)
@JvmInline value class FrameId(val value: String)
@JvmInline value class DocumentId(val value: String)
@JvmInline value class NavigationId(val value: String)
@JvmInline value class RequestId(val value: String)
@JvmInline value class NodeId(val value: String)
@JvmInline value class ScriptId(val value: String)
@JvmInline value class ModuleId(val value: String)

/**
 * Fábrica y validación de ids. Cada tipo lleva prefijo propio para que un
 * id cruzado (p. ej. usar un RequestId donde va un DocumentId) falle en
 * validación y no en un bug lejano.
 */
object Ids {
    const val MAX_VALUE_LENGTH = 128

    fun newProfileId(): ProfileId = ProfileId(new("profile"))
    fun newSessionId(): SessionId = SessionId(new("session"))
    fun newTabId(): TabId = TabId(new("tab"))
    fun newFrameId(): FrameId = FrameId(new("frame"))
    fun newDocumentId(): DocumentId = DocumentId(new("doc"))
    fun newNavigationId(): NavigationId = NavigationId(new("nav"))
    fun newRequestId(): RequestId = RequestId(new("req"))
    fun newNodeId(): NodeId = NodeId(new("node"))
    fun newScriptId(): ScriptId = ScriptId(new("script"))
    fun newModuleId(): ModuleId = ModuleId(new("module"))

    fun parseProfileId(raw: String): ProfileId = ProfileId(checked("profile", raw))
    fun parseSessionId(raw: String): SessionId = SessionId(checked("session", raw))
    fun parseTabId(raw: String): TabId = TabId(checked("tab", raw))
    fun parseFrameId(raw: String): FrameId = FrameId(checked("frame", raw))
    fun parseDocumentId(raw: String): DocumentId = DocumentId(checked("doc", raw))
    fun parseNavigationId(raw: String): NavigationId = NavigationId(checked("nav", raw))
    fun parseRequestId(raw: String): RequestId = RequestId(checked("req", raw))
    fun parseNodeId(raw: String): NodeId = NodeId(checked("node", raw))
    fun parseScriptId(raw: String): ScriptId = ScriptId(checked("script", raw))
    fun parseModuleId(raw: String): ModuleId = ModuleId(checked("module", raw))

    private fun new(prefix: String): String = prefix + "-" + UUID.randomUUID()

    internal fun checked(prefix: String, raw: String): String {
        require(raw.isNotBlank()) { "id vacío" }
        require(raw.length <= MAX_VALUE_LENGTH) {
            "id demasiado largo (máximo $MAX_VALUE_LENGTH caracteres)"
        }
        require(raw.startsWith("$prefix-")) {
            "un id de tipo $prefix debe empezar por '$prefix-'"
        }
        return raw
    }
}
