package nia.engine.net

/**
 * T026 v1 (R0052, RES-0031): politica de redirecciones 3xx del motor de
 * red (la parte que T011 v2 dejo delegada: HttpTransport entrega una 3xx
 * tal cual y esta politica decide si seguirla).
 *
 * - redirectable: 301, 302, 303, 307 y 308.
 * - plan: tope de 20 saltos (el salto 20 se permite; el 21 no); Location
 *   obligatoria (absoluta http/https o relativa al origen "/..."; una
 *   relativa compleja se entrega tal cual sin seguir); NUNCA se degrada
 *   https a http (Stop, la 3xx se entrega); 303 siempre GET sin cuerpo;
 *   301/302 convierten POST a GET (comportamiento clasico de navegador)
 *   y para GET/HEAD no hay cuerpo que conservar; 307/308 conservan el
 *   metodo y el cuerpo intactos.
 *
 * Limitaciones v1: Location relativa compleja ("pagina", "../x") no se
 * resuelve (exige el resolvedor relativo de URLs, fuera del alcance del
 * parser v2); sin reescritura de cabeceras sensibles en el salto (la
 * gestion de Authorization/cookies por salto llega con el cableado del
 * broker).
 */
object RedirectPolicy {

    const val MAX_HOPS = 20

    private val REDIRECTABLE = setOf(301, 302, 303, 307, 308)

    sealed class Plan {

        /** Seguir la redireccion: nuevo metodo/URL; stripBody = el cuerpo se descarta. */
        data class Follow(val method: String, val url: String, val stripBody: Boolean) : Plan()

        /** No seguir: la respuesta 3xx se entrega tal cual al que llamo. */
        data class Stop(val reason: String) : Plan()
    }

    fun redirectable(status: Int): Boolean = REDIRECTABLE.contains(status)

    fun plan(status: Int, method: String, location: String?, currentUrl: String, hops: Int): Plan {
        if (hops >= MAX_HOPS) return Plan.Stop("limite-de-redirecciones")
        if (location == null || location.trim().isEmpty()) return Plan.Stop("sin-location")
        val loc = location.trim()
        val target: String
        if (loc.startsWith("http://") || loc.startsWith("https://")) {
            target = loc
        } else if (loc.startsWith("/")) {
            val schemeEnd = currentUrl.indexOf("://")
            if (schemeEnd <= 0) return Plan.Stop("location-relativa-sin-base-absoluta")
            val pathStart = currentUrl.indexOf('/', schemeEnd + 3)
            val base = if (pathStart < 0) currentUrl else currentUrl.substring(0, pathStart)
            target = base + loc
        } else {
            return Plan.Stop("location-relativa-compleja-no-soportada")
        }
        if (currentUrl.startsWith("https://") && target.startsWith("http://")) {
            return Plan.Stop("degradacion-https-prohibida")
        }
        if (status == 303) return Plan.Follow("GET", target, true)
        if (status == 301 || status == 302) {
            if (method == "POST") return Plan.Follow("GET", target, true)
            return Plan.Follow(method, target, method == "GET" || method == "HEAD")
        }
        // 307/308: metodo y cuerpo intactos.
        return Plan.Follow(method, target, false)
    }
}
