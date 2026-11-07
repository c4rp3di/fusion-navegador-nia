package nia.engine.sitios

import nia.contracts.WallClock
import nia.engine.net.CookieJar
import nia.engine.net.FetchResponse
import nia.engine.net.HttpCache
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de datos del sitio (T085 v1, RES-0098): espejo del gemelo
 * TS (17 checks en verde antes del push) sobre los motores REALES:
 * cookies por domain-match RFC 6265 (host-only exacto, Domain con
 * amplitud, case-insensitive) y cache por (scheme, host, puerto
 * efectivo); el borrado de un sitio NUNCA toca los otros origenes
 * y una URL ilegible en el cache simplemente no matchea.
 */
class DatosSitioTest {

    private class RelojFijo : WallClock {
        override fun nowMillis(): Long = 0L
    }

    private fun jar(): CookieJar = CookieJar(RelojFijo())

    private fun cache(): HttpCache = HttpCache(RelojFijo())

    private fun resp(): FetchResponse = FetchResponse(200, mapOf("cache-control" to "max-age=60"), "cuerpo".toByteArray())

    @Test
    fun conteoSinDatosEsCero() {
        val j = jar()
        val c = cache()
        assertEquals(ConteoSitio(0, 0), DatosSitio.conteo(j, c, "https", "a.com", 443))
    }

    @Test
    fun cookiesDelHostHostOnly() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        assertEquals(1, j.contarDe("a.com"))
        assertEquals(0, j.contarDe("sub.a.com"))
        assertEquals(0, j.contarDe("b.com"))
    }

    @Test
    fun contarDeEsCaseInsensitive() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        assertEquals(1, j.contarDe("A.COM"))
    }

    @Test
    fun cookieConDomainMatcheaSubdominio() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1; Domain=a.com")
        assertEquals(1, j.contarDe("sub.a.com"))
        assertEquals(1, j.contarDe("a.com"))
    }

    @Test
    fun borrarDeCookiesNoTocaOtrosOrigenes() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        j.storeFromResponse("b.com", true, "sid=2")
        assertEquals(1, j.borrarDe("a.com"))
        assertEquals(0, j.contarDe("a.com"))
        assertEquals(1, j.contarDe("b.com"))
    }

    @Test
    fun dobleBorradoDevuelveCero() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        assertEquals(1, j.borrarDe("a.com"))
        assertEquals(0, j.borrarDe("a.com"))
    }

    @Test
    fun cacheContarPorOrigen() {
        val c = cache()
        c.store("https://a.com/x", resp())
        c.store("https://a.com/y", resp())
        c.store("https://b.com/z", resp())
        assertEquals(2, c.contarDe("https", "a.com", 443))
        assertEquals(1, c.contarDe("https", "b.com", 443))
    }

    @Test
    fun cachePuertosDistintosSonOrigenesDistintos() {
        val c = cache()
        c.store("http://a.com:8080/x", resp())
        assertEquals(1, c.contarDe("http", "a.com", 8080))
        assertEquals(0, c.contarDe("https", "a.com", 443))
    }

    @Test
    fun cacheBorrarDejaOtrosOrigenes() {
        val c = cache()
        c.store("https://a.com/x", resp())
        c.store("https://b.com/y", resp())
        assertEquals(1, c.borrarDe("https", "a.com", 443))
        assertEquals(0, c.contarDe("https", "a.com", 443))
        assertEquals(1, c.contarDe("https", "b.com", 443))
    }

    @Test
    fun cacheUrlIlegibleNoMatchea() {
        val c = cache()
        c.store("no-es-url", resp())
        assertEquals(0, c.contarDe("https", "a.com", 443))
        assertEquals(0, c.borrarDe("https", "a.com", 443))
    }

    @Test
    fun datosSitioConteoAgregado() {
        val j = jar()
        val c = cache()
        j.storeFromResponse("a.com", true, "sid=1")
        c.store("https://a.com/x", resp())
        assertEquals(ConteoSitio(1, 1), DatosSitio.conteo(j, c, "https", "a.com", 443))
    }

    @Test
    fun datosSitioBorrarSoloElSitio() {
        val j = jar()
        val c = cache()
        j.storeFromResponse("a.com", true, "sid=1")
        j.storeFromResponse("b.com", true, "sid=2")
        c.store("https://a.com/x", resp())
        c.store("https://b.com/y", resp())
        val borrado = DatosSitio.borrar(j, c, "https", "a.com", 443)
        assertEquals(ConteoSitio(1, 1), borrado)
        assertEquals(ConteoSitio(0, 0), DatosSitio.conteo(j, c, "https", "a.com", 443))
        assertEquals(ConteoSitio(1, 1), DatosSitio.conteo(j, c, "https", "b.com", 443))
    }
}
