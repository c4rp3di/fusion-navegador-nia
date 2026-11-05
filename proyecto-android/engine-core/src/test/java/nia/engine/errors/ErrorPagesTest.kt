package nia.engine.errors

import nia.contracts.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Espejo de los escenarios del gemelo TS de T083 v1 (R0157, RES-0076):
 * cada causa de :contracts mapea a su pagina (titulo, mensaje seguro y
 * accion), la cancelacion y el handle caducado NO reemplazan la pagina
 * util, los secretos NUNCA aparecen en la URL ni en el detalle y TLS no
 * ofrece recargar como recuperacion.
 */
class ErrorPagesTest {

    private fun pagina(code: ErrorCode, detalle: String, url: String?): ErrorPages.ErrorPage {
        val p = ErrorPages.desdeFallo(code, detalle, url)
        assertNotNull("debe generar pagina para " + code.name, p)
        return p ?: throw AssertionError("pagina nula inesperada para " + code.name)
    }

    @Test
    fun cancelacionNoReemplazaPaginaUtil() {
        assertNull(ErrorPages.desdeFallo(ErrorCode.CANCELLED, "user cancel", "https://a.test/x"))
    }

    @Test
    fun handleCaducadoNoReemplazaPaginaUtil() {
        assertNull(ErrorPages.desdeFallo(ErrorCode.STALE_HANDLE, "handle gone", null))
    }

    @Test
    fun cancelacionVaciaTampocoReemplaza() {
        assertNull(ErrorPages.desdeFallo(ErrorCode.CANCELLED, "", null))
    }

    @Test
    fun esquemaNoSoportadoVuelve() {
        val p = pagina(ErrorCode.UNSUPPORTED, "ftp no soportado", "ftp://a.test")
        assertEquals("Esquema no soportado", p.titulo)
        assertEquals(ErrorPages.Accion.VOLVER, p.accion)
    }

    @Test
    fun direccionInvalidaVuelve() {
        val p = pagina(ErrorCode.INVALID_INPUT, "parse error", "about:blank")
        assertEquals("Direccion no valida", p.titulo)
        assertEquals(ErrorPages.Accion.VOLVER, p.accion)
    }

    @Test
    fun accesoDenegadoVuelve() {
        val p = pagina(ErrorCode.DENIED, "denied by policy", "https://a.test")
        assertEquals("Acceso denegado", p.titulo)
        assertEquals(ErrorPages.Accion.VOLVER, p.accion)
    }

    @Test
    fun reglaDeOrigenNoOfreceAccion() {
        val p = pagina(ErrorCode.ORIGIN_VIOLATION, "origin rule", "https://a.test")
        assertEquals("Regla de origen", p.titulo)
        assertEquals(ErrorPages.Accion.NINGUNA, p.accion)
    }

    @Test
    fun tiempoAgotadoRecarga() {
        val p = pagina(ErrorCode.DEADLINE, "timeout 5000ms", "https://a.test/lento")
        assertEquals("Tiempo agotado", p.titulo)
        assertEquals(ErrorPages.Accion.RECARGAR, p.accion)
    }

    @Test
    fun limiteAlcanzadoRecarga() {
        val p = pagina(ErrorCode.QUOTA, "quota exceeded", "https://a.test")
        assertEquals("Limite alcanzado", p.titulo)
        assertEquals(ErrorPages.Accion.RECARGAR, p.accion)
    }

    @Test
    fun procesoDetenidoRecarga() {
        val p = pagina(ErrorCode.PROCESS_GONE, "renderer gone", "https://a.test")
        assertEquals("Proceso detenido", p.titulo)
        assertEquals(ErrorPages.Accion.RECARGAR, p.accion)
    }

    @Test
    fun sinRedRecarga() {
        val p = pagina(ErrorCode.NETWORK, "DNS no resuelve", "https://a.test")
        assertEquals("Sin red", p.titulo)
        assertEquals(ErrorPages.Accion.RECARGAR, p.accion)
    }

    @Test
    fun tlsNuncaOfreceRecargar() {
        val p = pagina(ErrorCode.TLS, "certificado invalido", "https://a.test")
        assertEquals("Conexion no segura", p.titulo)
        assertFalse(p.accion == ErrorPages.Accion.RECARGAR)
    }

    @Test
    fun paginaIlegibleVuelve() {
        val p = pagina(ErrorCode.PARSE, "bytes raros", "https://a.test/bin")
        assertEquals("Pagina ilegible", p.titulo)
        assertEquals(ErrorPages.Accion.VOLVER, p.accion)
    }

    @Test
    fun falloInternoRecarga() {
        val p = pagina(ErrorCode.INTERNAL, "boom", "https://a.test")
        assertEquals("Fallo interno", p.titulo)
        assertEquals(ErrorPages.Accion.RECARGAR, p.accion)
    }

    @Test
    fun onceCodigosGeneranPaginaYDosNull() {
        var conPagina = 0
        var nulos = 0
        for (code in ErrorCode.values()) {
            val p = ErrorPages.desdeFallo(code, "d", "https://a.test")
            if (p == null) {
                nulos += 1
            } else {
                conPagina += 1
            }
        }
        assertEquals(11, conPagina)
        assertEquals(2, nulos)
    }

    @Test
    fun titulosDistintosPorCodigo() {
        val titulos = HashSet<String>()
        for (code in ErrorCode.values()) {
            val p = ErrorPages.desdeFallo(code, "d", null)
            if (p != null) {
                assertTrue(titulos.add(p.titulo))
            }
        }
        assertEquals(11, titulos.size)
    }

    @Test
    fun urlConTokenSeRedacta() {
        val p = pagina(ErrorCode.NETWORK, "DNS", "https://a.test/x?token=secreto1")
        assertEquals("https://a.test/x?token=[redacted]", p.urlRedactada)
    }

    @Test
    fun urlConUserInfoSeRedacta() {
        val p = pagina(ErrorCode.NETWORK, "DNS", "https://user:clave@a.test/x")
        assertEquals("https://[redacted]@a.test/x", p.urlRedactada)
    }

    @Test
    fun urlConPasswordSeRedacta() {
        val p = pagina(ErrorCode.NETWORK, "DNS", "https://a.test/x?password=ocluso")
        assertEquals("https://a.test/x?password=[redacted]", p.urlRedactada)
    }

    @Test
    fun detalleConBearerSeRedacta() {
        val p = pagina(ErrorCode.INTERNAL, "Authorization: Bearer abc123def", null)
        assertEquals("Authorization: Bearer [redacted]", p.detalleRedactado)
    }

    @Test
    fun detalleConApiKeySeRedacta() {
        val p = pagina(ErrorCode.INTERNAL, "fallo con api-key=xyz y demas", null)
        assertEquals("fallo con api-key=[redacted] y demas", p.detalleRedactado)
    }

    @Test
    fun urlNulaYDetalleLimpioIntacto() {
        val p = pagina(ErrorCode.NETWORK, "DNS no resuelve", null)
        assertNull(p.urlRedactada)
        assertEquals("DNS no resuelve", p.detalleRedactado)
    }
}
