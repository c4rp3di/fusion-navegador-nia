package nia.engine.net

/**
 * Identidad y TLS detallado (T084 v1, R0160, RES-0077).
 *
 * La identidad la produce el motor desde el transporte REAL, jamas desde el
 * contenido de la pagina (no spoofeable): TlsInfo lleva emisor, sujeto y
 * ventana de validez SOLO cuando el adaptador los expone. PanelIdentidad
 * pinta esas lineas sin fabricar datos: un adaptador sin cadena se
 * muestra como "no disponible" (la cache conserva el tls guardado con
 * la entrada desde T113 v2) y el panel SIEMPRE recuerda que el cifrado
 * no hace que el sitio sea de confiar (HTTPS != sitio de confianza).
 *
 * ExcepcionesHttps mantiene las excepciones HTTP por sitio separadas,
 * deduplicadas y ordenadas (solo sesion). PoliticaHttpsOnly decide cada
 * salto: https navega siempre; http sin excepcion se bloquea ANTES de red
 * y con excepcion navega con el evento visible "excepcion-http:host".
 *
 * Exclusiones v1 (API propia del motor): sin visibilidad en NavTools
 * (T089), sin persistencia de excepciones y sin smoke T067-C.
 */
data class TlsInfo(
    val emisor: String?,
    val sujeto: String?,
    val validoDesde: String?,
    val validoHasta: String?
)

object PanelIdentidad {

    const val NO_GARANTIZA_CONFIANZA = "El cifrado no hace que el sitio sea de confiar"

    /** T084 v2 (R0319, RES-0129): aviso claro del cleartext (decision del usuario R0318). */
    const val AVISO_SIN_CIFRAR = "AVISO: esta pagina viaja sin cifrar; cualquiera en la red puede leerla o cambiarla"

    fun lineas(url: String, tls: TlsInfo?): List<String> {
        val bajo = url.trim().lowercase()
        if (bajo.startsWith("https://")) {
            val out = ArrayList<String>()
            out.add("Conexion cifrada (HTTPS)")
            val emisor = tls?.emisor
            val sujeto = tls?.sujeto
            val desde = tls?.validoDesde
            val hasta = tls?.validoHasta
            out.add(if (emisor != null) "Emisor: " + emisor else "Emisor: no disponible")
            if (sujeto != null) {
                out.add("Sujeto: " + sujeto)
            }
            if (desde != null) {
                out.add("Valido desde: " + desde)
            }
            if (hasta != null) {
                out.add("Valido hasta: " + hasta)
            }
            out.add(NO_GARANTIZA_CONFIANZA)
            return out
        }
        if (bajo.startsWith("http://")) {
            return listOf(
                "Conexion NO cifrada (HTTP)",
                AVISO_SIN_CIFRAR,
                "Sin certificado que mostrar",
                NO_GARANTIZA_CONFIANZA
            )
        }
        return listOf("Esquema sin conexion web", NO_GARANTIZA_CONFIANZA)
    }
}

/**
 * Excepciones HTTP concedidas por el usuario, por sitio y solo para la
 * sesion: deduplicadas y listadas en orden alfabetico.
 */
class ExcepcionesHttps {
    private val hosts = HashSet<String>()

    fun conceder(host: String) {
        hosts.add(host)
    }

    fun tiene(host: String): Boolean = hosts.contains(host)

    fun lista(): List<String> = hosts.sorted()
}

/**
 * Politica HTTPS-only inyectable en el NavController: decide cada salto
 * ANTES de llamar al transporte. https navega siempre; http sin
 * excepcion se bloquea (DENIED) y con excepcion navega con la
 * advertencia visible "excepcion-http:host".
 */
class PoliticaHttpsOnly(private val excepciones: ExcepcionesHttps) {

    sealed class Decision {
        object Navegar : Decision()
        data class Bloquear(val host: String) : Decision()
        data class PermitirConAdvertencia(val host: String) : Decision()
    }

    fun decidir(scheme: String, host: String): Decision {
        if (scheme == "https") {
            return Decision.Navegar
        }
        if (excepciones.tiene(host)) {
            return Decision.PermitirConAdvertencia(host)
        }
        return Decision.Bloquear(host)
    }
}
