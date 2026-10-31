package nia.engine.url

/**
 * Origen (T010): tupla (scheme, host, puerto efectivo) para URLs con origen
 * definido, opaco para el resto (file, data, ...). NUNCA usar la URL completa
 * ni su String como identidad de origen (maestro, seccion 3).
 */
sealed class Origin {

    data class Tuple(val scheme: String, val host: String, val port: Int) : Origin()

    data class Opaque(val reason: String) : Origin()

    /** Mismo origen: tuplas identicas; los origenes opacos nunca lo son. */
    fun sameOrigin(other: Origin): Boolean {
        if (this is Opaque || other is Opaque) return false
        return this == other
    }

    companion object {

        private val TUPLE_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")

        fun of(url: Url): Origin {
            if (TUPLE_SCHEMES.contains(url.scheme)) {
                return Tuple(url.scheme, url.host, url.effectivePort)
            }
            return Opaque("esquema ${'$'}{url.scheme} sin origen definido")
        }
    }
}
