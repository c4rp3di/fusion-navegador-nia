package nia.engine.html

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del tree builder HTML (T012 v2): espejo del gemelo TS validado
 * (45 casos con snapshot exacto del arbol via DomPretty y conteo exacto
 * de errores de parseo).
 */
class HtmlTreeBuilderTest {

    private fun arbol(input: String): String = DomPretty.stringify(HtmlTreeBuilder.parse(input).document)

    private fun errores(input: String): Int = HtmlTreeBuilder.parse(input).parseErrors.size

    @Test
    fun documentoVacio() {
        assertEquals("(document (html (head) (body)))", arbol(""))
        assertEquals(0, errores(""))
    }

    @Test
    fun textoSolo() {
        assertEquals("(document (html (head) (body \"hola\")))", arbol("hola"))
        assertEquals(0, errores("hola"))
    }

    @Test
    fun docCompleto() {
        assertEquals("(document (doctype html) (html (head (title \"H\")) (body (p \"hola\"))))", arbol("<!doctype html><html><head><title>H</title></head><body><p>hola</p></body></html>"))
        assertEquals(0, errores("<!doctype html><html><head><title>H</title></head><body><p>hola</p></body></html>"))
    }

    @Test
    fun headImplicito() {
        assertEquals("(document (html (head (title \"T\")) (body \"x\")))", arbol("<title>T</title>x"))
        assertEquals(0, errores("<title>T</title>x"))
    }

    @Test
    fun bodyDuplicado() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("<body><body>x"))
        assertEquals(1, errores("<body><body>x"))
    }

    @Test
    fun htmlDuplicado() {
        assertEquals("(document (html a=\"1\" b=\"2\" (head) (body \"x\")))", arbol("<html a=1><html b=2>x"))
        assertEquals(1, errores("<html a=1><html b=2>x"))
    }

    @Test
    fun comentarioEnHead() {
        assertEquals("(document (html (head (comment \"c\")) (body \"x\")))", arbol("<head><!--c--></head>x"))
        assertEquals(0, errores("<head><!--c--></head>x"))
    }

    @Test
    fun doctypeTardio() {
        assertEquals("(document (html (head) (body (p \"a\"))))", arbol("<p>a<!doctype html>"))
        assertEquals(2, errores("<p>a<!doctype html>"))
    }

    @Test
    fun eofConPAbierto() {
        assertEquals("(document (html (head) (body (p \"a\"))))", arbol("<p>a"))
        assertEquals(1, errores("<p>a"))
    }

    @Test
    fun divAnidadoCierreImplicito() {
        assertEquals("(document (html (head) (body (div (p \"x\") (div \"y\")))))", arbol("<div><p>x<div>y</div>"))
        assertEquals(1, errores("<div><p>x<div>y</div>"))
    }

    @Test
    fun endTagSinApertura() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("</div>x"))
        assertEquals(1, errores("</div>x"))
    }

    @Test
    fun endBodyConPAbierto() {
        assertEquals("(document (html (head) (body (p \"a\"))))", arbol("<p>a</body>"))
        assertEquals(0, errores("<p>a</body>"))
    }

    @Test
    fun endBodyConBAbierto() {
        assertEquals("(document (html (head) (body (b \"x\"))))", arbol("<b>x</body>"))
        assertEquals(1, errores("<b>x</body>"))
    }

    @Test
    fun h1() {
        assertEquals("(document (html (head) (body (h1 \"T\"))))", arbol("<h1>T</h1>"))
        assertEquals(0, errores("<h1>T</h1>"))
    }

    @Test
    fun headingsAnidados() {
        assertEquals("(document (html (head) (body (h1 \"a\") (h2 \"b\"))))", arbol("<h1>a<h2>b"))
        assertEquals(2, errores("<h1>a<h2>b"))
    }

    @Test
    fun pCerradoPorHeading() {
        assertEquals("(document (html (head) (body (p \"a\") (h1 \"b\"))))", arbol("<p>a<h1>b</h1>"))
        assertEquals(0, errores("<p>a<h1>b</h1>"))
    }

    @Test
    fun liHermanos() {
        assertEquals("(document (html (head) (body (ul (li \"a\") (li \"b\")))))", arbol("<ul><li>a<li>b</ul>"))
        assertEquals(0, errores("<ul><li>a<li>b</ul>"))
    }

    @Test
    fun dl() {
        assertEquals("(document (html (head) (body (dl (dt \"a\") (dd \"b\")))))", arbol("<dl><dt>a<dd>b</dl>"))
        assertEquals(0, errores("<dl><dt>a<dd>b</dl>"))
    }

    @Test
    fun voids() {
        assertEquals("(document (html (head) (body \"a\" (br) \"b\" (img src=\"x\") (hr))))", arbol("a<br>b<img src=x><hr>"))
        assertEquals(0, errores("a<br>b<img src=x><hr>"))
    }

    @Test
    fun selfClosingInvalido() {
        assertEquals("(document (html (head) (body (div \"x\"))))", arbol("<div/>x"))
        assertEquals(2, errores("<div/>x"))
    }

    @Test
    fun inlineAnidado() {
        assertEquals("(document (html (head) (body (b (i \"x\")))))", arbol("<b><i>x</b></i>"))
        assertEquals(2, errores("<b><i>x</b></i>"))
    }

    @Test
    fun mergeTexto() {
        assertEquals("(document (html (head) (body \"ab\")))", arbol("a</>b"))
        assertEquals(1, errores("a</>b"))
    }

    @Test
    fun tablaV1() {
        assertEquals("(document (html (head) (body (table (tr (td \"x\"))))))", arbol("<table><tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table><tr><td>x</td></tr></table>"))
    }

    @Test
    fun scriptEnHead() {
        assertEquals("(document (html (head (script \"var a = 1;\")) (body)))", arbol("<script>var a = 1;</script>"))
        assertEquals(0, errores("<script>var a = 1;</script>"))
    }

    @Test
    fun titleYP() {
        assertEquals("(document (html (head (title \"T\")) (body (p \"x\"))))", arbol("<title>T</title><p>x"))
        assertEquals(1, errores("<title>T</title><p>x"))
    }

    @Test
    fun wsInicial() {
        assertEquals("(document (html (head) (body \"   x\")))", arbol("   x"))
        assertEquals(0, errores("   x"))
    }

    @Test
    fun wsEnP() {
        assertEquals("(document (html (head) (body (p \"  \"))))", arbol("<p>  </p>"))
        assertEquals(0, errores("<p>  </p>"))
    }

    @Test
    fun eofFormatoAbierto() {
        assertEquals("(document (html (head) (body)))", arbol("<div x=\"1"))
        assertEquals(1, errores("<div x=\"1"))
    }

    @Test
    fun comentarioTrasBody() {
        assertEquals("(document (html (head) (body \"x\") (comment \"c\")))", arbol("<body>x</body><!--c-->"))
        assertEquals(0, errores("<body>x</body><!--c-->"))
    }

    @Test
    fun textoTrasBody() {
        assertEquals("(document (html (head) (body \"xy\")))", arbol("<body>x</body>y"))
        assertEquals(1, errores("<body>x</body>y"))
    }

    @Test
    fun endHtmlYTexto() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("<html></html>x"))
        assertEquals(1, errores("<html></html>x"))
    }

    @Test
    fun ulAnidado() {
        assertEquals("(document (html (head) (body (ul (li \"a\" (ul (li \"b\")))))))", arbol("<ul><li>a<ul><li>b</ul></ul>"))
        assertEquals(0, errores("<ul><li>a<ul><li>b</ul></ul>"))
    }

    @Test
    fun atributos() {
        assertEquals("(document (html (head) (body (div id=\"a\" class=\"b\"))))", arbol("<div id=\"a\" class=b>"))
        assertEquals(1, errores("<div id=\"a\" class=b>"))
    }

    @Test
    fun dupAttrsEnArbol() {
        assertEquals("(document (html (head) (body (p a=\"1\" \"x\"))))", arbol("<p a=1 a=2>x"))
        assertEquals(2, errores("<p a=1 a=2>x"))
    }

    @Test
    fun mismatch() {
        assertEquals("(document (html (head) (body (p (b \"x\")))))", arbol("<p><b>x</p>"))
        assertEquals(0, errores("<p><b>x</p>"))
    }

    @Test
    fun doctypeNull() {
        assertEquals("(document (doctype) (html (head) (body (p \"a\"))))", arbol("<!doctype><p>a"))
        assertEquals(2, errores("<!doctype><p>a"))
    }

    @Test
    fun textoAntesDeDoctype() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("x<!doctype html>"))
        assertEquals(1, errores("x<!doctype html>"))
    }

    @Test
    fun enfasis() {
        assertEquals("(document (html (head) (body (em (strong \"x\")))))", arbol("<em><strong>x</strong></em>"))
        assertEquals(0, errores("<em><strong>x</strong></em>"))
    }

    @Test
    fun spanConTexto() {
        assertEquals("(document (html (head) (body (span class=\"a b\" \"x\"))))", arbol("<span class=\"a b\">x</span>"))
        assertEquals(0, errores("<span class=\"a b\">x</span>"))
    }

    @Test
    fun textareaRawtext() {
        assertEquals("(document (html (head (textarea \"a<b\")) (body)))", arbol("<textarea>a<b</textarea>"))
        assertEquals(0, errores("<textarea>a<b</textarea>"))
    }

    @Test
    fun styleEnHead() {
        assertEquals("(document (html (head (style \"p{color:red}\")) (body)))", arbol("<style>p{color:red}</style>"))
        assertEquals(0, errores("<style>p{color:red}</style>"))
    }

    @Test
    fun formConInput() {
        assertEquals("(document (html (head) (body (form (input type=\"text\")))))", arbol("<form><input type=text></form>"))
        assertEquals(0, errores("<form><input type=text></form>"))
    }

    @Test
    fun pPorP() {
        assertEquals("(document (html (head) (body (p \"a\") (p \"b\"))))", arbol("<p>a<p>b"))
        assertEquals(1, errores("<p>a<p>b"))
    }

    @Test
    fun metaEnHead() {
        assertEquals("(document (html (head (meta charset=\"utf8\")) (body (p \"x\"))))", arbol("<meta charset=utf8><p>x"))
        assertEquals(1, errores("<meta charset=utf8><p>x"))
    }

    @Test
    fun comentarioAntesDeHtml() {
        assertEquals("(document (comment \"c\") (html (head) (body (p \"x\"))))", arbol("<!--c--><p>x"))
        assertEquals(1, errores("<!--c--><p>x"))
    }
}
