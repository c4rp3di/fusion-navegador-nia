package nia.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

class NiaConfigTest {

    private fun ok(o: Outcome<NiaConfig>): NiaConfig {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value
    }

    private fun fallo(o: Outcome<*>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    private val v2 = "version = 2\n" +
        "theme = dark\n" +
        "density = compact\n" +
        "fontScale = 120\n" +
        "homepage = https://ejemplo.com/\n" +
        "javascript.enabled = false\n" +
        "network.blockMixed = false\n"

    @Test fun parseaConfigV2() {
        val cfg = ok(NiaConfigStore().load(v2))
        assertEquals(2, cfg.version)
        assertEquals("dark", cfg[ConfigKeys.THEME])
        assertEquals("compact", cfg[ConfigKeys.DENSITY])
        assertEquals("false", cfg[ConfigKeys.JS_ENABLED])
    }

    @Test fun comentariosYLineasVaciasIgnorados() {
        val texto = "# comentario\n\nversion = 2\n# otro\ntheme = light\n"
        val cfg = ok(NiaConfigStore().load(texto))
        assertEquals("light", cfg[ConfigKeys.THEME])
    }

    @Test fun sinVersionFalla() {
        val e = fallo(NiaConfigStore().load("theme = dark\n"))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
    }

    @Test fun configVaciaFalla() {
        val e = fallo(NiaConfigStore().load("# solo comentarios\n"))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
    }

    @Test fun claveDesconocidaRechazada() {
        val e = fallo(NiaConfigStore().load("version = 2\ncolormode = azul\n"))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
    }

    @Test fun valorInvalidoRechazado() {
        val e = fallo(NiaConfigStore().load("version = 2\ntheme = azul\n"))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
    }

    @Test fun fontScaleFueraDeRangoRechazado() {
        val e = fallo(NiaConfigStore().load("version = 2\nfontScale = 500\n"))
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
    }

    @Test fun ultimaBuenaSobreviveACargaInvalida() {
        val store = NiaConfigStore()
        ok(store.apply(ok(store.stage(v2))))
        assertEquals("dark", store.lastKnownGood[ConfigKeys.THEME])
        val malo = store.load("version = 2\ntheme = azul\n")
        assertTrue(malo is Outcome.Failure)
        assertEquals("dark", store.lastKnownGood[ConfigKeys.THEME])
    }

    @Test fun migraV1() {
        val v1 = "version = 1\ndarkMode = true\nfontSize = 150\nhomepage = https://ejemplo.com/\n"
        val cfg = ok(NiaConfigStore().load(v1))
        assertEquals(2, cfg.version)
        assertEquals("dark", cfg[ConfigKeys.THEME])
        assertEquals("150", cfg[ConfigKeys.FONT_SCALE])
        assertEquals("https://ejemplo.com/", cfg[ConfigKeys.HOMEPAGE])
    }

    @Test fun migraV1ValorInvalidoFalla() {
        val v1 = "version = 1\ndarkMode = true\nfontSize = enorme\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(NiaConfigStore().load(v1)).code)
    }

    @Test fun versionFuturaRechazada() {
        val e = fallo(NiaConfigStore().load("version = 3\ntheme = dark\n"))
        assertEquals(ErrorCode.UNSUPPORTED, e.code)
    }

    @Test fun stagePreviewSinAplicar() {
        val store = NiaConfigStore()
        val staged = ok(store.stage(v2))
        assertEquals("dark", staged.config[ConfigKeys.THEME])
        assertEquals("system", store.lastKnownGood[ConfigKeys.THEME])
    }

    @Test fun applyCometeYRechazaReuso() {
        val store = NiaConfigStore()
        val staged = ok(store.stage(v2))
        ok(store.apply(staged))
        assertEquals("dark", store.lastKnownGood[ConfigKeys.THEME])
        assertEquals(ErrorCode.INVALID_INPUT, fallo(store.apply(staged)).code)
    }

    @Test fun recoverSinTema() {
        val store = NiaConfigStore()
        ok(store.apply(ok(store.stage(v2))))
        val esenciales = store.recover()
        assertEquals("system", esenciales[ConfigKeys.THEME])
        assertEquals("about:blank", esenciales[ConfigKeys.HOMEPAGE])
        assertEquals("true", esenciales[ConfigKeys.BLOCK_MIXED])
    }

    @Test fun exportRoundtrip() {
        val store = NiaConfigStore()
        val cfg = ok(store.load(v2))
        val rt = ok(store.load(store.export(cfg)))
        assertEquals(cfg, rt)
    }
}
