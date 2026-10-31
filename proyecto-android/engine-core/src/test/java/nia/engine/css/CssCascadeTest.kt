package nia.engine.css

import nia.engine.html.DomNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests de la cascada CSS (T014 v1): espejo validado del gemelo TS (69/69).
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
}
