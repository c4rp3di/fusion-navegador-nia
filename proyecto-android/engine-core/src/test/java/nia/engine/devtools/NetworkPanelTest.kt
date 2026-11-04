package nia.engine.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import nia.contracts.CancelReason
import nia.contracts.CancellationToken
import nia.contracts.CancellationTokenSource
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult

class NetworkPanelTest {

    private class FakeTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        var next: TransportResult = TransportResult.Success(
            FetchResponse(200, mapOf("content-type" to "text/plain"), "hola".toByteArray())
        )
        var onFetch: ((FetchRequest) -> Unit)? = null

        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            onFetch?.invoke(request)
            return next
        }
    }

    @Test
    fun exitoRegistraMetodoUrlEstadoCabecerasYTamano() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        val o = panel.fetch(FetchRequest(1, "GET", "http://x/a", mapOf("accept" to "text/plain"), null))
        assertTrue(o is Outcome.Success)
        val e = panel.entries()[0]
        assertEquals(1L, e.seq)
        assertEquals("GET", e.method)
        assertEquals("http://x/a", e.url)
        assertEquals("text/plain", e.requestHeaders["accept"])
        assertEquals(200, e.status)
        assertEquals(mapOf("content-type" to "text/plain"), e.responseHeaders)
        assertEquals(4, e.bodyBytes)
        assertEquals("ok", e.outcome)
    }

    @Test
    fun cabecerasSensiblesSeRedactanEnLaIngesta() {
        val t = FakeTransport()
        t.next = TransportResult.Success(
            FetchResponse(200, mapOf("Set-Cookie" to "sid=1", "content-type" to "text/plain"), ByteArray(0))
        )
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(
            FetchRequest(1, "GET", "http://x/a", mapOf("Authorization" to "Bearer secreto", "Cookie" to "sid=1"), null)
        )
        val e = panel.entries()[0]
        assertEquals("[redacted]", e.requestHeaders["Authorization"])
        assertEquals("[redacted]", e.requestHeaders["Cookie"])
        assertEquals("[redacted]", e.responseHeaders?.get("Set-Cookie"))
        assertEquals("text/plain", e.responseHeaders?.get("content-type"))
    }

    @Test
    fun tokenYUserinfoDeLaUrlSeRedactan() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "http://x/a?token=secreto&b=2", emptyMap(), null))
        assertEquals("http://x/a?token=[redacted]&b=2", panel.entries()[0].url)
        panel.clear()
        panel.fetch(FetchRequest(2, "GET", "http://user:pass@x/a", emptyMap(), null))
        assertEquals("http://[redacted]@x/a", panel.entries()[0].url)
    }

    @Test
    fun falloDeRedRegistraOutcomeSinEstado() {
        val t = FakeTransport()
        t.next = TransportResult.Failure(TransportFailure.NETWORK)
        val panel = NetworkPanel(FetchManager(t))
        val o = panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        assertTrue(o is Outcome.Failure)
        assertEquals(ErrorCode.NETWORK, (o as Outcome.Failure).error.code)
        val e = panel.entries()[0]
        assertEquals(null, e.status)
        assertEquals(null, e.responseHeaders)
        assertEquals(null, e.bodyBytes)
        assertEquals("network", e.outcome)
    }

    @Test
    fun falloTlsRegistraTls() {
        val t = FakeTransport()
        t.next = TransportResult.Failure(TransportFailure.TLS_INVALID)
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "https://x/a", emptyMap(), null))
        assertEquals("tls", panel.entries()[0].outcome)
    }

    @Test
    fun cancelacionRegistraCancelledYDescartaLaRespuesta() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        val source = CancellationTokenSource()
        source.cancel(CancelReason.Explicit)
        val o = panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null), source.token)
        assertTrue(o is Outcome.Failure)
        assertEquals(ErrorCode.CANCELLED, (o as Outcome.Failure).error.code)
        val e = panel.entries()[0]
        assertEquals("cancelled", e.outcome)
        assertEquals(null, e.status)
    }

    @Test
    fun staleRegistraStaleHandle() {
        val t = FakeTransport()
        val manager = FetchManager(t)
        val panel = NetworkPanel(manager)
        t.onFetch = { manager.invalidate() }
        val o = panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        assertTrue(o is Outcome.Failure)
        assertEquals(ErrorCode.STALE_HANDLE, (o as Outcome.Failure).error.code)
        assertEquals("stale_handle", panel.entries()[0].outcome)
    }

    @Test
    fun entradaInvalidaRegistraInvalidInputSinTransporte() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "TRACE", "http://x/a", emptyMap(), null))
        assertEquals("invalid_input", panel.entries()[0].outcome)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun esquemaNoSoportadoRegistraUnsupported() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "ftp://x/", emptyMap(), null))
        assertEquals("unsupported", panel.entries()[0].outcome)
    }

    @Test
    fun elOutcomeDelManagerLlegaIntacto() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        val ok = panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        assertTrue(ok is Outcome.Success)
        assertEquals(4, (ok as Outcome.Success).value.body.size)
        val source = CancellationTokenSource()
        source.cancel(CancelReason.Explicit)
        val ko = panel.fetch(FetchRequest(2, "GET", "http://x/a", emptyMap(), null), source.token)
        assertTrue(ko is Outcome.Failure)
        assertEquals(ErrorCode.CANCELLED, (ko as Outcome.Failure).error.code)
    }

    @Test
    fun seqMonotonaYOrdenDeRegistro() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        t.next = TransportResult.Failure(TransportFailure.NETWORK)
        panel.fetch(FetchRequest(2, "GET", "http://x/b", emptyMap(), null))
        t.next = TransportResult.Success(FetchResponse(200, emptyMap(), ByteArray(0)))
        panel.fetch(FetchRequest(3, "GET", "http://x/c", emptyMap(), null))
        assertEquals(listOf(1L, 2L, 3L), panel.entries().map { it.seq })
    }

    @Test
    fun bufferAcotadoExpulsaAlMasAntiguo() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t), 2)
        panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        panel.fetch(FetchRequest(2, "GET", "http://x/b", emptyMap(), null))
        panel.fetch(FetchRequest(3, "GET", "http://x/c", emptyMap(), null))
        assertEquals(2, panel.count())
        assertEquals(2L, panel.entries()[0].seq)
    }

    @Test
    fun clearBorraElRegistro() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        assertEquals(1, panel.count())
        panel.clear()
        assertEquals(0, panel.count())
        assertEquals("", panel.summary())
    }

    @Test
    fun summaryConFormatoDeLinea() {
        val t = FakeTransport()
        val panel = NetworkPanel(FetchManager(t))
        panel.fetch(FetchRequest(1, "GET", "http://x/a", emptyMap(), null))
        assertEquals("1 GET http://x/a 200 (4 B) ok\n", panel.summary())
        panel.clear()
        t.next = TransportResult.Failure(TransportFailure.NETWORK)
        panel.fetch(FetchRequest(2, "GET", "http://x/a", emptyMap(), null))
        assertEquals("2 GET http://x/a - network\n", panel.summary())
    }

    @Test
    fun maxEntriesInvalidoRequiere() {
        assertFailsWith<IllegalArgumentException> { NetworkPanel(FetchManager(FakeTransport()), 0) }
    }
}
