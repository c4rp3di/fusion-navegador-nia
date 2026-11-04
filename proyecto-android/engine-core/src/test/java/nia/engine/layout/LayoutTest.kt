package nia.engine.layout

import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.html.DomNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests del layout v2 (R0023): espejo validado del gemelo TS.
 * v1 (T015) + margin/padding/border (border-box, % contra contenedor,
 * em/rem contra la fuente del elemento).
 * v3 (R0084, RES-0047): tablas HTML (T032 v1) - reftests de columnas,
 * filas, estirado de celdas y exclusiones documentadas.
 * v4 (R0090, RES-0050): flexbox, grid y responsive (T032 v2) - reftests
 * de filas flex (justify/align/grow/gap/wrap), columnas grid y @media.
 */
class LayoutTest {

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

    private fun lay(d: DomNode.Document, css: String, vw: Double): LayoutResult =
        LayoutEngine.layout(d, CssCascade.compute(d, CssStylesheet.parse(css)), vw)

    private fun layVw(d: DomNode.Document, css: String, vw: Double): LayoutResult =
        LayoutEngine.layout(d, CssCascade.compute(d, CssStylesheet.parse(css), vw), vw)

    private fun assertBox(name: String, b: LayoutBox?, x: Double, y: Double, w: Double, h: Double) {
        assertNotNull(b, name + " es null")
        val bb = b!!
        assertEquals(x, bb.x, 0.01, name + " x")
        assertEquals(y, bb.y, 0.01, name + " y")
        assertEquals(w, bb.width, 0.01, name + " w")
        assertEquals(h, bb.height, 0.01, name + " h")
    }

    private fun assertVal(name: String, actual: Double?, expected: Double) {
        assertNotNull(actual, name + " es null")
        assertEquals(expected, actual!!, 0.01, name)
    }

    @Test
    fun bloqueSimple() {
        val t = tx("abc")
        val p = el("p", emptyMap(), listOf(t))
        val b = el("body", emptyMap(), listOf(p))
        val r = lay(doc(b), "", 200.0)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 200.0, 19.2)
        assertBox("texto", r.boxOf(t), 0.0, 0.0, 24.0, 19.2)
        assertBox("body", r.boxOf(b), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun bloquesApilan() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(p1, p2))
        val r = lay(doc(b), "", 200.0)
        assertBox("p1", r.boxOf(p1), 0.0, 0.0, 200.0, 19.2)
        assertBox("p2", r.boxOf(p2), 0.0, 19.2, 200.0, 19.2)
        assertVal("body h", r.boxOf(b)?.height, 38.4)
    }

    @Test
    fun anidadoAbsoluto() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(p1, p2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "", 200.0)
        assertBox("div", r.boxOf(dv), 0.0, 0.0, 200.0, 38.4)
        assertBox("p2", r.boxOf(p2), 0.0, 19.2, 200.0, 19.2)
    }

    @Test
    fun envuelveDosLineas() {
        val t = tx("aaaa bbbb cccc")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "", 100.0)
        val fs = r.fragmentsOf(t)
        assertEquals(3, fs.size, "palabras")
        assertVal("f2 x", fs[1]?.x, 40.0)
        assertVal("f3 y", fs[2]?.y, 19.2)
        assertVal("f3 x", fs[2]?.x, 0.0)
        assertBox("bound", r.boxOf(t), 0.0, 0.0, 72.0, 38.4)
    }

    @Test
    fun lineaExactaNoEnvuelve() {
        val t = tx("aaaaa")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "", 40.0)
        assertEquals(1, r.fragmentsOf(t).size, "una linea")
    }

    @Test
    fun overflowPermitido() {
        val t = tx("aaaa")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "", 20.0)
        assertBox("frag", r.fragmentsOf(t)[0], 0.0, 0.0, 32.0, 19.2)
        assertVal("p h", r.boxOf(p)?.height, 19.2)
    }

    @Test
    fun alineacionCentro() {
        val t = tx("abc")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{text-align:center}", 200.0)
        assertVal("x", r.fragmentsOf(t)[0]?.x, 88.0)
    }

    @Test
    fun alineacionDerecha() {
        val t = tx("abc")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{text-align:right}", 200.0)
        assertVal("x", r.fragmentsOf(t)[0]?.x, 176.0)
    }

    @Test
    fun heredaAlign() {
        val t = tx("abc")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "body{text-align:center}", 200.0)
        assertVal("x", r.fragmentsOf(t)[0]?.x, 88.0)
    }

    @Test
    fun displayNoneSalta() {
        val ip = el("p", emptyMap(), listOf(tx("a")))
        val dv = el("div", emptyMap(), listOf(ip))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(dv, p2))
        val r = lay(doc(b), "div{display:none}", 200.0)
        assertNull(r.boxOf(dv), "div none")
        assertNull(r.boxOf(ip), "hijo none")
        assertBox("p2", r.boxOf(p2), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun displayInlineForzado() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(p1, p2))
        val r = lay(doc(b), "p{display:inline}", 200.0)
        assertBox("p1", r.boxOf(p1), 0.0, 0.0, 8.0, 19.2)
        assertBox("p2", r.boxOf(p2), 16.0, 0.0, 8.0, 19.2)
        assertBox("body", r.boxOf(b), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun spansMismaLinea() {
        val s1 = el("span", emptyMap(), listOf(tx("aaa")))
        val s2 = el("span", emptyMap(), listOf(tx("bbb")))
        val r = lay(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(s1, s2))))), "", 200.0)
        assertVal("x", r.fragmentsOf(s2.children[0] as DomNode.Text)[0]?.x, 32.0)
    }

    @Test
    fun spanAnidadoBox() {
        val s = el("span", emptyMap(), listOf(tx("abc")))
        val r = lay(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(s))))), "", 200.0)
        assertBox("span", r.boxOf(s), 0.0, 0.0, 24.0, 19.2)
    }

    @Test
    fun spanVacioNull() {
        val s1 = el("span", emptyMap(), emptyList())
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val r = lay(doc(el("body", emptyMap(), listOf(el("p", emptyMap(), listOf(s1, s2))))), "", 200.0)
        assertNull(r.boxOf(s1), "vacio null")
        assertBox("s2", r.boxOf(s2), 0.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun fuenteGrande() {
        val h = el("h1", emptyMap(), listOf(tx("abc")))
        val r = lay(doc(el("body", emptyMap(), listOf(h))), "h1{font-size:32px}", 200.0)
        assertBox("frag", r.fragmentsOf(h.children[0] as DomNode.Text)[0], 0.0, 0.0, 48.0, 38.4)
        assertVal("h1 h", r.boxOf(h)?.height, 38.4)
    }

    @Test
    fun heredaFuente() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "body{font-size:32px}", 200.0)
        assertBox("frag", r.fragmentsOf(t)[0], 0.0, 0.0, 16.0, 38.4)
    }

    @Test
    fun lineHeightPx() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{line-height:24px}", 200.0)
        assertVal("frag h", r.fragmentsOf(t)[0]?.height, 24.0)
        assertVal("p h", r.boxOf(p)?.height, 24.0)
    }

    @Test
    fun heredaLineHeight() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "body{line-height:24px}", 200.0)
        assertVal("frag h", r.fragmentsOf(t)[0]?.height, 24.0)
    }

    @Test
    fun anonimosInterleaved() {
        val ta = tx("a")
        val tb = tx("b")
        val tc = tx("c")
        val inner = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(tb))))
        val dv = el("div", emptyMap(), listOf(ta, inner, tc))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "", 200.0)
        assertBox("inner", r.boxOf(inner), 0.0, 19.2, 200.0, 19.2)
        assertBox("ta", r.boxOf(ta), 0.0, 0.0, 8.0, 19.2)
        assertBox("tc", r.boxOf(tc), 0.0, 38.4, 8.0, 19.2)
        assertBox("div", r.boxOf(dv), 0.0, 0.0, 200.0, 57.6)
    }

    @Test
    fun whitespaceEntreBloquesIgnorado() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(p1, tx("  \n  "), p2))
        val r = lay(doc(b), "", 200.0)
        assertVal("p2 y", r.boxOf(p2)?.y, 19.2)
    }

    @Test
    fun comentarioIgnorado() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(p1, DomNode.Comment("x"), p2))
        val r = lay(doc(b), "", 200.0)
        assertVal("p2 y", r.boxOf(p2)?.y, 19.2)
    }

    @Test
    fun inlineSeEnvuelve() {
        val s1 = el("span", emptyMap(), listOf(tx("aaaa")))
        val s2 = el("span", emptyMap(), listOf(tx("bbbb")))
        val p = el("p", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "", 50.0)
        assertBox("s2", r.boxOf(s2), 0.0, 19.2, 32.0, 19.2)
        assertVal("p h", r.boxOf(p)?.height, 38.4)
    }

    @Test
    fun lineHeightMaxPorLinea() {
        val s1 = el("span", mapOf("class" to "big"), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val p = el("p", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "span.big{font-size:32px}", 200.0)
        assertVal("f1 h", r.fragmentsOf(s1.children[0] as DomNode.Text)[0]?.height, 38.4)
        assertVal("f2 h", r.fragmentsOf(s2.children[0] as DomNode.Text)[0]?.height, 38.4)
        assertVal("f2 x", r.fragmentsOf(s2.children[0] as DomNode.Text)[0]?.x, 24.0)
        assertVal("p h", r.boxOf(p)?.height, 38.4)
    }

    @Test
    fun dosPalabrasMismoNodo() {
        val t = tx("a  b")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "", 200.0)
        val fs = r.fragmentsOf(t)
        assertEquals(2, fs.size, "dos frags")
        assertVal("f2 x", fs[1]?.x, 16.0)
        assertBox("bound", r.boxOf(t), 0.0, 0.0, 24.0, 19.2)
    }

    @Test
    fun htmlBodyAnidados() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val b = el("body", emptyMap(), listOf(p))
        val h = el("html", emptyMap(), listOf(b))
        val r = lay(doc(h), "", 250.0)
        assertBox("html", r.boxOf(h), 0.0, 0.0, 250.0, 19.2)
        assertBox("body", r.boxOf(b), 0.0, 0.0, 250.0, 19.2)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 250.0, 19.2)
    }

    @Test
    fun bloqueVacio() {
        val b = el("body", emptyMap(), emptyList())
        val r = lay(doc(b), "", 200.0)
        assertBox("body", r.boxOf(b), 0.0, 0.0, 200.0, 0.0)
    }

    @Test
    fun textoDirectoEnBody() {
        val t = tx("a")
        val b = el("body", emptyMap(), listOf(t))
        val r = lay(doc(b), "", 200.0)
        assertBox("frag", r.fragmentsOf(t)[0], 0.0, 0.0, 8.0, 19.2)
        assertBox("body", r.boxOf(b), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun especificidadEnLayout() {
        val t = tx("abc")
        val p = el("p", mapOf("class" to "x"), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{text-align:right}.x{text-align:center}", 200.0)
        assertVal("x centro", r.fragmentsOf(t)[0]?.x, 88.0)
    }

    @Test
    fun marginEntreBloques() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val b = el("body", emptyMap(), listOf(p1, p2))
        val r = lay(doc(b), "p{margin:10px}", 200.0)
        assertBox("p1", r.boxOf(p1), 10.0, 10.0, 180.0, 19.2)
        assertBox("p2", r.boxOf(p2), 10.0, 49.2, 180.0, 19.2)
        assertVal("body h", r.boxOf(b)?.height, 78.4)
    }

    @Test
    fun marginPctContraContenedor() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{margin:5%}", 200.0)
        assertBox("p", r.boxOf(p), 10.0, 10.0, 180.0, 19.2)
    }

    @Test
    fun paddingEncogeContenido() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{padding:10px}", 200.0)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 200.0, 39.2)
        assertBox("frag", r.fragmentsOf(t)[0], 10.0, 10.0, 8.0, 19.2)
    }

    @Test
    fun borderDesplazaContenido() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{border-width:4px}", 200.0)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 200.0, 27.2)
        assertBox("frag", r.fragmentsOf(t)[0], 4.0, 4.0, 8.0, 19.2)
    }

    @Test
    fun combinadoMarginPaddingBorder() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{margin:10px;padding:5px;border-width:2px}", 200.0)
        assertBox("p", r.boxOf(p), 10.0, 10.0, 180.0, 33.2)
        assertBox("frag", r.fragmentsOf(t)[0], 17.0, 17.0, 8.0, 19.2)
    }

    @Test
    fun marginEm() {
        val t = tx("abc")
        val h = el("h1", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(h))), "h1{font-size:32px;margin:0.5em}", 200.0)
        assertBox("h1", r.boxOf(h), 16.0, 16.0, 168.0, 38.4)
        assertBox("frag", r.fragmentsOf(t)[0], 16.0, 16.0, 48.0, 38.4)
    }

    @Test
    fun nthChildMargin() {
        val p1 = el("p", emptyMap(), listOf(tx("1")))
        val p2 = el("p", emptyMap(), listOf(tx("2")))
        val p3 = el("p", emptyMap(), listOf(tx("3")))
        val r = lay(doc(el("body", emptyMap(), listOf(p1, p2, p3))), "p:nth-child(2){margin-top:20px}", 200.0)
        assertVal("p2 y", r.boxOf(p2)?.y, 39.2)
        assertVal("p3 y", r.boxOf(p3)?.y, 58.4)
    }

    @Test
    fun anidadoPaddingBorderMargin() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val dv = el("div", emptyMap(), listOf(p))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{padding:10px;border-width:4px}p{margin:4px}", 200.0)
        assertBox("div", r.boxOf(dv), 0.0, 0.0, 200.0, 55.2)
        assertBox("p", r.boxOf(p), 18.0, 18.0, 164.0, 19.2)
    }

    @Test
    fun inlineYPadding() {
        val t = tx("y")
        val p = el("p", emptyMap(), listOf(t))
        val b = el("b", emptyMap(), listOf(tx("x")))
        val body = el("body", emptyMap(), listOf(b, p))
        val r = lay(doc(body), "p{padding:8px}", 200.0)
        assertBox("frag", r.fragmentsOf(t)[0], 8.0, 27.2, 8.0, 19.2)
        assertBox("p", r.boxOf(p), 0.0, 19.2, 200.0, 35.2)
        assertVal("body h", r.boxOf(body)?.height, 54.4)
    }

    @Test
    fun borderPctContraContenedor() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{border-width:10%}", 200.0)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 200.0, 59.2)
        assertBox("frag", r.fragmentsOf(t)[0], 20.0, 20.0, 8.0, 19.2)
    }

    @Test
    fun shorthandAsimetrico() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{margin:1px 2px}", 200.0)
        assertBox("p", r.boxOf(p), 2.0, 1.0, 196.0, 19.2)
    }

    @Test
    fun pseudoMarginIzquierda() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val r = lay(doc(el("body", emptyMap(), listOf(p1, p2))), "p:first-child{margin-left:20px}", 200.0)
        assertBox("p1", r.boxOf(p1), 20.0, 0.0, 180.0, 19.2)
        assertBox("p2", r.boxOf(p2), 0.0, 19.2, 200.0, 19.2)
    }

    @Test
    fun paddingEm() {
        val t = tx("a")
        val p = el("p", emptyMap(), listOf(t))
        val r = lay(doc(el("body", emptyMap(), listOf(p))), "p{padding:0.25em}", 200.0)
        assertBox("frag", r.fragmentsOf(t)[0], 4.0, 4.0, 8.0, 19.2)
        assertBox("p", r.boxOf(p), 0.0, 0.0, 200.0, 27.2)
    }
    @Test
    fun tablaUnaCelda() {
        val td = el("td", emptyMap(), listOf(tx("x")))
        val tr = el("tr", emptyMap(), listOf(td))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 8.0, 19.2)
        assertBox("tr", r.boxOf(tr), 0.0, 0.0, 8.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun tablaColumnasPorContenido() {
        val td1 = el("td", emptyMap(), listOf(tx("abc")))
        val td2 = el("td", emptyMap(), listOf(tx("ab")))
        val tr = el("tr", emptyMap(), listOf(td1, td2))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("td1", r.boxOf(td1), 0.0, 0.0, 24.0, 19.2)
        assertBox("td2", r.boxOf(td2), 24.0, 0.0, 16.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 40.0, 19.2)
    }

    @Test
    fun tablaFilasApilan() {
        val td1 = el("td", emptyMap(), listOf(tx("a")))
        val td2 = el("td", emptyMap(), listOf(tx("b")))
        val tr1 = el("tr", emptyMap(), listOf(td1))
        val tr2 = el("tr", emptyMap(), listOf(td2))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr1, tr2))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("td1", r.boxOf(td1), 0.0, 0.0, 8.0, 19.2)
        assertBox("td2", r.boxOf(td2), 0.0, 19.2, 8.0, 19.2)
        assertBox("tr2", r.boxOf(tr2), 0.0, 19.2, 8.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 8.0, 38.4)
    }

    @Test
    fun celdaMultilineaNatural() {
        val w = tx("a b")
        val td = el("td", emptyMap(), listOf(w))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("fa", r.fragmentsOf(w)[0], 0.0, 0.0, 8.0, 19.2)
        assertBox("fb", r.fragmentsOf(w)[1], 16.0, 0.0, 8.0, 19.2)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 24.0, 19.2)
    }

    @Test
    fun celdaAnchoFijoSeEnvuelve() {
        val w = tx("aaaa bbbb")
        val td = el("td", emptyMap(), listOf(w))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "td{width:32px}", 200.0)
        assertBox("f1", r.fragmentsOf(w)[0], 0.0, 0.0, 32.0, 19.2)
        assertBox("f2", r.fragmentsOf(w)[1], 0.0, 19.2, 32.0, 19.2)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 32.0, 38.4)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 32.0, 38.4)
    }

    @Test
    fun tablaAnchoFijoDistribuye() {
        val td1 = el("td", emptyMap(), listOf(tx("a")))
        val td2 = el("td", emptyMap(), listOf(tx("b")))
        val tr = el("tr", emptyMap(), listOf(td1, td2))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "table{width:200px}", 200.0)
        assertBox("td1", r.boxOf(td1), 0.0, 0.0, 100.0, 19.2)
        assertBox("td2", r.boxOf(td2), 100.0, 0.0, 100.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun tablaAnchoPct() {
        val td1 = el("td", emptyMap(), listOf(tx("a")))
        val td2 = el("td", emptyMap(), listOf(tx("b")))
        val tr = el("tr", emptyMap(), listOf(td1, td2))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "table{width:50%}", 200.0)
        assertBox("td1", r.boxOf(td1), 0.0, 0.0, 50.0, 19.2)
        assertBox("td2", r.boxOf(td2), 50.0, 0.0, 50.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 100.0, 19.2)
    }

    @Test
    fun tablaPadding() {
        val w = tx("a")
        val td = el("td", emptyMap(), listOf(w))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "table{padding:10px}", 200.0)
        assertBox("frag", r.fragmentsOf(w)[0], 10.0, 10.0, 8.0, 19.2)
        assertBox("td", r.boxOf(td), 10.0, 10.0, 8.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 28.0, 39.2)
    }

    @Test
    fun celdaPaddingBorde() {
        val w = tx("a")
        val td = el("td", emptyMap(), listOf(w))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "td{padding:4px;border-width:2px}", 200.0)
        assertBox("frag", r.fragmentsOf(w)[0], 6.0, 6.0, 8.0, 19.2)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 20.0, 31.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 20.0, 31.2)
    }

    @Test
    fun tablaTheadTbodyTfoot() {
        val h = el("td", emptyMap(), listOf(tx("H")))
        val a = el("td", emptyMap(), listOf(tx("a")))
        val f = el("td", emptyMap(), listOf(tx("f")))
        val t = el("table", emptyMap(), listOf(
            el("thead", emptyMap(), listOf(el("tr", emptyMap(), listOf(h)))),
            el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(a)))),
            el("tfoot", emptyMap(), listOf(el("tr", emptyMap(), listOf(f))))
        ))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("H", r.boxOf(h), 0.0, 0.0, 8.0, 19.2)
        assertBox("a", r.boxOf(a), 0.0, 19.2, 8.0, 19.2)
        assertBox("f", r.boxOf(f), 0.0, 38.4, 8.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 0.0, 8.0, 57.6)
    }

    @Test
    fun captionYColgroupSeSaltan() {
        val td = el("td", emptyMap(), listOf(tx("x")))
        val cap = el("caption", emptyMap(), listOf(tx("T")))
        val cg = el("colgroup", emptyMap(), listOf(el("col"), el("col")))
        val t = el("table", emptyMap(), listOf(cap, cg, el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertNull(r.boxOf(cap), "caption sin caja")
        assertNull(r.boxOf(cg.children[0] as DomNode.Element), "col sin caja")
        assertBox("td", r.boxOf(td), 0.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun celdaEstiradaALaFila() {
        val w = tx("aa bb")
        val td1 = el("td", emptyMap(), listOf(w))
        val td2 = el("td", emptyMap(), listOf(tx("b")))
        val tr = el("tr", emptyMap(), listOf(td1, td2))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(tr))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "td:first-child{width:16px}", 200.0)
        assertBox("fa", r.fragmentsOf(w)[0], 0.0, 0.0, 16.0, 19.2)
        assertBox("fb", r.fragmentsOf(w)[1], 0.0, 19.2, 16.0, 19.2)
        assertBox("td1", r.boxOf(td1), 0.0, 0.0, 16.0, 38.4)
        assertBox("td2", r.boxOf(td2), 16.0, 0.0, 8.0, 38.4)
    }

    @Test
    fun tablaEntreParrafos() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val td = el("td", emptyMap(), listOf(tx("x")))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val b = el("body", emptyMap(), listOf(p1, t, p2))
        val r = lay(doc(b), "", 200.0)
        assertBox("p1", r.boxOf(p1), 0.0, 0.0, 200.0, 19.2)
        assertBox("table", r.boxOf(t), 0.0, 19.2, 8.0, 19.2)
        assertBox("p2", r.boxOf(p2), 0.0, 38.4, 200.0, 19.2)
        assertVal("body h", r.boxOf(b)?.height, 57.6)
    }

    @Test
    fun tablaMargin() {
        val td = el("td", emptyMap(), listOf(tx("a")))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val b = el("body", emptyMap(), listOf(t))
        val r = lay(doc(b), "table{margin:10px}", 200.0)
        assertBox("table", r.boxOf(t), 10.0, 10.0, 8.0, 19.2)
        assertVal("body h", r.boxOf(b)?.height, 39.2)
    }

    @Test
    fun tdFueraDeTablaInline() {
        val w = tx("x")
        val td = el("td", emptyMap(), listOf(w))
        val b = el("body", emptyMap(), listOf(td))
        val r = lay(doc(b), "", 200.0)
        assertBox("frag", r.fragmentsOf(w)[0], 0.0, 0.0, 8.0, 19.2)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun tablaAnidadaMideComoTexto() {
        val wa = tx("a")
        val wb = tx("b")
        val innerTd = el("td", emptyMap(), listOf(wb))
        val inner = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(innerTd))))))
        val td = el("td", emptyMap(), listOf(wa, inner))
        val t = el("table", emptyMap(), listOf(el("tbody", emptyMap(), listOf(el("tr", emptyMap(), listOf(td))))))
        val r = lay(doc(el("body", emptyMap(), listOf(t))), "", 200.0)
        assertBox("fa", r.fragmentsOf(wa)[0], 0.0, 0.0, 8.0, 19.2)
        assertBox("fb", r.fragmentsOf(wb)[0], 16.0, 0.0, 8.0, 19.2)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 24.0, 19.2)
        assertBox("inner", r.boxOf(inner), 16.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun displayTablePorCss() {
        val td = el("td", emptyMap(), listOf(tx("x")))
        val tr = el("tr", emptyMap(), listOf(td))
        val dv = el("div", emptyMap(), listOf(tr))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:table}", 200.0)
        assertBox("td", r.boxOf(td), 0.0, 0.0, 8.0, 19.2)
        assertBox("div", r.boxOf(dv), 0.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun flexRowDosItems() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 8.0, 19.2)
        assertBox("s2", r.boxOf(s2), 8.0, 0.0, 8.0, 19.2)
        assertBox("div", r.boxOf(dv), 0.0, 0.0, 200.0, 19.2)
    }

    @Test
    fun flexJustifyCenter() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;justify-content:center}", 100.0)
        assertBox("s1", r.boxOf(s1), 42.0, 0.0, 8.0, 19.2)
        assertBox("s2", r.boxOf(s2), 50.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun flexJustifySpaceBetween() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val s3 = el("span", emptyMap(), listOf(tx("c")))
        val dv = el("div", emptyMap(), listOf(s1, s2, s3))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;justify-content:space-between}", 40.0)
        assertVal("s1 x", r.boxOf(s1)?.x, 0.0)
        assertVal("s2 x", r.boxOf(s2)?.x, 16.0)
        assertVal("s3 x", r.boxOf(s3)?.x, 32.0)
    }

    @Test
    fun flexGap() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;gap:10px}", 200.0)
        assertVal("s2 x", r.boxOf(s2)?.x, 18.0)
    }

    @Test
    fun flexGrow() {
        val s1 = el("span", mapOf("class" to "a"), listOf(tx("a")))
        val s2 = el("span", mapOf("class" to "b"), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex}.a{flex-grow:1}.b{flex-grow:1}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 100.0, 19.2)
        assertBox("s2", r.boxOf(s2), 100.0, 0.0, 100.0, 19.2)
    }

    @Test
    fun flexRowReverse() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;flex-direction:row-reverse}", 200.0)
        assertVal("s1 x", r.boxOf(s1)?.x, 192.0)
        assertVal("s2 x", r.boxOf(s2)?.x, 184.0)
    }

    @Test
    fun flexWrap() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val s3 = el("span", emptyMap(), listOf(tx("c")))
        val dv = el("div", emptyMap(), listOf(s1, s2, s3))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;flex-wrap:wrap}", 20.0)
        assertBox("s3", r.boxOf(s3), 0.0, 19.2, 8.0, 19.2)
        assertVal("div h", r.boxOf(dv)?.height, 38.4)
    }

    @Test
    fun flexColumn() {
        val p1 = el("p", emptyMap(), listOf(tx("a")))
        val p2 = el("p", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(p1, p2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;flex-direction:column}", 200.0)
        assertBox("p1", r.boxOf(p1), 0.0, 0.0, 200.0, 19.2)
        assertBox("p2", r.boxOf(p2), 0.0, 19.2, 200.0, 19.2)
        assertVal("div h", r.boxOf(dv)?.height, 38.4)
    }

    @Test
    fun flexAlignCenter() {
        val s1 = el("span", mapOf("class" to "a"), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex;align-items:center}.a{padding:10px}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 28.0, 39.2)
        assertBox("s2", r.boxOf(s2), 28.0, 10.0, 8.0, 19.2)
    }

    @Test
    fun flexItemMargin() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex}span{margin:4px}", 200.0)
        assertBox("s1", r.boxOf(s1), 4.0, 4.0, 8.0, 19.2)
        assertVal("s2 x", r.boxOf(s2)?.x, 20.0)
    }

    @Test
    fun flexStretch() {
        val s1 = el("span", mapOf("class" to "a"), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:flex}.a{padding:10px}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 28.0, 39.2)
        assertBox("s2", r.boxOf(s2), 28.0, 0.0, 8.0, 39.2)
    }

    @Test
    fun gridDosColumnas() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:50px 50px}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 50.0, 19.2)
        assertBox("s2", r.boxOf(s2), 50.0, 0.0, 50.0, 19.2)
    }

    @Test
    fun gridFr() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:1fr 1fr}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 100.0, 19.2)
        assertBox("s2", r.boxOf(s2), 100.0, 0.0, 100.0, 19.2)
    }

    @Test
    fun gridAuto() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:auto auto}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 8.0, 19.2)
        assertBox("s2", r.boxOf(s2), 8.0, 0.0, 8.0, 19.2)
    }

    @Test
    fun gridPct() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:50% 25%}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 100.0, 19.2)
        assertBox("s2", r.boxOf(s2), 100.0, 0.0, 50.0, 19.2)
    }

    @Test
    fun gridGap() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val s2 = el("span", emptyMap(), listOf(tx("b")))
        val dv = el("div", emptyMap(), listOf(s1, s2))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:1fr 1fr;gap:10px}", 200.0)
        assertBox("s1", r.boxOf(s1), 0.0, 0.0, 95.0, 19.2)
        assertBox("s2", r.boxOf(s2), 105.0, 0.0, 95.0, 19.2)
    }

    @Test
    fun gridItemMargin() {
        val s1 = el("span", emptyMap(), listOf(tx("a")))
        val dv = el("div", emptyMap(), listOf(s1))
        val r = lay(doc(el("body", emptyMap(), listOf(dv))), "div{display:grid;grid-template-columns:1fr}span{margin:4px}", 200.0)
        assertBox("s1", r.boxOf(s1), 4.0, 4.0, 192.0, 19.2)
    }

    @Test
    fun mediaMinWidthAplica() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media (min-width: 100px) {p{margin-top:20px}}", 200.0)
        assertVal("p y", r.boxOf(p)?.y, 20.0)
    }

    @Test
    fun mediaMinWidthNoAplica() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media (min-width: 100px) {p{margin-top:20px}}", 50.0)
        assertVal("p y", r.boxOf(p)?.y, 10.0)
    }

    @Test
    fun mediaMaxWidthAplica() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media (max-width: 100px) {p{margin-top:20px}}", 50.0)
        assertVal("p y", r.boxOf(p)?.y, 20.0)
    }

    @Test
    fun mediaMaxWidthNoAplica() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media (max-width: 100px) {p{margin-top:20px}}", 150.0)
        assertVal("p y", r.boxOf(p)?.y, 10.0)
    }

    @Test
    fun mediaCombinada() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val css = "p{margin-top:10px}@media (min-width: 50px) and (max-width: 100px) {p{margin-top:20px}}"
        val r1 = layVw(doc(el("body", emptyMap(), listOf(p))), css, 75.0)
        assertVal("p y 75", r1.boxOf(p)?.y, 20.0)
        val r2 = layVw(doc(el("body", emptyMap(), listOf(p))), css, 200.0)
        assertVal("p y 200", r2.boxOf(p)?.y, 10.0)
    }

    @Test
    fun mediaTipoScreenAplica() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media screen and (min-width: 100px) {p{margin-top:20px}}", 200.0)
        assertVal("p y", r.boxOf(p)?.y, 20.0)
    }

    @Test
    fun mediaTipoPrintNoCasa() {
        val p = el("p", emptyMap(), listOf(tx("a")))
        val r = layVw(doc(el("body", emptyMap(), listOf(p))), "p{margin-top:10px}@media print and (min-width: 100px) {p{margin-top:20px}}", 200.0)
        assertVal("p y", r.boxOf(p)?.y, 10.0)
    }
}
