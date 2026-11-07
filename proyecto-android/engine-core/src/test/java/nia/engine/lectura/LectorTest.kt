package nia.engine.lectura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 79.251685): los 16 tests cubren el titulo del
 * title y su recorte, el titulo vacio sin title, el contenedor
 * article/main/body por prioridad, el parrafo corto descartado, el
 * orden del documento, los espacios colapsados, el marcado interno
 * concatenado, la lectura vacia sin contenido principal, el script
 * que NUNCA entra, el limite de 500 parrafos, el recorte de 4000 y
 * los textos fragmentados que acumulan hasta el umbral.
 * T082 v1 (R0191, RES-0092).
 */
class LectorTest {

    @Test
    fun tituloDelElementoTitle() {
        val l = Lector.leer("<html><head><title>Noticia del dia</title></head><body><p>corto</p></body></html>")
        assertEquals("Noticia del dia", l.titulo)
        assertEquals(0, l.parrafos.size)
    }

    @Test
    fun tituloVacioSinTitle() {
        val l = Lector.leer("<html><body><p>corto</p></body></html>")
        assertEquals("", l.titulo)
    }

    @Test
    fun tituloSeRecortaA200() {
        val l = Lector.leer("<html><head><title>" + "t".repeat(350) + "</title></head></html>")
        assertEquals(200, l.titulo.length)
    }

    @Test
    fun contenedorArticleGanaSobreBody() {
        val l = Lector.leer(
            "<html><body><article><p>parrafo del article suficientemente largo para contar</p></article>" +
                "<p>parrafo del body que queda fuera del contenedor elegido</p></body></html>"
        )
        assertEquals(1, l.parrafos.size)
        assertTrue(l.parrafos[0].startsWith("parrafo del article"))
    }

    @Test
    fun contenedorMainSiNoHayArticle() {
        val l = Lector.leer(
            "<html><body><main><p>parrafo del main suficientemente largo para contar</p></main>" +
                "<p>parrafo del body que queda fuera del contenedor elegido</p></body></html>"
        )
        assertEquals(1, l.parrafos.size)
        assertTrue(l.parrafos[0].startsWith("parrafo del main"))
    }

    @Test
    fun bodyComoContenedor() {
        val l = Lector.leer("<html><body><p>parrafo del body suficientemente largo para contar</p></body></html>")
        assertEquals(1, l.parrafos.size)
    }

    @Test
    fun parrafoCortoDescartado() {
        val l = Lector.leer("<html><body><p>corto</p></body></html>")
        assertEquals(0, l.parrafos.size)
    }

    @Test
    fun ordenDelDocumentoPreservado() {
        val l = Lector.leer(
            "<html><body><p>primera linea del documento con texto largo</p>" +
                "<p>segunda linea del documento con texto largo</p>" +
                "<p>tercera linea del documento con texto largo</p></body></html>"
        )
        assertEquals(
            listOf("primer", "segund", "tercer"),
            l.parrafos.map { p -> p.substring(0, 6) }
        )
    }

    @Test
    fun espaciosColapsados() {
        val l = Lector.leer("<html><body><p>  hola   mundo \n\t con espacios   raros y texto suficiente  </p></body></html>")
        assertEquals(1, l.parrafos.size)
        assertEquals("hola mundo con espacios raros y texto suficiente", l.parrafos[0])
    }

    @Test
    fun marcadoInternoConcatenado() {
        val l = Lector.leer(
            "<html><body><p><b>El motor </b><em>propio</em> pinta paginas sin re-fetch del navegador</p></body></html>"
        )
        assertEquals(1, l.parrafos.size)
        assertEquals("El motor propio pinta paginas sin re-fetch del navegador", l.parrafos[0])
    }

    @Test
    fun sinContenidoPrincipalLecturaVacia() {
        val l = Lector.leer("<html><body><div><span>solo decoracion</span></div></body></html>")
        assertEquals("", l.titulo)
        assertEquals(0, l.parrafos.size)
    }

    @Test
    fun scriptsNuncaEnLaLectura() {
        val l = Lector.leer(
            "<html><body><script>alert(1)</script><p>parrafo real de la pagina con contenido verdadero</p></body></html>"
        )
        assertEquals(1, l.parrafos.size)
        assertFalse(l.parrafos[0].contains("alert"))
    }

    @Test
    fun limiteDeQuinientosParrafos() {
        val sb = StringBuilder("<html><body>")
        for (i in 1..501) {
            sb.append("<p>parrafo numero ").append(i).append(" con texto suficientemente largo siempre</p>")
        }
        sb.append("</body></html>")
        assertEquals(500, Lector.leer(sb.toString()).parrafos.size)
    }

    @Test
    fun parrafoRecortadoA4000() {
        val l = Lector.leer("<html><body><p>" + "x".repeat(5000) + "</p></body></html>")
        assertEquals(1, l.parrafos.size)
        assertEquals(4000, l.parrafos[0].length)
    }

    @Test
    fun textosFragmentadosAcumulan() {
        val l = Lector.leer(
            "<html><body><p>aaa <b>bbb</b> ccc ddd eee fff ggg hhh iii jjj kkk</p></body></html>"
        )
        assertEquals(1, l.parrafos.size)
    }

    @Test
    fun anidadoProfundoDentroDelArticle() {
        val l = Lector.leer(
            "<html><body><article><div><section>" +
                "<p>parrafo anidado profundo dentro del article principal</p>" +
                "</section></div></article></body></html>"
        )
        assertEquals(1, l.parrafos.size)
    }
}
