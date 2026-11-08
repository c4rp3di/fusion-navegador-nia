package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock

/**
 * Espejo Kotlin del gemelo TS validado offline (R0242, vectores
 * tercero/orden): RequestFilter consultado ANTES de red y cache en
 * FetchManager, contexto del llamador y tercero del host contra
 * Site v1. T052 v1 (R0242, RES-0111), SIN red (transporte falso que
 * JAMAS se llama cuando algo bloquea).
 */
class RequestFilterTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private class FakeTransport : FetchTransport {
        val requests = mutableListOf<FetchRequest>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            return TransportResult.Success(FetchResponse(200, emptyMap(), ByteArray(0)))
        }
    }

    /** Filtro con decision fija que captura el contexto que ve. */
    private class FiltroFalso(
        private val decision: (RequestContext) -> FilterDecision
    ) : RequestFilter {
        var ultimo: RequestContext? = null
        override fun decide(context: RequestContext): FilterDecision {
            ultimo = context
            return decision(context)
        }
    }

    private fun bloqueaTodo(context: RequestContext): FilterDecision =
        FilterDecision.Block("demo.filtro")

    private fun permiteTodo(context: RequestContext): FilterDecision =
        FilterDecision.Allow

    @Test fun bloqueaAntesDeRedYDeCacheConEntradaFresca() {
        val reloj = FakeClock(1000)
        val cache = HttpCache(reloj)
        cache.store(
            "https://example.com/pagina",
            FetchResponse(200, mapOf("cache-control" to "max-age=60"), "cuerpo".toByteArray())
        )
        val t = FakeTransport()
        val m = FetchManager(t, reloj, cache, FiltroFalso(::bloqueaTodo))
        val e = m.fetch(m.newRequest("GET", "https://example.com/pagina"))
        assertTrue(e is Outcome.Failure)
        assertEquals(ErrorCode.DENIED, (e as Outcome.Failure).error.code)
        assertEquals("peticion bloqueada por un modulo", e.error.safeMessage)
        assertEquals(0, t.requests.size)
        assertEquals(0, m.deliveredCount())
        assertTrue(cache.entryFor("https://example.com/pagina") != null)
    }

    @Test fun permitirDejaElCicloIntacto() {
        val t = FakeTransport()
        val m = FetchManager(t, FakeClock(1000), null, FiltroFalso(::permiteTodo))
        val r = m.fetch(m.newRequest("GET", "https://example.com/x"))
        assertTrue(r is Outcome.Success)
        assertEquals(1, t.requests.size)
        assertEquals(1, m.deliveredCount())
    }

    @Test fun elFiltroVeElContextoDelLlamador() {
        val filtro = FiltroFalso(::permiteTodo)
        val m = FetchManager(FakeTransport(), FakeClock(1000), null, filtro)
        m.fetch(
            m.newRequest("GET", "https://cdn.tercero.com/img.png"),
            null,
            -1,
            RequestContext.of("https://cdn.tercero.com/img.png", "image", "https://mipagina.com/index.html")
        )
        val ctx = filtro.ultimo
        assertTrue(ctx != null)
        assertEquals("https://cdn.tercero.com/img.png", ctx!!.url)
        assertEquals("image", ctx.resourceType)
        assertEquals("https://mipagina.com/index.html", ctx.initiatorUrl)
        assertTrue(ctx.thirdParty == true)
    }

    @Test fun contextoPorDefectoCuandoElLlamadorNoPasaNada() {
        val filtro = FiltroFalso(::permiteTodo)
        val m = FetchManager(FakeTransport(), FakeClock(1000), null, filtro)
        m.fetch(m.newRequest("GET", "https://example.com/x"))
        val ctx = filtro.ultimo
        assertTrue(ctx != null)
        assertEquals("otro", ctx!!.resourceType)
        assertEquals("", ctx.initiatorUrl)
        assertTrue(ctx.thirdParty == null)
    }

    @Test fun mismoSitioNoEsTercero() {
        val c = RequestContext.of("https://static.ejemplo.com/a.png", "image", "https://www.ejemplo.com/")
        assertTrue(c.thirdParty == false)
    }

    @Test fun sitioDistintoSiEsTercero() {
        val c = RequestContext.of("https://ads.otro.net/x.js", "script", "https://mipagina.com/")
        assertTrue(c.thirdParty == true)
    }

    @Test fun iniciadorVacioDejaTerceroDesconocido() {
        val c = RequestContext.of("https://example.com/x", "fetch", "")
        assertTrue(c.thirdParty == null)
    }

    @Test fun iniciadorIlegibleDejaTerceroDesconocido() {
        val c = RequestContext.of("https://example.com/x", "fetch", "no-tiene-esquema")
        assertTrue(c.thirdParty == null)
    }

    @Test fun elBloqueoNoCuentaComoEntregada() {
        var bloquear = true
        val filtro = FiltroFalso {
            if (bloquear) FilterDecision.Block("demo.filtro") else FilterDecision.Allow
        }
        val m = FetchManager(FakeTransport(), FakeClock(1000), null, filtro)
        m.fetch(m.newRequest("GET", "https://example.com/a"))
        m.fetch(m.newRequest("GET", "https://example.com/b"))
        assertEquals(0, m.deliveredCount())
        bloquear = false
        val r = m.fetch(m.newRequest("GET", "https://example.com/c"))
        assertTrue(r is Outcome.Success)
        assertEquals(1, m.deliveredCount())
    }

    @Test fun sinFiltroElComportamientoEsIdentico() {
        val t = FakeTransport()
        val m = FetchManager(t)
        val r = m.fetch(m.newRequest("GET", "https://example.com/x"))
        assertTrue(r is Outcome.Success)
        assertEquals(1, t.requests.size)
        assertEquals(1, m.deliveredCount())
    }
}
