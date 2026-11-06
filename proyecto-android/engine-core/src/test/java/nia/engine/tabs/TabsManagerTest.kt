package nia.engine.tabs

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.WallClock
import nia.engine.nav.NavOutcome
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult
import nia.engine.profile.FireLevel
import nia.engine.profile.ProfileKind

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 32333.48541.155): los 14 tests cubren la creacion con
 * tarjetas ordenadas por tipo, el presupuesto por tipo, el
 * desalojo del mas viejo en las reabiertas, la pestana activa,
 * la actualizacion del shell, el duplicado, el cierre de la
 * unica, el borrado del perfil al cerrar la ultima incognito
 * (con cookies REALES del CookieJar compartidas dentro del
 * tipo), el aislamiento entre tipos, el vaciado de las
 * reabiertas de incognito, cerrarTodas, el reorden y Fire.
 * T073 v1 (RES-0079).
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

    private fun manager(t: FetchTransport, maxTabs: Int = 20, maxReab: Int = 10): TabsManager =
        TabsManager(FakeClock(), t, dir(), maxTabs, maxReab)

    private fun resp(status: Int, headers: Map<String, String>, body: String): FetchResponse =
        FetchResponse(status, headers, body.toByteArray())

    private fun cargar(manager: TabsManager, id: Long, url: String) {
        val out = manager.nav(id)!!.navigate(url)
        assertTrue("la navegacion de la pestana fallo", out is NavOutcome.Loaded)
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
}
