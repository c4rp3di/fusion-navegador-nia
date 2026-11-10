package nia.engine.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
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
 * - Tope de cuerpo (v6, R0307): la lectura del cuerpo esta ACOTADA a
 *   maxBodyBytes (4 MiB por defecto); excederlo aborta la descarga
 *   (disconnect en el finally) y el transporte informa TOO_LARGE.
 *
 * El transporte es sincrono y bloqueante: en Android se llama desde un
 * hilo de fondo (NetworkOnMainThreadException si no).
 */
class HttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxBodyBytes: Long = 4_194_304L
) : FetchTransport {

    init {
        require(maxBodyBytes > 0) { "maxBodyBytes > 0" }
    }

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
                    FetchResponse(status, responseHeaders(connection), responseBody(connection, request.method, status), seguridadTls(connection))
                )
            } catch (_: CuerpoExcedido) {
                TransportResult.Failure(TransportFailure.TOO_LARGE)
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

    /**
     * Cadena real de la conexion TLS: emisor, sujeto y ventana de validez
     * del certificado hoja que expone el transporte. Null cuando la conexion
     * no es TLS (http) o el adaptador no expone cadena: la identidad NUNCA
     * se fabrica.
     */
    private fun seguridadTls(connection: HttpURLConnection): TlsInfo? {
        val https = connection as? HttpsURLConnection ?: return null
        return try {
            val certs = https.serverCertificates
            if (certs.isEmpty()) return null
            val hoja = certs[0] as? X509Certificate ?: return null
            TlsInfo(
                hoja.issuerDN?.name,
                hoja.subjectDN?.name,
                hoja.notBefore?.toInstant()?.toString(),
                hoja.notAfter?.toInstant()?.toString()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cabeceras de respuesta: los nombres se normalizan a minusculas
     * en la frontera del transporte. HTTP/1.1 los declara
     * case-insensitive (RFC 7230) y cada servidor capitaliza a su
     * manera (el HttpServer del JDK envia X-nia cuando el handler
     * agrego X-Nia: su Headers.normalize solo respeta mayuscula en
     * el primer caracter). Los valores se respetan tal cual y las
     * repetidas se unen con coma HTTP.
     */
    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var index = 1
        while (true) {
            val key = connection.getHeaderFieldKey(index) ?: break
            val value = connection.getHeaderField(index) ?: ""
            val name = key.lowercase()
            val prev = headers[name]
            headers[name] = if (prev == null) value else prev + ", " + value
            index += 1
        }
        return headers
    }

    /**
     * Cuerpo de respuesta: errorStream para estados de error; vacio para
     * HEAD y 204 (leer el flujo en esos casos puede fallar en la JVM).
     * T011 v6 (R0307): lectura ACOTADA a maxBodyBytes en trozos de 8 KiB;
     * el cuerpo JAMAS se acumula sin tope en memoria. Exceder el tope
     * lanza CuerpoExcedido: el finally del fetch desconecta la conexion
     * y el transporte informa TOO_LARGE.
     */
    private fun responseBody(
        connection: HttpURLConnection,
        method: String,
        status: Int
    ): ByteArray {
        if (method == "HEAD" || status == 204) return ByteArray(0)
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        if (stream == null) return ByteArray(0)
        stream.use { input ->
            val buffer = ByteArray(8192)
            val salida = ByteArrayOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (salida.size().toLong() + n > maxBodyBytes) throw CuerpoExcedido()
                salida.write(buffer, 0, n)
            }
            return salida.toByteArray()
        }
    }

    /** Senal interna: el cuerpo excede maxBodyBytes; el fetch la mapea a TOO_LARGE. */
    private class CuerpoExcedido : IOException()
}
