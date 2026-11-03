package nia.engine.css

import nia.engine.html.DomNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests de la cascada CSS v2 (R0023): espejo validado del gemelo TS.
 * v1 (T014) + hermanos adyacente/general, pseudo-clases estaticas,
 * shorthands margin/padding/border-width y unidades em/rem/%.
 */
class CssCascadeTest {

    private fun el(
        name: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<DomNode> = emptyList()
    ): DomNode.Element {
        val e = DomNode.Element(name, attrs)
        e.children.addAll(children)
        return e
    }

    private fun doc(vararg children: DomNode): DomNode.Document {
        val d = DomNode.Document()
        d.children.addAll(children)
        return d
    }

    private fun css(root: DomNode, text: String): CssComputedStyle =
        CssCascade.compute(root, CssStylesheet.parse(text))

    @Test
    fun tipoAplicaYTextoEsNull() {
        val t = DomNode.Text("x")
        val p = el("p", emptyMap(), listOf(t))
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{color:red}")
        assertEquals("red", c.propertyOf(p, "color"), "color p")
        assertEquals(null, c.propertyOf(t, "color"), "texto null")
        assertEquals(null, c.propertyOf(p, "nope"), "prop inexistente")
    }

    @Test
    fun descendiente() {
        val p = el("p")
        val div = el("div", emptyMap(), listOf(p))
        val body = el("body", emptyMap(), listOf(div))
        val d = doc(body)
        assertEquals("red", css(d, "body p{color:red}").propertyOf(p, "color"), "desc")
    }

    @Test
    fun hijoDirecto() {
        val p = el("p")
        val div = el("div", emptyMap(), listOf(p))
        val body = el("body", emptyMap(), listOf(div))
        val d = doc(body)
        assertEquals("#000000", css(d, "body>p{color:red}").propertyOf(p, "color"), "body>p no")
        assertEquals("red", css(d, "div>p{color:red}").propertyOf(p, "color"), "div>p si")
        assertEquals("red", css(d, "body>div>p{color:red}").propertyOf(p, "color"), "cadena")
    }

    @Test
    fun clase() {
        val e = el("em", mapOf("class" to "a x b"))
        val d = doc(el("body", emptyMap(), listOf(e)))
        assertEquals("red", css(d, ".x{color:red}").propertyOf(e, "color"), "clase si")
        assertEquals("#000000", css(d, ".y{color:red}").propertyOf(e, "color"), "clase no")
    }

    @Test
    fun atributos() {
        val e = el("em", mapOf("title" to "t"))
        val d = doc(el("body", emptyMap(), listOf(e)))
        assertEquals("red", css(d, "[title]{color:red}").propertyOf(e, "color"), "presencia")
        assertEquals("red", css(d, "[title=t]{color:red}").propertyOf(e, "color"), "valor")
        assertEquals("#000000", css(d, "[title=z]{color:red}").propertyOf(e, "color"), "valor no")
    }

    @Test
    fun idGana() {
        val p = el("p", mapOf("id" to "z"))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("blue", css(d, "p{color:red}#z{color:blue}").propertyOf(p, "color"), "id gana")
    }

    @Test
    fun posteriorGana() {
        val p = el("p")
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("blue", css(d, "p{color:red}p{color:blue}").propertyOf(p, "color"), "posterior")
    }

    @Test
    fun herencia() {
        val p = el("p")
        val body = el("body", emptyMap(), listOf(p))
        val d = doc(body)
        val c = css(d, "body{color:red;background-color:gray}")
        assertEquals("red", c.propertyOf(p, "color"), "hereda color")
        assertEquals("transparent", c.propertyOf(p, "background-color"), "no hereda bg")
        assertEquals("gray", c.propertyOf(body, "background-color"), "body bg")
    }

    @Test
    fun propioSobreHeredado() {
        val p = el("p")
        val div = el("div", emptyMap(), listOf(p))
        val d = doc(el("body", emptyMap(), listOf(div)))
        val c = css(d, "body{color:red}p{color:blue}")
        assertEquals("blue", c.propertyOf(p, "color"), "p blue")
        assertEquals("red", c.propertyOf(div, "color"), "div red")
    }

    @Test
    fun defaults() {
        val p = el("p")
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "")
        assertEquals("#000000", c.propertyOf(p, "color"), "color")
        assertEquals("16px", c.propertyOf(p, "font-size"), "size")
        assertEquals("transparent", c.propertyOf(p, "background-color"), "bg")
        assertEquals(0, CssStylesheet.parse("").rules.size, "sin reglas")
    }

    @Test
    fun universal() {
        val p = el("p")
        val div = el("div")
        val d = doc(el("body", emptyMap(), listOf(p, div)))
        val c = css(d, "*{color:green}p{color:red}")
        assertEquals("red", c.propertyOf(p, "color"), "p red")
        assertEquals("green", c.propertyOf(div, "color"), "div green")
    }

    @Test
    fun agrupacionAplicaAmbos() {
        val h1 = el("h1")
        val h2 = el("h2")
        val d = doc(el("body", emptyMap(), listOf(h1, h2)))
        val c = css(d, "h1,h2{color:red}")
        assertEquals("red", c.propertyOf(h1, "color"), "h1")
        assertEquals("red", c.propertyOf(h2, "color"), "h2")
    }

    @Test
    fun acumulaDeclaraciones() {
        val p = el("p")
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{color:red}p{font-size:9px}")
        assertEquals("red", c.propertyOf(p, "color"), "color")
        assertEquals("9px", c.propertyOf(p, "font-size"), "size")
    }

    @Test
    fun tipoCaseInsensitive() {
        val dv = el("div")
        val d = doc(el("body", emptyMap(), listOf(dv)))
        assertEquals("red", css(d, "DIV{color:red}").propertyOf(dv, "color"), "DIV")
    }

    @Test
    fun attrSimbolo() {
        val a = el("p", mapOf("lang" to "en es"))
        val b = el("p", mapOf("lang" to "enn"))
        val d = doc(el("body", emptyMap(), listOf(a, b)))
        val c = css(d, "[lang~=en]{color:red}")
        assertEquals("red", c.propertyOf(a, "color"), "si")
        assertEquals("#000000", c.propertyOf(b, "color"), "no")
    }

    @Test
    fun heredaDosNiveles() {
        val p = el("p")
        val div = el("div", emptyMap(), listOf(p))
        val d = doc(el("body", emptyMap(), listOf(div)))
        assertEquals("italic", css(d, "body{font-style:italic}").propertyOf(p, "font-style"), "italic")
    }

    @Test
    fun claseSobreTipos() {
        val p = el("p", mapOf("class" to "c"))
        val div = el("div", emptyMap(), listOf(p))
        val d = doc(el("body", emptyMap(), listOf(div)))
        assertEquals("blue", css(d, "div p{color:red}.c{color:blue}").propertyOf(p, "color"), "clase gana")
    }

    @Test
    fun empateEspecificidad() {
        val p = el("p", mapOf("x" to "1", "class" to "a"))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("blue", css(d, "p[x]{color:red}p.a{color:blue}").propertyOf(p, "color"), "posterior")
        assertEquals("red", css(d, "p.a{color:blue}p[x]{color:red}").propertyOf(p, "color"), "anterior")
    }

    @Test
    fun listaSelectores() {
        val b = el("p", mapOf("class" to "b"))
        val d = doc(el("body", emptyMap(), listOf(b)))
        assertEquals("red", css(d, ".a,.b{color:red}").propertyOf(b, "color"), "lista")
    }

    @Test
    fun idTempranoGana() {
        val p = el("p", mapOf("id" to "z"))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("red", css(d, "#z{color:red}p{color:blue}").propertyOf(p, "color"), "id temprano")
    }

    @Test
    fun hermanosAdyacentes() {
        val p1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val p2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val p3 = el("p", emptyMap(), listOf(DomNode.Text("c")))
        val d = doc(el("body", emptyMap(), listOf(p1, p2, p3)))
        val c = css(d, "p+p{color:red}")
        assertEquals("#000000", c.propertyOf(p1, "color"), "p1 no")
        assertEquals("red", c.propertyOf(p2, "color"), "p2 si")
        assertEquals("red", c.propertyOf(p3, "color"), "p3 si")
    }

    @Test
    fun hermanosGenerales() {
        val h = el("h1", emptyMap(), listOf(DomNode.Text("t")))
        val q1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val q2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val d = doc(el("body", emptyMap(), listOf(h, q1, q2)))
        val c = css(d, "h1~p{color:red}")
        assertEquals("red", c.propertyOf(q1, "color"), "p1")
        assertEquals("red", c.propertyOf(q2, "color"), "p2")
        assertEquals("#000000", c.propertyOf(h, "color"), "h1 no")
    }

    @Test
    fun adyacenteNoSaltaElementos() {
        val p1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val dv = el("div", emptyMap(), listOf(el("p", emptyMap(), listOf(DomNode.Text("x")))))
        val p2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val d = doc(el("body", emptyMap(), listOf(p1, dv, p2)))
        assertEquals("#000000", css(d, "p+p{color:red}").propertyOf(p2, "color"), "adj con div intermedio no")
        assertEquals("red", css(d, "p~p{color:red}").propertyOf(p2, "color"), "general si salta")
    }

    @Test
    fun primerHijo() {
        val p1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val p2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val p3 = el("p", emptyMap(), listOf(DomNode.Text("c")))
        val d = doc(el("body", emptyMap(), listOf(p1, p2, p3)))
        val c = css(d, "p:first-child{color:red}")
        assertEquals("red", c.propertyOf(p1, "color"), "p1")
        assertEquals("#000000", c.propertyOf(p2, "color"), "p2 no")
    }

    @Test
    fun ultimoHijo() {
        val p1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val p2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val p3 = el("p", emptyMap(), listOf(DomNode.Text("c")))
        val d = doc(el("body", emptyMap(), listOf(p1, p2, p3)))
        val c = css(d, "p:last-child{color:red}")
        assertEquals("red", c.propertyOf(p3, "color"), "p3")
        assertEquals("#000000", c.propertyOf(p1, "color"), "p1 no")
    }

    @Test
    fun hijoUnico() {
        val solo = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d1 = doc(el("body", emptyMap(), listOf(solo)))
        assertEquals("red", css(d1, "p:only-child{color:red}").propertyOf(solo, "color"), "unico")
        val o1 = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val o2 = el("p", emptyMap(), listOf(DomNode.Text("b")))
        val d2 = doc(el("body", emptyMap(), listOf(o1, o2)))
        assertEquals("#000000", css(d2, "p:only-child{color:red}").propertyOf(o1, "color"), "con hermano no")
    }

    @Test
    fun nthImpares() {
        val ps = (1..4).map { el("p", emptyMap(), listOf(DomNode.Text(it.toString()))) }
        val d = doc(el("body", emptyMap(), ps))
        val c = css(d, "p:nth-child(2n+1){color:red}")
        assertEquals("red", c.propertyOf(ps[0], "color"), "p1")
        assertEquals("#000000", c.propertyOf(ps[1], "color"), "p2 no")
        assertEquals("red", c.propertyOf(ps[2], "color"), "p3")
        assertEquals("#000000", c.propertyOf(ps[3], "color"), "p4 no")
    }

    @Test
    fun nthExacto() {
        val ps = (1..4).map { el("p", emptyMap(), listOf(DomNode.Text(it.toString()))) }
        val d = doc(el("body", emptyMap(), ps))
        val c = css(d, "p:nth-child(3){color:red}")
        assertEquals("#000000", c.propertyOf(ps[0], "color"), "p1 no")
        assertEquals("red", c.propertyOf(ps[2], "color"), "p3")
    }

    @Test
    fun nthNegativo() {
        val ps = (1..4).map { el("p", emptyMap(), listOf(DomNode.Text(it.toString()))) }
        val d = doc(el("body", emptyMap(), ps))
        val c = css(d, "p:nth-child(-n+2){color:red}")
        assertEquals("red", c.propertyOf(ps[0], "color"), "p1")
        assertEquals("red", c.propertyOf(ps[1], "color"), "p2")
        assertEquals("#000000", c.propertyOf(ps[2], "color"), "p3 no")
    }

    @Test
    fun shorthandMargin() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{margin:10px 5px}")
        assertEquals("10px", c.propertyOf(p, "margin-top"), "top")
        assertEquals("5px", c.propertyOf(p, "margin-right"), "right")
        assertEquals("10px", c.propertyOf(p, "margin-bottom"), "bottom")
        assertEquals("5px", c.propertyOf(p, "margin-left"), "left")
    }

    @Test
    fun shorthandPadding() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{padding:1px 2px 3px}")
        assertEquals("1px", c.propertyOf(p, "padding-top"), "top")
        assertEquals("2px", c.propertyOf(p, "padding-right"), "right")
        assertEquals("3px", c.propertyOf(p, "padding-bottom"), "bottom")
        assertEquals("2px", c.propertyOf(p, "padding-left"), "left")
    }

    @Test
    fun shorthandBorderWidth() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{border-width:4px}")
        assertEquals("4px", c.propertyOf(p, "border-top-width"), "top")
        assertEquals("4px", c.propertyOf(p, "border-left-width"), "left")
    }

    @Test
    fun shorthandAutoDescartado() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("0px", css(d, "p{margin:auto}").propertyOf(p, "margin-top"), "auto no vale")
    }

    @Test
    fun shorthandCero() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("0px", css(d, "p{margin:0}").propertyOf(p, "margin-top"), "cero")
    }

    @Test
    fun fontSizeEm() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("16.0px", css(d, "body{font-size:32px}p{font-size:0.5em}").propertyOf(p, "font-size"), "em")
    }

    @Test
    fun fontSizeEmGrande() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("48.0px", css(d, "body{font-size:32px}p{font-size:1.5em}").propertyOf(p, "font-size"), "em grande")
    }

    @Test
    fun fontSizePct() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("16.0px", css(d, "body{font-size:32px}p{font-size:50%}").propertyOf(p, "font-size"), "pct")
    }

    @Test
    fun fontSizeRem() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val b = el("body", emptyMap(), listOf(p))
        val h = el("html", emptyMap(), listOf(b))
        val d = doc(h)
        assertEquals("30.0px", css(d, "html{font-size:20px}p{font-size:1.5rem}").propertyOf(p, "font-size"), "rem")
    }

    @Test
    fun lineHeightEm() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("24.0px", css(d, "p{font-size:16px;line-height:1.5em}").propertyOf(p, "line-height"), "em")
    }

    @Test
    fun lineHeightNumero() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("24.0px", css(d, "p{line-height:1.5}").propertyOf(p, "line-height"), "numero")
    }

    @Test
    fun letterSpacingEm() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("8.0px", css(d, "body{font-size:32px}p{letter-spacing:0.25em}").propertyOf(p, "letter-spacing"), "em")
    }

    @Test
    fun marginEm() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("16.0px", css(d, "p{font-size:32px;margin:0.5em}").propertyOf(p, "margin-top"), "em")
    }

    @Test
    fun marginPctSeQueda() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("5%", css(d, "p{margin:5%}").propertyOf(p, "margin-top"), "pct se queda")
    }

    @Test
    fun paddingRem() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("32.0px", css(d, "p{padding:2rem}").propertyOf(p, "padding-top"), "rem")
    }

    @Test
    fun remConRaiz() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val b = el("body", emptyMap(), listOf(p))
        val h = el("html", emptyMap(), listOf(b))
        val d = doc(h)
        assertEquals("20.0px", css(d, "html{font-size:20px}p{margin:1rem}").propertyOf(p, "margin-top"), "rem raiz")
    }

    @Test
    fun marginNoHereda() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("0px", css(d, "body{margin:10px}").propertyOf(p, "margin-top"), "no hereda")
    }

    @Test
    fun emCompuesto() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val dv = el("div", emptyMap(), listOf(p))
        val d = doc(el("body", emptyMap(), listOf(dv)))
        val c = css(d, "body{font-size:32px}div{font-size:0.5em}p{font-size:0.5em}")
        assertEquals("16.0px", c.propertyOf(dv, "font-size"), "div")
        assertEquals("8.0px", c.propertyOf(p, "font-size"), "p")
    }

    @Test
    fun borderEm() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        val c = css(d, "p{font-size:32px;border-width:0.25em}")
        assertEquals("8.0px", c.propertyOf(p, "border-top-width"), "top")
        assertEquals("8.0px", c.propertyOf(p, "border-left-width"), "left")
    }

    @Test
    fun paddingPctSeQueda() {
        val p = el("p", emptyMap(), listOf(DomNode.Text("a")))
        val d = doc(el("body", emptyMap(), listOf(p)))
        assertEquals("10%", css(d, "p{padding:10%}").propertyOf(p, "padding-top"), "pct se queda")
    }
}
