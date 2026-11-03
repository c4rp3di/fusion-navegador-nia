package nia.engine.dom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests del DOM propio (T013 v1): espejo validado del gemelo TS (42/42),
 * con expectativas exactas de arbol (DomPretty) y de conteo de revision.
 */
class DomDocumentTest {

    private fun arbol(doc: DomDocument): String = doc.snapshot().tree

    private fun revision(doc: DomDocument): Long = doc.snapshot().revision

    private fun assertCode(code: String, block: () -> Unit) {
        val e = assertFailsWith<DomException> { block() }
        assertEquals(code, e.code)
    }

    @Test
    fun idsPreOrdenDocumentoSimple() {
        val doc = DomDocument.parse("<p>a</p>")
        val root = doc.rootHandle()
        assertEquals(1, root.nodeId, "raiz")
        val html = doc.childrenOf(root)
        assertEquals(1, html.size, "hijos raiz")
        assertEquals(2, html[0].nodeId, "html id")
        val hb = doc.childrenOf(html[0])
        assertEquals(2, hb.size, "hijos html")
        assertEquals(3, hb[0].nodeId, "head id")
        assertEquals(4, hb[1].nodeId, "body id")
        val p = assertNotNull(doc.findFirst("p"))
        assertEquals(5, p.nodeId, "p id")
        val t = doc.childrenOf(p)[0]
        assertEquals(6, t.nodeId, "texto id")
        assertEquals("a", doc.textOf(t), "texto")
    }

    @Test
    fun arbolVacioSnapshot() {
        val doc = DomDocument.parse("")
        assertEquals("(document (html (head) (body)))", arbol(doc), "arbol")
        assertEquals(0L, revision(doc), "revision")
        assertNull(doc.findFirst("p"), "sin p")
        assertEquals(1, doc.rootHandle().nodeId, "raiz id")
    }

    @Test
    fun idsPreOrdenDocumentoAnidado() {
        val doc = DomDocument.parse("<div id=\"z\"><p>a</p><span>b</span></div>")
        val root = doc.rootHandle()
        val html = doc.childrenOf(root)
        val div = assertNotNull(doc.findFirst("div"))
        val dc = doc.childrenOf(div)
        val p = dc[0]
        val span = dc[1]
        assertEquals(1, root.nodeId, "r")
        assertEquals(2, html[0].nodeId, "html")
        val hb = doc.childrenOf(html[0])
        assertEquals(3, hb[0].nodeId, "head")
        assertEquals(4, hb[1].nodeId, "body")
        assertEquals(5, div.nodeId, "div")
        assertEquals(6, p.nodeId, "p")
        assertEquals(7, doc.childrenOf(p)[0].nodeId, "texto p")
        assertEquals(8, span.nodeId, "span")
        assertEquals(9, doc.childrenOf(span)[0].nodeId, "texto span")
    }

    @Test
    fun createElementDetachedNoBumpRevision() {
        val doc = DomDocument.parse("")
        val li = doc.createElement("li")
        assertEquals(0L, revision(doc), "revision sin bump")
        assertEquals(0, doc.childrenOf(li).size, "sin hijos")
        assertEquals("(document (html (head) (body)))", arbol(doc), "arbol intacto")
    }

    @Test
    fun createTextNodeDetached() {
        val doc = DomDocument.parse("")
        val t = doc.createTextNode("x")
        assertEquals("x", doc.textOf(t), "texto")
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun appendChildInsertaYBumpRevision() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        val t = doc.createTextNode("hola")
        doc.appendChild(div, t)
        assertEquals(1L, revision(doc), "revision")
        assertEquals("(document (html (head) (body (div \"hola\"))))", arbol(doc), "arbol")
        assertEquals("hola", doc.textOf(t), "texto")
        assertEquals(1, doc.childrenOf(div).size, "hijos div")
    }

    @Test
    fun appendChildEnDocumento() {
        val doc = DomDocument.parse("")
        val t = doc.createTextNode("z")
        doc.appendChild(doc.rootHandle(), t)
        assertEquals(1L, revision(doc), "revision")
        assertEquals(2, doc.childrenOf(doc.rootHandle()).size, "hijos documento")
        assertEquals("(document (html (head) (body)) \"z\")", arbol(doc), "arbol")
    }

    @Test
    fun appendChildNodoYaAttachadoFalla() {
        val doc = DomDocument.parse("<div></div><span></span>")
        val div = assertNotNull(doc.findFirst("div"))
        val span = assertNotNull(doc.findFirst("span"))
        val t = doc.createTextNode("q")
        doc.appendChild(div, t)
        assertCode("node-already-attached") { doc.appendChild(span, t) }
        assertEquals(1L, revision(doc), "revision sin bump")
    }

    @Test
    fun appendChildPadreTextoFalla() {
        val doc = DomDocument.parse("")
        val p1 = doc.createTextNode("a")
        val p2 = doc.createTextNode("b")
        assertCode("invalid-parent") { doc.appendChild(p1, p2) }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun appendChildDocumentoComoHijoFalla() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        assertCode("invalid-parent") { doc.appendChild(div, doc.rootHandle()) }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun insertBeforeOrdena() {
        val doc = DomDocument.parse("<ul><li>a</li><li>c</li></ul>")
        val ul = assertNotNull(doc.findFirst("ul"))
        val lis = doc.childrenOf(ul)
        val t = doc.createTextNode("b")
        doc.insertBefore(ul, t, lis[1])
        assertEquals(1L, revision(doc), "revision")
        val ahora = doc.childrenOf(ul)
        assertEquals(3, ahora.size, "hijos ul")
        assertEquals("b", doc.textOf(ahora[1]), "orden")
        assertEquals("(document (html (head) (body (ul (li \"a\") \"b\" (li \"c\")))))", arbol(doc), "arbol")
    }

    @Test
    fun insertBeforeRefNoHijoFalla() {
        val doc = DomDocument.parse("<div></div><p></p>")
        val div = assertNotNull(doc.findFirst("div"))
        val p = assertNotNull(doc.findFirst("p"))
        val t = doc.createTextNode("x")
        assertCode("not-a-child") { doc.insertBefore(div, t, p) }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun removeChildDeregistraSubtree() {
        val doc = DomDocument.parse("<div><p>x</p></div>")
        val div = assertNotNull(doc.findFirst("div"))
        val p = assertNotNull(doc.findFirst("p"))
        val t = doc.childrenOf(p)[0]
        doc.removeChild(div, p)
        assertEquals(1L, revision(doc), "revision")
        assertNull(doc.findFirst("p"), "p fuera del arbol")
        assertCode("node-not-found") { doc.textOf(t) }
        assertCode("node-not-found") { doc.appendChild(div, p) }
        assertEquals("(document (html (head) (body (div))))", arbol(doc), "arbol")
    }

    @Test
    fun removeChildNoHijoFalla() {
        val doc = DomDocument.parse("<div></div><p></p>")
        val div = assertNotNull(doc.findFirst("div"))
        val p = assertNotNull(doc.findFirst("p"))
        assertCode("not-a-child") { doc.removeChild(div, p) }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun removeChildDeDocumento() {
        val doc = DomDocument.parse("<p>a</p>")
        val c = doc.createComment("n")
        val root = doc.rootHandle()
        doc.appendChild(root, c)
        assertEquals(2, doc.childrenOf(root).size, "hijos tras append")
        doc.removeChild(root, c)
        assertEquals(1, doc.childrenOf(root).size, "hijos tras remove")
        assertEquals(2L, revision(doc), "revision")
    }

    @Test
    fun setTextMutaYBump() {
        val doc = DomDocument.parse("<p>hola</p>")
        val p = assertNotNull(doc.findFirst("p"))
        val t = doc.childrenOf(p)[0]
        doc.setText(t, "adios")
        assertEquals(1L, revision(doc), "revision")
        assertEquals("adios", doc.textOf(t), "texto")
        assertEquals("(document (html (head) (body (p \"adios\"))))", arbol(doc), "arbol")
    }

    @Test
    fun setTextSobreElementoFalla() {
        val doc = DomDocument.parse("<p>a</p>")
        val p = assertNotNull(doc.findFirst("p"))
        assertCode("wrong-node-type") { doc.setText(p, "x") }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun setTextSobreComentarioFalla() {
        val doc = DomDocument.parse("")
        val c = doc.createComment("x")
        assertCode("wrong-node-type") { doc.setText(c, "y") }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun setAttributeNuevo() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        doc.setAttribute(div, "id", "x")
        assertEquals(1L, revision(doc), "revision")
        assertEquals("x", doc.attributeOf(div, "id"), "valor")
        assertEquals("(document (html (head) (body (div id=\"x\"))))", arbol(doc), "arbol")
    }

    @Test
    fun setAttributeOverwritePreservaOrden() {
        val doc = DomDocument.parse("<div a=\"1\" b=\"2\" c=\"3\"></div>")
        val div = assertNotNull(doc.findFirst("div"))
        doc.setAttribute(div, "b", "9")
        assertEquals(1L, revision(doc), "revision")
        assertEquals("9", doc.attributeOf(div, "b"), "b")
        assertEquals("1", doc.attributeOf(div, "a"), "a")
        assertEquals("3", doc.attributeOf(div, "c"), "c")
        assertEquals("(document (html (head) (body (div a=\"1\" b=\"9\" c=\"3\"))))", arbol(doc), "arbol")
    }

    @Test
    fun setAttributeNombreInvalidoFalla() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        for (bad in listOf("", "a b", "a/b", "a=b", "a<b", "a\"b", "a'b")) {
            assertCode("invalid-attribute-name") { doc.setAttribute(div, bad, "v") }
        }
        assertEquals(0L, revision(doc), "revision sin bump")
        assertNull(doc.attributeOf(div, "a b"), "nada asignado")
    }

    @Test
    fun setAttributeSobreTextoFalla() {
        val doc = DomDocument.parse("")
        val t = doc.createTextNode("x")
        assertCode("wrong-node-type") { doc.setAttribute(t, "id", "v") }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun removeAttributeExiste() {
        val doc = DomDocument.parse("<div a=\"1\"></div>")
        val div = assertNotNull(doc.findFirst("div"))
        doc.removeAttribute(div, "a")
        assertEquals(1L, revision(doc), "revision")
        assertNull(doc.attributeOf(div, "a"), "atributo fuera")
        assertEquals("(document (html (head) (body (div))))", arbol(doc), "arbol")
    }

    @Test
    fun removeAttributeMissingFalla() {
        val doc = DomDocument.parse("<div a=\"1\"></div>")
        val div = assertNotNull(doc.findFirst("div"))
        assertCode("attribute-not-found") { doc.removeAttribute(div, "nope") }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun removeAttributeSobreTextoFalla() {
        val doc = DomDocument.parse("")
        val t = doc.createTextNode("x")
        assertCode("wrong-node-type") { doc.removeAttribute(t, "id") }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun snapshotInmutable() {
        val doc = DomDocument.parse("<p>hola</p>")
        val s1 = doc.snapshot()
        val t = doc.childrenOf(assertNotNull(doc.findFirst("p")))[0]
        doc.setText(t, "adios")
        val s2 = doc.snapshot()
        assertEquals(0L, s1.revision, "s1 revision")
        assertEquals(1L, s2.revision, "s2 revision")
        assertEquals("(document (html (head) (body (p \"hola\"))))", s1.tree, "s1 arbol")
        assertEquals("(document (html (head) (body (p \"adios\"))))", s2.tree, "s2 arbol")
    }

    @Test
    fun invalidacionPorGeneracion() {
        val doc = DomDocument.parse("<p>a</p>")
        val viejo = doc.rootHandle()
        doc.invalidate()
        assertCode("stale-handle") { doc.childrenOf(viejo) }
        val nuevo = doc.rootHandle()
        assertEquals(1, doc.childrenOf(nuevo).size, "handle fresco funciona")
        assertEquals(2L, doc.snapshot().generation, "generacion")
    }

    @Test
    fun handleDeOtroDocumentoEsStale() {
        val docA = DomDocument.parse("<p>x</p>")
        val docB = DomDocument.parse("<p>x</p>")
        val hb = docB.rootHandle()
        assertCode("stale-handle") { docA.setAttribute(hb, "id", "q") }
    }

    @Test
    fun fallosNoBumpRevision() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        assertCode("invalid-parent") { doc.appendChild(div, doc.rootHandle()) }
        assertCode("wrong-node-type") { doc.setText(div, "x") }
        assertCode("invalid-attribute-name") { doc.setAttribute(div, "", "v") }
        assertCode("attribute-not-found") { doc.removeAttribute(div, "zz") }
        assertCode("not-a-child") { doc.removeChild(div, doc.rootHandle()) }
        assertEquals(0L, revision(doc), "revision sin bump")
    }

    @Test
    fun textosNoSeFundenTrasAppend() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        val a = doc.createTextNode("a")
        val b = doc.createTextNode("b")
        doc.appendChild(div, a)
        doc.appendChild(div, b)
        assertEquals(2, doc.childrenOf(div).size, "dos hijos texto")
        assertEquals("(document (html (head) (body (div \"a\" \"b\"))))", arbol(doc), "arbol")
    }

    @Test
    fun revisionAcumulativa() {
        val doc = DomDocument.parse("<div></div>")
        val div = assertNotNull(doc.findFirst("div"))
        doc.setAttribute(div, "id", "q")
        val t = doc.createTextNode("x")
        doc.appendChild(div, t)
        doc.setText(t, "y")
        assertEquals(3L, revision(doc), "revision")
        assertEquals("(document (html (head) (body (div id=\"q\" \"y\"))))", arbol(doc), "arbol")
    }
}
