package nia.engine.js

import nia.engine.css.CssCascade
import nia.engine.dom.DomDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests de los bindings DOM + consola (T022 v1): evaluateWithDom instala
 * document y console sobre un DomDocument real. Expectativas generadas
 * programaticamente desde la semantica del estandar DOM (getElementById,
 * mutaciones tipificadas, eventos con __listeners) y la consola formateada
 * (primitivos con ToString, objetos/arrays con el dump canonico); los
 * asserts finales verifican que las mutaciones de JS son visibles en el
 * documento Kotlin (revision incluida). T024 v1 anade captura/burbujeo
 * por el arbol (fases captura/objetivo/burbuja con stopPropagation) y
 * querySelector/querySelectorAll con los selectores de CssCascade.
 */
class JsDomBindingsTest {

    @Test
    fun consolaLogString() {
        assertEquals("\"hola\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log(\"hola\");")))
    }

    @Test
    fun consolaLogDos() {
        assertEquals("\"a 1\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log(\"a\", 1);")))
    }

    @Test
    fun consolaLogNumeroBool() {
        assertEquals("\"42 true\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log(42, true);")))
    }

    @Test
    fun consolaLogArray() {
        assertEquals("\"(arr 1 \\\"x\\\")\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log([1, \"x\"]);")))
    }

    @Test
    fun consolaLogObjeto() {
        assertEquals("\"(obj a=1)\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log({a: 1});")))
    }

    @Test
    fun consolaLogVacio() {
        assertEquals("\"\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log();")))
    }

    @Test
    fun consolaLogNullUndefined() {
        assertEquals("\"null undefined\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.log(null, undefined);")))
    }

    @Test
    fun consolaErrorIgual() {
        assertEquals("\"e\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("console.error(\"e\");")))
    }

    @Test
    fun domGetElementById() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"saludo\") !== null;", doc)))
    }

    @Test
    fun domGetElementByIdNull() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"nope\") === null;", doc)))
    }

    @Test
    fun domGetAttr() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"saludo\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"saludo\").getAttribute(\"id\");", doc)))
    }

    @Test
    fun domGetAttrInexistente() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("null", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"saludo\").getAttribute(\"zzz\");", doc)))
    }

    @Test
    fun domBodyExiste() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.body !== null;", doc)))
    }

    @Test
    fun domCrearYAppend() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var d = document.createElement(\"div\"); document.body.appendChild(d); document.body.children().length;", doc)))
    }

    @Test
    fun domAppendDevuelveChild() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var d = document.createElement(\"div\"); document.body.appendChild(d) === d;", doc)))
    }

    @Test
    fun domCrearTexto() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"abc\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var t = document.createTextNode(\"abc\"); t.text();", doc)))
    }

    @Test
    fun domSetText() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"b\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var t = document.createTextNode(\"a\"); t.setText(\"b\"); t.text();", doc)))
    }

    @Test
    fun domRemoveChild() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var ps = document.body.children(); document.body.removeChild(ps[0]); document.body.children().length;", doc)))
    }

    @Test
    fun domRemoveDevuelveChild() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var ps = document.body.children(); document.body.removeChild(ps[0]) === ps[0];", doc)))
    }

    @Test
    fun domRemoveDosVeces() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"node-not-found\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var ps = document.body.children(); var p = ps[0]; document.body.removeChild(p); try { document.body.removeChild(p); } catch (e) { e.message; }", doc)))
    }

    @Test
    fun domSetAttrEnTexto() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"wrong-node-type\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var t = document.createTextNode(\"a\"); try { t.setAttribute(\"k\", \"v\"); } catch (e) { e.message; }", doc)))
    }

    @Test
    fun domSetGetAttr() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"v\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var d = document.createElement(\"div\"); d.setAttribute(\"k\", \"v\"); d.getAttribute(\"k\");", doc)))
    }

    @Test
    fun domSetAttrInvalido() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"invalid-attribute-name\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var d = document.createElement(\"div\"); try { d.setAttribute(\"a b\", \"v\"); } catch (e) { e.message; }", doc)))
    }

    @Test
    fun domIdentidadWrapper() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var a = document.getElementById(\"saludo\"); var b = document.getElementById(\"saludo\"); a === b;", doc)))
    }

    @Test
    fun domChildrenEsArray() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("Array.isArray(document.body.children());", doc)))
    }

    @Test
    fun domEventoListener() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var n = 0; var p = document.getElementById(\"saludo\"); p.addEventListener(\"click\", function() { n = n + 1; }); p.dispatchEvent(new Event(\"click\")); n;", doc)))
    }

    @Test
    fun domEventoTarget() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var p = document.getElementById(\"saludo\"); var r; p.addEventListener(\"click\", function(ev) { r = (ev.target === p); }); p.dispatchEvent(new Event(\"click\")); r;", doc)))
    }

    @Test
    fun domEventoThis() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var p = document.getElementById(\"saludo\"); var r; p.addEventListener(\"click\", function() { r = (this === p); }); p.dispatchEvent(new Event(\"click\")); r;", doc)))
    }

    @Test
    fun domEventoRemove() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var n = 0; function h() { n = n + 1; } var p = document.getElementById(\"saludo\"); p.addEventListener(\"click\", h); p.removeEventListener(\"click\", h); p.dispatchEvent(new Event(\"click\")); n;", doc)))
    }

    @Test
    fun domEventoOrden() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"12\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var s = \"\"; var p = document.getElementById(\"saludo\"); p.addEventListener(\"e\", function() { s = s + \"1\"; }); p.addEventListener(\"e\", function() { s = s + \"2\"; }); p.dispatchEvent(new Event(\"e\")); s;", doc)))
    }

    @Test
    fun domEventoCancel() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var p = document.getElementById(\"saludo\"); p.addEventListener(\"e\", function(ev) { ev.preventDefault(); }); p.dispatchEvent(new Event(\"e\", {cancelable: true}));", doc)))
    }

    @Test
    fun domEventoNoDuplicado() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var n = 0; function h() { n = n + 1; } var p = document.getElementById(\"saludo\"); p.addEventListener(\"e\", h); p.addEventListener(\"e\", h); p.dispatchEvent(new Event(\"e\")); n;", doc)))
    }

    @Test
    fun domEventoDispatchTrue() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var p = document.getElementById(\"saludo\"); p.dispatchEvent(new Event(\"x\"));", doc)))
    }

    @Test
    fun domTextoDeHijo() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"hola\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"saludo\").children()[0].text();", doc)))
    }

    @Test
    fun domQuerySelectorId() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"saludo\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelector(\"#saludo\").getAttribute(\"id\");", doc)))
    }

    @Test
    fun domQuerySelectorTipo() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"saludo\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelector(\"p\").getAttribute(\"id\");", doc)))
    }

    @Test
    fun domQuerySelectorNull() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelector(\".zzz\") === null;", doc)))
    }

    @Test
    fun domQuerySelectorHijo() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"saludo\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelector(\"body > p\").getAttribute(\"id\");", doc)))
    }

    @Test
    fun domQuerySelectorAtributo() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"saludo\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelector(\"[id]\").getAttribute(\"id\");", doc)))
    }

    @Test
    fun domQuerySelectorInvalido() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("\"invalid-selector\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("try { document.querySelector(\"p >\"); } catch (e) { e.message; }", doc)))
    }

    @Test
    fun domQuerySelectorAllLongitud() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelectorAll(\"p\").length;", doc)))
    }

    @Test
    fun domQuerySelectorAllComas() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelectorAll(\"p, div\").length;", doc)))
    }

    @Test
    fun domQuerySelectorAllNth() {
        val doc = DomDocument.parse("<html><body><p>uno</p><p>dos</p><p>tres</p></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.querySelectorAll(\"p:nth-child(2)\").length;", doc)))
    }

    @Test
    fun domQuerySelectorEnNodo() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"a\").querySelectorAll(\"p\").length;", doc)))
    }

    @Test
    fun domQuerySelectorCombinadoresDesdeNodo() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("document.getElementById(\"a\").querySelector(\"body p\") !== null;", doc)))
    }

    @Test
    fun domBurbujeoOrden() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"pd\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function() { o = o + \"d\"; }); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\", {bubbles: true})); o;", doc)))
    }

    @Test
    fun domSinBurbujeo() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"p\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function() { o = o + \"d\"; }); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\")); o;", doc)))
    }

    @Test
    fun domCapturaOrden() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"dp\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function() { o = o + \"d\"; }, true); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\")); o;", doc)))
    }

    @Test
    fun domCapturaConBurbujeo() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"cpb\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function() { o = o + \"c\"; }, true); d.addEventListener(\"e\", function() { o = o + \"b\"; }); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\", {bubbles: true})); o;", doc)))
    }

    @Test
    fun domStopPropagation() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"p\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); p.addEventListener(\"e\", function(ev) { ev.stopPropagation(); o = o + \"p\"; }); d.addEventListener(\"e\", function() { o = o + \"d\"; }); p.dispatchEvent(new Event(\"e\", {bubbles: true})); o;", doc)))
    }

    @Test
    fun domStopEnCaptura() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"B\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); document.body.addEventListener(\"e\", function(ev) { ev.stopPropagation(); o = o + \"B\"; }, true); d.addEventListener(\"e\", function() { o = o + \"d\"; }); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\", {bubbles: true})); o;", doc)))
    }

    @Test
    fun domCurrentTargetPorFase() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function(ev) { o = o + ev.currentTarget.getAttribute(\"id\"); }, true); p.addEventListener(\"e\", function(ev) { o = o + ev.currentTarget.getAttribute(\"id\"); }); p.dispatchEvent(new Event(\"e\")); o;", doc)))
    }

    @Test
    fun domRemoveEventListenerCaptura() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"p\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); function h() { o = o + \"d\"; } d.addEventListener(\"e\", h, true); d.removeEventListener(\"e\", h); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\")); o;", doc)))
    }

    @Test
    fun domCapturaNoDuplicada() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var n = 0; function h() { n = n + 1; } var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", h, true); d.addEventListener(\"e\", h, true); p.dispatchEvent(new Event(\"e\")); n;", doc)))
    }

    @Test
    fun domBurbujaSoloConFlag() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        assertEquals("\"p\"", JsInterpreter.dumpResult(JsInterpreter.evaluateWithDom("var o = \"\"; var p = document.getElementById(\"b\"); var d = document.getElementById(\"a\"); d.addEventListener(\"e\", function() { o = o + \"x\"; }); p.addEventListener(\"e\", function() { o = o + \"p\"; }); p.dispatchEvent(new Event(\"e\")); o;", doc)))
    }

    @Test
    fun domMutacionAtributo() {
        val doc = DomDocument.parse("<html><body><p id=\"q\">viejo</p></body></html>")
        JsInterpreter.evaluateWithDom("var p = document.getElementById(\"q\"); p.setAttribute(\"class\", \"nuevo\");", doc)
        val h = assertNotNull(doc.findFirst("p"))
        assertEquals("nuevo", doc.attributeOf(h, "class"))
    }

    @Test
    fun domMutacionTexto() {
        val doc = DomDocument.parse("<html><body><p id=\"q\">viejo</p></body></html>")
        JsInterpreter.evaluateWithDom("var p = document.getElementById(\"q\"); p.children()[0].setText(\"nuevo\");", doc)
        val p = assertNotNull(doc.findFirst("p"))
        val kids = doc.childrenOf(p)
        assertEquals("nuevo", doc.textOf(kids[0]))
    }

    @Test
    fun domRevisionConMutacion() {
        val doc = DomDocument.parse("<html><body><p id=\"q\">v</p></body></html>")
        val before = doc.snapshot().revision
        JsInterpreter.evaluateWithDom("var d = document.createElement(\"div\"); document.body.appendChild(d);", doc)
        assertEquals(before + 1L, doc.snapshot().revision)
    }

    @Test
    fun domParentOfKotlin() {
        val doc = DomDocument.parse("<html><body><div id=\"a\"><p id=\"b\">hola</p></div></body></html>")
        val p = assertNotNull(doc.findFirst("p"))
        val parent = assertNotNull(doc.parentOf(p))
        assertEquals("a", doc.attributeOf(parent, "id"))
    }

    @Test
    fun domQueryAllKotlin() {
        val doc = DomDocument.parse("<html><body><p id=\"saludo\">hola</p><p>dos</p></body></html>")
        val sels = assertNotNull(CssCascade.parseSelectorList("p, .zzz"))
        val parents = CssCascade.parentMap(doc.nodeOf(doc.rootHandle()))
        val p = assertNotNull(doc.findFirst("p"))
        assertEquals(true, CssCascade.matchesAny(doc.nodeOf(p), sels, parents))
    }
}
