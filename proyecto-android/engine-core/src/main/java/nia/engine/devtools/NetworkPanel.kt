package nia.engine.devtools

import nia.contracts.CancellationToken
import nia.contracts.Outcome
import nia.engine.inspector.SecretRedactor
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse

/**
 * T017 v5 (R0082, RES-0046): panel de red del DevTools. Envuelve al
 * FetchManager por composicion: fetch() delega y registra cada
 * peticion/respuesta como entrada observable, con redaccion de
 * secretos EN LA INGESTA (paridad con el inspector de T008: el crudo
 * no se persiste) via SecretRedactor: la URL redacta los parametros
 * token/passwd/password/secret/api_key y el userinfo de credenciales,
 * y las cabeceras sensibles (authorization, proxy-authorization,
 * cookie, set-cookie, x-api-key, x-auth-token) se sustituyen por la
 * constante REDACTED.
 *
 * Los cuerpos NO se guardan (exclusion heredada del inspector): solo
 * el tamano en bytes de la respuesta. El outcome por entrada es "ok"
 * o el nombre del ErrorCode en minusculas (cancelled, stale_handle,
 * network, tls, invalid_input, unsupported). El buffer es acotado con
 * expulsion FIFO.
 *
 * Exclusiones v5: sin cuerpos ni previews, sin timings, sin eventos
 * del inspector por peticion y sin captura automatica: quien hace
 * las peticiones pasa por el panel (el renderer real lo cableara
 * al FetchManager compartido).
 */
class NetworkPanel(
    private val manager: FetchManager,
    private val maxEntries: Int = 500
) {

    init {
        require(maxEntries > 0) { "maxEntries obligatorio" }
    }

    /** Entrada redactada de una peticion/respuesta: solo datos, sin cuerpos. */
    data class NetworkEntry(
        val seq: Long,
        val method: String,
        val url: String,
        val requestHeaders: Map<String, String>,
        val status: Int?,
        val responseHeaders: Map<String, String>?,
        val bodyBytes: Int?,
        val outcome: String
    )

    private val log = ArrayList<NetworkEntry>()
    private var nextSeq = 1L

    /** Delega en el manager y registra la entrada redactada; el Outcome vuelve intacto. */
    fun fetch(
        request: FetchRequest,
        token: CancellationToken? = null,
        deadlineMillis: Long = -1
    ): Outcome<FetchResponse> {
        val outcome = manager.fetch(request, token, deadlineMillis)
        val entry = NetworkEntry(
            nextSeq,
            request.method,
            SecretRedactor.redactUrl(request.url),
            SecretRedactor.redactHeaders(request.headers),
            extractStatus(outcome),
            extractHeaders(outcome),
            extractBytes(outcome),
            outcomeText(outcome)
        )
        nextSeq += 1
        while (log.size >= maxEntries) log.removeAt(0)
        log.add(entry)
        return outcome
    }

    /** Entradas registradas (redactadas) en orden de registro. */
    fun entries(): List<NetworkEntry> = log.toList()

    /** Cuantas entradas hay registradas. */
    fun count(): Int = log.size

    /** Borra el registro. */
    fun clear() {
        log.clear()
    }

    /** Una linea por entrada: seq metodo url estado (bytes) outcome. */
    fun summary(): String {
        val sb = StringBuilder()
        for (e in log) {
            sb.append(e.seq).append(' ').append(e.method).append(' ').append(e.url).append(' ')
            if (e.status != null) {
                sb.append(e.status).append(" (").append(e.bodyBytes ?: 0).append(" B)")
            } else {
                sb.append('-')
            }
            sb.append(' ').append(e.outcome).append('\n')
        }
        return sb.toString()
    }

    private fun extractStatus(o: Outcome<FetchResponse>): Int? = when (o) {
        is Outcome.Success -> o.value.status
        is Outcome.Failure -> null
    }

    private fun extractHeaders(o: Outcome<FetchResponse>): Map<String, String>? = when (o) {
        is Outcome.Success -> SecretRedactor.redactHeaders(o.value.headers)
        is Outcome.Failure -> null
    }

    private fun extractBytes(o: Outcome<FetchResponse>): Int? = when (o) {
        is Outcome.Success -> o.value.body.size
        is Outcome.Failure -> null
    }

    private fun outcomeText(o: Outcome<FetchResponse>): String = when (o) {
        is Outcome.Success -> "ok"
        is Outcome.Failure -> o.error.code.name.lowercase()
    }
}
