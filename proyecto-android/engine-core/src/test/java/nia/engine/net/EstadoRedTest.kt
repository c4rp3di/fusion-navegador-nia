package nia.engine.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del estado de red por pestana (T106 v1, RES-0100): espejo del
 * gemelo TS (22 checks en verde antes del push): redaccion en la
 * INGESTA con el SecretRedactor real (T008), contextualidad por
 * pestana (nunca se mezcla la red de otra pestana) y la linea de
 * dialogo con formato exacto.
 */
class EstadoRedTest {

    private fun reg(): RegistroRed = RegistroRed()

    @Test
    fun registrarExitoRedactaUserinfoEnLaIngesta() {
        val l = reg().registrar(1L, "https://user:pass@a.com/x", "http", 120L, true, null, "", 5000L)
        assertEquals("https://[redacted]@a.com/x", l.urlRedactada)
        assertTrue(l.exito)
        assertNull(l.codigo)
        assertEquals(120L, l.latenciaMillis)
        assertEquals(5000L, l.cuandoMillis)
    }

    @Test
    fun registrarRedactaSecretosDeQueryYDetalle() {
        val l = reg().registrar(3L, "https://c.com/z?token=xyz", "http", 77L, false, "INTERNAL", "bearer abc123 boom", 9L)
        assertEquals("https://c.com/z?token=[redacted]", l.urlRedactada)
        assertEquals("bearer [redacted] boom", l.detalleRedactado)
        assertFalse(l.exito)
        assertEquals("INTERNAL", l.codigo)
    }

    @Test
    fun registrarNoTocaLoQueNoEsSecreto() {
        val l = reg().registrar(1L, "https://a.com/x?b=2", "http", 10L, true, null, "", 1L)
        assertEquals("https://a.com/x?b=2", l.urlRedactada)
    }

    @Test
    fun registrarGuardaSoloLaUltimaDeLaPestana() {
        val r = reg()
        r.registrar(1L, "https://a.com/x", "http", 100L, true, null, "", 1L)
        r.registrar(1L, "https://a.com/y", "http", 200L, false, "NETWORK", "sin resolver DNS", 2L)
        assertEquals(200L, r.de(1L)!!.latenciaMillis)
        assertEquals("https://a.com/y", r.de(1L)!!.urlRedactada)
    }

    @Test
    fun pestanasNoSeMezclan() {
        val r = reg()
        assertNull(r.de(9L))
        r.registrar(1L, "https://a.com/x", "http", 100L, true, null, "", 1L)
        r.registrar(2L, "https://b.com/y", "http", 50L, false, "TLS", "certificado invalido", 2L)
        assertEquals("https://a.com/x", r.de(1L)!!.urlRedactada)
        assertEquals("https://b.com/y", r.de(2L)!!.urlRedactada)
    }

    @Test
    fun borrarRetiraSoloEsaPestana() {
        val r = reg()
        r.registrar(1L, "https://a.com/x", "http", 100L, true, null, "", 1L)
        r.registrar(2L, "https://b.com/y", "http", 50L, true, null, "", 2L)
        val borrada = r.borrar(1L)
        assertEquals(1L, borrada!!.tabId)
        assertNull(r.de(1L))
        assertEquals("https://b.com/y", r.de(2L)!!.urlRedactada)
        assertEquals(1, r.contar())
    }

    @Test
    fun contarEsReal() {
        val r = reg()
        assertEquals(0, r.contar())
        r.registrar(1L, "a", "http", 1L, true, null, "", 1L)
        r.registrar(2L, "b", "http", 1L, true, null, "", 1L)
        assertEquals(2, r.contar())
    }

    @Test
    fun textoExitoFormatoExacto() {
        val l = LineaRed(1L, "https://a.com/x", "http", 120L, true, null, "", 0L)
        assertEquals("pestana 1 | https://a.com/x | http | 120 ms | ok", l.texto())
    }

    @Test
    fun textoFalloFormatoExacto() {
        val l = LineaRed(2L, "https://b.com/y", "http", 50L, false, "NETWORK", "sin resolver DNS", 0L)
        assertEquals("pestana 2 | https://b.com/y | http | 50 ms | fallo NETWORK: sin resolver DNS", l.texto())
    }

    @Test
    fun textoConSecretosYaRedactados() {
        val r = reg()
        r.registrar(3L, "https://c.com/z?token=xyz", "http", 77L, false, "INTERNAL", "bearer abc123 boom", 9L)
        assertEquals("pestana 3 | https://c.com/z?token=[redacted] | http | 77 ms | fallo INTERNAL: bearer [redacted] boom", r.de(3L)!!.texto())
    }

    @Test
    fun registrarDevuelveLaLineaGuardada() {
        val r = reg()
        val l = r.registrar(1L, "https://a.com/x", "http", 10L, true, null, "", 42L)
        assertEquals(l, r.de(1L))
    }

    @Test
    fun redaccionDeterministaEntreLlamadas() {
        val a = reg().registrar(1L, "https://user:pass@a.com/x", "http", 1L, true, null, "", 1L)
        val b = reg().registrar(1L, "https://user:pass@a.com/x", "http", 1L, true, null, "", 1L)
        assertEquals(a.urlRedactada, b.urlRedactada)
    }
}
