package nia.engine.net

import nia.contracts.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del gestor de cookies (T101 v1, RES-0099): espejo del gemelo
 * TS (36 checks en verde antes del push) sobre el CookieJar REAL:
 * identidad compuesta (nombre, dominio, ruta, host-only) del RFC
 * 6265 5.3 -la MISMA del reemplazo de storeFromResponse-, secretos
 * ocultos por defecto, expiracion honesta (sesion/caducada/fecha
 * UTC) y presentacion fiel del almacen vivo.
 */
class GestorCookiesTest {

    private class RelojFijo : WallClock {
        override fun nowMillis(): Long = 0L
    }

    private fun jar(): CookieJar = CookieJar(RelojFijo())

    @Test
    fun borrarUnaPorIdentidadCompuesta() {
        val j = jar()
        assertTrue(j.storeFromResponse("a.com", true, "sid=1"))
        assertTrue(j.storeFromResponse("a.com", true, "sid=2; Path=/app"))
        assertTrue(j.borrarUna("sid", "a.com", "/", true))
        assertEquals(1, j.count())
        assertEquals("/app", j.all()[0].path)
    }

    @Test
    fun borrarUnaRutaDistintaNoBorra() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        j.storeFromResponse("a.com", true, "sid=2; Path=/app")
        assertFalse(j.borrarUna("sid", "a.com", "/x", true))
        assertEquals(2, j.count())
    }

    @Test
    fun borrarUnaDistingueHostOnlyDeDomain() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1; Domain=a.com")
        assertFalse(j.borrarUna("sid", "a.com", "/", true))
        assertTrue(j.borrarUna("sid", "a.com", "/", false))
        assertEquals(0, j.count())
    }

    @Test
    fun borrarUnaSinMatchDevuelveFalseYNoToca() {
        val j = jar()
        assertFalse(j.borrarUna("x", "y.com", "/", true))
        j.storeFromResponse("a.com", true, "sid=1")
        assertFalse(j.borrarUna("sid", "b.com", "/", true))
        assertEquals(1, j.count())
    }

    @Test
    fun borrarUnaNoTocaVecinas() {
        val j = jar()
        j.storeFromResponse("a.com", true, "sid=1")
        j.storeFromResponse("a.com", true, "tok=2")
        j.storeFromResponse("b.com", true, "sid=3")
        assertTrue(j.borrarUna("sid", "a.com", "/", true))
        assertEquals(listOf("tok", "sid"), j.all().map { it.name })
    }

    @Test
    fun valorPresentableOcultaPorDefecto() {
        assertEquals("****", GestorCookies.valorPresentable("secreto", false))
        assertEquals("secreto", GestorCookies.valorPresentable("secreto", true))
    }

    @Test
    fun expiraTextoSesionCaducadaYFecha() {
        assertEquals("sesion", GestorCookies.expiraTexto(null, 123L))
        assertEquals("caducada", GestorCookies.expiraTexto(-1L, 0L))
        assertEquals("caducada", GestorCookies.expiraTexto(99L, 1000L))
        assertEquals("2001-09-09 02:46:40Z", GestorCookies.expiraTexto(1000003600000L, 1000000000000L))
    }

    @Test
    fun sameSiteTextoPorDefectoLax() {
        assertEquals("lax", GestorCookies.sameSiteTexto(null))
        assertEquals("strict", GestorCookies.sameSiteTexto("strict"))
    }

    @Test
    fun filaDeOcultaYRevelaElValor() {
        val j = jar()
        j.storeFromResponse("a.com", true, "tok=secreto")
        val f = GestorCookies.filaDe(j.all()[0], false, 0L)
        assertEquals("****", f.valor)
        assertEquals("a.com", f.dominio)
        assertEquals("/", f.ruta)
        assertTrue(f.hostOnly)
        assertEquals("sesion", f.expira)
        assertEquals("lax", f.sameSite)
        assertEquals(GestorCookies.SIN_PARTICION, f.particion)
        assertEquals("secreto", GestorCookies.filaDe(j.all()[0], true, 0L).valor)
    }

    @Test
    fun textoFilaFormatoExacto() {
        val j = jar()
        j.storeFromResponse("a.com", true, "s=1; Secure; HttpOnly")
        assertEquals(
            "s = **** @ a.com/ | sesion | lax | host-only | httpOnly | secure",
            GestorCookies.texto(GestorCookies.filaDe(j.all()[0], false, 0L))
        )
        j.storeFromResponse("a.com", true, "d=1; Domain=a.com")
        assertEquals(
            "d = **** @ a.com/ | sesion | lax | domain",
            GestorCookies.texto(GestorCookies.filaDe(j.all()[1], false, 0L))
        )
    }

    @Test
    fun filasRespetaElOrdenDelJar() {
        val j = jar()
        j.storeFromResponse("a.com", true, "uno=1")
        j.storeFromResponse("b.com", true, "dos=2")
        j.storeFromResponse("c.com", true, "tres=3")
        assertEquals(listOf("uno", "dos", "tres"), GestorCookies.filas(j, false, 0L).map { it.nombre })
    }

    @Test
    fun textoMaxAgeFuturoMuestraFechaUtc() {
        val j = jar()
        j.storeFromResponse("a.com", true, "m=1; Max-Age=60")
        assertEquals(
            "m = **** @ a.com/ | 1970-01-01 00:01:00Z | lax | host-only",
            GestorCookies.texto(GestorCookies.filaDe(j.all()[0], false, 0L))
        )
    }
}
