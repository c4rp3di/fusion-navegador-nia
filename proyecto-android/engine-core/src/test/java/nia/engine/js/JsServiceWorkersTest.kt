package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nia.contracts.CancellationToken
import nia.contracts.WallClock
import nia.engine.dom.DomDocument
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult

/**
 * Tests de service workers y offline (T034 v2, RES-0045): registro por
 * scope con lifecycle a UN evento por pump, intercepcion de fetch
 * (string = 200 u objeto {status, body}), CacheStorage/Cache sincronos,
 * mensajes string como los workers v1, cancelacion cooperativa y la
 * integracion con fetch JS (JsFetchApi con serviceWorkers inyectado: el
 * cache sirve SIN transporte). API propia del motor: las expectativas
 * vienen del diseno de la ficha RES-0045, no de un oraculo del
 * estandar.
 */
class JsServiceWorkersTest {

    private fun withSw(limits: JsLimits = JsLimits(), block: (JsServiceWorkers) -> Unit) {
        val sw = JsServiceWorkers(limits)
        try {
            block(sw)
        } finally {
            sw.end()
        }
    }

    private class FakeClock : WallClock {
        var now = 1000L
        override fun nowMillis(): Long = now
    }

    private class FakeTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        var next: TransportResult = TransportResult.Success(
            FetchResponse(200, mapOf("content-type" to "text/plain"), "hola".toByteArray())
        )
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            return next
        }
    }

    private fun responded(h: JsServiceWorkers.Intercept): JsServiceWorkers.Intercept.Responded =
        h as JsServiceWorkers.Intercept.Responded

    @Test
    fun scriptCorreEnElRegistroQuedaInstalando() {
        withSw { sw ->
            val w = sw.register("/app", "postMessage('lista')")
            assertEquals(JsServiceWorkers.SwState.INSTALLING, w.state)
            assertEquals("lista", sw.pollMessage(w))
        }
    }

    @Test
    fun pumpInstalaYActivaSinHandlers() {
        withSw { sw ->
            val w = sw.register("/app", "")
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.INSTALLED, w.state)
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.ACTIVE, w.state)
        }
    }

    @Test
    fun oninstallYOnactivateUnEventoPorPump() {
        withSw { sw ->
            val w = sw.register(
                "/app",
                "oninstall = function () { postMessage('instalado') };" +
                    " onactivate = function () { postMessage('activado') }"
            )
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.INSTALLED, w.state)
            assertEquals("instalado", sw.pollMessage(w))
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.ACTIVE, w.state)
            assertEquals("activado", sw.pollMessage(w))
        }
    }

    @Test
    fun oninstallNoFuncionTermina() {
        withSw { sw ->
            val w = sw.register("/app", "oninstall = 42")
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w.state)
            assertEquals(null, w.lastAbort())
        }
    }

    @Test
    fun throwEnElScriptTerminaEnElRegistro() {
        withSw { sw ->
            val w = sw.register("/app", "throw 'boom'")
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w.state)
        }
    }

    @Test
    fun cuotaDePasosTerminaElSw() {
        withSw(JsLimits(maxSteps = 50)) { sw ->
            val w = sw.register("/app", "var i = 0; while (i < 100000) { i = i + 1 }")
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w.state)
            assertEquals(JsAbortReason.STEP_QUOTA, w.lastAbort())
        }
    }

    @Test
    fun mensajesSoloSeEntreganEnActive() {
        withSw { sw ->
            val w = sw.register("/app", "onmessage = function (m) { postMessage('eco ' + m) }")
            assertTrue(sw.postMessage(w, "hola"))
            sw.pump(w)
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.ACTIVE, w.state)
            assertEquals(1, w.pendingMessages())
            sw.pump(w)
            assertEquals("eco hola", sw.pollMessage(w))
            assertEquals(0, w.pendingMessages())
        }
    }

    @Test
    fun interceptPasaSinSwActivoNiHandler() {
        withSw { sw ->
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/a"))
            val w = sw.register("/app", "")
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/a"))
            sw.pump(w)
            sw.pump(w)
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/a"))
        }
    }

    @Test
    fun interceptRespondeUnString() {
        withSw { sw ->
            val w = sw.register("/app", "onfetch = function (e) { e.respondWith('desde el sw') }")
            sw.pump(w)
            sw.pump(w)
            val h = responded(sw.intercept("GET", "http://x/app/a"))
            assertEquals(200, h.status)
            assertEquals("desde el sw", h.body)
        }
    }

    @Test
    fun interceptRespondeUnObjetoDeEstado() {
        withSw { sw ->
            val w = sw.register("/app", "onfetch = function (e) { e.respondWith({status: 201, body: 'creado'}) }")
            sw.pump(w)
            sw.pump(w)
            val h = responded(sw.intercept("POST", "http://x/app/nuevo"))
            assertEquals(201, h.status)
            assertEquals("creado", h.body)
        }
    }

    @Test
    fun interceptSinRespondWithEntregaElEvento() {
        withSw { sw ->
            val w = sw.register(
                "/app",
                "onfetch = function (e) { postMessage(e.request.method + ' ' + e.request.url) }"
            )
            sw.pump(w)
            sw.pump(w)
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/datos"))
            assertEquals("GET http://x/app/datos", sw.pollMessage(w))
        }
    }

    @Test
    fun throwEnOnfetchRechazaYTermina() {
        withSw { sw ->
            val w = sw.register("/app", "onfetch = function (e) { throw 'boom' }")
            sw.pump(w)
            sw.pump(w)
            val h = sw.intercept("GET", "http://x/app/a")
            assertTrue(h is JsServiceWorkers.Intercept.Threw)
            val v = (h as JsServiceWorkers.Intercept.Threw).value
            assertEquals("boom", (v as JsStr).value)
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w.state)
        }
    }

    @Test
    fun onfetchNoFuncionRechazaTypeErrorYTermina() {
        withSw { sw ->
            val w = sw.register("/app", "onfetch = 7")
            sw.pump(w)
            sw.pump(w)
            val h = sw.intercept("GET", "http://x/app/a")
            assertTrue(h is JsServiceWorkers.Intercept.Threw)
            val v = (h as JsServiceWorkers.Intercept.Threw).value as JsObj
            val n = (v.props["name"] as? JsProp.Value)?.v
            assertEquals("TypeError", (n as JsStr).value)
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w.state)
        }
    }

    @Test
    fun cacheMatchSirveDesdeOnfetch() {
        withSw { sw ->
            val w = sw.register(
                "/app",
                "caches.open('v1').put('http://x/app/a', 'cacheado');" +
                    " caches.open('v1').put('http://x/app/b', {status: 404, body: 'no'});" +
                    " onfetch = function (e) { var r = caches.open('v1').match(e.request.url); if (r) { e.respondWith(r) } }"
            )
            sw.pump(w)
            sw.pump(w)
            assertEquals(2, sw.cacheEntries("v1"))
            val a = responded(sw.intercept("GET", "http://x/app/a"))
            assertEquals(200, a.status)
            assertEquals("cacheado", a.body)
            val b = responded(sw.intercept("GET", "http://x/app/b"))
            assertEquals(404, b.status)
            assertEquals("no", b.body)
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/c"))
        }
    }

    @Test
    fun cacheDeleteHasKeysYCacheStorage() {
        withSw { sw ->
            val w = sw.register(
                "/app",
                "oninstall = function () {" +
                    " var c = caches.open('c1');" +
                    " c.put('u1', 'a'); c.put('u2', 'b'); c.delete('u1');" +
                    " postMessage(c.keys().join(','));" +
                    " postMessage(String(c.has('u2')));" +
                    " postMessage(String(caches.has('c1')));" +
                    " postMessage(String(caches.has('c9')));" +
                    "}"
            )
            sw.pump(w)
            assertEquals("u2", sw.pollMessage(w))
            assertEquals("true", sw.pollMessage(w))
            assertEquals("true", sw.pollMessage(w))
            assertEquals("false", sw.pollMessage(w))
            assertEquals(1, sw.cacheEntries("c1"))
            assertEquals(listOf("c1"), sw.cacheNames())
        }
    }

    @Test
    fun scopesMultiplesYPrefijoMasLargo() {
        withSw { sw ->
            val a = sw.register("/app", "onfetch = function (e) { e.respondWith('A') }")
            val b = sw.register("/app/sub", "onfetch = function (e) { e.respondWith('B') }")
            val c = sw.register("/api", "onfetch = function (e) { e.respondWith('C') }")
            sw.pump(a)
            sw.pump(a)
            sw.pump(b)
            sw.pump(b)
            sw.pump(c)
            sw.pump(c)
            assertEquals("B", responded(sw.intercept("GET", "http://x/app/sub/z")).body)
            assertEquals("A", responded(sw.intercept("GET", "http://x/app/y")).body)
            assertEquals("C", responded(sw.intercept("GET", "http://x/api/w")).body)
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/otro"))
        }
    }

    @Test
    fun reRegisterSustituyeAlAnterior() {
        withSw { sw ->
            val w1 = sw.register("/app", "onfetch = function (e) { e.respondWith('uno') }")
            sw.pump(w1)
            sw.pump(w1)
            val w2 = sw.register("/app", "onfetch = function (e) { e.respondWith('dos') }")
            assertEquals(JsServiceWorkers.SwState.TERMINATED, w1.state)
            sw.pump(w2)
            sw.pump(w2)
            assertEquals("dos", responded(sw.intercept("GET", "http://x/app/a")).body)
            assertEquals(1, sw.registrations())
        }
    }

    @Test
    fun unregisterDetieneLaIntercepcion() {
        withSw { sw ->
            val w = sw.register("/app", "onfetch = function (e) { e.respondWith('x') }")
            sw.pump(w)
            sw.pump(w)
            assertTrue(sw.unregister("/app"))
            assertEquals(JsServiceWorkers.Intercept.None, sw.intercept("GET", "http://x/app/a"))
            assertEquals(0, sw.registrations())
            assertFalse(sw.unregister("/app"))
        }
    }

    @Test
    fun endLimpiaYReabre() {
        withSw { sw ->
            sw.register("/app", "caches.open('v1').put('u', 'a')")
            sw.end()
            assertEquals(0, sw.registrations())
            assertEquals(0, sw.cacheNames().size)
            val w = sw.register("/app", "")
            sw.pump(w)
            sw.pump(w)
            assertEquals(JsServiceWorkers.SwState.ACTIVE, w.state)
        }
    }

    @Test
    fun fetchJsSirveDesdeCacheSinTransporte() {
        val sw = JsServiceWorkers()
        try {
            val w = sw.register(
                "http://x/app",
                "caches.open('v1').put('http://x/app/datos', 'cacheado');" +
                    " onfetch = function (e) { var r = caches.open('v1').match(e.request.url); if (r) { e.respondWith(r) } }"
            )
            sw.pump(w)
            sw.pump(w)
            val t = FakeTransport()
            val doc = DomDocument.parse("<body></body>")
            val r = JsInterpreter.evaluateWithDom(
                "fetch('http://x/app/datos').then(function (x) { return x.text(); })" +
                    " .then(function (tx) { return 't=' + tx; })",
                doc,
                JsLimits(),
                JsFetchApi(t, FakeClock(), sw)
            )
            assertEquals("t=cacheado", (r.value as JsStr).value)
            assertTrue(t.requests.isEmpty())
        } finally {
            sw.end()
        }
    }

    @Test
    fun fetchJsFueraDelScopeUsaTransporte() {
        val sw = JsServiceWorkers()
        try {
            val w = sw.register(
                "http://x/app",
                "onfetch = function (e) { e.respondWith('sw') }"
            )
            sw.pump(w)
            sw.pump(w)
            val t = FakeTransport()
            val doc = DomDocument.parse("<body></body>")
            val r = JsInterpreter.evaluateWithDom(
                "fetch('http://x/otro').then(function (x) { return x.text(); })" +
                    " .then(function (tx) { return 't=' + tx; })",
                doc,
                JsLimits(),
                JsFetchApi(t, FakeClock(), sw)
            )
            assertEquals("t=hola", (r.value as JsStr).value)
            assertEquals(1, t.requests.size)
        } finally {
            sw.end()
        }
    }
}
