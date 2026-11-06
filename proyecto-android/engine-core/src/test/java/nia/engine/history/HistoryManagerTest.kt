package nia.engine.history

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.WallClock

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 2464.32773.839): los 16 tests cubren el registro con
 * redaccion (sin query ni fragmento), el rechazo de paginas
 * internas y otros esquemas, el dedup por URL con conteo de visitas
 * y fecha, el titulo vacio que usa la URL y el titulo truncado, el
 * orden recientes primero, la busqueda case-insensitive, el
 * borrado por id y por rango inclusivo, limpiar (Fire total), el
 * dia UTC, el presupuesto con desalojo de la mas vieja en registro
 * e import, el roundtrip exportar/importar, el export vacio, el
 * archivo corrupto que NUNCA toca el estado vivo y la persistencia
 * real entre reinicios.
 * T074 v1 (R0178, RES-0082).
 */
class HistoryManagerTest {

    private class FakeClock : WallClock {
        var now = 1000L
        override fun nowMillis(): Long = now
    }

    private var secuencia = 0

    private fun archivo(): File {
        secuencia += 1
        val dir = File(System.getProperty("java.io.tmpdir"), "nia-history-test")
        dir.mkdirs()
        return File(dir, "caso-" + secuencia + ".tsv")
    }

    @Test
    fun registraConRedaccionSinQueryNiFragmento() {
        val m = HistoryManager(FakeClock(), null)
        assertTrue(m.registrar("https://a.dev/p?token=secreto#f", "titulo A"))
        assertEquals(1, m.contar())
        val e = m.entradas()[0]
        assertEquals("https://a.dev/p", e.url)
        assertEquals("titulo A", e.titulo)
        assertEquals(1, e.visitas)
        assertEquals(1000L, e.fecha)
    }

    @Test
    fun rechazaInternasYOtrosEsquemas() {
        val m = HistoryManager(FakeClock(), null)
        assertFalse(m.registrar("about:nia", "x"))
        assertFalse(m.registrar("data:text/html,hola", "x"))
        assertFalse(m.registrar("ftp://a.dev/x", "x"))
        assertFalse(m.registrar("javascript:alert(1)", "x"))
        assertEquals(0, m.contar())
    }

    @Test
    fun revisitaDedupIncrementaVisitasYFecha() {
        val c = FakeClock()
        val m = HistoryManager(c, null)
        assertTrue(m.registrar("https://a.dev/p?x=1", "uno"))
        c.now = 2000L
        assertTrue(m.registrar("https://a.dev/p", "dos"))
        assertEquals(1, m.contar())
        val e = m.entradas()[0]
        assertEquals(2, e.visitas)
        assertEquals(2000L, e.fecha)
        assertEquals("dos", e.titulo)
    }

    @Test
    fun tituloVacioUsaLaUrlYElLargoSeTrunca() {
        val c = FakeClock()
        val m = HistoryManager(c, null)
        assertTrue(m.registrar("https://b.dev/", ""))
        assertEquals("https://b.dev/", m.entradas()[0].titulo)
        c.now = 2000L
        assertTrue(m.registrar("https://c.dev/", "x".repeat(100)))
        assertEquals(64, m.entradas()[0].titulo.length)
    }

    @Test
    fun ordenadasRecientesPrimero() {
        val c = FakeClock()
        val m = HistoryManager(c, null)
        m.registrar("https://a.dev/", "a")
        c.now = 2000L
        m.registrar("https://b.dev/", "b")
        c.now = 3000L
        m.registrar("https://c.dev/", "c")
        assertEquals(listOf("c", "b", "a"), m.entradas().map { it.titulo })
        c.now = 4000L
        m.registrar("https://a.dev/", "a2")
        assertEquals(listOf("a2", "c", "b"), m.entradas().map { it.titulo })
    }

    @Test
    fun buscarCaseInsensitiveEnUrlYTitulo() {
        val m = HistoryManager(FakeClock(), null)
        m.registrar("https://a.dev/pagina", "Noticias")
        m.registrar("https://b.dev/otra", "Deportes")
        assertEquals(1, m.buscar("A.DEV").size)
        assertEquals(1, m.buscar("noticias").size)
        assertEquals(2, m.buscar("dev").size)
        assertEquals(0, m.buscar("inexistente").size)
    }

    @Test
    fun borrarPorId() {
        val c = FakeClock()
        val m = HistoryManager(c, null)
        m.registrar("https://a.dev/", "a")
        c.now = 2000L
        m.registrar("https://b.dev/", "b")
        val idBorrable = m.entradas()[0].id
        assertTrue(m.borrar(idBorrable))
        assertEquals(1, m.contar())
        assertFalse(m.borrar(999L))
        assertEquals(1, m.contar())
    }

    @Test
    fun borrarRangoEsInclusivo() {
        val c = FakeClock()
        val m = HistoryManager(c, null)
        m.registrar("https://a.dev/", "a")
        c.now = 2000L
        m.registrar("https://b.dev/", "b")
        c.now = 3000L
        m.registrar("https://c.dev/", "c")
        assertEquals(1, m.borrarRango(2000L, 2000L))
        assertEquals(listOf("c", "a"), m.entradas().map { it.titulo })
    }

    @Test
    fun limpiarDevuelveElConYVuelveARegistrar() {
        val m = HistoryManager(FakeClock(), null)
        m.registrar("https://a.dev/", "a")
        m.registrar("https://b.dev/", "b")
        assertEquals(2, m.limpiar())
        assertEquals(0, m.contar())
        assertTrue(m.registrar("https://c.dev/", "c"))
        assertEquals(1, m.contar())
    }

    @Test
    fun diaDeUtcAgrupaPorDia() {
        assertEquals(0L, HistoryManager.diaDe(0L))
        assertEquals(0L, HistoryManager.diaDe(86399999L))
        assertEquals(1L, HistoryManager.diaDe(86400000L))
    }

    @Test
    fun presupuestoDesalojaALaMasVieja() {
        val c = FakeClock()
        val m = HistoryManager(c, null, 3)
        for (i in 1..4) {
            c.now = i * 1000L
            m.registrar("https://x" + i + ".dev/", "x" + i)
        }
        assertEquals(3, m.contar())
        assertEquals(listOf("x4", "x3", "x2"), m.entradas().map { it.titulo })
    }

    @Test
    fun exportarImportarRoundtrip() {
        val c = FakeClock()
        val m1 = HistoryManager(c, null)
        m1.registrar("https://a.dev/p?ignorada", "A")
        c.now = 2000L
        m1.registrar("https://b.dev/", "B")
        m1.registrar("https://b.dev/", "B2")
        val texto = m1.exportar()
        assertTrue(texto.startsWith("nia-history\t1"))
        val m2 = HistoryManager(FakeClock(), null)
        assertTrue(m2.importar(texto))
        assertEquals(m1.entradas(), m2.entradas())
        assertEquals(2, m2.contar())
        assertEquals(2, m2.entradas()[0].visitas)
    }

    @Test
    fun exportarVacioEImportarVacio() {
        val m = HistoryManager(FakeClock(), null)
        assertEquals("nia-history\t1", m.exportar())
        assertTrue(m.importar("nia-history\t1"))
        assertEquals(0, m.contar())
    }

    @Test
    fun importarCorruptoNuncaTocaElEstadoVivo() {
        val m = HistoryManager(FakeClock(), null)
        m.registrar("https://a.dev/", "a")
        assertFalse(m.importar("basura"))
        assertFalse(m.importar("nia-history\t1\nH\tbasura"))
        assertEquals(1, m.contar())
        assertEquals("https://a.dev/", m.entradas()[0].url)
    }

    @Test
    fun importarAplicaElPresupuesto() {
        val c = FakeClock()
        val m1 = HistoryManager(c, null)
        for (i in 1..4) {
            c.now = i * 1000L
            m1.registrar("https://x" + i + ".dev/", "x" + i)
        }
        val m2 = HistoryManager(FakeClock(), null, 3)
        assertTrue(m2.importar(m1.exportar()))
        assertEquals(3, m2.contar())
        assertEquals(listOf("x4", "x3", "x2"), m2.entradas().map { it.titulo })
    }

    @Test
    fun persisteEntreReiniciosYCorruptoSeIgnora() {
        val f = archivo()
        val c = FakeClock()
        val m1 = HistoryManager(c, f)
        m1.registrar("https://a.dev/x?q=1", "A")
        c.now = 2000L
        m1.registrar("https://b.dev/y", "B")
        val m2 = HistoryManager(FakeClock(), f)
        assertEquals(2, m2.contar())
        assertEquals("https://b.dev/y", m2.entradas()[0].url)
        assertEquals("https://a.dev/x", m2.entradas()[1].url)
        assertEquals(1, m2.entradas()[0].visitas)
        val roto = archivo()
        roto.writeText("esto no es un historial")
        assertEquals(0, HistoryManager(FakeClock(), roto).contar())
    }
}
