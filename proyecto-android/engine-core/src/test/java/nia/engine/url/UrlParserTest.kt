package nia.engine.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UrlParserTest {

    @Test
    fun urlSimple() {
        val u = UrlParser.parse("https://example.com/")
        assertEquals("https", u.scheme)
        assertEquals("example.com", u.host)
        assertNull(u.port)
        assertEquals("/", u.path)
        assertNull(u.query)
        assertNull(u.fragment)
        assertEquals(443, u.effectivePort)
    }

    @Test
    fun urlConPuertoYRuta() {
        val u = UrlParser.parse("http://example.com:8080/a/b")
        assertEquals(8080, u.port)
        assertEquals("/a/b", u.path)
        assertEquals(8080, u.effectivePort)
    }

    @Test
    fun puertoPorDefectoSeNormaliza() {
        val u = UrlParser.parse("http://example.com:80/x")
        assertNull(u.port)
        assertEquals(80, u.effectivePort)
    }

    @Test
    fun esquemaYHostEnMinusculas() {
        val u = UrlParser.parse("HTTP://EXAMPLE.COM/Ruta/Mayus")
        assertEquals("http", u.scheme)
        assertEquals("example.com", u.host)
        assertEquals("/Ruta/Mayus", u.path)
    }

    @Test
    fun queryYFragmento() {
        val u = UrlParser.parse("https://a.com/p?q=1&r=2#frag")
        assertEquals("q=1&r=2", u.query)
        assertEquals("frag", u.fragment)
    }

    @Test
    fun fragmentoAntesQueQuery() {
        val u = UrlParser.parse("https://a.com/p#f?q")
        assertEquals("f?q", u.fragment)
        assertNull(u.query)
    }

    @Test
    fun pathVacioEsRaiz() {
        val u = UrlParser.parse("https://a.com")
        assertEquals("/", u.path)
    }

    @Test
    fun normalizaSegmentosPunto() {
        val u = UrlParser.parse("https://a.com/x/./y/../z")
        assertEquals("/x/z", u.path)
    }

    @Test
    fun barraFinalTrasPuntoPunto() {
        val u = UrlParser.parse("https://a.com/a/b/..")
        assertEquals("/a/", u.path)
    }

    @Test
    fun userinfo() {
        val u = UrlParser.parse("https://user:pass@a.com/")
        assertEquals("user", u.username)
        assertEquals("pass", u.password)
        assertEquals("a.com", u.host)
    }

    @Test
    fun ipv4() {
        val u = UrlParser.parse("http://192.168.1.1:9000/x")
        assertEquals("192.168.1.1", u.host)
        assertEquals(9000, u.port)
    }

    @Test
    fun ipv6() {
        val u = UrlParser.parse("http://[2001:db8::1]/x")
        assertEquals("[2001:db8::1]", u.host)
    }

    @Test
    fun ipv6ConV4Embebido() {
        val u = UrlParser.parse("http://[::ffff:1.2.3.4]/")
        assertEquals("[::ffff:1.2.3.4]", u.host)
    }

    @Test
    fun tabsYSaltosSeIgnoran() {
        val u = UrlParser.parse("  ht\ttp://a.com\t/x\n")
        assertEquals("http", u.scheme)
        assertEquals("a.com", u.host)
        assertEquals("/x", u.path)
    }

    @Test
    fun fileSinHost() {
        val u = UrlParser.parse("file:///home/x.html")
        assertEquals("file", u.scheme)
        assertEquals("", u.host)
        assertEquals("/home/x.html", u.path)
    }

    @Test
    fun toStringReconstruye() {
        val s = "http://example.com:8080/a/b?x=1#f"
        assertEquals(s, UrlParser.parse(s).toString())
    }

    @Test
    fun errores() {
        assertFailsWith<UrlParseException> { UrlParser.parse("no-es-url") }
        assertFailsWith<UrlParseException> { UrlParser.parse("mailto:a@b.com") }
        assertFailsWith<UrlParseException> { UrlParser.parse("http:///path") }
        assertFailsWith<UrlParseException> { UrlParser.parse("http://a.com:99999/") }
        assertFailsWith<UrlParseException> { UrlParser.parse("http://a%.com/") }
        assertFailsWith<UrlParseException> { UrlParser.parse("http://exa mple.com/") }
    }

    @Test
    fun idnaDominioNoAscii() {
        val u = UrlParser.parse("https://españa.com/")
        assertEquals("xn--espaa-rta.com", u.host)
    }

    @Test
    fun idnaVectoresPunycode() {
        assertEquals("xn--bcher-kva.example", UrlParser.parse("https://bücher.example/").host)
        assertEquals("xn--wgv71a.jp", UrlParser.parse("https://日本.jp/").host)
    }

    @Test
    fun idnaMinusculasUnicode() {
        assertEquals("xn--ber-goa.com", UrlParser.parse("https://ÜBER.com/").host)
        assertEquals("xn--espaa-rta.com", UrlParser.parse("https://ESPAÑA.COM/").host)
    }

    @Test
    fun idnaYaPunycodeNoCambia() {
        assertEquals("xn--espaa-rta.com", UrlParser.parse("https://xn--espaa-rta.com/").host)
    }
}
