package nia.engine.modules

import java.io.File
import java.nio.file.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline para el gestor (T094
 * v1, R0226, RES-0106, ADR-005): estados del gestor, alcance
 * global/sitio del host, contadores de actividad, fail-open ante un
 * hook roto y persistencia del alcance cuyo archivo corrupto se
 * ignora. Con el registro y el runtime reales (T009/T029/T030).
 */
class GestorModulosTest {

    private fun <T> ok(o: Outcome<T>): T {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as T
    }

    private fun nuevo(almacen: File? = null): GestorModulos {
        val registry = ModuleRegistry()
        return GestorModulos(registry, PluginRuntime(registry), almacen)
    }

    @Test
    fun instalarDemoActivaYFichaVisible() {
        val g = nuevo()
        ok(g.instalarDemo())
        val fichas = g.fichas()
        assertEquals(1, fichas.size)
        val f = fichas[0]
        assertEquals("demo.observador", f.id)
        assertEquals(EstadoModulo.ACTIVO, f.estado)
        assertEquals(1, f.hooksStartup)
        assertEquals(1, f.hooksNavigate)
        assertEquals(0, f.hooksRequest)
        assertTrue(f.alcanceGlobal)
    }

    @Test
    fun fichasMuestranMetadatosV2() {
        val g = nuevo()
        ok(g.instalarDemo())
        val f = g.ficha("demo.observador")!!
        assertEquals("Observador demo", f.name)
        assertEquals(2, f.version)
        assertTrue(f.categories.contains("background"))
        assertTrue(f.capabilities.contains("observar"))
        assertTrue(f.permissions.isEmpty())
    }

    @Test
    fun dispararNavegarCuentaYVeredicto() {
        val g = nuevo()
        ok(g.instalarDemo())
        val res = g.dispararNavegar("https://ejemplo.test:443", "https://ejemplo.test:443/a")
        assertEquals(1, res.disparados)
        assertEquals(0, res.errores)
        val f = g.ficha("demo.observador")!!
        assertEquals(1, f.disparos)
        assertEquals(0, f.errores)
        assertEquals("observa", f.ultimoVeredicto)
    }

    @Test
    fun alcanceGlobalDisparaEnCualquierOrigen() {
        val g = nuevo()
        ok(g.instalarDemo())
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
        assertEquals(1, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
    }

    @Test
    fun alcanceSoloSitioFiltra() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.soloEnEsteSitio("demo.observador", "https://a.test:443"))
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
        assertEquals(0, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
        val f = g.ficha("demo.observador")!!
        assertTrue(!f.alcanceGlobal)
        assertEquals(listOf("https://a.test:443"), f.alcanceOrigenes)
    }

    @Test
    fun hacerGlobalRestaura() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.soloEnEsteSitio("demo.observador", "https://a.test:443"))
        ok(g.hacerGlobal("demo.observador"))
        assertEquals(1, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
    }

    @Test
    fun desactivarDetieneLosDisparos() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desactivar("demo.observador"))
        assertEquals(EstadoModulo.DESACTIVADO, g.ficha("demo.observador")!!.estado)
        assertEquals(0, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun reactivarVuelveADisparar() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desactivar("demo.observador"))
        ok(g.activar("demo.observador"))
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun hookRotoCuentaErrorYNoRompe() {
        val g = nuevo()
        ok(
            g.instalar(
                "id = demo.roto\nversion = 2\nentry = roto.js\ncapabilities = observar\n",
                "nia.onNavigate(function (url) { throw new Error(\"roto\"); });\n"
            )
        )
        val res = g.dispararNavegar("https://a.test:443", "https://a.test:443")
        assertEquals(1, res.disparados)
        assertEquals(1, res.errores)
        val f = g.ficha("demo.roto")!!
        assertEquals(EstadoModulo.ERROR, f.estado)
        assertEquals(1, f.errores)
        assertEquals(1, f.disparos)
    }

    @Test
    fun desinstalarRetiraTodo() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desinstalar("demo.observador"))
        assertEquals(0, g.fichas().size)
        assertTrue(g.desinstalar("demo.observador") is Outcome.Failure)
        assertEquals(0, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun activarSinEntryConocidaFalla() {
        val g = nuevo()
        assertTrue(g.activar("nada.modulo") is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (g.activar("nada.modulo") as Outcome.Failure).error.code)
    }

    @Test
    fun soloEnSitioDeNoInstaladoFalla() {
        val g = nuevo()
        assertTrue(g.soloEnEsteSitio("nada.modulo", "https://a.test:443") is Outcome.Failure)
    }

    @Test
    fun alcancePersisteEntreInstancias() {
        val dir = Files.createTempDirectory("gestor-test").toFile()
        try {
            val almacen = File(dir, "modulos.tsv")
            val g1 = nuevo(almacen)
            ok(g1.instalarDemo())
            ok(g1.soloEnEsteSitio("demo.observador", "https://a.test:443"))
            val g2 = nuevo(almacen)
            ok(g2.instalarDemo())
            val f = g2.ficha("demo.observador")!!
            assertTrue(!f.alcanceGlobal)
            assertEquals(listOf("https://a.test:443"), f.alcanceOrigenes)
            assertEquals(0, g2.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun archivoCorruptoSeIgnora() {
        val dir = Files.createTempDirectory("gestor-test").toFile()
        try {
            val almacen = File(dir, "modulos.tsv")
            almacen.writeText("basura sin tab\n\ndemo.observador\tQUE\n")
            val g = nuevo(almacen)
            ok(g.instalarDemo())
            val f = g.ficha("demo.observador")!!
            assertTrue(f.alcanceGlobal)
        } finally {
            dir.deleteRecursively()
        }
    }
}
