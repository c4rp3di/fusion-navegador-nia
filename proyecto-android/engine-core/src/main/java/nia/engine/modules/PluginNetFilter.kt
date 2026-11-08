package nia.engine.modules

import java.security.MessageDigest

import nia.contracts.Outcome

import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.RequestFilter
import nia.engine.profile.ConfigStore

/**
 * T052 v1 (R0242, RES-0111): RequestFilter que consulta los plugins
 * cargados (ADR-005, carril AntiAds). Recorre los modulos INSTALADOS
 * en su orden de instalacion estable, dispara onRequest de cada
 * sesion activa y el MAS restrictivo gana: veredicto exacto "block"
 * (tras trim) -> Block(moduleId).
 *
 * Fail-open ante hook que lanza, cuotas agotadas, modulo sin sesion
 * cargada o sin hooks de red: abstencion y sigue la cadena; si nadie
 * bloquea, Allow (la navegacion NUNCA se rompe por un modulo roto).
 *
 * T052 v2/C9 (R0253, RES-0113): config opcional del perfil. Un modulo
 * con general.enabled="false" en la config se abstiene SIN disparar su
 * hook (no gasta cuotas); la desactivacion del gestor SIEMPRE gana
 * (la config solo se consulta con sesion cargada). Sin config el
 * filtro es identico a la v1 (ausencia de ajuste = habilitado).
 *
 * T030 v2 (R0263, RES-0117, E4 C2): el filtro pasa el RequestContext
 * ENTERO a la sesion; el hook decide la forma del argumento su
 * api-version (1: solo la url; 2: el objeto con url, type, initiator
 * y thirdParty).
 *
 * C10 v2 (R0267, RES-0118): contextoDe opcional aporta el EventContext
 * REAL de cada peticion (la pestana que navega) para que el
 * diagnostico del modulo escriba en el inspector del host; sin
 * contexto o con proveedor null nia.diag se abstiene (devuelve false).
 *
 * C3 v1 (R0271, RES-0119): veredicto "sustituir:<nombre>" (solo
 * api-version 2). Gates fail-closed en orden: tipo fetch/otro (una
 * navegacion de documento JAMAS se sustituye), capability modificar
 * y permiso network.response del manifiesto, nombre declarado en
 * sustitutos, recurso resuelto por recursosDe, <= 64 KiB, sha256
 * intacto y tope de 50 por documento; cualquier fallo cae en Block
 * con diagnostico "no disponible" (WARNING) y el exito escribe
 * "sustituido" (INFO) via el DiagModulos compartido del shell.
 */
class PluginNetFilter(
    private val runtime: PluginRuntime,
    private val registry: ModuleRegistry,
    private val config: ConfigStore? = null,
    private val contextoDe: ((RequestContext) -> EventContext?)? = null,
    private val recursosDe: ((String, String) -> RecursoSustituto?)? = null,
    private val diag: DiagModulos? = null
) : RequestFilter {

    override fun decide(context: RequestContext): FilterDecision {
        for (id in registry.installedIds()) {
            val session = runtime.sessionOf(id) ?: continue
            if (config != null && !config.enabled(id)) continue
            if (session.hookCount(PluginEvent.REQUEST) == 0) continue
            val fired = session.fire(PluginEvent.REQUEST, context, contextoDe?.invoke(context))
            if (fired is Outcome.Success) {
                val veredicto = fired.value.trim()
                if (veredicto == "block") {
                    return FilterDecision.Block(id)
                }
                if (veredicto.startsWith("sustituir:")) {
                    val decision = sustitucionDe(id, veredicto, context)
                    if (decision != null) return decision
                }
            }
        }
        return FilterDecision.Allow
    }

    /**
     * C3 v1 (R0271, RES-0119): gates del veredicto sustituir:<nombre>
     * en orden fail-closed; null = abstencion (api-version 1 mantiene
     * su semantica). El EventContext de contextoDe identifica el
     * documento para el tope y el diagnostico del canal del host.
     */
    private fun sustitucionDe(
        id: String,
        veredicto: String,
        context: RequestContext
    ): FilterDecision? {
        val manifest = registry.manifestOf(id)
        if (manifest == null || manifest.apiVersion < 2) return null
        val ctx = contextoDe?.invoke(context)
        val nombre = veredicto.removePrefix("sustituir:").trim()
        fun noDisponible(): FilterDecision {
            if (ctx != null) {
                diag?.evento(id, EventLevel.WARNING, "no disponible: " + nombre, ctx)
            }
            return FilterDecision.Block(id)
        }
        if (context.resourceType != "fetch" && context.resourceType != "otro") {
            return noDisponible()
        }
        if (!manifest.capabilities.contains("modificar")) return noDisponible()
        if (!manifest.permissions.contains(ModuleSchema.NETWORK_RESPONSE)) return noDisponible()
        if (nombre.isEmpty() || !manifest.sustitutos.contains(nombre)) return noDisponible()
        val recurso = recursosDe?.invoke(id, nombre) ?: return noDisponible()
        if (recurso.bytes.size > Niamod.MAX_SUSTITUTO) return noDisponible()
        val sha = MessageDigest.getInstance("SHA-256").digest(recurso.bytes)
        if (recurso.sha256 != hexDe(sha)) return noDisponible()
        val doc = ctx?.documentId ?: "(sin-contexto)"
        val usados = topeSustituciones[doc] ?: 0
        if (usados >= MAX_SUSTITUCIONES_POR_DOCUMENTO) {
            return noDisponible()
        }
        topeSustituciones[doc] = usados + 1
        if (ctx != null) {
            diag?.evento(id, EventLevel.INFO, "sustituido " + nombre, ctx)
        }
        return FilterDecision.Substitute(id, recurso.bytes, contentTypeDe(nombre))
    }

    /** ContentType por extension (C3); lo calcula el host, no el modulo. */
    private fun contentTypeDe(nombre: String): String {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun hexDe(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private val topeSustituciones = HashMap<String, Int>()

    companion object {
        /** C3 v1 (R0271, RES-0119): tope de sustituciones por documento. */
        const val MAX_SUSTITUCIONES_POR_DOCUMENTO = 50
    }
}
