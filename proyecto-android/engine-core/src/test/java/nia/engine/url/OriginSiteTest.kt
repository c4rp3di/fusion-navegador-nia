package nia.engine.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginSiteTest {

    private fun origin(s: String): Origin = Origin.of(UrlParser.parse(s))

    private fun tuple(s: String): Origin.Tuple = origin(s) as Origin.Tuple

    @Test
    fun origenTuplaNormal() {
        val o = tuple("https://example.com/a")
        assertEquals("https", o.scheme)
        assertEquals("example.com", o.host)
        assertEquals(443, o.port)
    }

    @Test
    fun puertoExplicitoYCaso() {
        val o = tuple("HTTP://Example.COM:8080/")
        assertEquals("http", o.scheme)
        assertEquals("example.com", o.host)
        assertEquals(8080, o.port)
    }

    @Test
    fun esquemasDistintosNoSonMismoOrigen() {
        val a = origin("http://a.com/")
        val b = origin("https://a.com/")
        assertFalse(a.sameOrigin(b))
    }

    @Test
    fun mismoOrigenDeDistintasUrls() {
        val a = origin("https://a.com/x?q=1")
        val b = origin("https://a.com/y#f")
        assertTrue(a.sameOrigin(b))
    }

    @Test
    fun fileEsOrigenOpacoYNuncaEsMismoOrigen() {
        val o = origin("file:///x")
        assertTrue(o is Origin.Opaque)
        assertFalse(o.sameOrigin(origin("file:///x")))
    }

    @Test
    fun siteConSufijoDoble() {
        val s1 = Site.fromOrigin(tuple("https://sub.example.co.uk/"))
        val s2 = Site.fromOrigin(tuple("https://otro.example.co.uk/"))
        val s3 = Site.fromOrigin(tuple("https://evil.co.uk/"))
        assertEquals("example.co.uk", s1.registrableDomain)
        assertTrue(s1.sameSite(s2))
        assertFalse(s1.sameSite(s3))
    }

    @Test
    fun siteGithubIoAislaSubdominios() {
        val a = Site.fromOrigin(tuple("https://app1.github.io/"))
        val b = Site.fromOrigin(tuple("https://app2.github.io/"))
        assertEquals("app1.github.io", a.registrableDomain)
        assertFalse(a.sameSite(b))
    }

    @Test
    fun siteIpv4YLocalhost() {
        val v4 = Site.fromOrigin(tuple("http://10.0.0.1/"))
        assertEquals("10.0.0.1", v4.registrableDomain)
        val lh = Site.fromOrigin(tuple("http://localhost:8080/"))
        assertEquals("localhost", lh.registrableDomain)
    }
}
