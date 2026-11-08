package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Test

import nia.engine.dom.DomDocument
import nia.engine.html.DomNode

/**
 * Espejo Kotlin del gemelo TS validado offline para el validador y
 * el matcher de la hoja de estilos de un modulo (C6 v1, R0276,
 * RES-0120): lista cerrada (una unica declaracion display:none),
 * fail-closed (un selector invalido o un @-rule rechazan la hoja
 * ENTERA), tope de selectores y matching con la cascada real del
 * host (gemelo 2 corridas, huella 38.38.6944b7bd8d318521).
 */
class EstilosModulosTest {

    private fun raiz(html: String): DomNode.Document {
        val doc = DomDocument.parse(html)
        return doc.nodeOf(doc.rootHandle()) as DomNode.Document
    }

    private fun elementos(doc: DomNode.Document): List<DomNode.Element> {
        val out = ArrayList<DomNode.Element>()
        fun go(n: DomNode) {
            if (n is DomNode.Element) out.add(n)
            for (h in hijosDe(n)) go(h)
        }
        go(doc)
        return out
    }

    private fun hijosDe(n: DomNode): List<DomNode> = when (n) {
        is DomNode.Document -> n.children
        is DomNode.Element -> n.children
        else -> emptyList()
    }

    @Test
    fun hojaBuenaValida() {
        val reglas = EstilosModulos.validar(".anuncio { display: none }")
        assertTrue("la hoja de lista cerrada valida", reglas != null)
    }

    @Test
    fun declaracionesFueraDeLaListaRechazadas() {
        assertTrue(EstilosModulos.validar(".a { color: red }") == null)
        assertTrue(EstilosModulos.validar(".a { display: block }") == null)
        assertTrue(EstilosModulos.validar(".a { display: none; color: red }") == null)
    }

    @Test
    fun selectorInvalidoRechazaLaHojaEntera() {
        val reglas = EstilosModulos.validar(".a { display: none }\n.. { display: none }")
        assertTrue("un selector invalido rechaza la hoja entera", reglas == null)
    }

    @Test
    fun topeDeSelectoresRechazado() {
        // R0275: el tope cuenta la SUMA de selectores de todas las
        // reglas; se prueban 200/201 reglas de UN selector (el
        // agrupado por comas se prueba en ocultosMatchea).
        val dentro = (0..199).joinToString("\n") { ".c$" + it + " { display: none }" }
        assertTrue("200 selectores validan", EstilosModulos.validar(dentro) != null)
        val fuera = (0..200).joinToString("\n") { ".c$" + it + " { display: none }" }
        assertTrue("201 selectores NO validan", EstilosModulos.validar(fuera) == null)
    }

    @Test
    fun atRulesRechazados() {
        assertTrue(EstilosModulos.validar("@media (min-width: 1px) { .a { display: none } }") == null)
        assertTrue(EstilosModulos.validar("@import \"x.css\";\n.a { display: none }") == null)
    }

    @Test
    fun ocultosMatcheaClaseIdYDescendiente() {
        val doc = raiz(
            "<html><body>" +
                "<div class=\"anuncio\">a</div>" +
                "<div id=\"banner\">b</div>" +
                "<div class=\"caja\"><span class=\"ad\">c</span></div>" +
                "</body></html>"
        )
        val fuera = EstilosModulos.ocultos(doc, ".anuncio, #banner, .caja .ad { display: none }")
        assertEquals(3, fuera.size)
        val els = elementos(doc)
        val anuncio = els.first { it.attributes["class"] == "anuncio" }
        val banner = els.first { it.attributes["id"] == "banner" }
        val ad = els.first { it.attributes["class"] == "ad" }
        val caja = els.first { it.attributes["class"] == "caja" }
        assertTrue(fuera.contains(anuncio))
        assertTrue(fuera.contains(banner))
        assertTrue(fuera.contains(ad))
        assertTrue("la caja sola no se esconde", !fuera.contains(caja))
    }

    @Test
    fun ocultosSinMatchEsVacio() {
        val doc = raiz("<html><body><div class=\"contenido\">a</div></body></html>")
        assertTrue(EstilosModulos.ocultos(doc, ".nada { display: none }").isEmpty())
    }

    @Test
    fun ocultosHojaInvalidaEsVacio() {
        val doc = raiz("<html><body><div class=\"a\">a</div></body></html>")
        assertTrue(
            "hoja invalida = fail-closed sin ocultos",
            EstilosModulos.ocultos(doc, ".a { color: red }").isEmpty()
        )
    }
}
