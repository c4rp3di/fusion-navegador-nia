package nia.engine.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

import nia.contracts.CancellationToken

/**
 * T011 v2 (R0043, RES-0028): transporte real para FetchManager sobre la
 * frontera FetchTransport de v1. JVM puro (java.net): HttpURLConnection
 * es la misma pila que Android usa, asi que este transporte sirve en CI
 * (servidor local en los tests) y en el dispositivo (permiso INTERNET
 * del manifest). Politicas fijas de v2:
 *
 * - TLS SIEMPRE valida cadena y hostname (validacion por defecto de la
 *   JVM/Android; NUNCA se instala un TrustManager que acepte todo).
 * - Las redirecciones NO se siguen: una 3xx se entrega tal cual y el
 *   navegador decidira en T026 (misma-origen, politicas, cache).
 * - Cancelacion cooperativa: token.onCancel desconecta el socket para
 *   desbloquear el hilo llamador; el FetchManager observa la
 *   cancelacion y mapea a CANCELLED descartando la respuesta.
 * - Timeouts de socket fijos (10s por defecto): el deadline SEMANTICO
 *   de la operacion es del manager; estos solo protegen el socket.
 *
 * El transporte es sincrono y bloqueante: en Android se llama desde un
 * hilo de fondo (NetworkOnMainThreadException si no).
 */
class HttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000
) : FetchTransport {

    override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
        if (token.isCancelled) {
            return TransportResult.Failure(TransportFailure.NETWORK)
        }
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return TransportResult.Failure(TransportFailure.NETWORK)
        }
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            for ((key, value) in request.headers) {
                connection.setRequestProperty(key, value)
            }
            val unsubscribe = token.onCancel {
                connection.disconnect()
            }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body)
                    output.flush()
                }
            }
            return try {
                val status = connection.responseCode
                TransportResult.Success(
                    FetchResponse(status, responseHeaders(connection), responseBody(connection, request.method, status))
                )
            } catch (_: SSLException) {
                TransportResult.Failure(TransportFailure.TLS_INVALID)
            } catch (_: UnknownHostException) {
                TransportResult.Failure(TransportFailure.DNS)
            } catch (_: IOException) {
                TransportResult.Failure(TransportFailure.NETWORK)
            } finally {
                unsubscribe()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Cabeceras de respuesta: las repetidas se unen con coma HTTP. */
    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var index = 1
        while (true) {
            val key = connection.getHeaderFieldKey(index) ?: break
            val value = connection.getHeaderField(index) ?: ""
            val prev = headers[key]
            headers[key] = if (prev == null) value else prev + ", " + value
            index += 1
        }
        return headers
    }

    /**
     * Cuerpo de respuesta: errorStream para estados de error; vacio para
     * HEAD y 204 (leer el flujo en esos casos puede fallar en la JVM).
     */
    private fun responseBody(
        connection: HttpURLConnection,
        method: String,
        status: Int
    ): ByteArray {
        if (method == "HEAD" || status == 204) return ByteArray(0)
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes() } ?: ByteArray(0)
    }
}
