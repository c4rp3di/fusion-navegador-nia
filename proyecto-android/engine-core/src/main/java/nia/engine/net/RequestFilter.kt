package nia.engine.net

import nia.engine.url.Origin
import nia.engine.url.Site
import nia.engine.url.Url
import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser

/**
 * T052 v1 (R0242, RES-0111): contexto y decision de red de los modulos
 * (ADR-005, carril AntiAds). RequestContext viaja con cada peticion y
 * lleva lo que C1/C2 piden: url, tipo de recurso (document, image,
 * stylesheet, script, fetch u otro), iniciador y tercero. El tercero
 * lo calcula el HOST contra Site v1 (APROXIMACION v1: subconjunto de
 * sufijos publicos, no el PSL completo); iniciador vacio o URL
 * ilegible dejan thirdParty en null: desconocido, NUNCA true por
 * defecto.
 *
 * FilterDecision es la decision cerrada del filtro: Allow o
 * Block(moduleId). El hook recibe la url en api-version 1 y el
 * objeto de contexto completo en api-version 2 (T030 v2, C2): el
 * contexto YA llega al hook, no solo a la frontera del filtro.
 */
data class RequestContext(
    val url: String,
    val resourceType: String,
    val initiatorUrl: String,
    val thirdParty: Boolean?
) {
    companion object {

        fun of(url: String, resourceType: String, initiatorUrl: String): RequestContext {
            return RequestContext(url, resourceType, initiatorUrl, terceroDe(url, initiatorUrl))
        }

        /** Tercero contra Site v1; iniciador vacio o URL ilegible -> null. */
        private fun terceroDe(url: String, initiatorUrl: String): Boolean? {
            if (initiatorUrl.isEmpty()) return null
            val sitio = sitioDe(url) ?: return null
            val iniciador = sitioDe(initiatorUrl) ?: return null
            return sitio.registrableDomain != iniciador.registrableDomain
        }

        /** Site v1 de una URL cruda; ilegible u origen opaco -> null. */
        private fun sitioDe(raw: String): Site? {
            val u: Url = try {
                UrlParser.parse(raw)
            } catch (_: UrlParseException) {
                return null
            }
            val tuple = Origin.of(u) as? Origin.Tuple ?: return null
            return Site.fromOrigin(tuple)
        }
    }
}

/** Decision cerrada del filtro de red: continuar o bloquear por un modulo. */
sealed class FilterDecision {
    object Allow : FilterDecision()
    data class Block(val moduleId: String) : FilterDecision()
}

/** Filtro de red consultado ANTES de red, cache y service worker. */
interface RequestFilter {
    fun decide(context: RequestContext): FilterDecision
}
