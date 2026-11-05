package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, 16 escenarios): cada escenario del gemelo tiene su
 * prueba aqui contra las clases reales, con el interprete real y
 * sus cuotas. T029/T030 v1 (R0131, RES-0068, ADR-016): runtime de
 * plugins restringido, SIN red.
 */
class PluginRuntimeTest {

    private fun registry(): ModuleRegistry {
        val r = ModuleRegistry(null)
        val instalado = r.install(
            "id = demo.adblock\nversion = 1\nentry = plugin.js\npermissions = network.fetch"
        )
        assertTrue(instalado is Outcome.Success)
        return r
    }

    private fun valorDe(r: Outcome<String>): String = if (r is Outcome.Success) r.value else "FALLO"

    private fun mensajeDe(r: Outcome<String>): String = if (r is Outcome.Failure) r.error.safeMessage else ""

    private fun entryDe(registry: ModuleRegistry, id: String): String = registry.manifestOf(id)?.entry ?: ""

    @Test
    fun loadDeModuloNoInstaladoFalla() {
        val rt = PluginRuntime(registry())
        val r = rt.load("x.no-instalado", "nia.onNavigate(function (u) { return 'x'; });")
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r as Outcome.Failure).error.code)
        assertEquals("modulo no instalado: x.no-instalado", r.error.safeMessage)
        assertNull(rt.sessionOf("x.no-instalado"))
    }

    @Test
    fun loadRegistraLosHooksDelEntry() {
        val rt = PluginRuntime(registry())
        val r = rt.load(
            "demo.adblock",
            "nia.onNavigate(function (u) { return u.indexOf('/ads/') >= 0 ? 'block' : 'allow'; });" +
                "nia.onRequest(function (u) { return u.indexOf('/tracker/') >= 0 ? 'block' : 'allow'; });"
        )
        assertTrue(r is Outcome.Success)
        val s = (r as Outcome.Success).value
        assertEquals(1, s.hookCount(PluginEvent.NAVIGATE))
        assertEquals(1, s.hookCount(PluginEvent.REQUEST))
        assertEquals(0, s.hookCount(PluginEvent.STARTUP))
        assertTrue(s.activo)
    }

    @Test
    fun loadConEntryQueNoCompilaFalla() {
        val rt = PluginRuntime(registry())
        val r = rt.load("demo.adblock", "nia.onNavigate(function(")
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r as Outcome.Failure).error.code)
        assertEquals("entry del modulo demo.adblock es invalido: no compila o lanzo", r.error.safeMessage)
        assertNull(rt.sessionOf("demo.adblock"))
    }

    @Test
    fun loadConEntryQueLanzaFalla() {
        val rt = PluginRuntime(registry())
        val r = rt.load("demo.adblock", "throw new Error('x');")
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r as Outcome.Failure).error.code)
        assertEquals("entry del modulo demo.adblock es invalido: no compila o lanzo", r.error.safeMessage)
        assertNull(rt.sessionOf("demo.adblock"))
    }

    @Test
    fun loadConHookNoFuncionFalla() {
        val rt = PluginRuntime(registry())
        val r = rt.load("demo.adblock", "nia.onNavigate('no-funcion');")
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r as Outcome.Failure).error.code)
        assertNull(rt.sessionOf("demo.adblock"))
    }

    @Test
    fun onRequestExigePermisoNetworkFetch() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.solo-nav\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val sinPermiso = rt.load("demo.solo-nav", "nia.onRequest(function (u) { return 'x'; });")
        assertTrue(sinPermiso is Outcome.Failure)
        val conOnNavigate = rt.load("demo.solo-nav", "nia.onNavigate(function (u) { return 'nav'; });")
        assertTrue(conOnNavigate is Outcome.Success)
        assertEquals(1, (conOnNavigate as Outcome.Success).value.hookCount(PluginEvent.NAVIGATE))
    }

    @Test
    fun onStartupYOnNavigateDisponiblesSinPermisos() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.solo-nav\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load(
            "demo.solo-nav",
            "nia.onStartup(function () { return 's'; });" +
                "nia.onNavigate(function (u) { return 'nav'; });"
        )
        assertTrue(r is Outcome.Success)
        val s = (r as Outcome.Success).value
        assertEquals(1, s.hookCount(PluginEvent.STARTUP))
        assertEquals(1, s.hookCount(PluginEvent.NAVIGATE))
    }

    @Test
    fun fireEjecutaElHookConLaUrl() {
        val rt = PluginRuntime(registry())
        val r = rt.load(
            "demo.adblock",
            "nia.onNavigate(function (u) { return u.indexOf('/ads/') >= 0 ? 'block' : 'allow'; });" +
                "nia.onRequest(function (u) { return u.indexOf('/tracker/') >= 0 ? 'block' : 'allow'; });"
        )
        val s = (r as Outcome.Success).value
        assertEquals("block", valorDe(s.fire(PluginEvent.NAVIGATE, "https://example.com/ads/banner.png")))
        assertEquals("allow", valorDe(s.fire(PluginEvent.NAVIGATE, "https://example.com/index.html")))
        assertEquals("block", valorDe(s.fire(PluginEvent.REQUEST, "https://example.com/tracker/t.js")))
        assertEquals("allow", valorDe(s.fire(PluginEvent.REQUEST, "https://example.com/index.html")))
    }

    @Test
    fun fireConVariosHooksDevuelveElUltimo() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.doble\nversion = 1\nentry = p.js\npermissions = network.fetch")
        val rt = PluginRuntime(reg)
        val r = rt.load(
            "demo.doble",
            "nia.onRequest(function (u) { return 'primero:' + (u.indexOf('/ads/') >= 0); });" +
                "nia.onRequest(function (u) { return 'segundo:' + (u.indexOf('/ads/') >= 0); });"
        )
        val s = (r as Outcome.Success).value
        assertEquals(2, s.hookCount(PluginEvent.REQUEST))
        assertEquals("segundo:true", valorDe(s.fire(PluginEvent.REQUEST, "https://x/ads/a.png")))
    }

    @Test
    fun fireSinHooksDevuelveCadenaVacia() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.vacio\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load("demo.vacio", "var x = 1;")
        val s = (r as Outcome.Success).value
        assertEquals("", valorDe(s.fire(PluginEvent.NAVIGATE, "https://x/")))
        assertEquals("", valorDe(s.fire(PluginEvent.REQUEST, "https://x/")))
    }

    @Test
    fun fireConHookQueLanzaFallaTipificado() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.lanza\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load("demo.lanza", "nia.onNavigate(function (u) { throw new Error('boom'); });")
        val s = (r as Outcome.Success).value
        val f = s.fire(PluginEvent.NAVIGATE, "https://x/")
        assertTrue(f is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (f as Outcome.Failure).error.code)
        assertEquals("hook de onNavigate del modulo demo.lanza lanzo una excepcion", f.error.safeMessage)
    }

    @Test
    fun fireTrasUnloadDevuelveDenied() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.lanza\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load("demo.lanza", "nia.onNavigate(function (u) { return 'ok'; });")
        val s = (r as Outcome.Success).value
        assertTrue(rt.unload("demo.lanza") is Outcome.Success)
        val f = s.fire(PluginEvent.NAVIGATE, "https://x/")
        assertTrue(f is Outcome.Failure)
        assertEquals(ErrorCode.DENIED, (f as Outcome.Failure).error.code)
        assertEquals("plugin descargado", f.error.safeMessage)
        assertNull(rt.sessionOf("demo.lanza"))
        assertTrue(rt.unload("demo.lanza") is Outcome.Failure)
    }

    @Test
    fun fireAbortaPorCuotaDePasos() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.bucle\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load("demo.bucle", "nia.onNavigate(function (u) { while (true) { } });")
        val s = (r as Outcome.Success).value
        val f = s.fire(PluginEvent.NAVIGATE, "https://x/")
        assertTrue(f is Outcome.Failure)
        assertEquals("hook abortado por cuotas (STEP_QUOTA)", mensajeDe(f))
    }

    @Test
    fun fireAbortaPorCuotaDeHeap() {
        val reg = ModuleRegistry(null)
        reg.install("id = demo.heap\nversion = 1\nentry = p.js")
        val rt = PluginRuntime(reg)
        val r = rt.load(
            "demo.heap",
            "nia.onNavigate(function (u) { for (var i = 0; i < 6000; i++) { var a = {}; var b = [a]; } });"
        )
        val s = (r as Outcome.Success).value
        val f = s.fire(PluginEvent.NAVIGATE, "https://x/")
        assertTrue(f is Outcome.Failure)
        assertEquals("hook abortado por cuotas (HEAP_QUOTA)", mensajeDe(f))
    }

    @Test
    fun updateReemplazaManifiestoYHooks() {
        val reg = registry()
        val rt = PluginRuntime(reg)
        rt.load(
            "demo.adblock",
            "nia.onRequest(function (u) { return 'v1'; });"
        )
        val u = rt.update(
            "demo.adblock",
            "id = demo.adblock\nversion = 1\nentry = plugin-v2.js\npermissions = network.fetch",
            "nia.onRequest(function (u) { return 'block-v2'; });"
        )
        assertTrue(u is Outcome.Success)
        val s2 = (u as Outcome.Success).value
        assertEquals("block-v2", valorDe(s2.fire(PluginEvent.REQUEST, "https://x/ads/a.png")))
        assertEquals("plugin-v2.js", entryDe(reg, "demo.adblock"))
        assertEquals(s2, rt.sessionOf("demo.adblock"))
    }

    @Test
    fun updateConManifiestoInvalidoNoTocaElModulo() {
        val reg = registry()
        val rt = PluginRuntime(reg)
        val r = rt.load(
            "demo.adblock",
            "nia.onRequest(function (u) { return 'v1'; });"
        )
        val s = (r as Outcome.Success).value
        val u = rt.update(
            "demo.adblock",
            "id = demo.adblock\nversion = 99\nentry = p.js\npermissions = network.fetch",
            "nia.onRequest(function (u) { return 'v99'; });"
        )
        assertTrue(u is Outcome.Failure)
        assertEquals(ErrorCode.UNSUPPORTED, (u as Outcome.Failure).error.code)
        assertEquals("plugin.js", entryDe(reg, "demo.adblock"))
        assertEquals("v1", valorDe(s.fire(PluginEvent.REQUEST, "https://x/ads/a.png")))
        assertEquals(s, rt.sessionOf("demo.adblock"))
    }

    @Test
    fun updateConCodigoRotoHaceRollback() {
        val reg = registry()
        val rt = PluginRuntime(reg)
        val r = rt.load(
            "demo.adblock",
            "nia.onRequest(function (u) { return 'v1'; });"
        )
        val s = (r as Outcome.Success).value
        val u = rt.update(
            "demo.adblock",
            "id = demo.adblock\nversion = 1\nentry = plugin-v3.js\npermissions = network.fetch",
            "throw new Error('roto');"
        )
        assertTrue(u is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (u as Outcome.Failure).error.code)
        assertEquals("plugin.js", entryDe(reg, "demo.adblock"))
        assertEquals("v1", valorDe(s.fire(PluginEvent.REQUEST, "https://x/ads/a.png")))
        assertEquals(s, rt.sessionOf("demo.adblock"))
        assertTrue(s.activo)
    }

    @Test
    fun updateDeModuloNoCargadoFalla() {
        val reg = registry()
        val rt = PluginRuntime(reg)
        val u = rt.update("no.cargado", "id = no.cargado\nversion = 1\nentry = p.js", "var x = 1;")
        assertTrue(u is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (u as Outcome.Failure).error.code)
        assertEquals("plugin no cargado: no.cargado", u.error.safeMessage)
    }

    @Test
    fun manifestTextoHaceRoundtripConYSinPermisos() {
        val rt = PluginRuntime(registry())
        val m1 = ModuleParser.parse(
            "id = a.b\nversion = 1\nentry = p.js\npermissions = network.fetch,storage.read"
        )
        assertTrue(m1 is Outcome.Success)
        val texto1 = rt.manifestTexto((m1 as Outcome.Success).value)
        val re1 = ModuleParser.parse(texto1)
        assertTrue(re1 is Outcome.Success)
        val man1 = (re1 as Outcome.Success).value
        assertEquals("a.b", man1.id)
        assertEquals(1, man1.version)
        assertEquals("p.js", man1.entry)
        assertTrue(man1.permissions.contains("network.fetch"))
        assertTrue(man1.permissions.contains("storage.read"))
        val m2 = ModuleParser.parse("id = a.b\nversion = 1\nentry = p.js")
        assertTrue(m2 is Outcome.Success)
        val texto2 = rt.manifestTexto((m2 as Outcome.Success).value)
        val re2 = ModuleParser.parse(texto2)
        assertTrue(re2 is Outcome.Success)
        assertEquals(0, (re2 as Outcome.Success).value.permissions.size)
    }
}
