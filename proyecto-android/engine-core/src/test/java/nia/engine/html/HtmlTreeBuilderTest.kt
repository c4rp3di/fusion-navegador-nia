package nia.engine.html

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del tree builder HTML (T012 v3): espejo del gemelo TS validado
 * (76 casos con snapshot exacto del arbol via DomPretty y conteo exacto
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
    fun tablaBasica() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"x\")))))))", arbol("<table><tr><td>x</td></tr></table>"))
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

    @Test
    fun tablaTheadTbodyTfoot() {
        assertEquals("(document (html (head) (body (table (thead (tr (th \"H\"))) (tbody (tr (td \"a\"))) (tfoot (tr (td \"f\")))))))", arbol("<table><thead><tr><th>H</th></tr></thead><tbody><tr><td>a</td></tr></tbody><tfoot><tr><td>f</td></tr></tfoot></table>"))
        assertEquals(0, errores("<table><thead><tr><th>H</th></tr></thead><tbody><tr><td>a</td></tr></tbody><tfoot><tr><td>f</td></tr></tfoot></table>"))
    }

    @Test
    fun tablaDosCeldasFila() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"1\") (td \"2\")))))))", arbol("<table><tbody><tr><td>1</td><td>2</td></tr></tbody></table>"))
        assertEquals(0, errores("<table><tbody><tr><td>1</td><td>2</td></tr></tbody></table>"))
    }

    @Test
    fun tablaTdSuelto() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"x\")))))))", arbol("<table><td>x</td></table>"))
        assertEquals(1, errores("<table><td>x</td></table>"))
    }

    @Test
    fun tablaDosFilasUnTbody() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\")) (tr (td \"b\")))))))", arbol("<table><tr><td>a</td></tr><tr><td>b</td></tr></table>"))
        assertEquals(0, errores("<table><tr><td>a</td></tr><tr><td>b</td></tr></table>"))
    }

    @Test
    fun tablaAnidadaEnCelda() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\" (table (tbody (tr (td \"b\")))))))))))", arbol("<table><tr><td>a<table><tr><td>b</td></tr></table></td></tr></table>"))
        assertEquals(0, errores("<table><tr><td>a<table><tr><td>b</td></tr></table></td></tr></table>"))
    }

    @Test
    fun tablaFosterTexto() {
        assertEquals("(document (html (head) (body \"x\" (table))))", arbol("<table>x</table>"))
        assertEquals(1, errores("<table>x</table>"))
    }

    @Test
    fun tablaFosterB() {
        assertEquals("(document (html (head) (body (b \"x\") (table))))", arbol("<table><b>x</b></table>"))
        assertEquals(3, errores("<table><b>x</b></table>"))
    }

    @Test
    fun tablaFosterDivTexto() {
        assertEquals("(document (html (head) (body (div \"a\") (table))))", arbol("<table><div>a</div></table>"))
        assertEquals(3, errores("<table><div>a</div></table>"))
    }

    @Test
    fun tablaWsDentro() {
        assertEquals("(document (html (head) (body (table \" \" (tbody (tr (td \"x\")))))))", arbol("<table> <tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table> <tr><td>x</td></tr></table>"))
    }

    @Test
    fun tablaCaption() {
        assertEquals("(document (html (head) (body (table (caption \"T\") (tbody (tr (td \"x\")))))))", arbol("<table><caption>T</caption><tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table><caption>T</caption><tr><td>x</td></tr></table>"))
    }

    @Test
    fun tablaCaptionConP() {
        assertEquals("(document (html (head) (body (table (caption (p \"a\")) (tbody (tr (td \"b\")))))))", arbol("<table><caption><p>a</p></caption><tr><td>b</td></tr></table>"))
        assertEquals(0, errores("<table><caption><p>a</p></caption><tr><td>b</td></tr></table>"))
    }

    @Test
    fun tablaCaptionEnd() {
        assertEquals("(document (html (head) (body (table (caption \"x\")))))", arbol("<table><caption>x</caption></table>"))
        assertEquals(0, errores("<table><caption>x</caption></table>"))
    }

    @Test
    fun tablaColgroup() {
        assertEquals("(document (html (head) (body (table (colgroup (col) (col)) (tbody (tr (td \"x\")))))))", arbol("<table><colgroup><col><col></colgroup><tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table><colgroup><col><col></colgroup><tr><td>x</td></tr></table>"))
    }

    @Test
    fun tablaColImplicito() {
        assertEquals("(document (html (head) (body (table (colgroup (col)) (tbody (tr (td \"x\")))))))", arbol("<table><col><tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table><col><tr><td>x</td></tr></table>"))
    }

    @Test
    fun tablaEndCierraCeldas() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\")))))))", arbol("<table><tr><td>a</table>"))
        assertEquals(0, errores("<table><tr><td>a</table>"))
    }

    @Test
    fun tablaTdImplicitoSegundo() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\") (td \"b\")))))))", arbol("<table><tr><td>a<td>b</td></tr></table>"))
        assertEquals(0, errores("<table><tr><td>a<td>b</td></tr></table>"))
    }

    @Test
    fun tablaTrImplicitoSegundo() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\")) (tr (td \"b\")))))))", arbol("<table><tr><td>a<tr><td>b</td></tr></table>"))
        assertEquals(1, errores("<table><tr><td>a<tr><td>b</td></tr></table>"))
    }

    @Test
    fun tablaEndTdSuelto() {
        assertEquals("(document (html (head) (body (table))))", arbol("<table></td></table>"))
        assertEquals(1, errores("<table></td></table>"))
    }

    @Test
    fun tablaTrSinCerrarFin() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\")))))))", arbol("<table><tr><td>a</td>"))
        assertEquals(1, errores("<table><tr><td>a</td>"))
    }

    @Test
    fun tablaDosHermanas() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \"a\")))) \"texto\" (table (tbody (tr (td \"b\")))))))", arbol("<table><tr><td>a</td></tr></table>texto<table><tr><td>b</td></tr></table>"))
        assertEquals(0, errores("<table><tr><td>a</td></tr></table>texto<table><tr><td>b</td></tr></table>"))
    }

    @Test
    fun tablaTextoEnCelda() {
        assertEquals("(document (html (head) (body (table (tbody (tr (td \" a \") (td \"b\")))))))", arbol("<table><tr><td> a </td><td>b</td></tr></table>"))
        assertEquals(0, errores("<table><tr><td> a </td><td>b</td></tr></table>"))
    }

    @Test
    fun tablaComentario() {
        assertEquals("(document (html (head) (body (table (comment \"c\") (tbody (tr (td \"x\")))))))", arbol("<table><!--c--><tr><td>x</td></tr></table>"))
        assertEquals(0, errores("<table><!--c--><tr><td>x</td></tr></table>"))
    }

    @Test
    fun hrCierraP() {
        assertEquals("(document (html (head) (body (p \"a\") (hr))))", arbol("<p>a<hr>"))
        assertEquals(0, errores("<p>a<hr>"))
    }

    @Test
    fun tdSueltoEnBody() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("<td>x"))
        assertEquals(1, errores("<td>x"))
    }

    @Test
    fun templateEnBody() {
        assertEquals("(document (html (head (template \"a\" (b \"c\") \"d\")) (body \"x\")))", arbol("<template>a<b>c</b>d</template>x"))
        assertEquals(0, errores("<template>a<b>c</b>d</template>x"))
    }

    @Test
    fun templateEnHead() {
        assertEquals("(document (html (head (template \"x\")) (body \"y\")))", arbol("<template>x</template>y"))
        assertEquals(0, errores("<template>x</template>y"))
    }

    @Test
    fun templateFragmentoTabla() {
        assertEquals("(document (html (head (template (tr (td \"x\")))) (body)))", arbol("<template><tr><td>x</td></tr></template>"))
        assertEquals(0, errores("<template><tr><td>x</td></tr></template>"))
    }

    @Test
    fun templateTablaNormal() {
        assertEquals("(document (html (head (template (table (tbody (tr (td \"x\")))))) (body)))", arbol("<template><table><tr><td>x</td></tr></table></template>"))
        assertEquals(0, errores("<template><table><tr><td>x</td></tr></table></template>"))
    }

    @Test
    fun templateAnidado() {
        assertEquals("(document (html (head (template (template \"x\") \"y\")) (body \"z\")))", arbol("<template><template>x</template>y</template>z"))
        assertEquals(0, errores("<template><template>x</template>y</template>z"))
    }

    @Test
    fun templateEof() {
        assertEquals("(document (html (head (template \"x\")) (body)))", arbol("<template>x"))
        assertEquals(1, errores("<template>x"))
    }

    @Test
    fun templateEndSuelto() {
        assertEquals("(document (html (head) (body \"x\")))", arbol("</template>x"))
        assertEquals(1, errores("</template>x"))
    }
}
