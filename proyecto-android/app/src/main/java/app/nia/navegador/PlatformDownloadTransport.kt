package app.nia.navegador

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import nia.engine.download.DownloadOpen
import nia.engine.download.DownloadStream
import nia.engine.download.DownloadTransport
import nia.engine.net.TransportFailure

/**
 * T046 v1 (R0097, RES-0053): transporte de descargas sobre
 * HttpURLConnection, la misma pila de HttpTransport de T011 v2 (asi
 * sirve en CI y en el dispositivo con el permiso INTERNET). Politicas
 * fijas: TLS SIEMPRE valida cadena y hostname por defecto (nunca un
 * TrustManager que acepte todo), la cancelacion cooperativa desconecta
 * el socket para desbloquear la lectura (la suscripcion vive hasta que
 * el stream se cierra) y los fallos se mapean a TransportFailure. Sin
 * dependencias nuevas.
 *
 * T046 v2 (R0124, RES-0065): reanudacion Range. Un startByte mayor que
 * cero pide la cabecera Range: bytes=N- ; el estado 206 (Partial
 * Content) abre el stream en ese byte y el total COMPLETO se computa
 * como el Content-Length del resto mas el startByte, y un 200 tras
 * pedir un rango significa que el servidor ignoro el Range: el stream
 * empieza en cero y el motor decide truncar y reiniciar. Cualquier otro
 * estado falla como red (sin cuerpo; los reintentos y la cancelacion
 * son del motor).
 */
class PlatformDownloadTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000
) : DownloadTransport {

    override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return DownloadOpen.Failed(TransportFailure.NETWORK)
        }
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (startByte > 0L) {
                connection.setRequestProperty("Range", "bytes=" + startByte + "-")
            }
            val unsubscribe = token.onCancel {
                connection.disconnect()
            }
            val status = try {
                connection.responseCode
            } catch (e: SSLException) {
                unsubscribe()
                return DownloadOpen.Failed(TransportFailure.TLS_INVALID)
            } catch (e: UnknownHostException) {
                unsubscribe()
                return DownloadOpen.Failed(TransportFailure.DNS)
            } catch (e: IOException) {
                unsubscribe()
                return DownloadOpen.Failed(TransportFailure.NETWORK)
            }
            if (status != 200 && status != 206) {
                unsubscribe()
                connection.disconnect()
                return DownloadOpen.Failed(TransportFailure.NETWORK)
            }
            val startAt = if (status == 206) startByte else 0L
            val restLength = connection.contentLengthLong
            val total = if (status == 206 && restLength >= 0L) startByte + restLength else restLength
            val stream = try {
                connection.inputStream
            } catch (e: IOException) {
                unsubscribe()
                connection.disconnect()
                return DownloadOpen.Failed(TransportFailure.NETWORK)
            }
            return DownloadOpen.Opened(object : DownloadStream {
                override fun read(buffer: ByteArray): Int = stream.read(buffer)
                override fun close() {
                    unsubscribe()
                    try {
                        stream.close()
                    } catch (e: IOException) {
                    } finally {
                        connection.disconnect()
                    }
                }
            }, total, startAt)
        } catch (e: IOException) {
            connection.disconnect()
            return DownloadOpen.Failed(TransportFailure.NETWORK)
        }
    }
}
