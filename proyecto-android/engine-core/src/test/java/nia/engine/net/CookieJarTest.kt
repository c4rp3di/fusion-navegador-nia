package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

import nia.contracts.WallClock

/**
 * Tests T026 v1 (R0052): cookies RFC 6265. Espejo 1:1 del gemelo TS
 * validado offline: mismas fuentes, mismas expectativas.
 */
class CookieJarTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private fun jarWith(vararg stores: Triple<String, Boolean, String>): CookieJar {
        val clock = FakeClock(1000000)
        val jar = CookieJar(clock)
        for (s in stores) jar.storeFromResponse(s.first, s.second, s.third)
        return jar
    }

    @Test fun cookieAlmacenSimple() {
        val jar = jarWith(Triple("a.example", true, "sid=abc; Path=/; Secure"))
        assertEquals(1, jar.count())
    }

    @Test fun cookieNoViajaAOtroHost() {
        val jar = jarWith(Triple("a.example", true, "sid=abc; Path=/; Secure"))
        assertEquals("", jar.cookieHeader("b.example", "/", true))
    }

    @Test fun cookieSiViajaASuHost() {
        val jar = jarWith(Triple("a.example", true, "sid=abc; Path=/; Secure"))
        assertEquals("sid=abc", jar.cookieHeader("a.example", "/", true))
    }

    @Test fun cookieSecureSoloHttps() {
        val jar = jarWith(Triple("a.example", true, "sid=abc; Path=/; Secure"))
        assertEquals("", jar.cookieHeader("a.example", "/", false))
    }

    @Test fun cookieDominioCubreSubdominio() {
        val jar = jarWith(Triple("sub.a.example", false, "dom=x; Domain=.a.example"))
        assertEquals("dom=x", jar.cookieHeader("sub.a.example", "/", false))
    }

    @Test fun cookieDominioCubrePadre() {
        val jar = jarWith(Triple("sub.a.example", false, "dom=x; Domain=.a.example"))
        assertEquals("dom=x", jar.cookieHeader("a.example", "/", false))
    }

    @Test fun cookieRechazadaDominioAjeno() {
        val jar = jarWith(Triple("a.example", true, "evil=1; Domain=b.example"))
        assertEquals(0, jar.count())
    }

    @Test fun cookieInvalidaSinIgual() {
        val jar = jarWith(Triple("a.example", true, "nueva"))
        assertEquals(0, jar.count())
    }

    @Test fun cookiePathEspecifico() {
        val jar = jarWith(
            Triple("a.example", true, "sid=abc; Path=/; Secure"),
            Triple("sub.a.example", false, "dom=x; Domain=.a.example"),
            Triple("a.example", true, "ses=1; Path=/app")
        )
        assertEquals("ses=1; sid=abc; dom=x", jar.cookieHeader("a.example", "/app/page", true))
    }

    @Test fun cookiePathNoMatcheaSoloRaiz() {
        val jar = jarWith(
            Triple("a.example", true, "sid=abc; Path=/; Secure"),
            Triple("sub.a.example", false, "dom=x; Domain=.a.example")
        )
        assertEquals("dom=x", jar.cookieHeader("a.example", "/otro", false))
    }

    @Test fun cookieAttributes() {
        val jar = jarWith(Triple("a.example", true, "ses=1; Path=/app; HttpOnly; SameSite=Lax"))
        val c = jar.all().first { it.name == "ses" }
        assertEquals(listOf(true, "lax"), listOf(c.httpOnly, c.sameSite))
    }

    @Test fun cookieOrdenPorPath() {
        val jar = jarWith(
            Triple("a.example", true, "p1=1; Path=/"),
            Triple("a.example", true, "p2=2; Path=/a/b")
        )
        assertTrue(jar.cookieHeader("a.example", "/a/b/c", true).startsWith("p2="))
    }

    @Test fun cookieExpiradaPorMaxAge() {
        val clock = FakeClock(1000000)
        val jar = CookieJar(clock)
        assertTrue(jar.storeFromResponse("a.example", true, "tmp=1; Max-Age=5"))
        clock.valor = 1000000 + 6000
        assertFalse(jar.cookieHeader("a.example", "/", true).contains("tmp"))
    }

    @Test fun cookieMaxAgeCeroDescartada() {
        val jar = jarWith(Triple("a.example", true, "muerta=1; Max-Age=0"))
        assertEquals("", jar.cookieHeader("a.example", "/", true))
    }

    @Test fun cookieSinAtributosEsHostOnly() {
        val jar = jarWith(Triple("a.example", false, "h=1"))
        val c = jar.all()[0]
        assertEquals(listOf(true, "a.example", "/"), listOf(c.hostOnly, c.domain, c.path))
    }

    @Test fun cookieSinIgualInicial() {
        assertNull(CookieJar.parseSetCookie("=v", 0))
    }

    @Test fun cookiePathNoAbsolutoSeIgnora() {
        val jar = jarWith(Triple("a.example", false, "h=1; Path=x"))
        val c = jar.all()[0]
        assertEquals("/", c.path)
        assertEquals("h=1", jar.cookieHeader("a.example", "/otro", false))
    }
}
