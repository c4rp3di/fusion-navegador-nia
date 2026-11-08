package nia.engine.tabs

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.WallClock
import nia.engine.nav.NavOutcome
import nia.engine.nav.NavState
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.RequestFilter
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult
import nia.engine.profile.FireLevel
import nia.engine.profile.ProfileKind

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 32333.48541.155): los 20 tests cubren la creacion con
 * tarjetas ordenadas por tipo, el presupuesto por tipo, el
 * desalojo del mas viejo en las reabiertas, la pestana activa,
 * la actualizacion del shell, el duplicado, el cierre de la
 * unica, el borrado del perfil al cerrar la ultima incognito
 * (con cookies REALES del CookieJar compartidas dentro del
 * tipo), el aislamiento entre tipos, el vaciado de las
 * reabiertas de incognito, cerrarTodas, el reorden, Fire, la
 * limpieza dedicada del cache HTTP (T093 v4, R0183) y las cerradas
 * recientes con reabrirEn (T075 v1, R0184, RES-0087) y el cableado
 * del filtro de red (T052/T094, R0249, RES-0112; gemelo 2 corridas,
 * huella 16.16.1556b2ab): el filtro decide ANTES de red/cache en el documento
 * y los subrecursos de cada pestana (normal e incognito); un
 * veredicto "block" corta la peticion con DENIED.
 * T073 v1 (RES-0079) + T093 v4 (R0183) + T075 v1 (R0184, RES-0087)
 * + T094 v2 (R0249, RES-0112).
 */
class TabsManagerTest {

    private class FakeClock : WallClock {
        var now = 5000L
        override fun nowMillis(): Long = now
    }

    private class FakeTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        val routes = HashMap<String, FetchResponse>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            val found = routes[request.url]
            if (found != null) return TransportResult.Success(found)
            return TransportResult.Failure(TransportFailure.NETWORK)
        }
    }

    private var secuencia = 0

    private fun dir(): File {
        secuencia += 1
        val d = File(File(System.getProperty("java.io.tmpdir"), "nia-tabs-test"), "caso-" + secuencia)
        d.mkdirs()
        return d
    }

    private fun manager(
        t: FetchTransport,
        maxTabs: Int = 20,
        maxReab: Int = 10,
        filter: RequestFilter? = null
    ): TabsManager =
        TabsManager(FakeClock(), t, dir(), maxTabs, maxReab, filter)

    private fun resp(status: Int, headers: Map<String, String>, body: String): FetchResponse =
        FetchResponse(status, headers, body.toByteArray())

    private fun cargar(manager: TabsManager, id: Long, url: String) {
        val out = manager.nav(id)!!.navigate(url)
        assertTrue("la navegacion de la pestana fallo", out is NavOutcome.Loaded)
    }

    /** T052/T094 (R0249, RES-0112): filtro que registra cada consulta y
     *  bloquea toda peticion cuya URL contiene el host dado. */
    private class FiltroBloqueador(private val host: String) : RequestFilter {
        val consultas = ArrayList<RequestContext>()
        override fun decide(context: RequestContext): FilterDecision {
            consultas.add(context)
            return if (context.url.contains(host)) {
                FilterDecision.Block("test.bloqueador")
            } else {
                FilterDecision.Allow
            }
        }
    }

    @Test
    fun crearDejaTarjetasOrdenadasPorTipo() {
        val m = manager(FakeTransport())
        val a = m.crear(ProfileKind.NORMAL, "http://a.dev/", "A", null)
        val b = m.crear(ProfileKind.NORMAL, "http://b.dev/", "B", "prevB")
        val i = m.crear(ProfileKind.INCOGNITO, "http://i.dev/", "I", null)
        assertTrue(a is TabCrear.Ok)
        assertTrue(b is TabCrear.Ok)
        assertTrue(i is TabCrear.Ok)
        assertEquals(3, m.tarjetas().size)
        assertEquals(1, m.tarjetas(ProfileKind.INCOGNITO).size)
        val tarjeta = m.tarjeta((b as TabCrear.Ok).id)
        assertEquals("B", tarjeta!!.title)
        assertEquals("http://b.dev/", tarjeta.url)
        assertEquals("prevB", tarjeta.preview)
    }

    @Test
    fun presupuestoPorTipoRechazaElExceso() {
        val m = manager(FakeTransport(), 2, 10)
        assertTrue(m.crear(ProfileKind.NORMAL, "u1", "t1", null) is TabCrear.Ok)
        assertTrue(m.crear(ProfileKind.NORMAL, "u2", "t2", null) is TabCrear.Ok)
        assertTrue(m.crear(ProfileKind.NORMAL, "u3", "t3", null) is TabCrear.PresupuestoExcedido)
        val primera = m.tarjetas(ProfileKind.NORMAL)[0].id
        assertTrue(m.duplicar(primera) is TabCrear.PresupuestoExcedido)
        assertTrue(m.crear(ProfileKind.INCOGNITO, "u3", "t3", null) is TabCrear.Ok)
    }

    @Test
    fun presupuestoDeReabiertasDesalojaAlMasViejo() {
        val m = manager(FakeTransport(), 20, 2)
        m.crear(ProfileKind.NORMAL, "u1", "t1", null)
        m.crear(ProfileKind.NORMAL, "u2", "t2", null)
        m.crear(ProfileKind.NORMAL, "u3", "t3", null)
        val ids = m.tarjetas().map { it.id }
        for (id in ids) m.cerrar(id)
        val r1 = m.reabrir()
        val r2 = m.reabrir()
        val r3 = m.reabrir()
        assertTrue(r1 is TabCrear.Ok)
        assertTrue(r2 is TabCrear.Ok)
        assertTrue(r3 is TabCrear.NoExiste)
        assertTrue(m.tarjetas().none { it.url == "u1" })
    }

    @Test
    fun activarCambiaLaPestanaActiva() {
        val m = manager(FakeTransport())
        assertNull(m.activa())
        val primera = m.crear(ProfileKind.NORMAL, "u1", "t1", null) as TabCrear.Ok
        assertEquals(primera.id, m.activa())
        val segunda = m.crear(ProfileKind.NORMAL, "u2", "t2", null) as TabCrear.Ok
        assertFalse(m.activar(999L))
        assertTrue(m.activar(segunda.id))
        assertEquals(segunda.id, m.activa())
    }

    @Test
    fun actualizarReflejaTituloUrlYPreview() {
        val m = manager(FakeTransport())
        val id = (m.crear(ProfileKind.NORMAL, "u1", "t1", null) as TabCrear.Ok).id
        assertFalse(m.actualizar(999L, "x", "y", "z"))
        assertTrue(m.actualizar(id, "Nuevo", "http://n.dev/", "p"))
        val tarjeta = m.tarjeta(id)!!
        assertEquals("Nuevo", tarjeta.title)
        assertEquals("http://n.dev/", tarjeta.url)
        assertEquals("p", tarjeta.preview)
        assertTrue(m.nav(id) != null)
        assertNull(m.nav(999L))
    }

    @Test
    fun duplicarConservaTipoTituloUrlSinPreview() {
        val m = manager(FakeTransport())
        val id = (m.crear(ProfileKind.INCOGNITO, "u1", "t1", "prev") as TabCrear.Ok).id
        assertTrue(m.duplicar(999L) is TabCrear.NoExiste)
        val dup = m.duplicar(id) as TabCrear.Ok
        val tarjeta = m.tarjeta(dup.id)!!
        assertEquals(ProfileKind.INCOGNITO, tarjeta.kind)
        assertEquals("u1", tarjeta.url)
        assertEquals("t1", tarjeta.title)
        assertNull(tarjeta.preview)
    }

    @Test
    fun cerrarLaUnicaDejaSinActivaYSePuedeReabrir() {
        val m = manager(FakeTransport())
        val id = (m.crear(ProfileKind.NORMAL, "u1", "t1", null) as TabCrear.Ok).id
        val cierre = m.cerrar(id)!!
        assertEquals(ProfileKind.NORMAL, cierre.kind)
        assertFalse(cierre.perfilBorrado)
        assertNull(m.activa())
        assertNull(m.nav(id))
        assertNull(m.tarjeta(id))
        val vuelta = m.reabrir() as TabCrear.Ok
        assertEquals("u1", m.tarjeta(vuelta.id)!!.url)
    }

    @Test
    fun cerrarLaUltimaIncognitoBorraElPerfilVivo() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        val m = manager(t)
        val primera = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val segunda = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        cargar(m, primera, "http://a.dev/")
        assertNull(t.requests[0].headers["Cookie"])
        cargar(m, segunda, "http://a.dev/")
        assertEquals("la cookie vive compartida en el perfil incognito", "sid=uno", t.requests[1].headers["Cookie"])
        assertFalse(m.cerrar(primera)!!.perfilBorrado)
        assertTrue(m.cerrar(segunda)!!.perfilBorrado)
        val nueva = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        cargar(m, nueva, "http://a.dev/")
        assertNull("el perfil incognito debe morir con su ultima pestana", t.requests[2].headers["Cookie"])
    }

    @Test
    fun cookiesCompartidasEntrePestanasIncognito() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        val m = manager(t)
        val primera = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val segunda = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        assertTrue(m.nav(primera) !== m.nav(segunda))
        cargar(m, primera, "http://a.dev/")
        cargar(m, segunda, "http://a.dev/")
        assertEquals("sid=uno", t.requests[1].headers["Cookie"])
    }

    @Test
    fun sinContaminacionEntreTiposDePestana() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        val m = manager(t)
        val normal = (m.crear(ProfileKind.NORMAL, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val incognito = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        cargar(m, normal, "http://a.dev/")
        cargar(m, incognito, "http://a.dev/")
        assertNull("la incognito NO hereda cookies del perfil normal", t.requests[1].headers["Cookie"])
    }

    @Test
    fun cerrarLaUltimaIncognitoVaciaSusReabiertas() {
        val m = manager(FakeTransport())
        val primera = (m.crear(ProfileKind.INCOGNITO, "ua", "ta", null) as TabCrear.Ok).id
        val segunda = (m.crear(ProfileKind.INCOGNITO, "ub", "tb", null) as TabCrear.Ok).id
        m.cerrar(primera)
        m.cerrar(segunda)
        val vuelta = m.reabrir()
        assertTrue(vuelta is TabCrear.Ok)
        assertTrue("la tarjeta de la primera incognito murio con el perfil", m.reabrir() is TabCrear.NoExiste)
        assertEquals("ub", m.tarjeta((vuelta as TabCrear.Ok).id)!!.url)
    }

    @Test
    fun cerrarTodasIncognitoDevuelveElConteoYBorraElPerfil() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        val m = manager(t)
        val i1 = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        m.crear(ProfileKind.INCOGNITO, "u2", "t2", null)
        m.crear(ProfileKind.NORMAL, "u3", "t3", null)
        cargar(m, i1, "http://a.dev/")
        assertEquals(2, m.cerrarTodas(ProfileKind.INCOGNITO))
        assertEquals(0, m.tarjetas(ProfileKind.INCOGNITO).size)
        assertEquals(1, m.tarjetas().size)
        val nueva = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        cargar(m, nueva, "http://a.dev/")
        assertNull("el perfil incognito murio con cerrarTodas", t.requests[1].headers["Cookie"])
    }

    @Test
    fun reordenarMueveLaTarjetaEnElStrip() {
        val m = manager(FakeTransport())
        val a = (m.crear(ProfileKind.NORMAL, "u1", "t", null) as TabCrear.Ok).id
        val b = (m.crear(ProfileKind.NORMAL, "u2", "t", null) as TabCrear.Ok).id
        val c = (m.crear(ProfileKind.NORMAL, "u3", "t", null) as TabCrear.Ok).id
        assertFalse(m.reordenar(-1, 0))
        assertFalse(m.reordenar(0, 3))
        assertTrue(m.reordenar(1, 1))
        assertTrue(m.reordenar(0, 2))
        assertEquals(listOf(b, c, a), m.tarjetas().map { it.id })
    }

    @Test
    fun fireSobreElPerfilDelTipoYNulloSinPerfil() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        val m = manager(t)
        val normal = (m.crear(ProfileKind.NORMAL, "http://a.dev/", "a", null) as TabCrear.Ok).id
        cargar(m, normal, "http://a.dev/")
        assertNull("sin pestanas incognito no hay perfil incognito", m.fire(FireLevel.SESION, ProfileKind.INCOGNITO))
        val total = m.fire(FireLevel.TOTAL, ProfileKind.NORMAL)
        assertEquals(1, total!!.cookies)
        assertEquals(0, total.sessionPartitions)
        val incognito = (m.crear(ProfileKind.INCOGNITO, "u", "t", null) as TabCrear.Ok).id
        val sesion = m.fire(FireLevel.SESION, ProfileKind.INCOGNITO)
        assertEquals(0, sesion!!.sessionPartitions)
        assertTrue(m.nav(incognito) != null)
    }

    @Test
    fun limpiarCacheBorraSoloElCacheDeLosPerfilesVivos() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, mapOf("cache-control" to "max-age=60", "set-cookie" to "sid=uno; Path=/"), "<html><body>a</body></html>")
        t.routes["http://i.dev/"] = resp(200, mapOf("cache-control" to "max-age=60"), "<html><body>i</body></html>")
        val m = manager(t)
        val normal = (m.crear(ProfileKind.NORMAL, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val incognito = (m.crear(ProfileKind.INCOGNITO, "http://i.dev/", "i", null) as TabCrear.Ok).id
        cargar(m, normal, "http://a.dev/")
        cargar(m, incognito, "http://i.dev/")
        assertEquals("las entradas de cache del normal y del incognito mueren", 2, m.limpiarCache())
        assertEquals("un cache ya vacio devuelve 0", 0, m.limpiarCache())
        cargar(m, normal, "http://a.dev/")
        assertEquals("tras limpiar el GET vuelve a la red", 3, t.requests.size)
        assertEquals("la cookie sobrevive: limpiarCache NO es Fire", "sid=uno", t.requests[2].headers["Cookie"])
    }

    @Test
    fun reabrirEnReabreLaElegidaYConservaElResto() {
        val m = manager(FakeTransport())
        val a = (m.crear(ProfileKind.NORMAL, "u1", "t1", null) as TabCrear.Ok).id
        val b = (m.crear(ProfileKind.NORMAL, "u2", "t2", null) as TabCrear.Ok).id
        val c = (m.crear(ProfileKind.NORMAL, "u3", "t3", null) as TabCrear.Ok).id
        m.cerrar(a)
        m.cerrar(b)
        m.cerrar(c)
        assertEquals("la lista es la mas vieja primero", listOf("t1", "t2", "t3"), m.reabiertas().map { it.title })
        val vuelta = m.reabrirEn(0) as TabCrear.Ok
        assertEquals("la elegida revive", "u1", m.tarjeta(vuelta.id)?.url)
        assertEquals("las demas siguen en la lista", listOf("t2", "t3"), m.reabiertas().map { it.title })
        assertFalse(m.reabrirEn(-1) is TabCrear.Ok)
        assertFalse(m.reabrirEn(5) is TabCrear.Ok)
        assertEquals("fuera de rango no toca la lista", listOf("t2", "t3"), m.reabiertas().map { it.title })
        val ultima = m.reabrirEn(1) as TabCrear.Ok
        assertEquals("t3", m.tarjeta(ultima.id)?.title)
        assertEquals(listOf("t2"), m.reabiertas().map { it.title })
    }

    @Test
    fun reabiertasSoloLaRecienCerradaTrasElWipe() {
        val m = manager(FakeTransport())
        val n = (m.crear(ProfileKind.NORMAL, "un", "tn", null) as TabCrear.Ok).id
        val i1 = (m.crear(ProfileKind.INCOGNITO, "ui1", "ti1", null) as TabCrear.Ok).id
        val i2 = (m.crear(ProfileKind.INCOGNITO, "ui2", "ti2", null) as TabCrear.Ok).id
        m.cerrar(i1)
        assertEquals("la incognito vive en la lista mientras el perfil vive", listOf("ti1"), m.reabiertas().map { it.title })
        val cierre = m.cerrar(i2)
        assertTrue(cierre != null && cierre.perfilBorrado)
        assertEquals("la destruida vieja JAMAS revive; solo queda la recien cerrada", listOf("ti2"), m.reabiertas().map { it.title })
        val vuelta = m.reabrirEn(0) as TabCrear.Ok
        assertEquals("ui2", m.tarjeta(vuelta.id)?.url)
        assertEquals("la lista queda vacia", 0, m.reabiertas().size)
        m.cerrar(n)
        assertEquals(listOf("tn"), m.reabiertas().map { it.title })
    }

    @Test
    fun filtroBloqueaElDocumentoAntesDelTransporte() {
        val t = FakeTransport()
        t.routes["http://anuncio.dev/pagina"] = resp(200, emptyMap(), "<html><body>ok</body></html>")
        val filtro = FiltroBloqueador("anuncio.dev")
        val m = manager(t, filter = filtro)
        val id = (m.crear(ProfileKind.NORMAL, "http://anuncio.dev/pagina", "a", null) as TabCrear.Ok).id
        val out = m.nav(id)!!.navigate("http://anuncio.dev/pagina")
        assertTrue("el bloqueo del modulo falla la navegacion", out is NavOutcome.Failed)
        val fallo = out as NavOutcome.Failed
        assertEquals(ErrorCode.DENIED, fallo.code)
        assertEquals(NavState.NAVIGATING, fallo.phase)
        assertNull("el fallo es ANTES del commit: sin pagina", fallo.page)
        assertEquals("el filtro decide ANTES de tocar el transporte", 0, t.requests.size)
        assertEquals("solo se consulta el documento", 1, filtro.consultas.size)
        assertEquals("document", filtro.consultas[0].resourceType)
        assertEquals("http://anuncio.dev/pagina", filtro.consultas[0].url)
        assertEquals("el iniciador del documento es vacio", "", filtro.consultas[0].initiatorUrl)
    }

    @Test
    fun filtroQuePermiteNavegaIgualYConsultaSubrecursos() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(
            200, emptyMap(),
            "<html><body><img src=\"/logo.png\">" +
                "<script src=\"/app.js\"></script></body></html>"
        )
        t.routes["http://a.dev/logo.png"] = resp(200, emptyMap(), "img")
        t.routes["http://a.dev/app.js"] = resp(200, emptyMap(), "var x = 1;")
        val filtro = FiltroBloqueador("nadie.bloquea")
        val m = manager(t, filter = filtro)
        val id = (m.crear(ProfileKind.NORMAL, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val out = m.nav(id)!!.navigate("http://a.dev/")
        assertTrue("con un filtro que siempre permite se navega igual", out is NavOutcome.Loaded)
        assertEquals("document, image y script consultados al filtro", 3, filtro.consultas.size)
        assertEquals("document", filtro.consultas[0].resourceType)
        assertEquals("image", filtro.consultas[1].resourceType)
        assertEquals("script", filtro.consultas[2].resourceType)
        assertEquals("el iniciador de los subrecursos es el documento", "http://a.dev/", filtro.consultas[1].initiatorUrl)
        assertEquals("las tres peticiones llegan al transporte", 3, t.requests.size)
    }

    @Test
    fun elFiltroAplicaTambienALasPestanasIncognito() {
        val t = FakeTransport()
        t.routes["http://a.dev/"] = resp(200, emptyMap(), "<html><body>a</body></html>")
        val filtro = FiltroBloqueador("a.dev")
        val m = manager(t, filter = filtro)
        val id = (m.crear(ProfileKind.INCOGNITO, "http://a.dev/", "a", null) as TabCrear.Ok).id
        val out = m.nav(id)!!.navigate("http://a.dev/")
        assertTrue("el filtro tambien corta el documento incognito", out is NavOutcome.Failed)
        assertEquals(ErrorCode.DENIED, (out as NavOutcome.Failed).code)
        assertEquals("el transporte incognito tampoco se toca", 0, t.requests.size)
    }
}

