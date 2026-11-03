package nia.engine.url

/**
 * Site (T010 v1): particion de "mismo sitio" para storage.
 *
 * NO se usan los dos ultimos segmentos del hostname (maestro, seccion 3): se
 * consulta una tabla de sufijos publicos (subconjunto embebido, NO el PSL
 * completo). APROXIMACION v1: no usar para decisiones de seguridad hasta
 * integrar el PSL real.
 */
data class Site(val scheme: String, val registrableDomain: String) {

    fun sameSite(other: Site): Boolean = this == other

    companion object {

        private val SUFFIXES = sortedSetOf(
            "com", "org", "net", "edu", "gov", "io", "co", "dev", "app",
            "es", "de", "fr", "it", "eu", "mx", "ar", "br", "cl",
            "com.ar", "com.br", "com.mx", "com.au", "co.uk", "org.uk", "ac.uk",
            "github.io", "gitlab.io", "web.app", "firebaseapp.com",
            "googleusercontent.com", "cloudfront.net"
        )

        fun fromOrigin(origin: Origin.Tuple): Site {
            val host = origin.host
            if (host.startsWith("[")) return Site(origin.scheme, host)
            val labels = host.split('.')
            if (labels.size == 4 && labels.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                return Site(origin.scheme, host)
            }
            if (labels.size < 2) return Site(origin.scheme, host)
            var suffixLen = -1
            for (i in labels.indices) {
                val cand = labels.subList(i, labels.size).joinToString(".")
                if (SUFFIXES.contains(cand)) {
                    suffixLen = labels.size - i
                    break
                }
            }
            if (suffixLen < 0) suffixLen = 1
            val regLen = suffixLen + 1
            if (regLen > labels.size) return Site(origin.scheme, host)
            val reg = labels.subList(labels.size - regLen, labels.size).joinToString(".")
            return Site(origin.scheme, reg)
        }
    }
}
