package nia.engine.settings

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 60360.47986.977): los 19 tests cubren el registro con
 * validacion (id, duplicado, defecto por tipo), asignar por tipo
 * (BOOL exacto, ENTERO en rango, ELECCION cerrada, TEXTO acotado
 * sin tabuladores), la opcion no disponible que JAMAS cambia, la
 * busqueda case-insensitive en los cuatro campos, las secciones
 * en orden de registro, restablecer una y todo, el export que
 * SOLO lleva configurables (secretas y no disponibles JAMAS), el
 * roundtrip exportar/importar, el import que rechaza lo que no
 * viaja (desconocida, secreta, no disponible, version 2) sin
 * tocar el estado vivo, la persistencia con recargar() tras
 * registrar (registrar NUNCA pisa el archivo) y el archivo
 * corrupto que se ignora.
 * T093 v1 (R0179, RES-0083).
 */
class SettingsManagerTest {

    private var secuencia = 0

    private fun archivo(): File {
        secuencia += 1
        val dir = File(System.getProperty("java.io.tmpdir"), "nia-settings-test")
        dir.mkdirs()
        return File(dir, "caso-" + secuencia + ".tsv")
    }

    private fun opBool(id: String, defecto: String): OpcionAjuste =
        OpcionAjuste(id, "Prueba", "opcion " + id, "desc", TipoOpcion.BOOL, defecto)

    @Test
    fun registrarAceptaYValorPorDefecto() {
        val m = SettingsManager()
        assertTrue(m.registrar(opBool("a.b", "true")))
        assertEquals(1, m.contar())
        assertEquals("true", m.valor("a.b"))
        assertNull(m.valor("no.existe"))
    }

    @Test
    fun registrarRechazaIdDuplicadoEInvalido() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        assertFalse(m.registrar(opBool("a.b", "false")))
        assertFalse(m.registrar(opBool("MAYUS", "true")))
        assertFalse(m.registrar(opBool("", "true")))
        assertFalse(m.registrar(opBool("con espacio", "true")))
        assertEquals(1, m.contar())
        assertEquals("true", m.valor("a.b"))
    }

    @Test
    fun registrarRechazaDefectoInvalido() {
        val m = SettingsManager()
        assertFalse(m.registrar(opBool("bool.malo", "si")))
        assertFalse(
            m.registrar(
                OpcionAjuste("entero.malo", "S", "t", "d", TipoOpcion.ENTERO, "999", 1L, 10L)
            )
        )
        assertFalse(
            m.registrar(
                OpcionAjuste("elec.mala", "S", "t", "d", TipoOpcion.ELECCION, "rojo", elecciones = listOf("claro", "oscuro"))
            )
        )
        assertFalse(
            m.registrar(OpcionAjuste("texto.malo", "S", "t", "d", TipoOpcion.TEXTO, ""))
        )
        assertEquals(0, m.contar())
    }

    @Test
    fun asignarBoolValida() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        assertTrue(m.asignar("a.b", "false"))
        assertEquals("false", m.valor("a.b"))
        assertFalse(m.asignar("a.b", "verdadero"))
        assertEquals("false", m.valor("a.b"))
    }

    @Test
    fun asignarEnteroEnRango() {
        val m = SettingsManager()
        m.registrar(OpcionAjuste("entero.ok", "S", "t", "d", TipoOpcion.ENTERO, "10", 10L, 200L))
        assertTrue(m.asignar("entero.ok", "200"))
        assertFalse(m.asignar("entero.ok", "201"))
        assertFalse(m.asignar("entero.ok", "9"))
        assertFalse(m.asignar("entero.ok", "abc"))
        assertEquals("200", m.valor("entero.ok"))
    }

    @Test
    fun asignarEleccionCerrada() {
        val m = SettingsManager()
        m.registrar(
            OpcionAjuste("eleccion.ok", "S", "t", "d", TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro"))
        )
        assertTrue(m.asignar("eleccion.ok", "oscuro"))
        assertFalse(m.asignar("eleccion.ok", "verde"))
        assertEquals("oscuro", m.valor("eleccion.ok"))
    }

    @Test
    fun asignarTextoValida() {
        val m = SettingsManager()
        m.registrar(OpcionAjuste("texto.ok", "S", "t", "d", TipoOpcion.TEXTO, "hola"))
        assertTrue(m.asignar("texto.ok", "adios"))
        assertFalse(m.asignar("texto.ok", ""))
        assertFalse(m.asignar("texto.ok", "con\ttab"))
        assertFalse(m.asignar("texto.ok", "x".repeat(65)))
        assertEquals("adios", m.valor("texto.ok"))
    }

    @Test
    fun noDisponibleJamasCambia() {
        val m = SettingsManager()
        m.registrar(OpcionAjuste("nodisp.x", "S", "t", "d", TipoOpcion.BOOL, "true", disponible = false))
        assertFalse(m.asignar("nodisp.x", "false"))
        assertEquals("true", m.valor("nodisp.x"))
    }

    @Test
    fun buscarCaseInsensitiveEnLosCuatroCampos() {
        val m = SettingsManager()
        m.registrar(
            OpcionAjuste("privacidad.dnt", "Privacidad", "No rastrear", "Cabecera DNT", TipoOpcion.BOOL, "false")
        )
        assertEquals(1, m.buscar("DNT").size)
        assertEquals(1, m.buscar("PRIVACIDAD").size)
        assertEquals(1, m.buscar("No rastrear").size)
        assertEquals(1, m.buscar("cabecera").size)
        assertEquals(0, m.buscar("zzz").size)
    }

    @Test
    fun seccionesEnOrdenDeRegistro() {
        val m = SettingsManager()
        m.registrar(OpcionAjuste("a.x", "Prueba", "t", "d", TipoOpcion.BOOL, "true"))
        m.registrar(OpcionAjuste("b.x", "Privacidad", "t", "d", TipoOpcion.BOOL, "true"))
        m.registrar(OpcionAjuste("c.x", "Prueba", "t2", "d", TipoOpcion.BOOL, "true"))
        assertEquals(listOf("Prueba", "Privacidad"), m.secciones())
    }

    @Test
    fun restablecerUnaYTodoCuentaCambiadas() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        m.registrar(opBool("c.d", "false"))
        m.asignar("a.b", "false")
        assertTrue(m.restablecer("a.b"))
        assertEquals("true", m.valor("a.b"))
        m.asignar("a.b", "false")
        m.asignar("c.d", "true")
        assertEquals(2, m.restablecerTodo())
        assertEquals("true", m.valor("a.b"))
        assertEquals("false", m.valor("c.d"))
        assertEquals(0, m.restablecerTodo())
    }

    @Test
    fun exportarSoloConfigurables() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        m.registrar(OpcionAjuste("nodisp.x", "S", "t", "d", TipoOpcion.BOOL, "true", disponible = false))
        m.registrar(OpcionAjuste("secreta.x", "S", "t", "d", TipoOpcion.BOOL, "true", secreta = true))
        val texto = m.exportar()
        assertTrue(texto.startsWith("nia-settings\t1"))
        assertFalse(texto.contains("nodisp.x"))
        assertFalse(texto.contains("secreta.x"))
        assertTrue(texto.contains("S\ta.b\ttrue"))
    }

    @Test
    fun exportarImportarRoundtrip() {
        val m1 = SettingsManager()
        m1.registrar(opBool("a.b", "true"))
        m1.registrar(OpcionAjuste("elec.x", "S", "t", "d", TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro")))
        m1.asignar("a.b", "false")
        m1.asignar("elec.x", "oscuro")
        val texto = m1.exportar()
        val m2 = SettingsManager()
        m2.registrar(opBool("a.b", "true"))
        m2.registrar(OpcionAjuste("elec.x", "S", "t", "d", TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro")))
        assertTrue(m2.importar(texto))
        assertEquals("false", m2.valor("a.b"))
        assertEquals("oscuro", m2.valor("elec.x"))
    }

    @Test
    fun importarRechazaLoQueNoViaja() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        m.registrar(OpcionAjuste("secreta.x", "S", "t", "d", TipoOpcion.BOOL, "true", secreta = true))
        m.registrar(OpcionAjuste("nodisp.x", "S", "t", "d", TipoOpcion.BOOL, "true", disponible = false))
        assertFalse(m.importar("nia-settings\t1\nS\tdesconocida\ttrue"))
        assertFalse(m.importar("nia-settings\t1\nS\tsecreta.x\tfalse"))
        assertFalse(m.importar("nia-settings\t1\nS\tnodisp.x\tfalse"))
        assertFalse(m.importar("nia-settings\t2\nS\ta.b\tfalse"))
        assertEquals("true", m.valor("a.b"))
    }

    @Test
    fun importarCorruptoNoTocaElEstadoVivo() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        m.asignar("a.b", "false")
        val antes = m.exportar()
        assertFalse(m.importar("basura"))
        assertFalse(m.importar("nia-settings\t1\nX\ta.b\tfalse"))
        assertFalse(m.importar("nia-settings\t1\nS\ta.b"))
        assertFalse(m.importar("nia-settings\t1\nS\ta.b\tzzz"))
        assertEquals(antes, m.exportar())
    }

    @Test
    fun importarVacioYSoloHeaderEsValido() {
        val m = SettingsManager()
        m.registrar(opBool("a.b", "true"))
        assertTrue(m.importar("nia-settings\t1"))
        assertEquals("true", m.valor("a.b"))
    }

    @Test
    fun persisteEntreReiniciosYCorruptoSeIgnora() {
        val f = archivo()
        val m1 = SettingsManager(f)
        m1.registrar(opBool("a.b", "true"))
        m1.registrar(OpcionAjuste("elec.x", "S", "t", "d", TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro")))
        m1.asignar("a.b", "false")
        m1.asignar("elec.x", "oscuro")
        val m2 = SettingsManager(f)
        m2.registrar(opBool("a.b", "true"))
        m2.registrar(OpcionAjuste("elec.x", "S", "t", "d", TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro")))
        assertTrue(m2.recargar())
        assertEquals("false", m2.valor("a.b"))
        assertEquals("oscuro", m2.valor("elec.x"))
        val roto = archivo()
        roto.writeText("esto no es un ajuste")
        val m3 = SettingsManager(roto)
        m3.registrar(opBool("a.b", "true"))
        assertFalse(m3.recargar())
        assertEquals("true", m3.valor("a.b"))
    }

    @Test
    fun registrarNoPisaElArchivoAntesDeRecargar() {
        val f = archivo()
        val m1 = SettingsManager(f)
        m1.registrar(opBool("a.b", "true"))
        m1.asignar("a.b", "false")
        val guardado = f.readText()
        assertTrue(guardado.contains("S\ta.b\tfalse"))
        val m2 = SettingsManager(f)
        m2.registrar(opBool("a.b", "true"))
        assertEquals(guardado, f.readText())
        assertTrue(m2.recargar())
        assertEquals("false", m2.valor("a.b"))
    }

    @Test
    fun recargarSinArchivoDejaLosDefectos() {
        val m = SettingsManager(null)
        m.registrar(opBool("a.b", "true"))
        assertFalse(m.recargar())
        assertEquals("true", m.valor("a.b"))
    }
}
