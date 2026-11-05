package nia.engine.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.WallClock
import nia.engine.dom.DomDocument
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsStorageApi
import nia.engine.js.JsStr
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult
import nia.engine.profile.BrowserProfile
import nia.engine.url.Origin

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella f0bd98c8.2740b7a8.76): los 14 tests cubren el
 * orden de estados, la redireccion 302 relativa con cookies por
 * salto, la cadena 301 + 303 cross-host, la degradacion https
 * prohibida, el fallo DNS antes del commit, la cancelacion por
 * deadline, el stale por generacion, el commit de identidad, los
 * subrecursos con mixed-content, las cookies de subrecurso por
 * contexto de sitio, la navegacion completa con scripts, el fallo
 * tras el commit, el back/forward con truncamiento y la mutacion
 * del documento vivo que invalida el layout. T071 v1 (R0149,
 * RES-0074).
 */
class NavControllerTest {

    private class FakeClock : WallClock {
        var now = 5000L
        override fun nowMillis(): Long = now
    }

    private class FakeTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        val routes = HashMap<String, FetchResponse>()
        val failures = HashMap<String, TransportFailure>()
        var hook: ((FetchRequest) -> Unit)? = null
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            val h = hook
            if (h != null) h(request)
            val failure = failures[request.url]
            if (failure != null) return TransportResult.Failure(failure)
            val found = routes[request.url]
            if (found != null) return TransportResult.Success(found)
            return TransportResult.Failure(TransportFailure.NETWORK)
        }
    }

    private fun nav(t: FakeTransport, c: FakeClock): NavController =
        NavController(t, c, BrowserProfile.incognito(c))

    private fun loaded(out: NavOutcome): NavPage = (out as NavOutcome.Loaded).page

    private fun failed(out: NavOutcome): NavOutcome.Failed = out as NavOutcome.Failed

    private fun resp(status: Int, headers: Map<String, String>, body: String): FetchResponse =
        FetchResponse(status, headers, body.toByteArray())

    @Test
    fun ordenDeEstadosEnNavegacionExitosa() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://a.dev/"] = resp(200, emptyMap(), "<html><body><p>hola</p></body></html>")
        val controller = nav(t, c)
        val out = controller.navigate("http://a.dev/")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals(
            listOf(
                NavEvent(NavState.NAVIGATING, "http://a.dev/"),
                NavEvent(NavState.RESPONSE_RECEIVED, "http://a.dev/"),
                NavEvent(NavState.PARSING, "http://a.dev/"),
                NavEvent(NavState.COMMITTED, "http://a.dev/"),
                NavEvent(NavState.LOADED, "http://a.dev/")
            ),
            p.events
        )
        assertEquals("http://a.dev/", p.url)
        assertEquals(200, p.status)
        assertEquals(NavState.LOADED, controller.state())
        assertFalse(controller.canGoBack())
    }

    @Test
    fun redireccion302RelativaConCookiesPorSalto() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://a.dev/"] = resp(302, mapOf("location" to "/b", "set-cookie" to "sid=uno; Path=/"), "")
        t.routes["http://a.dev/b"] = resp(200, emptyMap(), "<html><body>b</body></html>")
        val controller = nav(t, c)
        val out = controller.navigate("http://a.dev/")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals("http://a.dev/b", p.url)
        assertEquals("sid=uno", t.requests[1].headers["Cookie"])
        assertEquals(2, t.requests.size)
        assertEquals(6, p.events.size)
        assertEquals(NavState.LOADED, p.events[p.events.size - 1].state)
    }

    @Test
    fun cadena301y303CrossHostHttps() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://x.dev/"] = resp(301, mapOf("location" to "https://y.dev/"), "")
        t.routes["https://y.dev/"] = resp(303, mapOf("location" to "/fin"), "")
        t.routes["https://y.dev/fin"] = resp(200, emptyMap(), "<html><body>fin</body></html>")
        val controller = nav(t, c)
        val out = controller.navigate("http://x.dev/")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals("https://y.dev/fin", p.url)
        assertEquals(Origin.Tuple("https", "y.dev", 443), p.origin)
        assertEquals("y.dev", p.site.registrableDomain)
        assertEquals(listOf("GET", "GET", "GET"), t.requests.map { it.method })
        assertEquals(7, p.events.size)
    }

    @Test
    fun degradacionHttpsAHttpNoSeSigue() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["https://a.dev/"] = resp(302, mapOf("location" to "http://a.dev/plano"), "")
        val controller = nav(t, c)
        val out = controller.navigate("https://a.dev/")
        assertTrue(out is NavOutcome.Failed)
        val f = failed(out)
        assertEquals(NavState.NAVIGATING, f.phase)
        assertEquals(ErrorCode.UNSUPPORTED, f.code)
        assertEquals("redireccion no seguida: degradacion-https-prohibida", f.message)
        assertNull(f.page)
        assertEquals(0, controller.historyUrls().size)
    }

    @Test
    fun falloDeDnsAntesDelCommitDejaLaHistoriaIntacta() {
        val t = FakeTransport()
        val c = FakeClock()
        t.failures["http://caido.dev/"] = TransportFailure.DNS
        val controller = nav(t, c)
        val out = controller.navigate("http://caido.dev/")
        assertTrue(out is NavOutcome.Failed)
        val f = failed(out)
        assertEquals(NavState.NAVIGATING, f.phase)
        assertEquals(ErrorCode.NETWORK, f.code)
        assertEquals("fallo de red: DNS", f.message)
        assertNull(controller.currentPage())
        assertEquals(0, controller.historyUrls().size)
        assertFalse(controller.canGoBack())
    }

    @Test
    fun cancelacionPorDeadlineSinLlamarAlTransporte() {
        val t = FakeTransport()
        val c = FakeClock()
        val controller = nav(t, c)
        val out = controller.navigate("http://a.dev/", 1000)
        assertTrue(out is NavOutcome.Failed)
        val f = failed(out)
        assertEquals(ErrorCode.CANCELLED, f.code)
        assertTrue(f.message.startsWith("operacion cancelada"))
        assertEquals(0, t.requests.size)
        assertEquals(0, controller.historyUrls().size)
    }

    @Test
    fun respuestaStaleAlInvalidarLaGeneracionDuranteElTransporte() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://a.dev/"] = resp(200, emptyMap(), "<html><body>x</body></html>")
        val controller = nav(t, c)
        t.hook = { controller.invalidateGeneration() }
        val out = controller.navigate("http://a.dev/")
        assertTrue(out is NavOutcome.Failed)
        val f = failed(out)
        assertEquals(ErrorCode.STALE_HANDLE, f.code)
        assertEquals("respuesta descartada: generacion anterior", f.message)
        assertEquals(0, controller.historyUrls().size)
    }

    @Test
    fun commitDeIdentidadConOrigenYSite() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["https://app.nia.dev/pagina"] = resp(200, emptyMap(), "<html><body><p id="t">x</p></body></html>")
        val controller = nav(t, c)
        val out = controller.navigate("https://app.nia.dev/pagina")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals(Origin.Tuple("https", "app.nia.dev", 443), p.origin)
        assertEquals("nia.dev", p.site.registrableDomain)
        assertEquals(200, p.status)
        assertEquals(NavState.LOADED, controller.state())
        assertEquals(listOf("https://app.nia.dev/pagina"), controller.historyUrls())
    }

    @Test
    fun subrecursosPorTipoConMixedContentBloqueado() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["https://a.dev/p"] = resp(
            200, emptyMap(),
            "<html><body><img src="/ok.png"><img src="http://m.dev/x.png">" +
                "<link rel="stylesheet" href="/estilos.css"></body></html>"
        )
        t.routes["https://a.dev/ok.png"] = resp(200, emptyMap(), "img")
        t.routes["https://a.dev/estilos.css"] = resp(200, emptyMap(), "body{}")
        val controller = nav(t, c)
        val out = controller.navigate("https://a.dev/p")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals(
            listOf(
                NavSubresource("image", "https://a.dev/ok.png", "ok"),
                NavSubresource("image", "http://m.dev/x.png", "blocked-mixed-content"),
                NavSubresource("stylesheet", "https://a.dev/estilos.css", "ok")
            ),
            p.subresources
        )
        assertEquals(3, t.requests.size)
    }

    @Test
    fun cookiesDeSubrecursoPorContextoSameSite() {
        val t = FakeTransport()
        val c = FakeClock()
        val profile = BrowserProfile.incognito(c)
        assertTrue(profile.cookies.storeFromResponse("a.dev", true, "sida=uno; Path=/; SameSite=Lax"))
        assertTrue(profile.cookies.storeFromResponse("b.dev", true, "sidb=dos; Path=/; SameSite=Lax"))
        t.routes["https://a.dev/p"] = resp(
            200, emptyMap(),
            "<html><body><img src="https://b.dev/x.png"><img src="/q.png"></body></html>"
        )
        t.routes["https://b.dev/x.png"] = resp(200, emptyMap(), "x")
        t.routes["https://a.dev/q.png"] = resp(200, emptyMap(), "q")
        val controller = NavController(t, c, profile)
        val out = controller.navigate("https://a.dev/p")
        assertTrue(out is NavOutcome.Loaded)
        assertNull(t.requests.first { it.url == "https://b.dev/x.png" }.headers["Cookie"])
        assertEquals("sida=uno", t.requests.first { it.url == "https://a.dev/q.png" }.headers["Cookie"])
        assertEquals(3, t.requests.size)
    }

    @Test
    fun navegacionCompletaConScriptsEnFixture() {
        val t = FakeTransport()
        val c = FakeClock()
        val profile = BrowserProfile.incognito(c)
        t.routes["https://a.dev/app"] = resp(
            200, emptyMap(),
            "<html><body><p id="t">x</p>" +
                "<script>localStorage.setItem('nav', 'listo')</script>" +
                "<script src="/segundo.js"></script></body></html>"
        )
        t.routes["https://a.dev/segundo.js"] = resp(200, emptyMap(), "localStorage.setItem('js', 'ok')")
        val controller = NavController(t, c, profile)
        val out = controller.navigate("https://a.dev/app")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertEquals(2, p.scripts.size)
        assertEquals("inline:1", p.scripts[0].ref)
        assertEquals("src:https://a.dev/segundo.js", p.scripts[1].ref)
        assertEquals(5, p.events.size)
        assertEquals(NavState.LOADED, p.events[p.events.size - 1].state)
        assertEquals(2, t.requests.size)
        val api = JsStorageApi(profile.storage, profile.idb, p.site, p.origin)
        val r = JsInterpreter.evaluateWithDom(
            "localStorage.getItem('nav') + ',' + localStorage.getItem('js')",
            DomDocument.parse("<body></body>"), JsLimits(), null, api
        )
        assertEquals("listo,ok", (r.value as JsStr).value)
        assertEquals(1, profile.storage.partitionCount())
    }

    @Test
    fun falloDespuesDelCommitEnSubrecursoStale() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["https://a.dev/p"] = resp(200, emptyMap(), "<html><body><img src="/boom.png"></body></html>")
        t.routes["https://a.dev/boom.png"] = resp(200, emptyMap(), "b")
        val controller = nav(t, c)
        t.hook = { r -> if (r.url == "https://a.dev/boom.png") controller.invalidateGeneration() }
        val out = controller.navigate("https://a.dev/p")
        assertTrue(out is NavOutcome.Failed)
        val f = failed(out)
        assertEquals(NavState.LOADED, f.phase)
        assertEquals(ErrorCode.STALE_HANDLE, f.code)
        assertEquals("respuesta descartada: generacion anterior", f.message)
        assertTrue(f.page != null)
        assertEquals(listOf("https://a.dev/p"), controller.historyUrls())
        assertTrue(f.page != null && f.page.events.any { it.state == NavState.COMMITTED })
    }

    @Test
    fun backForwardYTruncamientoAlNavegar() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://a.dev/"] = resp(200, emptyMap(), "<html><body>a</body></html>")
        t.routes["http://a.dev/b"] = resp(200, emptyMap(), "<html><body>b</body></html>")
        t.routes["http://a.dev/c"] = resp(200, emptyMap(), "<html><body>c</body></html>")
        val controller = nav(t, c)
        assertTrue(controller.navigate("http://a.dev/") is NavOutcome.Loaded)
        assertTrue(controller.navigate("http://a.dev/b") is NavOutcome.Loaded)
        assertTrue(controller.canGoBack())
        assertFalse(controller.canGoForward())
        val back = controller.back() ?: throw AssertionError("back sin pagina")
        assertTrue(back is NavOutcome.Loaded)
        assertEquals("http://a.dev/", loaded(back).url)
        assertFalse(controller.canGoBack())
        assertTrue(controller.canGoForward())
        val fwd = controller.forward() ?: throw AssertionError("forward sin pagina")
        assertTrue(fwd is NavOutcome.Loaded)
        assertEquals("http://a.dev/b", loaded(fwd).url)
        assertTrue(controller.navigate("http://a.dev/c") is NavOutcome.Loaded)
        assertEquals(
            listOf("http://a.dev/", "http://a.dev/b", "http://a.dev/c"),
            controller.historyUrls()
        )
        assertFalse(controller.canGoForward())
    }

    @Test
    fun mutacionDelDocumentoVivoInvalidaElLayout() {
        val t = FakeTransport()
        val c = FakeClock()
        t.routes["http://a.dev/"] = resp(200, emptyMap(), "<html><body><p id="t">x</p></body></html>")
        val controller = nav(t, c)
        val out = controller.navigate("http://a.dev/")
        assertTrue(out is NavOutcome.Loaded)
        val p = loaded(out)
        assertFalse(controller.needsLayout())
        val parrafo = p.document.findFirst("p") ?: throw AssertionError("falta el parrafo")
        p.document.setAttribute(parrafo, "data-x", "1")
        assertEquals("1", p.document.attributeOf(parrafo, "data-x"))
        assertTrue(controller.needsLayout())
    }
}
