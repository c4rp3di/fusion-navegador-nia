package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Test

import nia.engine.dom.DomDocument
import nia.engine.html.DomNode

/**
 * Espejo Kotlin del gemelo TS validado offline para el subconjunto
 * cosmético de EasyList (T053 v1, R0289, RES-0123): parse por linea
 * (## sel, dominios por sufijo, excepciones #@#), descartes contados
 * (selector ilegible, ~dom, mas de 8 dominios), topes fail-closed
 * (lineas, selectores, cero reglas) y el matching DOM contra la
 * cascada real del host.
 */
class CosmeticosModulosTest {

    private fun valida(texto: String): CosmeticosModulos.ListaCosmetica {
        val r = CosmeticosModulos.parse(texto)
        assertTrue("la lista valida parsea", r != null)
        return r!!
    }

    @Test
    fun parseAceptaSelectorGlobal() {
        val lista = valida("##.anuncio\n")
        assertEquals(1, lista.reglas.size)
        assertTrue(lista.reglas[0].dominios.isEmpty())
        assertEquals(".anuncio", lista.reglas[0].texto)
        assertEquals(0, lista.descartados)
    }

    @Test
    fun parseConDominiosGuardaElSufijo() {
        val lista = valida("dom1.com,dom2.com##.ad\n")
        assertEquals(1, lista.reglas.size)
        assertEquals(setOf("dom1.com", "dom2.com"), lista.reglas[0].dominios)
    }

    @Test
    fun parseExcepcionesRetiranPorTexto() {
        val lista = valida("##.anuncio\n#@#.anuncio\n")
        assertEquals(1, lista.reglas.size)
        assertTrue(lista.excepciones.contains(".anuncio"))
    }

    @Test
    fun parseIgnoraRedComentariosYSnippets() {
        val lista = valida(
            "||example.com^\n! comentario\n[Adblock Plus 2.0]\n" +
            "example.com#$#div\nexample.com#?#div\n##.anuncio\n"
        )
        assertEquals(1, lista.reglas.size)
        assertEquals(0, lista.descartados)
    }

    @Test
    fun parseDescartaSelectorIlegible() {
        val lista = valida("##.a\n##div[class=\n")
        assertEquals(1, lista.reglas.size)
        assertEquals("el selector ilegible se descuenta", 1, lista.descartados)
    }

    @Test
    fun parseDescartaNegacionDeDominio() {
        val lista = valida("##.a\n~dom.com##.b\n")
        assertEquals(1, lista.reglas.size)
        assertEquals(1, lista.descartados)
    }

    @Test
    fun parseDescartaMasDeOchoDominios() {
        val sb = StringBuilder()
        sb.append("##.a\n")
        sb.append("d1.com,d2.com,d3.com,d4.com,d5.com,d6.com,d7.com,d8.com,d9.com##.b\n")
        val lista = valida(sb.toString())
        assertEquals(1, lista.reglas.size)
        assertEquals(1, lista.descartados)
    }

    @Test
    fun parseTopeDeLineasRechaza() {
        val sb = StringBuilder()
        for (i in 0 until 5001) sb.append("##.a\n")
        assertTrue("demasiadas lineas rechazan la lista", CosmeticosModulos.parse(sb.toString()) == null)
    }

    @Test
    fun parseTopeDeSelectoresRechaza() {
        val sb = StringBuilder()
        for (i in 0 until 2001) sb.append("##.a").append(i).append('\n')
        assertTrue("demasiados selectores rechazan la lista", CosmeticosModulos.parse(sb.toString()) == null)
    }

    @Test
    fun parseSinReglasDevuelveNull() {
        assertTrue("cero reglas validas rechazan la lista", CosmeticosModulos.parse("||x.com^\n") == null)
    }

    @Test
    fun aplicaDominioCasaPorSufijo() {
        val dominios = setOf("a.dev")
        assertTrue(CosmeticosModulos.aplicaDominio(dominios, "a.dev"))
        assertTrue(CosmeticosModulos.aplicaDominio(dominios, "www.a.dev"))
        assertTrue(!CosmeticosModulos.aplicaDominio(dominios, "xa.dev"))
        assertTrue(!CosmeticosModulos.aplicaDominio(dominios, "b.dev"))
        assertTrue(CosmeticosModulos.aplicaDominio(emptySet(), "b.dev"))
    }

    @Test
    fun selectoresParaFiltraPorDominio() {
        val lista = valida("a.dev##.x\n##.y\n")
        assertEquals(2, CosmeticosModulos.selectoresPara(lista, "a.dev").size)
        assertEquals(1, CosmeticosModulos.selectoresPara(lista, "b.dev").size)
    }

    @Test
    fun excepcionesRetiranSelectoresDelHost() {
        val lista = valida("##.anuncio\n#@#.anuncio\n")
        assertTrue(CosmeticosModulos.selectoresPara(lista, "a.dev").isEmpty())
    }

    @Test
    fun ocultosCasaElementosDelDom() {
        val lista = valida("##.anuncio\n")
        val document = DomDocument.parse("<div><p class=\"anuncio\">x</p><p>y</p></div>")
        val root = document.nodeOf(document.rootHandle())
        val ocultos = CosmeticosModulos.ocultos(root, lista, "a.dev")
        assertEquals(1, ocultos.size)
        val p = ocultos.iterator().next() as DomNode.Element
        assertEquals("anuncio", p.attributes["class"])
    }
}
