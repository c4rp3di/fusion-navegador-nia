package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import nia.contracts.CancelReason
import nia.contracts.CancellationToken
import nia.contracts.CancellationTokenSource
import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome
import nia.contracts.Retryability
import nia.contracts.WallClock

class FetchManagerTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private class FakeTransport : FetchTransport {
        var hook: (FetchRequest, CancellationToken) -> TransportResult = { _, _ ->
            TransportResult.Success(FetchResponse(200, emptyMap(), ByteArray(0)))
        }
        val requests = mutableListOf<FetchRequest>()
        val tokens = mutableListOf<CancellationToken>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            tokens.add(token)
            return hook(request, token)
        }
    }

    private fun okResp(
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): TransportResult = TransportResult.Success(FetchResponse(status, headers, body))

    private class FiltroFijo(private val decision: FilterDecision) : RequestFilter {
        override fun decide(context: RequestContext): FilterDecision = decision
    }

    private fun <T> valor(o: Outcome<T>): T {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as T
    }

    private fun fallo(o: Outcome<*>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    @Test fun httpsGetSeEntrega() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val resp = valor(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertEquals(200, resp.status)
        assertEquals(1, m.deliveredCount())
    }

    @Test fun httpAceptado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        valor(m.fetch(m.newRequest("GET", "http://example.com/x")))
        assertEquals(1, m.deliveredCount())
    }

    @Test fun metodoInvalidoRechazado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val e = fallo(m.fetch(m.newRequest("TRACE", "https://example.com/")))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
        assertEquals(0, t.requests.size)
    }

    @Test fun esquemaNoSoportadoRechazado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val e = fallo(m.fetch(m.newRequest("GET", "ftp://example.com/")))
        assertEquals(ErrorCode.UNSUPPORTED, e.code)
        assertEquals(0, t.requests.size)
    }

    @Test fun metodoPostConBodyIntacto() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val body = "hola".toByteArray(Charsets.UTF_8)
        t.hook = { req, _ ->
            assertEquals("POST", req.method)
            okResp()
        }
        valor(m.fetch(m.newRequest("POST", "https://example.com/submit", emptyMap(), body)))
        val b = t.requests[0].body ?: throw AssertionError("falta body")
        assertTrue(b.contentEquals(body))
    }

    @Test fun tlsInvalidoControlado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.TLS_INVALID) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertEquals(ErrorCode.TLS, e.code)
        assertEquals(0, m.deliveredCount())
    }

    @Test fun tlsMensajeSeguroSinUrl() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.TLS_INVALID) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertTrue(!e.safeMessage.contains("example.com"))
    }

    @Test fun tlsNoEsReintentable() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.TLS_INVALID) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertEquals(Retryability.NONE, e.retryability)
    }

    @Test fun dnsFalloEsRedReintentable() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.DNS) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertEquals(ErrorCode.NETWORK, e.code)
        assertEquals(Retryability.RETRY_LATER, e.retryability)
    }

    @Test fun redFalloControlado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.NETWORK) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/")))
        assertEquals(ErrorCode.NETWORK, e.code)
        assertEquals(0, m.deliveredCount())
    }

    @Test fun respuestaDemasiadoGrandeEsQUOTA() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> TransportResult.Failure(TransportFailure.TOO_LARGE) }
        val e = fallo(m.fetch(m.newRequest("GET", "https://example.com/grande")))
        assertEquals(ErrorCode.QUOTA, e.code)
        assertEquals("respuesta demasiado grande", e.safeMessage)
        assertEquals(Retryability.RETRY_LATER, e.retryability)
        assertEquals(0, m.deliveredCount())
    }

    @Test fun cancelacionExplicitaDescartaRespuesta() {
        val clock = FakeClock(1000)
        val source = CancellationTokenSource(clock)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        t.hook = { _, _ ->
            source.cancel(CancelReason.Explicit)
            okResp()
        }
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/"), source.token))
        assertEquals(ErrorCode.CANCELLED, e.code)
        assertTrue(e.safeMessage.contains("explicit"))
        assertEquals(0, m.deliveredCount())
    }

    @Test fun deadlineVencidoCancelaAntesDelTransporte() {
        val clock = FakeClock(5000)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/"), null, 4000))
        assertEquals(ErrorCode.CANCELLED, e.code)
        assertTrue(e.safeMessage.contains("deadline"))
        assertEquals(0, t.requests.size)
    }

    @Test fun deadlineFuturoNoCancela() {
        val clock = FakeClock(5000)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        valor(m.fetch(m.newRequest("GET", "https://a.example/"), null, 9000))
        assertEquals(1, m.deliveredCount())
    }

    @Test fun tokenPasadoAlTransporte() {
        val clock = FakeClock(1000)
        val source = CancellationTokenSource(clock)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        t.hook = { _, tok ->
            assertTrue(!tok.isCancelled)
            okResp()
        }
        valor(m.fetch(m.newRequest("GET", "https://a.example/"), source.token))
        assertEquals(1, t.tokens.size)
        source.cancel(CancelReason.Explicit)
        assertTrue(t.tokens[0].isCancelled)
    }

    @Test fun respuestaStaleDescartada() {
        val clock = FakeClock(1000)
        val source = CancellationTokenSource(clock)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        t.hook = { _, _ ->
            m.invalidate()
            okResp()
        }
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/"), source.token))
        assertEquals(ErrorCode.STALE_HANDLE, e.code)
        assertEquals(0, m.deliveredCount())
    }

    @Test fun invalidateIncrementaGeneracion() {
        val m = FetchManager(FakeTransport())
        assertEquals(1, m.invalidate())
        assertEquals(2, m.invalidate())
    }

    @Test fun staleNoAfectaFetchesNuevos() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ ->
            m.invalidate()
            okResp()
        }
        fallo(m.fetch(m.newRequest("GET", "https://a.example/")))
        t.hook = { _, _ -> okResp() }
        valor(m.fetch(m.newRequest("GET", "https://b.example/")))
        assertEquals(1, m.deliveredCount())
    }

    @Test fun generacionEstableSinInvalidate() {
        val t = FakeTransport()
        val m = FetchManager(t)
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        valor(m.fetch(m.newRequest("GET", "https://b.example/")))
        assertEquals(2, m.deliveredCount())
    }

    @Test fun idsMonotonicos() {
        val m = FetchManager(FakeTransport())
        assertEquals(1, m.newRequest("GET", "https://a.example/").id)
        assertEquals(2, m.newRequest("GET", "https://b.example/").id)
        assertEquals(3, m.newRequest("GET", "https://c.example/").id)
    }

    @Test fun cabecerasIntactas() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> okResp(200, mapOf("content-type" to "text/html")) }
        val resp = valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals("text/html", resp.headers["content-type"])
    }

    @Test fun canceladoPrevaleceSobreStale() {
        val clock = FakeClock(1000)
        val source = CancellationTokenSource(clock)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        t.hook = { _, _ ->
            source.cancel(CancelReason.Explicit)
            m.invalidate()
            okResp()
        }
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/"), source.token))
        assertEquals(ErrorCode.CANCELLED, e.code)
        assertEquals(0, m.deliveredCount())
    }

    @Test fun cuerpoVacioEntregado() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val resp = valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals(0, resp.body.size)
    }

    @Test fun sustitucionSeSirveSinCacheNiTransporte() {
        val t = FakeTransport()
        val cache = HttpCache(FakeClock(1000))
        val bytes = "video juguete".toByteArray(Charsets.UTF_8)
        val m = FetchManager(
            t, FakeClock(1000), cache,
            FiltroFijo(FilterDecision.Substitute("demo.mod", bytes, "video/mp4"))
        )
        val resp = valor(m.fetch(m.newRequest("GET", "https://cdn.example.org/v.mp4")))
        assertEquals(200, resp.status)
        assertEquals("video/mp4", resp.headers["Content-Type"])
        assertEquals("bytes", resp.headers["Accept-Ranges"])
        assertEquals("no-store", resp.headers["Cache-Control"])
        assertEquals(bytes.size.toString(), resp.headers["Content-Length"])
        assertTrue(resp.body.contentEquals(bytes))
        assertEquals(0, t.requests.size, "la sustitucion no toca el transporte")
        assertEquals(0, m.deliveredCount(), "la sustitucion no cuenta como entrega")
        assertTrue(
            cache.lookup("GET", "https://cdn.example.org/v.mp4") is HttpCache.Lookup.Miss,
            "la sustitucion jamas puebla la cache"
        )
        assertEquals(0, cache.count())
    }

    @Test fun sustitucionConRangeParcialResponde206() {
        val bytes = ByteArray(200) { it.toByte() }
        val m = FetchManager(
            FakeTransport(),
            filter = FiltroFijo(FilterDecision.Substitute("demo.mod", bytes, "video/mp4"))
        )
        val req = m.newRequest("GET", "https://cdn.example.org/v.mp4", mapOf("Range" to "bytes=0-99"))
        val resp = valor(m.fetch(req))
        assertEquals(206, resp.status)
        assertEquals("bytes 0-99/200", resp.headers["Content-Range"])
        assertEquals("100", resp.headers["Content-Length"])
        assertEquals(100, resp.body.size)
        assertTrue(resp.body.contentEquals(bytes.sliceArray(0..99)))
    }

    @Test fun sustitucionConRangeInvalidoDenegada() {
        val bytes = "x".toByteArray(Charsets.UTF_8)
        val m = FetchManager(
            FakeTransport(),
            filter = FiltroFijo(FilterDecision.Substitute("demo.mod", bytes, "video/mp4"))
        )
        val req = m.newRequest("GET", "https://cdn.example.org/v.mp4", mapOf("Range" to "bytes=9-3"))
        assertEquals(ErrorCode.DENIED, fallo(m.fetch(req)).code)
    }

    @Test fun sustitucionHeadNoLlevaCuerpo() {
        val bytes = "video juguete".toByteArray(Charsets.UTF_8)
        val m = FetchManager(
            FakeTransport(),
            filter = FiltroFijo(FilterDecision.Substitute("demo.mod", bytes, "video/mp4"))
        )
        val resp = valor(m.fetch(m.newRequest("HEAD", "https://cdn.example.org/v.mp4")))
        assertEquals(200, resp.status)
        assertEquals(0, resp.body.size)
        assertEquals(
            bytes.size.toString(),
            resp.headers["Content-Length"],
            "el content-length es del cuerpo que GET habria dado"
        )
    }

    @Test fun sustitucionCrossOriginReflejaElOrigenDelIniciador() {
        val bytes = "x".toByteArray(Charsets.UTF_8)
        val m = FetchManager(
            FakeTransport(),
            filter = FiltroFijo(FilterDecision.Substitute("demo.mod", bytes, "video/mp4"))
        )
        val ctx = RequestContext("https://cdn.example.org/v.mp4", "fetch", "https://sitio.com/pagina", true)
        val resp = valor(m.fetch(m.newRequest("GET", "https://cdn.example.org/v.mp4"), null, -1, ctx))
        assertEquals("sitio.com", resp.headers["Access-Control-Allow-Origin"])
        val same = RequestContext("https://sitio.com/v.mp4", "fetch", "https://sitio.com/pagina", false)
        val resp2 = valor(m.fetch(m.newRequest("GET", "https://sitio.com/v.mp4"), null, -1, same))
        assertTrue(!resp2.headers.containsKey("Access-Control-Allow-Origin"), "same-origin sin ACAO")
    }

    @Test fun invalidateCancelaElTokenInternoEnVuelo() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ ->
            m.invalidate()
            okResp()
        }
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals(ErrorCode.CANCELLED, e.code)
        assertTrue(t.tokens[0].isCancelled)
        assertEquals(0, m.operacionesActivas())
    }

    @Test fun operacionCompletadaSeRetiraDelRegistroActivo() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> okResp() }
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals(0, m.operacionesActivas())
        assertEquals(1, m.invalidate())
    }

    @Test fun invalidateNoCancelaElTokenExterno() {
        val clock = FakeClock(1000)
        val source = CancellationTokenSource(clock)
        val t = FakeTransport()
        val m = FetchManager(t, clock)
        t.hook = { _, _ ->
            m.invalidate()
            okResp()
        }
        val e = fallo(m.fetch(m.newRequest("GET", "https://a.example/"), source.token))
        assertTrue(!source.token.isCancelled)
        assertEquals(ErrorCode.STALE_HANDLE, e.code)
        assertEquals(0, m.operacionesActivas())
    }
}
