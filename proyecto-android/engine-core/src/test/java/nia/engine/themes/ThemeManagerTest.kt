package nia.engine.themes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 10263001.15f71b63): los 17 tests cubren el
 * parser declarativo, los builtin protegidos, la activacion con
 * ultimo bueno y el recovery del acceptance de T031 (uninstall del
 * tema activo cae al ultimo bueno instalado, con claro de suelo).
 * T031 v1 (R0133, RES-0069, ADR-018).
 */
class ThemeManagerTest {

    private val temaPlaya: String =
        "id = sol.playa\nname = Playa\nbg = #fdf6e3\nsurface = #eee8d5\n" +
            "fg = #657b83\naccent = #2aa198\nwarn = #cb4b16\nerror = #dc322f"

    private val temaMar: String =
        "id = demo.mar\nname = Mar\nbg = #e0f2fe\nsurface = #bae6fd\n" +
            "fg = #0c4a6e\naccent = #0284c7\nwarn = #b45309\nerror = #b91c1c"

    private val temaMar2: String =
        "id = demo.mar\nname = Mar 2\nbg = #e0f2fe\nsurface = #bae6fd\n" +
            "fg = #0c4a6e\naccent = #0284c7\nwarn = #b45309\nerror = #b91c1c"

    private val temaNoche: String =
        "id = demo.noche\nname = Noche\nbg = #111111\nsurface = #1f1f1f\n" +
            "fg = #eeeeee\naccent = #7c3aed\nwarn = #d97706\nerror = #ef4444"

    private fun temaCompleto(id: String, name: String): String =
        "id = " + id + "\nname = " + name + "\nbg = #ffffff\nsurface = #f5f5f5\n" +
            "fg = #1a1a1a\naccent = #0066cc\nwarn = #b45309\nerror = #b91c1c"

    private fun valorDe(r: Outcome<Theme>): Theme? = (r as? Outcome.Success)?.value

    private fun mensajeDe(r: Outcome<Theme>): String =
        if (r is Outcome.Failure) r.error.safeMessage else ""

    @Test
    fun builtinClaroActivoPorDefecto() {
        val temas = ThemeManager()
        assertEquals("claro", temas.active.id)
        assertEquals(listOf("claro", "oscuro"), temas.list())
    }

    @Test
    fun parserAceptaTemaCompleto() {
        val r = ThemeParser.parse(temaPlaya)
        val tema = valorDe(r)
        assertNotNull(tema)
        assertEquals("sol.playa", tema?.id)
        assertEquals("Playa", tema?.name)
        assertEquals("#fdf6e3", tema?.colors?.get("bg"))
        assertEquals("#dc322f", tema?.colors?.get("error"))
        assertEquals(6, tema?.colors?.size)
    }

    @Test
    fun parserRechazaColorInvalido() {
        val r = ThemeParser.parse(temaCompleto("x.mal", "X").replace("accent = #0066cc", "accent = 0066cc"))
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r as Outcome.Failure).error.code)
        assertEquals("color invalido: accent", mensajeDe(r))
    }

    @Test
    fun parserRechazaClaveDesconocida() {
        val r = ThemeParser.parse(
            temaCompleto("x.mal", "X").replace("bg = #ffffff", "fuente = #ffffff\nbg = #ffffff")
        )
        assertTrue(r is Outcome.Failure)
        assertEquals("clave desconocida: fuente", mensajeDe(r))
    }

    @Test
    fun parserRechazaFaltaDeId() {
        val r = ThemeParser.parse(temaCompleto("x.mal", "X").replace("id = x.mal\n", ""))
        assertTrue(r is Outcome.Failure)
        assertEquals("falta la clave id", mensajeDe(r))
    }

    @Test
    fun parserRechazaIdInvalida() {
        val r = ThemeParser.parse(temaCompleto("Sol.Playa", "X"))
        assertTrue(r is Outcome.Failure)
        assertEquals("id no valida: Sol.Playa", mensajeDe(r))
    }

    @Test
    fun parserRechazaClaveRepetida() {
        val r = ThemeParser.parse(
            temaCompleto("x.mal", "X").replace("bg = #ffffff", "bg = #ffffff\nbg = #f5f5f5")
        )
        assertTrue(r is Outcome.Failure)
        assertEquals("clave repetida: bg", mensajeDe(r))
    }

    @Test
    fun parserRechazaTextoVacio() {
        val r1 = ThemeParser.parse("")
        val r2 = ThemeParser.parse("# solo un comentario\n")
        assertTrue(r1 is Outcome.Failure)
        assertEquals("tema vacio", mensajeDe(r1))
        assertTrue(r2 is Outcome.Failure)
        assertEquals("tema vacio", mensajeDe(r2))
    }

    @Test
    fun installAgregaElTemaYActivateCambiaElActivo() {
        val temas = ThemeManager()
        val r = temas.install(temaPlaya)
        val tema = valorDe(r)
        assertNotNull(tema)
        assertEquals("sol.playa", tema?.id)
        assertTrue(temas.list().contains("sol.playa"))
        assertNotNull(temas.themeOf("sol.playa"))
        val activado = temas.activate("sol.playa")
        assertEquals("sol.playa", valorDe(activado)?.id)
        assertEquals("sol.playa", temas.active.id)
    }

    @Test
    fun installRechazaIdReservada() {
        val temas = ThemeManager()
        val r = temas.install(temaCompleto("claro", "Claro X"))
        assertTrue(r is Outcome.Failure)
        assertEquals("tema reservado: claro", mensajeDe(r))
        assertEquals("Claro", temas.themeOf("claro")?.name)
    }

    @Test
    fun installDuplicadoReemplazaEnCaliente() {
        val temas = ThemeManager()
        temas.install(temaMar)
        temas.activate("demo.mar")
        val r = temas.install(temaMar2)
        assertEquals("Mar 2", valorDe(r)?.name)
        assertEquals("Mar 2", temas.themeOf("demo.mar")?.name)
        assertEquals("Mar 2", temas.active.name)
        assertEquals(1, temas.list().filter { it == "demo.mar" }.size)
    }

    @Test
    fun activateCambiaElActivo() {
        val temas = ThemeManager()
        temas.activate("oscuro")
        assertEquals("oscuro", temas.active.id)
    }

    @Test
    fun activateInexistenteFallaSinCambiarElActivo() {
        val temas = ThemeManager()
        temas.activate("oscuro")
        val r = temas.activate("no.hay")
        assertTrue(r is Outcome.Failure)
        assertEquals("tema no instalado: no.hay", mensajeDe(r))
        assertEquals("oscuro", temas.active.id)
    }

    @Test
    fun uninstallDelActivoRecuperaAlUltimoBueno() {
        val temas = ThemeManager()
        temas.install(temaMar)
        temas.install(temaNoche)
        temas.activate("demo.mar")
        temas.activate("demo.noche")
        val r = temas.uninstall("demo.noche")
        assertEquals("demo.mar", valorDe(r)?.id)
        assertEquals("demo.mar", temas.active.id)
        assertNull(temas.themeOf("demo.noche"))
    }

    @Test
    fun uninstallDelActivoSinUltimoBuenoCaeAClaro() {
        val temas = ThemeManager()
        temas.install(temaMar)
        temas.install(temaNoche)
        temas.activate("demo.mar")
        temas.activate("demo.noche")
        val quitaNoActivo = temas.uninstall("demo.mar")
        assertEquals("demo.noche", valorDe(quitaNoActivo)?.id)
        val r = temas.uninstall("demo.noche")
        assertEquals("claro", valorDe(r)?.id)
        assertEquals("claro", temas.active.id)
    }

    @Test
    fun uninstallRechazaReservadosYNulos() {
        val temas = ThemeManager()
        val rClaro = temas.uninstall("claro")
        assertTrue(rClaro is Outcome.Failure)
        assertEquals("tema reservado: claro", mensajeDe(rClaro))
        val rOscuro = temas.uninstall("oscuro")
        assertTrue(rOscuro is Outcome.Failure)
        assertEquals("tema reservado: oscuro", mensajeDe(rOscuro))
        val rNulo = temas.uninstall("x.nada")
        assertTrue(rNulo is Outcome.Failure)
        assertEquals("tema no instalado: x.nada", mensajeDe(rNulo))
    }

    @Test
    fun recoverDevuelveAClaro() {
        val temas = ThemeManager()
        temas.activate("oscuro")
        assertEquals("claro", temas.recover().id)
        assertEquals("claro", temas.active.id)
    }
}
