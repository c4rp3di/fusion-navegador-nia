package nia.engine.sitios

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de preferencias por sitio (T080 v1, RES-0097): espejo del
 * gemelo TS (28 checks en verde antes del push). La clave es el
 * origen, los limites son honestos y clampados, los defectos nunca
 * se persisten, el tope expulsa al mas viejo (re-fijar rejuvenece),
 * el import es TODO-o-NADA y la persistencia real usa un archivo
 * temporal (un corrupto se ignora y quedan los defectos).
 */
class AjustesSitioTest {

    @Test
    fun defectoYLimites() {
        val d = PrefSitio()
        assertEquals(1.0, d.zoom, 0.0)
        assertEquals(1.0, d.texto, 0.0)
        assertFalse(d.escritorio)
        assertTrue(AjustesSitio.esDefecto(d))
        assertEquals(0.5, AjustesSitio.limitarZoom(0.4), 0.0)
        assertEquals(3.0, AjustesSitio.limitarZoom(5.0), 0.0)
        assertEquals(1.25, AjustesSitio.limitarZoom(1.25), 0.0)
        assertEquals(0.75, AjustesSitio.limitarTexto(0.5), 0.0)
        assertEquals(1.5, AjustesSitio.limitarTexto(2.0), 0.0)
    }

    @Test
    fun viewportBase() {
        assertEquals(360.0, AjustesSitio.viewportBase(false), 0.0)
        assertEquals(720.0, AjustesSitio.viewportBase(true), 0.0)
    }

    @Test
    fun obtenerSinRegistrarDaDefecto() {
        val m = SitiosManager()
        assertEquals(PrefSitio(), m.obtener("https://a:443"))
        assertEquals(0, m.total())
    }

    @Test
    fun fijarYObtener() {
        val m = SitiosManager()
        assertTrue(m.fijar("https://a:443", PrefSitio(1.25, 1.2, true)))
        assertEquals(1.25, m.obtener("https://a:443").zoom, 0.0)
        assertEquals(1.2, m.obtener("https://a:443").texto, 0.0)
        assertTrue(m.obtener("https://a:443").escritorio)
        assertEquals(1, m.total())
    }

    @Test
    fun fijarClampaValores() {
        val m = SitiosManager()
        m.fijar("https://b:443", PrefSitio(9.0, 0.1, false))
        assertEquals(3.0, m.obtener("https://b:443").zoom, 0.0)
        assertEquals(0.75, m.obtener("https://b:443").texto, 0.0)
    }

    @Test
    fun fijarDefectoBorraLaEntrada() {
        val m = SitiosManager()
        m.fijar("https://c:443", PrefSitio(1.5, 1.0, false))
        assertEquals(1, m.total())
        assertTrue(m.fijar("https://c:443", PrefSitio()))
        assertEquals(0, m.total())
    }

    @Test
    fun clavesInvalidasRechazadas() {
        val m = SitiosManager()
        assertFalse(m.fijar("", PrefSitio(1.5, 1.0, false)))
        assertFalse(m.fijar("x".repeat(201), PrefSitio(1.5, 1.0, false)))
        assertTrue(m.fijar("x".repeat(200), PrefSitio(1.5, 1.0, false)))
    }

    @Test
    fun borrarYBorrarTodo() {
        val m = SitiosManager()
        m.fijar("k1", PrefSitio(1.25, 1.1, true))
        m.fijar("k2", PrefSitio(2.0, 1.0, false))
        assertTrue(m.borrar("k1"))
        assertFalse(m.borrar("k1"))
        assertEquals(1, m.total())
        assertEquals(1, m.borrarTodo())
        assertEquals(0, m.borrarTodo())
    }

    @Test
    fun topeExpulsaAlMasViejo() {
        val m = SitiosManager()
        for (i in 0..100) {
            m.fijar("s" + i, PrefSitio(1.5, 1.0, false))
        }
        assertEquals(AjustesSitio.MAX_SITIOS, m.total())
        assertEquals("s1", m.claves()[0])
        assertEquals("s100", m.claves()[m.total() - 1])
    }

    @Test
    fun reFijarRejuvenece() {
        val m = SitiosManager()
        for (i in 0 until AjustesSitio.MAX_SITIOS) {
            m.fijar("s" + i, PrefSitio(1.5, 1.0, false))
        }
        m.fijar("s0", PrefSitio(2.0, 1.0, false))
        m.fijar("nuevo", PrefSitio(1.5, 1.0, false))
        assertEquals(AjustesSitio.MAX_SITIOS, m.total())
        assertEquals("s2", m.claves()[0])
        assertFalse(m.claves().contains("s1"))
        assertTrue(m.claves().contains("s0"))
        assertEquals(2.0, m.obtener("s0").zoom, 0.0)
    }

    @Test
    fun exportarFormato() {
        val m = SitiosManager()
        m.fijar("https://a:443", PrefSitio(1.25, 1.2, true))
        val lineas = m.exportar().split('\n')
        assertEquals("nia-sitios\t1", lineas[0])
        assertEquals("S\thttps://a:443\t1.250\t1.200\ttrue", lineas[1])
    }

    @Test
    fun roundtripExportImport() {
        val m = SitiosManager()
        m.fijar("a", PrefSitio(1.5, 1.0, false))
        m.fijar("b", PrefSitio(2.5, 1.25, true))
        val m2 = SitiosManager()
        assertTrue(m2.importar(m.exportar()))
        assertEquals(2, m2.total())
        assertEquals("a", m2.claves()[0])
        assertEquals(2.5, m2.obtener("b").zoom, 0.0)
        assertTrue(m2.obtener("b").escritorio)
    }

    @Test
    fun importInvalidoNoTocaElVivo() {
        val m = SitiosManager()
        m.fijar("vivo", PrefSitio(1.5, 1.0, false))
        assertFalse(m.importar("nia-sitios\t2\nS\tvivo\t1.000\t1.000\tfalse"))
        assertFalse(m.importar("nia-sitios\t1\nX\tvivo\t1.000\t1.000\tfalse"))
        assertFalse(m.importar("nia-sitios\t1\nS\tx\t9.000\t1.000\tfalse"))
        assertFalse(m.importar("nia-sitios\t1\nS\tx\tNaN\t1.000\tfalse"))
        assertFalse(m.importar("nia-sitios\t1\nS\tx\t1.000\t1.000\tsi"))
        assertFalse(m.importar("nia-sitios\t1\nS\tx\t1.000\t1.000\tfalse\nS\tx\t1.000\t1.000\tfalse"))
        assertEquals(1, m.total())
        assertEquals(1.5, m.obtener("vivo").zoom, 0.0)
    }

    @Test
    fun importVacioDejaCero() {
        val m = SitiosManager()
        assertTrue(m.importar("nia-sitios\t1"))
        assertEquals(0, m.total())
    }

    @Test
    fun escapeDeClaveRara() {
        val m = SitiosManager()
        val clave = "ht\ttps://r\\ara\n.x:1"
        m.fijar(clave, PrefSitio(0.75, 1.5, true))
        val m2 = SitiosManager()
        assertTrue(m2.importar(m.exportar()))
        assertEquals(1, m2.total())
        assertEquals(clave, m2.claves()[0])
        assertEquals(0.75, m2.obtener(clave).zoom, 0.0)
        assertEquals(1.5, m2.obtener(clave).texto, 0.0)
    }

    @Test
    fun persistenciaRealConArchivo() {
        val f = File.createTempFile("sitios", ".tsv")
        f.deleteOnExit()
        val m = SitiosManager(f)
        m.fijar("https://a:443", PrefSitio(1.25, 1.1, true))
        val m2 = SitiosManager(f)
        assertTrue(m2.recargar())
        assertEquals(1.25, m2.obtener("https://a:443").zoom, 0.0)
        assertTrue(m2.obtener("https://a:443").escritorio)
        f.writeText("chatarra")
        val m3 = SitiosManager(f)
        assertFalse(m3.recargar())
        assertEquals(PrefSitio(), m3.obtener("https://a:443"))
    }
}
