package nia.engine.net

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import nia.contracts.CancelReason
import nia.contracts.CancellationTokenSource
import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * T011 v2 (R0043): el transporte real contra un servidor HTTP local del
 * JDK (127.0.0.1, puerto libre). CI ejecuta estos tests en la JVM; el
 * mismo HttpTransport corre en el dispositivo con permiso INTERNET.
 *
 * Las cabeceras de respuesta se asertan con nombres en minusculas: el
 * transporte las normaliza porque HTTP/1.1 es case-insensitive (RFC
 * 7230) y cada servidor capitaliza los nombres a su manera.
 */
class HttpTransportTest {

    /** Servidor local en 127.0.0.1 con puerto libre; el handler atiende todo. */
    private fun withServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        try {
            block("http://127.0.0.1:" + server.address.port + "/")
        } finally {
            server.stop(0)
        }
    }

    private fun success(result: TransportResult): FetchResponse {
        assertTrue(result is TransportResult.Success)
        return (result as TransportResult.Success).response
    }

    private fun failure(result: TransportResult): TransportFailure {
        assertTrue(result is TransportResult.Failure)
        return (result as TransportResult.Failure).kind
    }

    private fun responder(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test fun getDevuelveEstadoCabecerasYCuerpo() {
        withServer({ exchange ->
            exchange.responseHeaders.add("X-Nia", "hola")
            responder(exchange, 200, "hola")
        }, { url ->
            val transport = HttpTransport()
            val request = FetchRequest(1, "GET", url, mapOf("X-Prueba" to "1"), null)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(200, response.status)
            assertEquals("hola", response.headers["x-nia"])
            assertEquals("hola", String(response.body, Charsets.UTF_8))
        })
    }

    @Test fun postHaceEchoDelMetodoYCuerpo() {
        withServer({ exchange ->
            val recibido = exchange.requestBody.readBytes()
            exchange.responseHeaders.add("X-Metodo", exchange.requestMethod)
            responder(exchange, 200, String(recibido, Charsets.UTF_8))
        }, { url ->
            val transport = HttpTransport()
            val body = "cuerpo-post".toByteArray(Charsets.UTF_8)
            val request = FetchRequest(2, "POST", url, mapOf("Content-Type" to "text/plain"), body)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(200, response.status)
            assertEquals("POST", response.headers["x-metodo"])
            assertEquals("cuerpo-post", String(response.body, Charsets.UTF_8))
        })
    }

    @Test fun cabecerasRepetidasSeUnenConComa() {
        withServer({ exchange ->
            exchange.responseHeaders.add("X-Rep", "a")
            exchange.responseHeaders.add("X-Rep", "b")
            responder(exchange, 200, "x")
        }, { url ->
            val transport = HttpTransport()
            val request = FetchRequest(3, "GET", url, emptyMap(), null)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals("a, b", response.headers["x-rep"])
        })
    }

    @Test fun redireccionNoSeSigue() {
        withServer({ exchange ->
            if (exchange.requestURI.path == "/salto") {
                exchange.responseHeaders.add("Location", "/destino")
                exchange.sendResponseHeaders(301, -1)
                exchange.close()
            } else {
                responder(exchange, 200, "destino")
            }
        }, { url ->
            val transport = HttpTransport()
            val request = FetchRequest(4, "GET", url + "salto", emptyMap(), null)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(301, response.status)
            assertEquals("/destino", response.headers["location"])
        })
    }

    @Test fun estadoDeErrorConCuerpo() {
        withServer({ exchange ->
            responder(exchange, 404, "no hay")
        }, { url ->
            val transport = HttpTransport()
            val request = FetchRequest(5, "GET", url, emptyMap(), null)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(404, response.status)
            assertEquals("no hay", String(response.body, Charsets.UTF_8))
        })
    }

    @Test fun headDevuelveEstadoSinCuerpo() {
        withServer({ exchange ->
            responder(exchange, 200, "ignorado")
        }, { url ->
            val transport = HttpTransport()
            val request = FetchRequest(6, "HEAD", url, emptyMap(), null)
            val response = success(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(200, response.status)
            assertEquals(0, response.body.size)
        })
    }

    @Test fun dnsInexistenteVuelveDNS() {
        val transport = HttpTransport()
        val request = FetchRequest(7, "GET", "http://dominio-nia-inexistente.invalid/", emptyMap(), null)
        val kind = failure(transport.fetch(request, CancellationTokenSource().token))
        assertEquals(TransportFailure.DNS, kind)
    }

    @Test fun tlsSobrePuertoPlanoVuelveTLS_INVALID() {
        withServer({ exchange ->
            responder(exchange, 200, "plano")
        }, { url ->
            val puerto = url.substringAfter("127.0.0.1:").substringBefore("/")
            val transport = HttpTransport()
            val request = FetchRequest(8, "GET", "https://127.0.0.1:" + puerto + "/", emptyMap(), null)
            val kind = failure(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(TransportFailure.TLS_INVALID, kind)
        })
    }

    @Test fun timeoutDeLecturaVuelveNETWORK() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer({ exchange ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            try {
                responder(exchange, 200, "tarde")
            } catch (_: IOException) {
                // el cliente se desconecto: esperado en este test
            }
        }, { url ->
            val transport = HttpTransport(readTimeoutMillis = 300)
            val request = FetchRequest(9, "GET", url, emptyMap(), null)
            val kind = failure(transport.fetch(request, CancellationTokenSource().token))
            assertEquals(TransportFailure.NETWORK, kind)
            release.countDown()
        })
    }

    @Test fun cancelacionDesconectaYElManagerInformaCANCELLED() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServer({ exchange ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            try {
                responder(exchange, 200, "tarde")
            } catch (_: IOException) {
                // el cliente se desconecto: esperado en este test
            }
        }, { url ->
            // Determinista: si disconnect() no desbloquea la lectura a tiempo
            // (el JDK no lo garantiza), el readTimeout corto la libera igual
            // y el manager informa CANCELLED en ambos caminos.
            val manager = FetchManager(HttpTransport(readTimeoutMillis = 500))
            val source = CancellationTokenSource()
            var outcome: Outcome<FetchResponse>? = null
            val worker = Thread {
                outcome = manager.fetch(manager.newRequest("GET", url), source.token)
            }
            worker.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            source.cancel(CancelReason.Explicit)
            worker.join(10_000)
            release.countDown()
            val resultado = outcome
            assertTrue(resultado is Outcome.Failure)
            assertEquals(ErrorCode.CANCELLED, (resultado as Outcome.Failure).error.code)
        })
    }
}
