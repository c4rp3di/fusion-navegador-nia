package nia.engine.modules

import nia.contracts.Outcome

import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.RequestFilter

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
 * El hook recibe SOLO la url (T030).
 */
class PluginNetFilter(
    private val runtime: PluginRuntime,
    private val registry: ModuleRegistry
) : RequestFilter {

    override fun decide(context: RequestContext): FilterDecision {
        for (id in registry.installedIds()) {
            val session = runtime.sessionOf(id) ?: continue
            if (session.hookCount(PluginEvent.REQUEST) == 0) continue
            val fired = session.fire(PluginEvent.REQUEST, context.url)
            if (fired is Outcome.Success && fired.value.trim() == "block") {
                return FilterDecision.Block(id)
            }
        }
        return FilterDecision.Allow
    }
}
