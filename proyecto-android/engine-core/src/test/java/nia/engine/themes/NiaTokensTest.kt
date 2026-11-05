package nia.engine.themes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas identicas,
 * huella 28f9254d.101fd178.278): los 15 tests cubren la resolucion de
 * modo claro/oscuro/auto, los builtin con sus pares AA (WCAG 2.1), el
 * tema activo custom, el contraste y el catalogo comprobable de 27
 * tokens. T069 v1 (R0144, RES-0073, ADR-021).
 */
class NiaTokensTest {

    private val temaPlaya: String =
        "id = sol.playa\nname = Playa\nbg = #fdf6e3\nsurface = #eee8d5\n" +
            "fg = #657b83\naccent = #2aa198\nwarn = #cb4b16\nerror = #dc322f"

    @Test
    fun builtinClaroResuelveLosSeisColores() {
        val c = NiaTokens.builtin(NiaTokens.Modo.CLARO, false)
        assertEquals("#ffffff", c.bg)
        assertEquals("#f5f5f5", c.surface)
        assertEquals("#1a1a1a", c.fg)
        assertEquals("#0066cc", c.accent)
        assertEquals("#b45309", c.warn)
        assertEquals("#b91c1c", c.error)
    }

    @Test
    fun builtinOscuroResuelveLosSeisColores() {
        val c = NiaTokens.builtin(NiaTokens.Modo.OSCURO, true)
        assertEquals("#0d1117", c.bg)
        assertEquals("#161b22", c.surface)
        assertEquals("#e6edf3", c.fg)
        assertEquals("#2f81f7", c.accent)
        assertEquals("#d29922", c.warn)
        assertEquals("#f85149", c.error)
    }

    @Test
    fun autoSigueAlSistema() {
        assertEquals(NiaTokens.Modo.CLARO, NiaTokens.resolverModo(NiaTokens.Modo.AUTO, false))
        assertEquals(NiaTokens.Modo.OSCURO, NiaTokens.resolverModo(NiaTokens.Modo.AUTO, true))
        assertEquals("#0d1117", NiaTokens.builtin(NiaTokens.Modo.AUTO, true).bg)
        assertEquals("#ffffff", NiaTokens.builtin(NiaTokens.Modo.AUTO, false).bg)
    }

    @Test
    fun modoFijoIgnoraAlSistema() {
        assertEquals("#0d1117", NiaTokens.builtin(NiaTokens.Modo.OSCURO, false).bg)
        assertEquals("#ffffff", NiaTokens.builtin(NiaTokens.Modo.CLARO, true).bg)
    }

    @Test
    fun delActivoDevuelveElTemaCustom() {
        val temas = ThemeManager()
        temas.install(temaPlaya)
        temas.activate("sol.playa")
        val c = NiaTokens.delActivo(temas)
        assertEquals("#fdf6e3", c.bg)
        assertEquals("#657b83", c.fg)
        assertEquals("#2aa198", c.accent)
    }

    @Test
    fun contrasteNegroSobreBlancoEs21() {
        assertEquals(21.0, NiaTokens.contraste("#000000", "#ffffff"), 1e-9)
    }

    @Test
    fun contrasteEsSimetrico() {
        assertEquals(
            NiaTokens.contraste("#0066cc", "#ffffff"),
            NiaTokens.contraste("#ffffff", "#0066cc"),
            1e-12
        )
    }

    @Test
    fun contrasteIdenticoEsUno() {
        assertEquals(1.0, NiaTokens.contraste("#b45309", "#b45309"), 1e-9)
    }

    @Test
    fun luminanciaDeBlancoYNegro() {
        assertEquals(1.0, NiaTokens.luminancia("#ffffff"), 1e-9)
        assertEquals(0.0, NiaTokens.luminancia("#000000"), 1e-9)
    }

    @Test
    fun paresBuiltinClaroCumplenAA() {
        val c = NiaTokens.builtin(NiaTokens.Modo.CLARO, false)
        val pares = NiaTokens.pares(c)
        assertEquals(5, pares.size)
        for (par in pares) {
            assertTrue(par.nombre + " " + par.ratio(), par.cumple())
        }
        assertTrue(NiaTokens.esAA(c))
    }

    @Test
    fun paresBuiltinOscuroCumplenAA() {
        val c = NiaTokens.builtin(NiaTokens.Modo.OSCURO, true)
        val pares = NiaTokens.pares(c)
        for (par in pares) {
            assertTrue(par.nombre + " " + par.ratio(), par.cumple())
        }
        assertTrue(NiaTokens.esAA(c))
    }

    @Test
    fun paresAARechazanContrasteInsuficiente() {
        val gris = NiaTokens.Colores(
            "#777777", "#777777", "#777777", "#777777", "#777777", "#777777"
        )
        assertFalse(NiaTokens.esAA(gris))
    }

    @Test
    fun catalogoCompletoYSinDuplicados() {
        val catalogo = NiaTokens.catalogo(NiaTokens.builtin(NiaTokens.Modo.CLARO, false))
        assertEquals(27, catalogo.size)
        val nombres = catalogo.map { it.first }
        assertEquals(nombres.size, nombres.distinct().size)
        assertEquals("color.bg", nombres[0])
        assertEquals("color.error", nombres[5])
        assertEquals("tipo.display", nombres[6])
        assertEquals("elevacion.3", nombres.last())
    }

    @Test
    fun escalasAscendentesYTipografiaDescendente() {
        assertTrue(NiaTokens.ESPACIADO.zipWithNext().all { (a, b) -> a < b })
        assertTrue(NiaTokens.RADIOS.zipWithNext().all { (a, b) -> a < b })
        assertTrue(NiaTokens.ELEVACION.zipWithNext().all { (a, b) -> a < b })
        assertTrue(NiaTokens.TIPOGRAFIA.zipWithNext().all { (a, b) -> a.sp > b.sp })
        assertEquals(5, NiaTokens.TIPOGRAFIA.size)
    }

    @Test
    fun paresCubrenLosRolesDelSchema() {
        val nombres = NiaTokens.pares(NiaTokens.builtin(NiaTokens.Modo.OSCURO, true))
            .map { it.nombre }
        assertEquals(
            listOf("fg/superficie", "fg/fondo", "accent/fondo", "warn/fondo", "error/fondo"),
            nombres
        )
    }
}
