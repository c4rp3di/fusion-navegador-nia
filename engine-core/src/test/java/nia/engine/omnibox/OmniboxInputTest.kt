package nia.engine.omnibox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OmniboxInputTest (T072 v1, RES-0075, R0155): espejo del gemelo TS validado
 * offline (2 corridas identicas, huella 9822.26f4.982, 34 escenarios) contra
 * el UrlParser REAL de T010, una tabla de resolucion por regla del ADR-022:
 * esquema soportado, esquema no soportado, dominio implicito, localhost,
 * IP, query con espacios, query con acentos y query sin punto.
 */
class OmniboxInputTest {

    private fun navegarDe(r: OmniboxInput.Resolucion): OmniboxInput.Resolucion.Navegar =
        r as? OmniboxInput.Resolucion.Navegar
            ?: throw AssertionError("esperaba Navegar, fue: " + r)

    private fun buscarDe(r: OmniboxInput.Resolucion): OmniboxInput.Resolucion.Buscar =
        r as? OmniboxInput.Resolucion.Buscar
            ?: throw AssertionError("esperaba Buscar, fue: " + r)

    private fun noSoportadoDe(r: OmniboxInput.Resolucion): OmniboxInput.Resolucion.EsquemaNoSoportado =
        r as? OmniboxInput.Resolucion.EsquemaNoSoportado
            ?: throw AssertionError("esperaba EsquemaNoSoportado, fue: " + r)

    @Test
    fun textoVacioSinAccion() {
        assertEquals(OmniboxInput.Resolucion.Vacio, OmniboxInput.resolver(""))
    }

    @Test
    fun espaciosSolosSinAccion() {
        assertEquals(OmniboxInput.Resolucion.Vacio, OmniboxInput.resolver("   "))
    }

    @Test
    fun httpsExplicitoNavegaTalCual() {
        val n = navegarDe(OmniboxInput.resolver("https://ejemplo.com/p?q=1#f"))
        assertEquals("https://ejemplo.com/p?q=1#f", n.url)
        assertFalse(n.dominioImplicito)
    }

    @Test
    fun esquemaEnMayusculasNavega() {
        val n = navegarDe(OmniboxInput.resolver("HTTPS://EJEMPLO.COM"))
        assertEquals("HTTPS://EJEMPLO.COM", n.url)
        assertFalse(n.dominioImplicito)
    }

    @Test
    fun httpLocalhostConPuertoExplicito() {
        val n = navegarDe(OmniboxInput.resolver("http://localhost:8080/x"))
        assertEquals("http://localhost:8080/x", n.url)
        assertFalse(n.dominioImplicito)
    }

    @Test
    fun httpSinDobleBarraEsErrorDeParseo() {
        val r = OmniboxInput.resolver("http:x")
        assertTrue(r is OmniboxInput.Resolucion.ErrorDeParseo)
    }

    @Test
    fun aboutNoSoportado() {
        assertEquals("about", noSoportadoDe(OmniboxInput.resolver("about:blank")).esquema)
    }

    @Test
    fun javascriptNoSoportado() {
        assertEquals("javascript", noSoportadoDe(OmniboxInput.resolver("javascript:alert(1)")).esquema)
    }

    @Test
    fun fileNoSoportado() {
        assertEquals("file", noSoportadoDe(OmniboxInput.resolver("file:///etc/passwd")).esquema)
    }

    @Test
    fun viewSourceNoSoportado() {
        assertEquals("view-source", noSoportadoDe(OmniboxInput.resolver("view-source:https://ejemplo.com")).esquema)
    }

    @Test
    fun dataNoSoportado() {
        assertEquals("data", noSoportadoDe(OmniboxInput.resolver("data:text/html,hola")).esquema)
    }

    @Test
    fun esquemaDesconocidoConDobleBarraNoSoportado() {
        assertEquals("foo", noSoportadoDe(OmniboxInput.resolver("foo://bar")).esquema)
    }

    @Test
    fun dominioImplicitoConPunto() {
        val n = navegarDe(OmniboxInput.resolver("ejemplo.com"))
        assertEquals("https://ejemplo.com", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun dominioImplicitoConRutaYQuery() {
        val n = navegarDe(OmniboxInput.resolver("ejemplo.com/ruta?q=1"))
        assertEquals("https://ejemplo.com/ruta?q=1", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun localhostImplicito() {
        val n = navegarDe(OmniboxInput.resolver("localhost"))
        assertEquals("https://localhost", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun localhostConPuertoImplicito() {
        val n = navegarDe(OmniboxInput.resolver("localhost:8080"))
        assertEquals("https://localhost:8080", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun ipv4Implicito() {
        val n = navegarDe(OmniboxInput.resolver("192.168.1.10"))
        assertEquals("https://192.168.1.10", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun ipv6ConCorchetesImplicito() {
        val n = navegarDe(OmniboxInput.resolver("[::1]/ruta"))
        assertEquals("https://[::1]/ruta", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun hostConPuertoImplicito() {
        val n = navegarDe(OmniboxInput.resolver("ejemplo.com:8080"))
        assertEquals("https://ejemplo.com:8080", n.url)
        assertTrue(n.dominioImplicito)
    }

    @Test
    fun hostConPuertoNoEsquemaExplicito() {
        assertNull(OmniboxInput.esquemaExplicito("ejemplo.com:8080"))
    }

    @Test
    fun queryConEspaciosBusca() {
        val b = buscarDe(OmniboxInput.resolver("que es un navegador"))
        assertEquals(OmniboxInput.BASE_BUSQUEDA_DEFECTO + "que%20es%20un%20navegador", b.url)
        assertEquals("que es un navegador", b.query)
    }

    @Test
    fun queryConAcentosCodificaUtf8() {
        val b = buscarDe(OmniboxInput.resolver("caf\u00E9 y t\u00E9"))
        assertEquals(OmniboxInput.BASE_BUSQUEDA_DEFECTO + "caf%C3%A9%20y%20t%C3%A9", b.url)
    }

    @Test
    fun espacioInteriorNoEsDominio() {
        val b = buscarDe(OmniboxInput.resolver("gato negro.com"))
        assertEquals(OmniboxInput.BASE_BUSQUEDA_DEFECTO + "gato%20negro.com", b.url)
    }

    @Test
    fun correoNoEsDominio() {
        val b = buscarDe(OmniboxInput.resolver("user@ejemplo.com"))
        assertEquals(OmniboxInput.BASE_BUSQUEDA_DEFECTO + "user%40ejemplo.com", b.url)
    }

    @Test
    fun querySinPuntoBusca() {
        val b = buscarDe(OmniboxInput.resolver("hola"))
        assertEquals(OmniboxInput.BASE_BUSQUEDA_DEFECTO + "hola", b.url)
    }

    @Test
    fun httpsConHostVacioEsErrorDeParseo() {
        val r = OmniboxInput.resolver("https://")
        assertTrue(r is OmniboxInput.Resolucion.ErrorDeParseo)
    }

    @Test
    fun buscaUrlCompletaDDG() {
        val b = buscarDe(OmniboxInput.resolver("navegacion privada"))
        assertEquals("https://html.duckduckgo.com/html/?q=navegacion%20privada", b.url)
    }
}
