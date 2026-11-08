package nia.engine.modules

import nia.engine.inspector.EventContext

/**
 * C10 v2 (R0267, RES-0118, carril E4): identidad de los fires reales
 * del shell. El host es el DUENO de la identidad (E4 C10: se monta
 * SOBRE el inspector del host y el SecretRedactor manda): el motor no
 * tiene id de documento propio, asi que esta clase acuna por pestana
 * una generacion monotona (1, 2, ...) y un id de documento por
 * navegacion; el frameId es SIEMPRE "main" (el motor v1 no tiene frame
 * tree: exclusion honesta, nunca un frame inventado). El shell acuna
 * la sesion al arrancar y el id de pestana lo aporta TabsManager (la
 * factory del filtro recibe el id de la pestana nueva, T094 v4).
 *
 * SOLO construye contextos: no escribe en el inspector (Inspector
 * T008); el EventContext viaja a las fires que lo reciben (T030 v2) y
 * sin contexto no hay evento (nia.diag.evento devuelve false).
 */
class ContextoModulos(private val sessionId: String) {

    init {
        require(sessionId.isNotBlank()) { "sessionId obligatorio" }
    }

    companion object {
        /** Frame unico del motor v1 (sin frame tree). */
        const val FRAME_MAIN = "main"
    }

    private val generaciones = HashMap<Long, Long>()
    private val documentos = HashMap<Long, String>()

    /**
     * Acuna el contexto de una NUEVA navegacion de la pestana: sube la
     * generacion, acuna el id del documento y lo deja como el actual
     * de la pestana (las peticiones de esa navegacion y el onNavigate
     * posterior comparten el MISMO documento).
     */
    @Synchronized
    fun nuevaNavegacion(tabId: Long): EventContext {
        val generacion = (generaciones[tabId] ?: 0L) + 1L
        generaciones[tabId] = generacion
        val documento = "d-" + tabId + "-" + generacion
        documentos[tabId] = documento
        return EventContext(sessionId, "t-" + tabId, FRAME_MAIN, documento, generacion)
    }

    /**
     * Contexto ACTUAL de la pestana (el de su ultima navegacion) o
     * null si nunca navigo: sin contexto no hay evento.
     */
    @Synchronized
    fun actual(tabId: Long): EventContext? {
        val documento = documentos[tabId] ?: return null
        val generacion = generaciones[tabId] ?: return null
        return EventContext(sessionId, "t-" + tabId, FRAME_MAIN, documento, generacion)
    }
}
