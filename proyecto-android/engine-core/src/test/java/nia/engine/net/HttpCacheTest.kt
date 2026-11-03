package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import nia.contracts.CancellationToken
import nia.contracts.Outcome
import nia.contracts.WallClock

/**
 * Tests T026 v2 (R0060): cache HTTP (HttpCache) y FetchManager con
 * cache opcional inyectado (mismo reloj falso para ambos).
 */
class HttpCacheTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private class FakeTransport : FetchTransport {
        var hook: (FetchRequest, CancellationToken) -> TransportResult = { _, _ ->
            TransportResult.Success(FetchResponse(200, emptyMap(), ByteArray(0)))
        }
        val requests = mutableListOf<FetchRequest>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            return hook(request, token)
        }
    }

    private fun okResp(
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): TransportResult = TransportResult.Success(FetchResponse(status, headers, body))

    private fun <T> valor(o: Outcome<T>): T {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as T
    }

    private fun body(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    @Test fun cacheNoStoreNoSeAlmacena() {
        val cache = HttpCache(FakeClock(1000))
        val stored = cache.store("https://a.example/", FetchResponse(200, mapOf("cache-control" to "no-store"), body("x")))
        if (stored != null) throw AssertionError("no-store se almaceno")
        assertEquals(0, cache.count())
    }

    @Test fun cacheMaxAgeFrescoSeSirveSinTransporte() {
        val clock = FakeClock(1000)
        val t = FakeTransport()
        val m = FetchManager(t, clock, HttpCache(clock))
        t.hook = { _, _ -> okResp(200, mapOf("cache-control" to "max-age=60"), body("v1")) }
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        val r = valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals("v1", String(r.body, Charsets.UTF_8))
        assertEquals(1, t.requests.size)
        assertEquals(2, m.deliveredCount())
    }

    @Test fun cacheExpiracionDevuelveStale() {
        val clock = FakeClock(1000)
        val cache = HttpCache(clock)
        cache.store("https://a.example/", FetchResponse(200, mapOf("cache-control" to "max-age=60"), body("v1")))
        clock.valor = 1000 + 61000
        assertTrue(cache.lookup("GET", "https://a.example/") is HttpCache.Lookup.Stale)
    }

    @Test fun cache304ReviveConCuerpoGuardado() {
        val clock = FakeClock(1000)
        val t = FakeTransport()
        val m = FetchManager(t, clock, HttpCache(clock))
        t.hook = { _, _ -> okResp(200, mapOf("cache-control" to "max-age=1", "etag" to "x1"), body("viejo")) }
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        clock.valor = 1000 + 2000
        t.hook = { req, _ ->
            assertEquals("x1", req.headers["if-none-match"])
            okResp(304, mapOf("cache-control" to "max-age=60"))
        }
        val r = valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals("viejo", String(r.body, Charsets.UTF_8))
        assertEquals(200, r.status)
        assertEquals(2, m.deliveredCount())
    }

    @Test fun cache200ReemplazaEntrada() {
        val cache = HttpCache(FakeClock(1000))
        cache.store("https://a.example/", FetchResponse(200, emptyMap(), body("v1")))
        cache.store("https://a.example/", FetchResponse(200, emptyMap(), body("v2")))
        val e = cache.entryFor("https://a.example/")
        if (e == null) throw AssertionError("falta entrada")
        assertEquals("v2", String(e.body, Charsets.UTF_8))
    }

    @Test fun cacheNoCacheSiempreRevalida() {
        val cache = HttpCache(FakeClock(1000))
        cache.store("https://a.example/", FetchResponse(200, mapOf("cache-control" to "no-cache, max-age=60"), body("v")))
        assertTrue(cache.lookup("GET", "https://a.example/") is HttpCache.Lookup.Stale)
    }

    @Test fun cacheSoloGetSeCachea() {
        val clock = FakeClock(1000)
        val t = FakeTransport()
        val cache = HttpCache(clock)
        val m = FetchManager(t, clock, cache)
        t.hook = { _, _ -> okResp(200, mapOf("cache-control" to "max-age=60"), body("p")) }
        valor(m.fetch(m.newRequest("POST", "https://a.example/", emptyMap(), body("q"))))
        assertEquals(0, cache.count())
        assertTrue(cache.lookup("POST", "https://a.example/") is HttpCache.Lookup.Miss)
    }

    @Test fun cachePeticionNoCacheFuerzaTransporte() {
        val clock = FakeClock(1000)
        val t = FakeTransport()
        val cache = HttpCache(clock)
        val m = FetchManager(t, clock, cache)
        t.hook = { _, _ -> okResp(200, mapOf("cache-control" to "max-age=60"), body("v1")) }
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        t.hook = { req, _ ->
            assertEquals("no-cache", req.headers["cache-control"])
            okResp(200, mapOf("cache-control" to "max-age=60"), body("v2"))
        }
        val r = valor(m.fetch(m.newRequest("GET", "https://a.example/", mapOf("cache-control" to "no-cache"))))
        assertEquals("v2", String(r.body, Charsets.UTF_8))
        assertEquals(2, t.requests.size)
    }

    @Test fun cacheExpiresDateFresco() {
        val cache = HttpCache(FakeClock(1000))
        cache.store(
            "https://a.example/",
            FetchResponse(
                200,
                mapOf(
                    "date" to "Mon, 02 Nov 2026 14:00:00 GMT",
                    "expires" to "Mon, 02 Nov 2026 14:00:30 GMT"
                ),
                body("v")
            )
        )
        assertTrue(cache.lookup("GET", "https://a.example/") is HttpCache.Lookup.Fresh)
    }

    @Test fun cacheSinFrescuraNuncaFresco() {
        val cache = HttpCache(FakeClock(1000))
        cache.store("https://a.example/", FetchResponse(200, emptyMap(), body("v")))
        assertTrue(cache.lookup("GET", "https://a.example/") is HttpCache.Lookup.Stale)
    }

    @Test fun cacheLastModifiedDaIfModifiedSince() {
        val cache = HttpCache(FakeClock(1000))
        cache.store(
            "https://a.example/",
            FetchResponse(200, mapOf("last-modified" to "Mon, 02 Nov 2026 13:00:00 GMT"), body("v"))
        )
        val e = cache.entryFor("https://a.example/")
        if (e == null) throw AssertionError("falta entrada")
        assertEquals(
            mapOf("if-modified-since" to "Mon, 02 Nov 2026 13:00:00 GMT"),
            cache.revalidationHeaders(e)
        )
    }

    @Test fun cacheNuloPorDefectoNoCachea() {
        val t = FakeTransport()
        val m = FetchManager(t)
        t.hook = { _, _ -> okResp(200, mapOf("cache-control" to "max-age=60"), body("v")) }
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        valor(m.fetch(m.newRequest("GET", "https://a.example/")))
        assertEquals(2, t.requests.size)
    }
}
