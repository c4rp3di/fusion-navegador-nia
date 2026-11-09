package nia.engine.modules

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.TransportResult
import nia.engine.profile.ConfigStore

/**
 * Espejo Kotlin del gemelo TS validado offline (R0242, vectores
 * decide/trim): PluginNetFilter sobre el runtime real de T029/T030
 * con modulos demo, SIN red (el transporte es falso y JAMAS se llama
 * cuando algo bloquea). T052 v1 (R0242, RES-0111, ADR-005) y v2/C9
 * (R0253, RES-0113): config del perfil, enabled=false abstiene.
 * C10 v2 (R0267, RES-0118): contextoDe aporta el EventContext real
 * de cada peticion y nia.diag escribe en el inspector del host
 * (gemelo 2 corridas, huella 14.14.25e8790c).
 * C3 v1 (R0271, RES-0119): veredicto sustituir con gates
 * fail-closed y diagnostico sustituido/no disponible (gemelo 2
 * corridas, huella 33.33.1f2c43d20ca9851c).
 * T054 v1 (R0284, RES-0122): excepciones de sitio (exceptuado
 * abstiene sin disparar el hook, origen del contexto iniciador)
 * y contadores de bloqueos/sustituciones por modulo (gemelo 2
 * corridas, huella 34.34.9b2eb42d).
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

    private val manifestSustituto =
        "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch, network.response\ncapabilities = bloquear, modificar\napi-version = 2\nsustitutos = juguete.mp4"

    private val entrySustituto =
        "nia.onRequest(function (c) { return 'sustituir: juguete.mp4'; });"

    private val manifestStats =
        "id = demo.stats\nversion = 1\nentry = entry.js\npermissions = network.fetch, network.response\ncapabilities = bloquear, modificar\napi-version = 2\nsustitutos = juguete.mp4"

    private fun sha256Hex(b: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(b)
        val sb = StringBuilder(d.size * 2)
        for (x in d) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private class MontajeSustituto(val filtro: PluginNetFilter, val inspector: InspectorSession)

    /** Monta el modulo con el manifiesto dado y el filtro C3 completo
     *  (id del modulo; demo.api2 por defecto). */
    private fun montarSustituto(
        manifiesto: String,
        entry: String,
        recurso: RecursoSustituto?,
        ctx: EventContext?,
        id: String = "demo.api2"
    ): MontajeSustituto {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        assertTrue(reg.install(manifiesto) is Outcome.Success)
        assertTrue(rt.load(id, entry) is Outcome.Success)
        val inspector = InspectorSession(FakeClock(9L))
        val filtro = PluginNetFilter(
            rt, reg, null,
            { _ -> ctx },
            { _, _ -> recurso },
            DiagModulos(inspector)
        )
        return MontajeSustituto(filtro, inspector)
    }

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

    @Test
    fun enabledFalseSeAbstieneYDejaPasar() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.bloqueador", entryCondicional)
        val config = ConfigStore(null)
        assertTrue(
            config.escribir("demo.bloqueador", "general.enabled", "false") is Outcome.Success
        )
        val d = PluginNetFilter(rt, reg, config).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue("general.enabled=false deshabilita el bloqueo del modulo", d is FilterDecision.Allow)
    }

    @Test
    fun enabledFalseNoCortaALosDemasModulos() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        val config = ConfigStore(null)
        assertTrue(config.escribir("demo.apagado", "general.enabled", "false") is Outcome.Success)
        instalar(reg, rt, "demo.apagado", entryCondicional)
        instalar(reg, rt, "demo.encendido", entryCondicional)
        val d = PluginNetFilter(rt, reg, config).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue(d is FilterDecision.Block)
        assertEquals("demo.encendido", (d as FilterDecision.Block).moduleId)
    }

    @Test
    fun sinConfigElFiltroSigueSiendoElDeV1() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.bloqueador", entryCondicional)
        val d = PluginNetFilter(rt, reg).decide(
            RequestContext.of("https://example.com/ads/x.png", "image", "")
        )
        assertTrue("sin config el modulo bloquea igual que en v1", d is FilterDecision.Block)
    }

    @Test
    fun api2ElFiltroPasaElContextoAlHook() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        val instalado = reg.install(
            "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch\ncapabilities = bloquear\napi-version = 2"
        )
        assertTrue(instalado is Outcome.Success)
        val carga = rt.load(
            "demo.api2",
            "nia.onRequest(function (c) { return c.type === 'image' ? 'block' : 'allow'; });"
        )
        assertTrue(carga is Outcome.Success)
        val f = PluginNetFilter(rt, reg)
        assertTrue(
            f.decide(RequestContext.of("https://cdn.example.org/x.png", "image", "")) is FilterDecision.Block
        )
        assertTrue(
            f.decide(RequestContext.of("https://example.com/", "document", "")) is FilterDecision.Allow
        )
    }

    @Test
    fun api2ElContextoDelProveedorLlegaAlDiagnostico() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        val instalado = reg.install(
            "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch\ncapabilities = bloquear\napi-version = 2"
        )
        assertTrue(instalado is Outcome.Success)
        val inspector = InspectorSession(FakeClock(9L))
        val diag = DiagModulos(inspector)
        val carga = rt.load(
            "demo.api2",
            "nia.onRequest(function (c) { nia.diag.evento('info', 'peticion ' + c.url); return 'allow'; });",
            EntornoModulo(null, UiModelDeclarado(emptyList(), emptyList(), emptyList()), emptyMap(), diag)
        )
        assertTrue(carga is Outcome.Success)
        val ctx = EventContext("s-demo", "t-3", "main", "d-3-1", 1L)
        val vistas = ArrayList<RequestContext>()
        val f = PluginNetFilter(rt, reg, null, { c -> vistas.add(c); ctx })
        assertTrue(
            "el hook solo escribe diagnostico: no bloquea",
            f.decide(RequestContext.of("https://cdn.example.org/x.png", "image", "")) is FilterDecision.Allow
        )
        assertEquals("el proveedor ve la peticion", 1, vistas.size)
        assertEquals("image", vistas[0].resourceType)
        val eventos = inspector.all()
        assertEquals("el diagnostico del modulo llega al inspector del host", 1, eventos.size)
        assertEquals("modulo", eventos[0].kind)
        assertEquals(EventLevel.INFO, eventos[0].level)
        assertEquals("demo.api2", eventos[0].payload["modulo"])
        assertEquals("peticion https://cdn.example.org/x.png", eventos[0].payload["texto"])
        assertEquals("el contexto del proveedor viaja al evento", ctx, eventos[0].context)
    }

    @Test
    fun api2SinProveedorElDiagnosticoSeAbstiene() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        val instalado = reg.install(
            "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch\ncapabilities = bloquear\napi-version = 2"
        )
        assertTrue(instalado is Outcome.Success)
        val inspector = InspectorSession(FakeClock(9L))
        val diag = DiagModulos(inspector)
        val carga = rt.load(
            "demo.api2",
            "nia.onRequest(function (c) { return 'n=' + nia.diag.evento('info', 'x'); });",
            EntornoModulo(null, UiModelDeclarado(emptyList(), emptyList(), emptyMap()), emptyMap(), diag)
        )
        assertTrue(carga is Outcome.Success)
        val f = PluginNetFilter(rt, reg)
        assertTrue(
            f.decide(RequestContext.of("https://cdn.example.org/x.png", "image", "")) is FilterDecision.Allow
        )
        assertEquals("sin proveedor nia.diag devuelve false y no hay evento", 0, inspector.all().size)
        val fNulo = PluginNetFilter(rt, reg, null, { _ -> null })
        assertTrue(
            fNulo.decide(RequestContext.of("https://cdn.example.org/y.png", "image", "")) is FilterDecision.Allow
        )
        assertEquals("proveedor null: sin contexto no hay evento", 0, inspector.all().size)
    }

    @Test
    fun api2SustituyeElRecursoDelModulo() {
        val mp4 = "MP4 de juguete".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val montaje = montarSustituto(manifestSustituto, entrySustituto, recurso, ctx)
        val d = montaje.filtro.decide(
            RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")
        )
        assertTrue(d is FilterDecision.Substitute)
        assertEquals("demo.api2", (d as FilterDecision.Substitute).moduleId)
        assertTrue(d.bytes.contentEquals(mp4))
        assertEquals("video/mp4", d.contentType)
        val eventos = montaje.inspector.all()
        assertEquals("el diagnostico del exito llega al inspector", 1, eventos.size)
        assertEquals(EventLevel.INFO, eventos[0].level)
        assertEquals("sustituido juguete.mp4", eventos[0].payload["texto"])
        assertEquals("el contexto del proveedor viaja al evento", ctx, eventos[0].context)
    }

    @Test
    fun api2UnaNavegacionDeDocumentoJamasSeSustituye() {
        val mp4 = "MP4".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val f = montarSustituto(manifestSustituto, entrySustituto, recurso, ctx).filtro
        assertTrue(
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "document", "")) is FilterDecision.Block
        )
    }

    @Test
    fun api2TipoNoSustituibleCaeABlock() {
        val mp4 = "MP4".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val f = montarSustituto(manifestSustituto, entrySustituto, recurso, ctx).filtro
        assertTrue(
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "script", "")) is FilterDecision.Block
        )
    }

    @Test
    fun api1IgnoraElVeredictoSustituir() {
        val manifest =
            "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch\ncapabilities = bloquear"
        val mp4 = "MP4".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val f = montarSustituto(manifest, entrySustituto, recurso, ctx).filtro
        assertTrue(
            "api-version 1 mantiene su semantica: el veredicto raro no bloquea",
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Allow
        )
    }

    @Test
    fun api2SinCapabilityModificarCaeABlock() {
        val manifest =
            "id = demo.api2\nversion = 1\nentry = entry.js\npermissions = network.fetch, network.response\ncapabilities = bloquear\napi-version = 2"
        val mp4 = "MP4".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val f = montarSustituto(manifest, entrySustituto, recurso, ctx).filtro
        assertTrue(
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Block
        )
    }

    @Test
    fun api2RecursoAlteradoCaeABlockYDiagnostica() {
        val mp4 = "MP4 de juguete".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto(
            "juguete.mp4", mp4, sha256Hex("MANIPULADO".toByteArray(Charsets.UTF_8))
        )
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val montaje = montarSustituto(manifestSustituto, entrySustituto, recurso, ctx)
        assertTrue(
            montaje.filtro.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Block
        )
        val eventos = montaje.inspector.all()
        assertEquals("el fallo escribe no disponible en el canal del host", 1, eventos.size)
        assertEquals(EventLevel.WARNING, eventos[0].level)
        assertEquals("no disponible: juguete.mp4", eventos[0].payload["texto"])
    }

    @Test
    fun api2TopeDeSustitucionesPorDocumento() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        assertTrue(reg.install(manifestSustituto) is Outcome.Success)
        assertTrue(rt.load("demo.api2", entrySustituto) is Outcome.Success)
        val mp4 = "MP4 de juguete".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        var ctx: EventContext? = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val f = PluginNetFilter(rt, reg, null, { _ -> ctx }, { _, _ -> recurso }, null)
        repeat(50) {
            assertTrue(
                f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Substitute
            )
        }
        assertTrue(
            "la sustitucion 51 del mismo documento cae en Block",
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Block
        )
        ctx = EventContext("s-demo", "t-1", "main", "d-2-1", 1L)
        assertTrue(
            "otro documento sigue sustituyendo",
            f.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Substitute
        )
    }

    @Test
    fun decideCuentaBloqueosPorModulo() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.contablock", entryCondicional)
        val f = PluginNetFilter(rt, reg)
        assertTrue(
            f.decide(RequestContext.of("https://example.com/ads/x.png", "image", "")) is FilterDecision.Block
        )
        assertTrue(
            f.decide(RequestContext.of("https://example.com/ads/y.png", "image", "")) is FilterDecision.Block
        )
        assertTrue(
            f.decide(RequestContext.of("https://example.com/ok.png", "image", "")) is FilterDecision.Allow
        )
        assertEquals(
            "dos bloqueos contados para el modulo",
            2,
            PluginNetFilter.bloqueosDe("demo.contablock")
        )
        assertEquals(
            "las peticiones permitidas no cuentan",
            0,
            PluginNetFilter.sustitucionesDe("demo.contablock")
        )
        assertEquals(
            "un modulo sin bloqueos marca cero",
            0,
            PluginNetFilter.bloqueosDe("demo.nada")
        )
    }

    @Test
    fun decideCuentaSustitucionesCompartidasEntreFiltros() {
        val mp4 = "MP4 de juguete".toByteArray(Charsets.UTF_8)
        val recurso = RecursoSustituto("juguete.mp4", mp4, sha256Hex(mp4))
        val ctx = EventContext("s-demo", "t-1", "main", "d-1-1", 1L)
        val m1 = montarSustituto(manifestStats, entrySustituto, recurso, ctx, "demo.stats")
        val m2 = montarSustituto(manifestStats, entrySustituto, recurso, ctx, "demo.stats")
        assertTrue(
            m1.filtro.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Substitute
        )
        assertTrue(
            m2.filtro.decide(RequestContext.of("https://cdn.example.org/v.mp4", "fetch", "")) is FilterDecision.Substitute
        )
        assertEquals(
            "los contadores son del proceso: comparten pestanas",
            2,
            PluginNetFilter.sustitucionesDe("demo.stats")
        )
    }

    @Test
    fun moduloExceptuadoSeAbstieneSinDispararElHook() {
        val reg = ModuleRegistry(null)
        val rt = PluginRuntime(reg)
        instalar(reg, rt, "demo.exceptuado", entryCondicional)
        val filtro = PluginNetFilter(
            rt, reg, null, null, null, null,
            { id, origen -> id == "demo.exceptuado" && origen == "https://a.dev:443" }
        )
        assertTrue(
            "el modulo exceptuado se abstiene",
            filtro.decide(
                RequestContext.of("https://cdn.example.org/ads/x.png", "image", "https://a.dev:443/pagina")
            ) is FilterDecision.Allow
        )
        assertEquals(
            "sin hook no hay bloqueos del modulo",
            0,
            PluginNetFilter.bloqueosDe("demo.exceptuado")
        )
        assertTrue(
            "fuera de la excepcion el modulo bloquea",
            filtro.decide(
                RequestContext.of("https://cdn.example.org/ads/x.png", "image", "https://b.dev:443/pagina")
            ) is FilterDecision.Block
        )
    }

    @Test
    fun persistirContadoresCargaTsvPrevio() {
        val dir = Files.createTempDirectory("filtro-contadores").toFile()
        try {
            val archivo = File(dir, "filtro-contadores.tsv")
            archivo.writeText(
                "demo.persistido\t5\t2\n" +
                "linea corrupta\n" +
                "demo.mal\tNo\t1\n" +
                "demo.neg\t-3\t0\n"
            )
            PluginNetFilter.persistirContadoresEn(archivo)
            assertEquals(5, PluginNetFilter.bloqueosDe("demo.persistido"))
            assertEquals(2, PluginNetFilter.sustitucionesDe("demo.persistido"))
            assertEquals("la linea corrupta se ignora", 0, PluginNetFilter.bloqueosDe("demo.mal"))
            assertEquals("el negativo no carga", 0, PluginNetFilter.bloqueosDe("demo.neg"))
        } finally {
            PluginNetFilter.persistirContadoresEn(null)
            PluginNetFilter.reiniciarContadores()
            dir.deleteRecursively()
        }
    }

    @Test
    fun bloqueoMarcaPendientesYGuardarEscribeTsv() {
        val dir = Files.createTempDirectory("filtro-contadores").toFile()
        try {
            val archivo = File(dir, "filtro-contadores.tsv")
            val reg = ModuleRegistry(null)
            val rt = PluginRuntime(reg)
            instalar(reg, rt, "demo.persist", entryCondicional)
            val f = PluginNetFilter(rt, reg)
            PluginNetFilter.persistirContadoresEn(archivo)
            PluginNetFilter.reiniciarContadores()
            assertTrue(
                f.decide(RequestContext.of("https://example.com/ads/x.png", "image", "")) is FilterDecision.Block
            )
            assertTrue("el bloqueo marca pendientes", PluginNetFilter.hayContadoresPendientes())
            PluginNetFilter.guardarContadoresSiPendientes()
            assertFalse("guardar limpia pendientes", PluginNetFilter.hayContadoresPendientes())
            assertEquals(1, PluginNetFilter.bloqueosDe("demo.persist"))
            assertEquals("demo.persist\t1\t0\n", archivo.readText())
            PluginNetFilter.reiniciarContadores()
            assertEquals("reiniciar borra en memoria", 0, PluginNetFilter.bloqueosDe("demo.persist"))
            PluginNetFilter.persistirContadoresEn(archivo)
            assertEquals("recargar restaura los contadores", 1, PluginNetFilter.bloqueosDe("demo.persist"))
        } finally {
            PluginNetFilter.persistirContadoresEn(null)
            PluginNetFilter.reiniciarContadores()
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistirNuloApagaSinExcepcionesNiArchivo() {
        val dir = Files.createTempDirectory("filtro-contadores").toFile()
        try {
            val archivo = File(dir, "filtro-contadores.tsv")
            PluginNetFilter.persistirContadoresEn(null)
            PluginNetFilter.reiniciarContadores()
            val reg = ModuleRegistry(null)
            val rt = PluginRuntime(reg)
            instalar(reg, rt, "demo.apagado", entryCondicional)
            val f = PluginNetFilter(rt, reg)
            assertTrue(
                f.decide(RequestContext.of("https://example.com/ads/x.png", "image", "")) is FilterDecision.Block
            )
            assertEquals("sin archivo los contadores viven igual", 1, PluginNetFilter.bloqueosDe("demo.apagado"))
            PluginNetFilter.guardarContadoresSiPendientes()
            assertFalse("sin persistencia no se escribe nada", archivo.exists())
        } finally {
            PluginNetFilter.persistirContadoresEn(null)
            PluginNetFilter.reiniciarContadores()
            dir.deleteRecursively()
        }
    }

    @Test
    fun reiniciarContadoresPersisteElVacio() {
        val dir = Files.createTempDirectory("filtro-contadores").toFile()
        try {
            val archivo = File(dir, "filtro-contadores.tsv")
            archivo.writeText("demo.viejo\t7\t4\n")
            PluginNetFilter.persistirContadoresEn(archivo)
            assertEquals(7, PluginNetFilter.bloqueosDe("demo.viejo"))
            assertEquals(4, PluginNetFilter.sustitucionesDe("demo.viejo"))
            PluginNetFilter.reiniciarContadores()
            assertEquals(0, PluginNetFilter.bloqueosDe("demo.viejo"))
            assertEquals("el vacio se persiste", "", archivo.readText())
            assertFalse("reiniciar limpia pendientes", PluginNetFilter.hayContadoresPendientes())
        } finally {
            PluginNetFilter.persistirContadoresEn(null)
            PluginNetFilter.reiniciarContadores()
            dir.deleteRecursively()
        }
    }
}
