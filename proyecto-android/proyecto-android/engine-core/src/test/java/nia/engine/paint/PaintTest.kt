package nia.engine.paint

import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de pintura v2 (R0023): espejo validado del gemelo TS.
 * v1 (T016) + tiras de borde (hasta 4 por elemento tras el fondo).
 * Cada test construye el arbol, computa estilos con la cascada CSS real,
 * corre el layout real y comprueba la display list y el hitTest.
 */
class PaintTest {

    private fun el(
        name: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<DomNode> = emptyList()
    ): DomNode.Element {
        val e = DomNode.Element(name, attrs)
        e.children.addAll(children)
        return e
    }

    private fun tx(data: String): DomNode.Text = DomNode.Text(data)

    private fun doc(vararg children: DomNode): DomNode.Document {
        val d = DomNode.Document()
        d.children.addAll(children)
        return d
    }

    private fun scene(d: DomNode.Document, css: String, vw: Double): PaintScene {
        val computed = CssCascade.compute(d, CssStylesheet.parse(css))
        val layout = LayoutEngine.layout(d, computed, vw)
        return PaintSceneBuilder.build(d, computed, layout)
    }

    private fun kind(op: PaintOp): String =
        if (op is PaintOp.DrawRect) "rect" else "text"

    @Test
    fun fondoTransparenteSinRect() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))), "", 200.0)
        assertEquals(1, s.ops.size, "una op")
        assertTrue(s.ops[0] is PaintOp.DrawText, "es texto")
        assertEquals("a", (s.ops[0] as PaintOp.DrawText).text, "texto")
    }

    @Test
    fun fondoBodyRectPrimero() {
        val b = el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))
        val s = scene(doc(b), "body{background-color:#112233}", 200.0)
        val o0 = s.ops[0]
        assertTrue(o0 is PaintOp.DrawRect, "rect primero")
        val r = o0 as PaintOp.DrawRect
        assertEquals("#112233", r.color, "color")
        assertTrue(r.node === b, "nodo body")
        assertEquals(0.0, r.x, 0.01, "x")
        assertEquals(19.2, r.height, 0.01, "h")
    }

    @Test
    fun ordenPreorder() {
        val dv = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))
        val b = el("body", emptyMap(), listOf(dv))
        val s = scene(doc(b), "body{background-color:#111111}div{background-color:#222222}", 200.0)
        assertEquals("rect,rect,text", s.ops.joinToString(",") { kind(it) }, "orden")
        val r = s.ops[1] as PaintOp.DrawRect
        assertEquals("#222222", r.color, "div color")
        assertTrue(r.node === dv, "nodo div")
    }

    @Test
    fun textoColorHeredado() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))), "body{color:#abcdef}", 200.0)
        assertEquals("#abcdef", (s.ops[0] as PaintOp.DrawText).color, "color heredado")
    }

    @Test
    fun textoColorPropio() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))), "body{color:#abcdef}p{color:#ff0000}", 200.0)
        assertEquals("#ff0000", (s.ops[0] as PaintOp.DrawText).color, "color propio")
    }

    @Test
    fun colorDefault() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))), "", 200.0)
        assertEquals("#000000", (s.ops[0] as PaintOp.DrawText).color, "default")
    }

    @Test
    fun fontSizeOp() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("h1", emptyMap(), listOf(tx("a")))))), "h1{font-size:32px}", 200.0)
        assertEquals(32.0, (s.ops[0] as PaintOp.DrawText).fontSize, 0.01, "fs 32")
    }

    @Test
    fun unaOpPorPalabra() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a b")))))), "", 200.0)
        assertEquals(2, s.ops.size, "dos ops")
        assertEquals("a", (s.ops[0] as PaintOp.DrawText).text, "p1")
        assertEquals("b", (s.ops[1] as PaintOp.DrawText).text, "p2")
        assertEquals(16.0, (s.ops[1] as PaintOp.DrawText).x, 0.01, "x2")
    }

    @Test
    fun correspondenciaPalabras() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("hola mundo")))))), "", 200.0)
        assertEquals("hola mundo", s.ops.joinToString(" ") { (it as PaintOp.DrawText).text }, "palabras")
    }

    @Test
    fun tresPalabrasEnvueltas() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("aaaa bbbb cccc")))))), "", 100.0)
        assertEquals(3, s.ops.size, "tres ops")
        assertEquals(19.2, (s.ops[2] as PaintOp.DrawText).y, 0.01, "y3")
    }

    @Test
    fun hitTexto() {
        val t = tx("abc")
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(t))))), "", 200.0)
        val hit = s.hitTest(1.0, 1.0)
        assertNotNull(hit, "hit")
        assertTrue(hit is PaintOp.DrawText && hit.node === t, "hit texto")
    }

    @Test
    fun hitFueraNull() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("abc")))))), "", 200.0)
        assertNull(s.hitTest(500.0, 500.0), "fuera")
    }

    @Test
    fun hitBordeExclusivo() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("abc")))))), "", 200.0)
        assertNull(s.hitTest(24.0, 1.0), "x24 fuera")
        assertNotNull(s.hitTest(23.99, 1.0), "x23 dentro")
    }

    @Test
    fun hitUltimoGana() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("abc")))))), "body{background-color:#111111}", 200.0)
        val hit = s.hitTest(1.0, 1.0)
        assertTrue(hit is PaintOp.DrawText, "texto gana")
    }

    @Test
    fun hitDivDevuelveElemento() {
        val dv = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))
        val s = scene(doc(el("body", emptyMap(), listOf(dv))), "div{background-color:#112233}", 200.0)
        val hit = s.hitTest(100.0, 1.0)
        assertTrue(hit is PaintOp.DrawRect && hit.node === dv, "div rect")
    }

    @Test
    fun hermanos() {
        val d1 = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("a")))))
        val d2 = el("div", mapOf("id" to "z"), listOf(el("p", emptyMap(), listOf(tx("b")))))
        val s = scene(doc(el("body", emptyMap(), listOf(d1, d2))), "div{background-color:#111111}#z{background-color:#222222}", 200.0)
        val hit = s.hitTest(100.0, 20.0)
        assertTrue(hit is PaintOp.DrawRect && hit.color == "#222222", "z gana")
    }

    @Test
    fun displayNoneSinOps() {
        val dn = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("x")))))
        val s = scene(doc(el("body", emptyMap(), listOf(dn, el("p", emptyMap(), listOf(tx("a")))))), "div{display:none}", 200.0)
        assertEquals(1, s.ops.size, "solo p")
        assertEquals("a", (s.ops[0] as PaintOp.DrawText).text, "texto a")
    }

    @Test
    fun ordenDocumentalMixto() {
        val dv = el("div", mapOf("id" to "q"), listOf(el("p", emptyMap(), listOf(tx("b")))))
        val b = el("body", emptyMap(), listOf(tx("a"), dv, tx("c")))
        val s = scene(doc(b), "#q{background-color:#112233}", 200.0)
        val seq = s.ops.joinToString(" ") { op ->
            if (op is PaintOp.DrawText) "text:" + op.text else "rect:" + op.color
        }
        assertEquals("text:a rect:#112233 text:b text:c", seq, "orden documental")
    }

    @Test
    fun hitEnvuelto() {
        val s = scene(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(tx("aaaa bbbb cccc")))))), "", 100.0)
        val hit = s.hitTest(0.0, 20.0)
        assertTrue(hit is PaintOp.DrawText && hit.text == "cccc", "linea 2")
    }

    @Test
    fun hitNestedHijoGana() {
        val inner = el("div", mapOf("id" to "in"), listOf(el("p", emptyMap(), listOf(tx("a")))))
        val outer = el("div", mapOf("id" to "out"), listOf(inner))
        val s = scene(doc(el("body", emptyMap(), listOf(outer))), "#out{background-color:#111111}#in{background-color:#222222}", 200.0)
        val hit = s.hitTest(100.0, 10.0)
        assertTrue(hit is PaintOp.DrawRect && hit.color == "#222222", "inner gana")
    }

    @Test
    fun fondoConMargin() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val s = scene(doc(el("body", emptyMap(), listOf(p))), "p{background-color:#00ff00;margin:10px}", 200.0)
        assertEquals(2, s.ops.size, "dos ops")
        val r = s.ops[0] as PaintOp.DrawRect
        assertEquals(10.0, r.x, 0.01, "x")
        assertEquals(10.0, r.y, 0.01, "y")
        assertEquals(180.0, r.width, 0.01, "w")
        assertEquals(19.2, r.height, 0.01, "h")
        assertEquals("#00ff00", r.color, "color")
        assertTrue(s.ops[1] is PaintOp.DrawText, "texto despues")
    }

    @Test
    fun bordesCompletos() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val s = scene(doc(el("body", emptyMap(), listOf(p))), "p{background-color:#eeeeee;border-width:4px;border-color:#ff0000}", 200.0)
        assertEquals(6, s.ops.size, "seis ops")
        val rect: (Int) -> PaintOp.DrawRect = { s.ops[it] as PaintOp.DrawRect }
        assertEquals(0.0, rect(0).x, 0.01, "bg x")
        assertEquals(27.2, rect(0).height, 0.01, "bg h")
        assertEquals("#eeeeee", rect(0).color, "bg color")
        assertEquals(4.0, rect(1).height, 0.01, "top h")
        assertEquals(23.2, rect(2).y, 0.01, "bottom y")
        assertEquals(4.0, rect(2).height, 0.01, "bottom h")
        assertEquals(4.0, rect(3).width, 0.01, "left w")
        assertEquals(19.2, rect(3).height, 0.01, "left h")
        assertEquals(196.0, rect(4).x, 0.01, "right x")
        assertEquals(19.2, rect(4).height, 0.01, "right h")
        for (i in 1..4) assertEquals("#ff0000", rect(i).color, "tira color " + i)
        val t = s.ops[5] as PaintOp.DrawText
        assertEquals(4.0, t.x, 0.01, "texto x")
        assertEquals(4.0, t.y, 0.01, "texto y")
    }

    @Test
    fun bordeSinFondo() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val s = scene(doc(el("body", emptyMap(), listOf(p))), "p{border-width:2px}", 200.0)
        assertEquals(5, s.ops.size, "cinco ops")
        for (i in 0..3) assertEquals("#000000", (s.ops[i] as PaintOp.DrawRect).color, "tira color " + i)
        assertEquals(21.2, (s.ops[1] as PaintOp.DrawRect).y, 0.01, "bottom y")
        val t = s.ops[4] as PaintOp.DrawText
        assertEquals(2.0, t.x, 0.01, "texto x")
        assertEquals(2.0, t.y, 0.01, "texto y")
        val hit = s.hitTest(1.0, 1.0)
        assertTrue(hit is PaintOp.DrawRect, "hit rect")
    }

    @Test
    fun paddingMueveTexto() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val s = scene(doc(el("body", emptyMap(), listOf(p))), "p{padding:6px}", 200.0)
        assertEquals(1, s.ops.size, "una op")
        assertEquals(6.0, (s.ops[0] as PaintOp.DrawText).x, 0.01, "x")
        assertEquals(6.0, (s.ops[0] as PaintOp.DrawText).y, 0.01, "y")
    }

    @Test
    fun bordeCeroSoloTexto() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val s = scene(doc(el("body", emptyMap(), listOf(p))), "p{border-width:0px}", 200.0)
        assertEquals(1, s.ops.size, "una op")
        assertTrue(s.ops[0] is PaintOp.DrawText, "solo texto")
    }

    @Test
    fun bordeColorHeredado() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val b = el("body", emptyMap(), listOf(p))
        val s = scene(doc(b), "body{color:#123456}p{border-width:4px}", 200.0)
        assertEquals(5, s.ops.size, "cinco ops")
        for (i in 0..3) assertEquals("#123456", (s.ops[i] as PaintOp.DrawRect).color, "tira hereda color " + i)
    }
}
