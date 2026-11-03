package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nia.contracts.CancellationToken
import nia.contracts.WallClock
import nia.engine.dom.DomDocument
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult

/**
 * Tests de forms y fetch JS (T027 v1, RES-0033): fetch(url, opts) sobre
 * FetchManager con transporte falso, AbortController cooperativo y los
 * bindings de formularios (input.value, textarea.value, form.elements,
 * form.submit). La API es PROPIA del motor: las expectativas vienen del
 * diseno registrado en la ficha RES-0033 (rechazos tipificados, respuesta
 * descartada al cancelar, deadline ya vencido), no de un oraculo del
 * estandar; el orden de then/await ya esta cubierto por los tests del
 * interprete (T023/T018 v5). La demo interactiva con cancelacion de la
 * aceptacion de T027 se cubre como tests de flujo completo (submit ->
 * fetch -> render y submit -> abort -> rechazo).
 */
class JsFetchApiTest {

    private fun str(r: JsEvalResult): String = (r.value as JsStr).value

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

    /** Transporte que vence el deadline mientras atiende: cancelacion observada tras el transporte. */
    private class CancellingTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            token.checkDeadline(0L)
            return TransportResult.Success(FetchResponse(200, emptyMap(), "x".toByteArray()))
        }
    }

    private fun api(t: FetchTransport): JsFetchApi = JsFetchApi(t, FakeClock())

    private fun eval(source: String, doc: DomDocument, a: JsFetchApi): JsEvalResult =
        JsInterpreter.evaluateWithDom(source, doc, JsLimits(), a)

    @Test
    fun fetchResuelveConTexto() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').then(function (r) { return r.text(); })" +
                " .then(function (t) { return 't=' + t; })",
            doc, api(t)
        )
        assertEquals("t=hola", str(r))
        assertEquals(1, t.requests.size)
    }

    @Test
    fun fetchOkStatusYStatusText() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').then(function (r) { return r.ok + ',' + r.status + ',' + r.statusText; })",
            doc, api(t)
        )
        assertEquals("true,200,OK", str(r))
    }

    @Test
    fun fetch404NoOk() {
        val t = FakeTransport()
        t.next = TransportResult.Success(
            FetchResponse(404, emptyMap(), "nope".toByteArray())
        )
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').then(function (r) { return r.ok + ',' + r.status + ',' + r.statusText; })",
            doc, api(t)
        )
        assertEquals("false,404,Not Found", str(r))
    }

    @Test
    fun fetchCabecerasGetInsensibleAMayusculas() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').then(function (r) {" +
                " return r.headers.get('Content-Type') + ',' + r.headers.get('content-type')" +
                " + ',' + String(r.headers.get('nope')); })",
            doc, api(t)
        )
        assertEquals("text/plain,text/plain,null", str(r))
    }

    @Test
    fun fetchPostMetodoBodyYHeaders() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/enviar', {method: 'POST', body: 'a=1', headers: {'X-Nia': '1'}})" +
                " .then(function (r) { return 'ok ' + r.status; })",
            doc, api(t)
        )
        assertEquals("ok 200", str(r))
        assertEquals(1, t.requests.size)
        assertEquals("POST", t.requests[0].method)
        assertEquals("a=1", String(t.requests[0].body ?: ByteArray(0)))
        assertEquals("1", t.requests[0].headers["X-Nia"])
    }

    @Test
    fun fetchMetodoInvalidoRechazaTypeError() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/', {method: 'TRACE'}).catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("TypeError", str(r))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun fetchEsquemaInvalidoRechazaTypeError() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('ftp://x/').catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("TypeError", str(r))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun fetchAbortAntesDelTransporteRechaza() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "var c = new AbortController(); c.abort();" +
                " fetch('http://x/', {signal: c.signal}).catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("AbortError", str(r))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun fetchTimeoutVencidoRechazaSinTransporte() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/', {timeoutMs: 0}).catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("AbortError", str(r))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun fetchCancelObservadoTrasTransporteDescarta() {
        val t = CancellingTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').catch(function (e) { return e.name + '|' + e.message; })",
            doc, api(t)
        )
        assertEquals("AbortError|operacion cancelada: Deadline(deadlineMillis=0)", str(r))
        assertEquals(1, t.requests.size)
    }

    @Test
    fun fetchDnsRechazaNetworkError() {
        val t = FakeTransport()
        t.next = TransportResult.Failure(nia.engine.net.TransportFailure.DNS)
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("NetworkError", str(r))
    }

    @Test
    fun fetchTlsRechazaTlsError() {
        val t = FakeTransport()
        t.next = TransportResult.Failure(nia.engine.net.TransportFailure.TLS_INVALID)
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "fetch('http://x/').catch(function (e) { return e.name; })",
            doc, api(t)
        )
        assertEquals("TlsError", str(r))
    }

    @Test
    fun fetchConAwaitEnFuncionAsync() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<body></body>")
        val r = eval(
            "var o = []; async function carga() {" +
                " o.push('start'); var r = await fetch('http://x/');" +
                " o.push(r.status); return 'fin'; }" +
                " carga().then(function (x) { o.push(x); return o.join(','); }); 'sync'",
            doc, api(t)
        )
        assertEquals("start,200,fin", str(r))
    }

    @Test
    fun demoSubmitFetchRender() {
        val t = FakeTransport()
        val doc = DomDocument.parse(
            "<form id="f"><input id="q" name="q" value="hola"></form>"
        )
        val r = eval(
            "var o = []; var f = document.getElementById('f');" +
                " f.addEventListener('submit', function () {" +
                " fetch('http://x/datos?q=' + document.getElementById('q').value)" +
                " .then(function (r) { return r.text(); })" +
                " .then(function (txt) { o.push('render ' + txt); return o.join(','); }); });" +
                " o.push('sync'); f.submit(); 'start'",
            doc, api(t)
        )
        assertEquals("sync,render hola", str(r))
        assertEquals(1, t.requests.size)
        assertEquals("http://x/datos?q=hola", t.requests[0].url)
    }

    @Test
    fun demoSubmitFetchCancelado() {
        val t = FakeTransport()
        val doc = DomDocument.parse(
            "<form id="f"><input id="q" name="q" value="hola"></form>"
        )
        val r = eval(
            "var o = []; var f = document.getElementById('f');" +
                " var c = new AbortController();" +
                " f.addEventListener('submit', function () {" +
                " fetch('http://x/datos', {signal: c.signal})" +
                " .then(function (r) { return r.text(); })" +
                " .then(function (txt) { o.push('render ' + txt); return o.join(','); })" +
                " .catch(function (e) { o.push('err ' + e.name); return o.join(','); }); });" +
                " o.push('sync'); c.abort(); f.submit(); 'start'",
            doc, api(t)
        )
        assertEquals("sync,err AbortError", str(r))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun inputValueLeeYEscribe() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<input id="n" value="7">")
        val r = eval(
            "var i = document.getElementById('n'); var out = i.value;" +
                " i.value = '5'; out + ',' + i.value + ',' + i.getAttribute('value')",
            doc, api(t)
        )
        assertEquals("7,5,5", str(r))
    }

    @Test
    fun textareaValueLeeYEscribe() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<textarea id="t">abc</textarea>")
        val r = eval(
            "var t = document.getElementById('t'); var out = t.value;" +
                " t.value = 'xyz'; out + ',' + t.value + ',' + t.textContent",
            doc, api(t)
        )
        assertEquals("abc,xyz,xyz", str(r))
    }

    @Test
    fun formElementsConName() {
        val t = FakeTransport()
        val doc = DomDocument.parse(
            "<form id="f"><input name="a"><textarea name="b"></textarea><p>sin</p></form>"
        )
        const r = eval(
            "var f = document.getElementById('f');" +
                " f.elements.length + ':' + f.elements[0].getAttribute('name')" +
                " + ',' + f.elements[1].getAttribute('name')",
            doc, api(t)
        )
        assertEquals("2:a,b", str(r))
    }

    @Test
    fun formSubmitDisparaYBurbujea() {
        val t = FakeTransport()
        const doc = DomDocument.parse("<form id="f"><input name="q"></form>")
        const r = eval(
            "var o = []; var f = document.getElementById('f');" +
                " document.body.addEventListener('submit', function (ev) {" +
                " o.push('b:' + ev.target.getAttribute('id')); });" +
                " f.addEventListener('submit', function (ev) {" +
                " o.push('f:' + ev.target.getAttribute('id')); });" +
                " f.submit(); o.join(',')",
            doc, api(t)
        )
        assertEquals("f:f,b:f", str(r))
    }

    @Test
    fun formSubmitConPreventDefaultDevuelveFalse() {
        val t = FakeTransport()
        val doc = DomDocument.parse("<form id="f"></form>")
        val r = eval(
            "var f = document.getElementById('f');" +
                " f.addEventListener('submit', function (ev) { ev.preventDefault(); });" +
                " String(f.submit())",
            doc, api(t)
        )
        assertEquals("false", str(r))
    }
}
