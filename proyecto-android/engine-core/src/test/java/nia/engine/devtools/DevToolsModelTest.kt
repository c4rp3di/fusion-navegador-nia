package nia.engine.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import nia.engine.css.CssSpecificity
import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.inspector.EventContext
import nia.engine.inspector.InspectorSession

class DevToolsModelTest {

    private val html = "<html><head><title>demo</title></head><body><h1 id=\"titulo\">Hola</h1><p class=\"tarjeta\">Uno</p><p>Dos</p><!--nota--></body></html>"

    private val css = "body { color: #112233; font-size: 20px; }" + "\n" +
        "h1 { color: #445566; }" + "\n" +
        "#titulo { color: #778899; }" + "\n" +
        "p { margin: 1em; }" + "\n" +
        ".tarjeta { margin: 2em; }" + "\n"

    private fun fixture(): Pair<DomDocument, DevToolsModel> {
        val doc = DomDocument.parse(html)
        return Pair(doc, DevToolsModel(doc, css))
    }

    private fun body(doc: DomDocument): DomHandle =
        doc.findFirst("body") ?: throw AssertionError("falta body")

    private fun h1(doc: DomDocument): DomHandle =
        doc.findFirst("h1") ?: throw AssertionError("falta h1")

    private fun hijos(doc: DomDocument, h: DomHandle): List<DomHandle> = doc.childrenOf(h)

    @Test fun arbolPreOrdenConIds() {
        val (doc, model) = fixture()
        val lines = model.tree().split('\n').filter { it.isNotEmpty() }
        assertEquals(13, lines.size)
        assertEquals("1 document", lines[0])
        assertEquals("      7 h1 id=\"titulo\"", lines[6])
    }

    @Test fun arbolTextoYComentario() {
        val (_, model) = fixture()
        val lines = model.tree().split('\n').filter { it.isNotEmpty() }
        assertTrue(lines[4].contains("text \"demo\""))
        assertTrue(lines[11].contains("12 text \"Dos\""))
        assertTrue(lines[12].contains("13 comment \"nota\""))
    }

    @Test fun computedPorDefecto() {
        val (doc, model) = fixture()
        assertEquals("transparent", model.computedOf(h1(doc))["background-color"])
    }

    @Test fun computedHeredaTipografia() {
        val (doc, model) = fixture()
        assertEquals("20px", model.computedOf(h1(doc))["font-size"])
    }

    @Test fun computedResuelveEmAPx() {
        val (doc, model) = fixture()
        val pTarjeta = hijos(doc, body(doc))[1]
        assertEquals("40px", model.computedOf(pTarjeta)["margin-top"])
    }

    @Test fun causaReglaDirecta() {
        val (doc, model) = fixture()
        val c = model.causeOf(body(doc), "color")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("body", r.selector)
        assertEquals("#112233", r.declaredValue)
        assertEquals(CssSpecificity(0, 0, 1), r.specificity)
        assertEquals(0, r.ruleOrder)
    }

    @Test fun causaReglaGanaPorId() {
        val (doc, model) = fixture()
        val c = model.causeOf(h1(doc), "color")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("#titulo", r.selector)
        assertEquals("#778899", r.declaredValue)
        assertEquals(CssSpecificity(1, 0, 0), r.specificity)
        assertEquals(2, r.ruleOrder)
    }

    @Test fun causaMejorSelectorDeRegla() {
        val doc = DomDocument.parse(html)
        val model = DevToolsModel(doc, "h1, #titulo { color: red }")
        val c = model.causeOf(h1(doc), "color")
        assertTrue(c is StyleCause.Rule)
        assertEquals("#titulo", (c as StyleCause.Rule).selector)
    }

    @Test fun causaEmpateGanaUltimo() {
        val doc = DomDocument.parse(html)
        val model = DevToolsModel(doc, "p { color: red } p { color: blue }")
        val pDos = hijos(doc, body(doc))[2]
        val c = model.causeOf(pDos, "color")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("blue", r.declaredValue)
        assertEquals(1, r.ruleOrder)
    }

    @Test fun causaEspecifidadSobreOrden() {
        val (doc, model) = fixture()
        val pTarjeta = hijos(doc, body(doc))[1]
        val c = model.causeOf(pTarjeta, "margin-top")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals(".tarjeta", r.selector)
        assertEquals("2em", r.declaredValue)
    }

    @Test fun causaSelectorComplejo() {
        val doc = DomDocument.parse(html)
        val model = DevToolsModel(doc, "body > p { color: green }")
        val pDos = hijos(doc, body(doc))[2]
        val c = model.causeOf(pDos, "color")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("body > p", r.selector)
        assertEquals(CssSpecificity(0, 0, 2), r.specificity)
    }

    @Test fun causaPseudoDescripcion() {
        val doc = DomDocument.parse(html)
        val model = DevToolsModel(doc, "h1:first-child { background-color: pink }")
        val c = model.causeOf(h1(doc), "background-color")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("h1:first-child", r.selector)
        assertEquals(CssSpecificity(0, 1, 1), r.specificity)
    }

    @Test fun nthDescripcion() {
        val doc = DomDocument.parse(html)
        val model = DevToolsModel(doc, "p:nth-child(odd) { color: red }")
        val b = body(doc)
        val pDos = hijos(doc, b)[2]
        val matches = model.matchingSelectors(pDos)
        assertEquals(1, matches.size)
        assertEquals("p:nth-child(2n+1)", matches[0].selector)
        assertEquals(0, model.matchingSelectors(hijos(doc, b)[1]).size)
    }

    @Test fun causaHeredada() {
        val (doc, model) = fixture()
        val pDos = hijos(doc, body(doc))[2]
        val c = model.causeOf(pDos, "font-size")
        assertTrue(c is StyleCause.Inherited)
        val i = c as StyleCause.Inherited
        assertEquals(6, i.fromNodeId)
        assertEquals("20px", i.value)
    }

    @Test fun causaPorDefecto() {
        val (doc, model) = fixture()
        val pDos = hijos(doc, body(doc))[2]
        val c = model.causeOf(pDos, "background-color")
        assertTrue(c is StyleCause.Default)
        assertEquals("transparent", (c as StyleCause.Default).value)
    }

    @Test fun causaDesconocidaNull() {
        val (doc, model) = fixture()
        assertTrue(model.causeOf(h1(doc), "width") == null)
    }

    @Test fun causaEnTextoNull() {
        val (doc, model) = fixture()
        val texto = hijos(doc, h1(doc))[0]
        assertTrue(model.causeOf(texto, "color") == null)
    }

    @Test fun computedNoElementoVacio() {
        val (doc, model) = fixture()
        val texto = hijos(doc, h1(doc))[0]
        assertTrue(model.computedOf(texto).isEmpty())
    }

    @Test fun selectoresQueMatchean() {
        val (doc, model) = fixture()
        val matches = model.matchingSelectors(h1(doc))
        assertEquals(2, matches.size)
        assertEquals("h1", matches[0].selector)
        assertEquals(CssSpecificity(0, 0, 1), matches[0].specificity)
        assertEquals(1, matches[0].ruleOrder)
        assertEquals("#titulo", matches[1].selector)
        assertEquals(CssSpecificity(1, 0, 0), matches[1].specificity)
        assertEquals(2, matches[1].ruleOrder)
    }

    @Test fun selectoresEnTextoVacio() {
        val (doc, model) = fixture()
        val texto = hijos(doc, h1(doc))[0]
        assertTrue(model.matchingSelectors(texto).isEmpty())
    }

    @Test fun causaDeclaredVsComputed() {
        val (doc, model) = fixture()
        val pTarjeta = hijos(doc, body(doc))[1]
        val c = model.causeOf(pTarjeta, "margin-top")
        assertTrue(c is StyleCause.Rule)
        assertEquals("2em", (c as StyleCause.Rule).declaredValue)
        assertEquals("40px", model.computedOf(pTarjeta)["margin-top"])
    }

    @Test fun shorthandMarginCausa() {
        val (doc, model) = fixture()
        val pDos = hijos(doc, body(doc))[2]
        val c = model.causeOf(pDos, "margin-top")
        assertTrue(c is StyleCause.Rule)
        val r = c as StyleCause.Rule
        assertEquals("p", r.selector)
        assertEquals("1em", r.declaredValue)
    }

    @Test fun mutacionVisibleEnArbol() {
        val (doc, model) = fixture()
        val texto = hijos(doc, h1(doc))[0]
        doc.setText(texto, "Nuevo")
        assertTrue(model.tree().contains("8 text \"Nuevo\""))
    }

    @Test fun staleHandleLanza() {
        val (doc, model) = fixture()
        val h = h1(doc)
        doc.invalidate()
        val ex = assertFailsWith<DomException> { model.causeOf(h, "color") }
        assertEquals("stale-handle", ex.code)
    }

    @Test fun seleccionGrabaEventoInspector() {
        val (doc, model) = fixture()
        val session = InspectorSession()
        val ctx = EventContext("s1", "t1", "f1", doc.documentId, 1L)
        val ev = model.recordSelection(session, ctx, h1(doc))
        assertEquals(1L, ev.seq)
        assertEquals("devtools", ev.kind)
        assertEquals("7", ev.payload["nodeId"])
        assertEquals("h1", ev.payload["name"])
    }

    @Test fun seleccionRedactaTexto() {
        val (doc, model) = fixture()
        val session = InspectorSession()
        val ctx = EventContext("s1", "t1", "f1", doc.documentId, 1L)
        val texto = hijos(doc, h1(doc))[0]
        doc.setText(texto, "token=secreto")
        val ev = model.recordSelection(session, ctx, texto)
        assertEquals("token=[redacted]", ev.payload["text"])
    }

    @Test fun eventosMonotonicos() {
        val (doc, model) = fixture()
        val session = InspectorSession()
        val ctx = EventContext("s1", "t1", "f1", doc.documentId, 1L)
        val b = body(doc)
        model.recordSelection(session, ctx, hijos(doc, b)[1])
        model.recordSelection(session, ctx, hijos(doc, b)[2])
        assertEquals(2, session.all().size)
        assertEquals(1, session.eventsSince(1).size)
        assertEquals(3, session.eventsSince(1)[0].payload["nodeId"])
    }
}
