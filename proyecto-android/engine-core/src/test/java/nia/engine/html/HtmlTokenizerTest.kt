package nia.engine.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlTokenizerTest {

    private fun tokens(input: String): List<HtmlToken> = HtmlTokenizer.tokenize(input).tokens

    private fun parseErrors(input: String): List<String> = HtmlTokenizer.tokenize(input).parseErrors

    @Test
    fun textoSimple() {
        assertEquals(listOf(HtmlToken.Data("hola"), HtmlToken.Eof), tokens("hola"))
        assertTrue(parseErrors("hola").isEmpty())
    }

    @Test
    fun etiquetaConTexto() {
        assertEquals(
            listOf(HtmlToken.StartTag("b", emptyMap(), false), HtmlToken.Data("x"), HtmlToken.EndTag("b"), HtmlToken.Eof),
            tokens("<b>x</b>")
        )
    }

    @Test
    fun nombresMinusculas() {
        assertEquals(
            listOf(
                HtmlToken.StartTag("div", mapOf("class" to "x"), false),
                HtmlToken.Data("h"),
                HtmlToken.EndTag("div"),
                HtmlToken.Eof
            ),
            tokens("<DIV CLASS='x'>h</DIV>")
        )
    }

    @Test
    fun atributosFormas() {
        assertEquals(
            listOf(
                HtmlToken.StartTag(
                    "input",
                    mapOf("type" to "checkbox", "name" to "a", "disabled" to ""),
                    false
                ),
                HtmlToken.Eof
            ),
            tokens("<input type=checkbox name=a disabled>")
        )
    }

    @Test
    fun atributosComillas() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("href" to "x y", "title" to "t"), false), HtmlToken.Eof),
            tokens("<a href=\"x y\" title='t'>")
        )
    }

    @Test
    fun espaciosEntreAtributos() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("href" to "x", "hidden" to ""), false), HtmlToken.Eof),
            tokens("<a href=\"x\"  hidden >")
        )
    }

    @Test
    fun igualConEspaciosQuirkWhatwg() {
        // Quirk documentado del algoritmo WHATWG: '=' tras espacios abre un
        // atributo cuyo nombre es literalmente "=".
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("href" to "", "=" to "", "x" to ""), false), HtmlToken.Eof),
            tokens("<a href = x >")
        )
        assertTrue(parseErrors("<a href = x >").isNotEmpty())
    }

    @Test
    fun selfClosing() {
        assertEquals(listOf(HtmlToken.StartTag("br", emptyMap(), true), HtmlToken.Eof), tokens("<br/>"))
    }

    @Test
    fun selfClosingConEspacio() {
        assertEquals(listOf(HtmlToken.StartTag("b", emptyMap(), true), HtmlToken.Eof), tokens("<b />"))
    }

    @Test
    fun comentario() {
        assertEquals(listOf(HtmlToken.Comment(" hola "), HtmlToken.Eof), tokens("<!-- hola -->"))
        assertTrue(parseErrors("<!-- hola -->").isEmpty())
    }

    @Test
    fun comentarioGuionExtra() {
        // "--->" cierra con un guion colgante y parse error abrupt-closing.
        assertEquals(listOf(HtmlToken.Comment(" x -"), HtmlToken.Eof), tokens("<!-- x --->"))
        assertTrue(parseErrors("<!-- x --->").isNotEmpty())
    }

    @Test
    fun comentarioConBangEnMedio() {
        assertEquals(listOf(HtmlToken.Comment("x--! y"), HtmlToken.Eof), tokens("<!--x--! y-->"))
        assertTrue(parseErrors("<!--x--! y-->").isEmpty())
    }

    @Test
    fun comentarioBangCerrado() {
        assertEquals(listOf(HtmlToken.Comment("x"), HtmlToken.Eof), tokens("<!--x--!>"))
        assertTrue(parseErrors("<!--x--!>").isNotEmpty())
    }

    @Test
    fun comentarioVacio() {
        assertEquals(listOf(HtmlToken.Comment(""), HtmlToken.Eof), tokens("<!-->"))
        assertTrue(parseErrors("<!-->").isNotEmpty())
    }

    @Test
    fun comentarioTresGuiones() {
        assertEquals(listOf(HtmlToken.Comment(""), HtmlToken.Eof), tokens("<!--->"))
        assertTrue(parseErrors("<!--->").isNotEmpty())
    }

    @Test
    fun comentarioSinCerrar() {
        assertEquals(listOf(HtmlToken.Comment("x"), HtmlToken.Eof), tokens("<!--x"))
        assertTrue(parseErrors("<!--x").isNotEmpty())
    }

    @Test
    fun doctype() {
        assertEquals(listOf(HtmlToken.Doctype("html", false), HtmlToken.Eof), tokens("<!DOCTYPE html>"))
    }

    @Test
    fun doctypeSinNombre() {
        assertEquals(listOf(HtmlToken.Doctype(null, true), HtmlToken.Eof), tokens("<!DOCTYPE>"))
        assertTrue(parseErrors("<!DOCTYPE>").isNotEmpty())
    }

    @Test
    fun doctypeConIds() {
        assertEquals(listOf(HtmlToken.Doctype("html", false), HtmlToken.Eof), tokens("<!DOCTYPE html PUBLIC \"x\" \"y\">"))
    }

    @Test
    fun doctypeMayusculas() {
        assertEquals(listOf(HtmlToken.Doctype("html", false), HtmlToken.Eof), tokens("<!doctype HTML>"))
    }

    @Test
    fun cierreConAtributos() {
        assertEquals(listOf(HtmlToken.EndTag("b"), HtmlToken.Eof), tokens("</b class=\"x\">"))
        assertTrue(parseErrors("</b class=\"x\">").isEmpty())
    }

    @Test
    fun cierreAutoCerrado() {
        assertEquals(listOf(HtmlToken.EndTag("b"), HtmlToken.Eof), tokens("</b/>"))
        assertTrue(parseErrors("</b/>").isNotEmpty())
    }

    @Test
    fun menorSuelto() {
        assertEquals(listOf(HtmlToken.Data("a < b"), HtmlToken.Eof), tokens("a < b"))
        assertTrue(parseErrors("a < b").isNotEmpty())
    }

    @Test
    fun menorAlFinal() {
        assertEquals(listOf(HtmlToken.Data("a<"), HtmlToken.Eof), tokens("a<"))
        assertTrue(parseErrors("a<").isNotEmpty())
    }

    @Test
    fun cierreSinNombre() {
        assertEquals(listOf(HtmlToken.Data("a"), HtmlToken.Data("b"), HtmlToken.Eof), tokens("a</>b"))
        assertTrue(parseErrors("a</>b").isNotEmpty())
    }

    @Test
    fun bogusQuestion() {
        assertEquals(listOf(HtmlToken.Comment("?xml version='1.0'?"), HtmlToken.Eof), tokens("<?xml version='1.0'?>"))
        assertTrue(parseErrors("<?xml version='1.0'?>").isNotEmpty())
    }

    @Test
    fun bogusBang() {
        assertEquals(listOf(HtmlToken.Comment("bogus"), HtmlToken.Eof), tokens("<!bogus>"))
        assertTrue(parseErrors("<!bogus>").isNotEmpty())
    }

    @Test
    fun cdataSeDegradaAComentario() {
        assertEquals(listOf(HtmlToken.Comment("x]]"), HtmlToken.Eof), tokens("<![CDATA[x]]>"))
        assertTrue(parseErrors("<![CDATA[x]]>").isNotEmpty())
    }

    @Test
    fun eofEnEtiqueta() {
        assertEquals(listOf(HtmlToken.Eof), tokens("<div x=\"1"))
        assertTrue(parseErrors("<div x=\"1").isNotEmpty())
    }

    @Test
    fun eofEnNombre() {
        assertEquals(listOf(HtmlToken.Eof), tokens("<div"))
        assertTrue(parseErrors("<div").isNotEmpty())
    }

    @Test
    fun atributoDuplicado() {
        assertEquals(
            listOf(HtmlToken.StartTag("p", mapOf("a" to "1"), false), HtmlToken.Eof),
            tokens("<p a=\"1\" a=\"2\">")
        )
        assertTrue(parseErrors("<p a=\"1\" a=\"2\">").isNotEmpty())
    }

    @Test
    fun nulEnTexto() {
        assertEquals(listOf(HtmlToken.Data("ab"), HtmlToken.Eof), tokens("ab"))
        assertTrue(parseErrors("ab").isNotEmpty())
    }

    @Test
    fun nulEnAtributo() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("href" to "x"), false), HtmlToken.Eof),
            tokens("<a href=\"x\">")
        )
        assertTrue(parseErrors("<a href=\"x\">").isNotEmpty())
    }

    @Test
    fun scriptRawtext() {
        assertEquals(
            listOf(
                HtmlToken.StartTag("script", emptyMap(), false),
                HtmlToken.Data("if (a<b) { }"),
                HtmlToken.EndTag("script"),
                HtmlToken.Eof
            ),
            tokens("<script>if (a<b) { }</script>")
        )
    }

    @Test
    fun rawtextSinCerrar() {
        assertEquals(
            listOf(HtmlToken.StartTag("script", emptyMap(), false), HtmlToken.Data("x"), HtmlToken.Eof),
            tokens("<script>x")
        )
        assertTrue(parseErrors("<script>x").isEmpty())
    }

    @Test
    fun rawtextCierreParcial() {
        assertEquals(
            listOf(
                HtmlToken.StartTag("script", emptyMap(), false),
                HtmlToken.Data("a</scr"),
                HtmlToken.EndTag("script"),
                HtmlToken.Eof
            ),
            tokens("<script>a</scr</script>")
        )
    }

    @Test
    fun titleRCDATA() {
        assertEquals(
            listOf(
                HtmlToken.StartTag("title", emptyMap(), false),
                HtmlToken.Data("a<b"),
                HtmlToken.EndTag("title"),
                HtmlToken.Eof
            ),
            tokens("<title>a<b</title>")
        )
    }

    @Test
    fun endTagCaseInsensitive() {
        assertEquals(
            listOf(
                HtmlToken.StartTag("script", emptyMap(), false),
                HtmlToken.Data("x"),
                HtmlToken.EndTag("script"),
                HtmlToken.Data(" "),
                HtmlToken.Eof
            ),
            tokens("<script>x</SCRIPT> ")
        )
    }

    @Test
    fun ampersandLiteralV1() {
        // v1 sin character references: '&' pasa literal.
        assertEquals(listOf(HtmlToken.Data("p & q; &amp;"), HtmlToken.Eof), tokens("p & q; &amp;"))
    }

    @Test
    fun attrValorSinComillasConErrores() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("href" to "x'y"), false), HtmlToken.Data("z"), HtmlToken.Eof),
            tokens("<a href=x'y>z")
        )
        assertTrue(parseErrors("<a href=x'y>z").isNotEmpty())
    }

    @Test
    fun attrSinValorConIgual() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("x" to ""), false), HtmlToken.Eof),
            tokens("<a x=>")
        )
        assertTrue(parseErrors("<a x=>").isNotEmpty())
    }

    @Test
    fun attrNombreConComillas() {
        assertEquals(
            listOf(HtmlToken.StartTag("a", mapOf("a\"b" to "c"), false), HtmlToken.Eof),
            tokens("<a a\"b=c>")
        )
        assertTrue(parseErrors("<a a\"b=c>").isNotEmpty())
    }
}
