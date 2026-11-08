package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.TransportResult

/**
 * Espejo Kotlin del gemelo TS validado offline (R0242, vectores
 * decide/trim): PluginNetFilter sobre el runtime real de T029/T030
 * con modulos demo, SIN red (el transporte es falso y JAMAS se llama
 * cuando algo bloquea). T052 v1 (R0242, RES-0111, ADR-005).
 */
class PluginNetFilterTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private class FakeTransport : FetchTransport {
        val requests = mutableListOf<FetchRequest>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            return TransportResult.Success(FetchResponse(200, emptyMap(), ByteArray(0)))
        }
    }

    /** Instala y carga un modulo demo con entry JS dado. */
    private fun instalar(
        reg: ModuleRegistry,
        rt: PluginRuntime,
        id: String,
        entry: String,
        permisoRed: Boolean = true
    ) {
        val manifest = if (permisoRed) {
            "id = " + id + "\nversion = 2\nentry = entry.js\npermissions = network.fetch\ncapabilities = bloquear"
        } else {
            "id = " + id + "\nversion = 2\nentry = entry.js"
        }
        assertTrue(reg.install(manifest) is Outcome.Success)
        assertTrue(rt.load(id, entry) is Outcome.Success)
    }

    private val entryCondicional =
        "nia.onRequest(function (u) { return u.indexOf('/ads/') >= 0 ? 'block' : 'allow'; });"

    @Test
    fun bloqueoExtremoAFinSinLlamarAlTransporte() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.bloqueador", entryCondicional)
        val filtro = PluginNetFilter(rt, reg)
        val t = FakeTransport()
        val m = FetchManager(t, filter = filtro)
        val e = m.fetch(m.newRequest("GET", "https://example.com/ads/banner.png"))
        assertTrue(e is Outcome.Failure)
        assertEquals(ErrorCode.DENIED, (e as Outcome.Failure).error.code)
        assertEquals("peticion bloqueada por un modulo", e.error.safeMessage)
        assertEquals(0, t.requests.size)
        assertEquals(0, m.deliveredCount())
        val ok = m.fetch(m.newRequest("GET", "https://example.com/index.html"))
        assertTrue(ok is Outcome.Success)
        assertEquals(1, t.requests.size)
    }

    @Test
    fun soloElVeredictoExactoBlockTrasTrimBloquea() {
        val veredictos = listOf(
            Pair("block", true),
            Pair(" block ", true),
            Pair("BLOCK", false),
            Pair("blocked", false),
            Pair("", false),
            Pair("permitir", false)
        )
        for ((veredicto, debeBloquear) in veredictos) {
            val reg = ModuleRegistry(null)
            val rt = PluginRuntime(reg)
            instalar(
                reg, rt, "demo.constante",
                "nia.onRequest(function (u) { return '" + veredicto + "'; });"
            )
            val d = PluginNetFilter(rt, reg).decide(
                RequestContext.of("https://example.com/x", "image", "")
            )
            if (debeBloquear) {
                assertTrue("debia bloquear con: " + veredicto, d is FilterDecision.Block)
            } else {
                assertTrue("debia permitir con: " + veredicto, d is FilterDecision.Allow)
            }
        }
    }

    @Test
    fun failOpenConHookQueLanza() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.lanza", "nia.onRequest(function (u) { throw new Error('boom'); });")
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Allow)
    }

    @Test
    fun failOpenPorCuotasDelHook() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.cuotas", "nia.onRequest(function (u) { while (true) { } });")
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Allow)
    }

    @Test
    fun instaladoSinSesionSeAbstiene() {
        val reg = ModuleRegistry(null)
        assertTrue(
            reg.install(
                "id = demo.nocargado\nversion = 2\nentry = entry.js\npermissions = network.fetch"
            ) is Outcome.Success
        )
        val rt = PluginRuntime(reg)
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Allow)
    }

    @Test
    fun sinPermisoDeRedNoTieneHookYSeAbstiene() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(
            reg, rt, "demo.solo-nav",
            "nia.onNavigate(function (u) { return 'nav'; });",
            permisoRed = false
        )
        val s = rt.sessionOf("demo.solo-nav")
        assertTrue(s != null)
        assertEquals(0, s!!.hookCount(PluginEvent.REQUEST))
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Allow)
    }

    @Test
    fun elMasRestrictivoGanaEnAmbosOrdenesDeInstalacion() {
        // Escenario 1: permisivo primero, bloqueador despues.
        val reg1 = ModuleRegistry(null)
        val rt1 = PluginRuntime(reg1)
        instalar(reg1, rt1, "demo.permisivo", "nia.onRequest(function (u) { return 'allow'; });")
        instalar(reg1, rt1, "demo.bloqueador", entryCondicional)
        val d1 = PluginNetFilter(rt1, reg1).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d1 is FilterDecision.Block)
        assertEquals("demo.bloqueador", (d1 as FilterDecision.Block).moduleId)
        // Escenario 2: bloqueador primero (registro fresco, sin update).
        val reg2 = ModuleRegistry(null)
        val rt2 = PluginRuntime(reg2)
        instalar(reg2, rt2, "demo.bloqueador", entryCondicional)
        instalar(reg2, rt2, "demo.permisivo", "nia.onRequest(function (u) { return 'allow'; });")
        val d2 = PluginNetFilter(rt2, reg2).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d2 is FilterDecision.Block)
        assertEquals("demo.bloqueador", (d2 as FilterDecision.Block).moduleId)
        // Y una URL sin /ads/ pasa en el escenario 1.
        val d3 = PluginNetFilter(rt1, reg1).decide(
            RequestContext.of("https://example.com/ok.png", "image", "")
        )
        assertTrue(d3 is FilterDecision.Allow)
    }

    @Test
    fun elModuloRotoNoCortaLaCadena() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.roto", "nia.onRequest(function (u) { throw new Error('boom'); });")
        instalar(reg, rt, "demo.bloqueador", entryCondicional)
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Block)
        assertEquals("demo.bloqueador", (d as FilterDecision.Block).moduleId)
    }

    @Test
    fun trasUnloadSeAbstiene() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.bloqueador", entryCondicional)
        val filtro = PluginNetFilter(rt, reg)
        val antes = filtro.decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(antes is FilterDecision.Block)
        assertTrue(rt.unload("demo.bloqueador") is Outcome.Success)
        val despues = filtro.decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(despues is FilterDecision.Allow)
    }
}
