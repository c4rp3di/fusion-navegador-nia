package nia.engine.net

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.WallClock
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.profile.BrowserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T084 v1 (R0160, RES-0077): espejo Kotlin del gemelo TS validado offline
 * (huella 0070.3417.3348). El panel de identidad deriva SOLO de URL y TLS
 * del transporte real y nunca fabrica datos; las excepciones HTTP son por
 * sitio; la politica HTTPS-only decide cada salto ANTES de red y las
 * integraciones del NavController (degradacion https prohibida, redirect
 * por host, mixed content) quedan intactas con la politica activa.
 */
class SiteSecurityTest {

    private class RelojFalso : WallClock {
        var ahora = 5000L
        override fun nowMillis(): Long = ahora
    }

    private class TransporteFalso : FetchTransport {
        val peticiones = ArrayList<String>()
        val rutas = HashMap<String, FetchResponse>()

        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            peticiones.add(request.url)
            val respuesta = rutas[request.url] ?: return TransportResult.Failure(TransportFailure.NETWORK)
            return TransportResult.Success(respuesta)
        }
    }

    private fun resp(status: Int, headers: Map<String, String>, body: String): FetchResponse =
        FetchResponse(status, headers, body.toByteArray())

    private fun nav(t: TransporteFalso, c: RelojFalso, politica: PoliticaHttpsOnly?): NavController =
        NavController(t, c, BrowserProfile.incognito(c), politica)

    private fun loaded(out: NavOutcome): NavOutcome.Loaded = out as NavOutcome.Loaded

    private fun failed(out: NavOutcome): NavOutcome.Failed = out as NavOutcome.Failed

    @Test
    fun panelHttpsCompleto() {
        assertEquals(
            listOf(
                "Conexion cifrada (HTTPS)",
                "Emisor: Emisor X",
                "Sujeto: Sujeto Y",
                "Valido desde: 2026-01-01T00:00:00Z",
                "Valido hasta: 2027-01-01T00:00:00Z",
                PanelIdentidad.NO_GARANTIZA_CONFIANZA
            ),
            PanelIdentidad.lineas(
                "https://a.test/pagina",
                TlsInfo("Emisor X", "Sujeto Y", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")
            )
        )
    }

    @Test
    fun panelHttpsSinTlsNoFabrica() {
        assertEquals(
            listOf("Conexion cifrada (HTTPS)", "Emisor: no disponible", PanelIdentidad.NO_GARANTIZA_CONFIANZA),
            PanelIdentidad.lineas("https://a.test/", null)
        )
    }

    @Test
    fun panelHttpsTlsParcialSoloEmisor() {
        assertEquals(
            listOf("Conexion cifrada (HTTPS)", "Emisor: Emisor X", PanelIdentidad.NO_GARANTIZA_CONFIANZA),
            PanelIdentidad.lineas("https://a.test/", TlsInfo("Emisor X", null, null, null))
        )
    }

    @Test
    fun panelHttpNoCifradaSinCertificado() {
        assertEquals(
            listOf(
                "Conexion NO cifrada (HTTP)",
                PanelIdentidad.AVISO_SIN_CIFRAR,
                "Sin certificado que mostrar",
                PanelIdentidad.NO_GARANTIZA_CONFIANZA
            ),
            PanelIdentidad.lineas("http://a.test/", null)
        )
    }

    @Test
    fun panelHttpNuncaMuestraEmisor() {
        val lineas = PanelIdentidad.lineas("http://a.test/", TlsInfo("Emisor X", "Sujeto Y", "d", "h"))
        assertFalse(lineas.any { it.contains("Emisor") })
        assertFalse(lineas.any { it.contains("Sujeto") })
    }

    @Test
    fun disclaimerSiempreUltimaLinea() {
        val aviso = PanelIdentidad.NO_GARANTIZA_CONFIANZA
        assertEquals(aviso, PanelIdentidad.lineas("https://a.test/", null).last())
        assertEquals(aviso, PanelIdentidad.lineas("http://a.test/", null).last())
        assertEquals(aviso, PanelIdentidad.lineas("ftp://a.test/", null).last())
    }

    @Test
    fun panelEsquemaNoWeb() {
        assertEquals(
            listOf("Esquema sin conexion web", PanelIdentidad.NO_GARANTIZA_CONFIANZA),
            PanelIdentidad.lineas("about:blank", null)
        )
    }

    @Test
    fun panelNormalizaUrlAntesDeClasificar() {
        assertEquals(
            listOf("Conexion cifrada (HTTPS)", "Emisor: no disponible", PanelIdentidad.NO_GARANTIZA_CONFIANZA),
            PanelIdentidad.lineas("  HTTPS://A.TEST/  ", null)
        )
    }

    @Test
    fun excepcionesVaciaNoTiene() {
        val ex = ExcepcionesHttps()
        assertFalse(ex.tiene("a.test"))
        assertEquals(emptyList<String>(), ex.lista())
    }

    @Test
    fun excepcionesConcederYConsultar() {
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        assertTrue(ex.tiene("a.test"))
    }

    @Test
    fun excepcionesDedup() {
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        ex.conceder("a.test")
        assertEquals(listOf("a.test"), ex.lista())
    }

    @Test
    fun excepcionesListaOrdenada() {
        val ex = ExcepcionesHttps()
        ex.conceder("c.test")
        ex.conceder("a.test")
        ex.conceder("b.test")
        assertEquals(listOf("a.test", "b.test", "c.test"), ex.lista())
    }

    @Test
    fun excepcionesPorSitioSeparadas() {
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        assertFalse(ex.tiene("b.test"))
    }

    @Test
    fun politicaHttpsNavegaSinExcepciones() {
        val pol = PoliticaHttpsOnly(ExcepcionesHttps())
        assertTrue(pol.decidir("https", "a.test") is PoliticaHttpsOnly.Decision.Navegar)
    }

    @Test
    fun politicaHttpSinExcepcionBloquea() {
        val pol = PoliticaHttpsOnly(ExcepcionesHttps())
        assertEquals(PoliticaHttpsOnly.Decision.Bloquear("a.test"), pol.decidir("http", "a.test"))
    }

    @Test
    fun politicaHttpConExcepcionPermite() {
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        val pol = PoliticaHttpsOnly(ex)
        assertEquals(PoliticaHttpsOnly.Decision.PermitirConAdvertencia("a.test"), pol.decidir("http", "a.test"))
    }

    @Test
    fun politicaExcepcionSitioExacto() {
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        val pol = PoliticaHttpsOnly(ex)
        assertEquals(PoliticaHttpsOnly.Decision.Bloquear("sub.a.test"), pol.decidir("http", "sub.a.test"))
    }

    @Test
    fun navCompatSinPoliticaHttpCarga() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(200, emptyMap(), "<html><body><h1>ok</h1></body></html>")
        val out = nav(t, RelojFalso(), null).navigate("http://a.test/")
        val p = loaded(out).page
        assertEquals("http://a.test/", p.url)
        assertFalse(p.events.any { it.url.startsWith("excepcion-http:") })
    }

    @Test
    fun navBloqueoAntesDeRedSinPeticiones() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(200, emptyMap(), "<html></html>")
        val pol = PoliticaHttpsOnly(ExcepcionesHttps())
        val out = nav(t, RelojFalso(), pol).navigate("http://a.test/")
        val f = failed(out)
        assertEquals(ErrorCode.DENIED, f.code)
        assertNull(f.page)
        assertTrue(t.peticiones.isEmpty())
    }

    @Test
    fun navConExcepcionCargaYAdvierte() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(200, emptyMap(), "<html><body><h1>ok</h1></body></html>")
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        val out = nav(t, RelojFalso(), PoliticaHttpsOnly(ex)).navigate("http://a.test/")
        val p = loaded(out).page
        assertTrue(p.events.any { it.url == "excepcion-http:a.test" })
    }

    @Test
    fun navHttpsConPoliticaSinEvento() {
        val t = TransporteFalso()
        t.rutas["https://a.test/"] = resp(200, emptyMap(), "<html><body><h1>ok</h1></body></html>")
        val out = nav(t, RelojFalso(), PoliticaHttpsOnly(ExcepcionesHttps())).navigate("https://a.test/")
        val p = loaded(out).page
        assertFalse(p.events.any { it.url.startsWith("excepcion-http:") })
    }

    @Test
    fun navPaginaLlevaTlsDelTransporte() {
        val t = TransporteFalso()
        val tls = TlsInfo("Emisor X", "Sujeto Y", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")
        t.rutas["https://a.test/"] = FetchResponse(200, emptyMap(), "<html><body><h1>ok</h1></body></html>".toByteArray(), tls)
        val out = nav(t, RelojFalso(), null).navigate("https://a.test/")
        assertEquals(tls, loaded(out).page.tls)
    }

    @Test
    fun navPaginaHttpTlsNull() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(200, emptyMap(), "<html><body><h1>ok</h1></body></html>")
        val out = nav(t, RelojFalso(), null).navigate("http://a.test/")
        assertNull(loaded(out).page.tls)
    }

    @Test
    fun navDegradacionIntacta() {
        val t = TransporteFalso()
        t.rutas["https://r.test/"] = resp(302, mapOf("location" to "http://a.test/"), "")
        val out = nav(t, RelojFalso(), null).navigate("https://r.test/")
        val f = failed(out)
        assertEquals(ErrorCode.UNSUPPORTED, f.code)
        assertEquals("redireccion no seguida: degradacion-https-prohibida", f.message)
    }

    @Test
    fun navRedirectAHostSinExcepcionBloquea() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(302, mapOf("location" to "http://b.test/"), "")
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        val out = nav(t, RelojFalso(), PoliticaHttpsOnly(ex)).navigate("http://a.test/")
        val f = failed(out)
        assertEquals(ErrorCode.DENIED, f.code)
        assertEquals(1, t.peticiones.size)
        assertNull(f.page)
    }

    @Test
    fun navRedirectAHostConExcepcionCarga() {
        val t = TransporteFalso()
        t.rutas["http://a.test/"] = resp(302, mapOf("location" to "http://b.test/"), "")
        t.rutas["http://b.test/"] = resp(200, emptyMap(), "<html><body><h1>final</h1></body></html>")
        val ex = ExcepcionesHttps()
        ex.conceder("a.test")
        ex.conceder("b.test")
        val out = nav(t, RelojFalso(), PoliticaHttpsOnly(ex)).navigate("http://a.test/")
        val p = loaded(out).page
        assertEquals("http://b.test/", p.url)
        assertEquals(2, p.events.count { it.url.startsWith("excepcion-http:") })
    }

    @Test
    fun mixedContentBloqueadoAntesDeRedConPolitica() {
        val t = TransporteFalso()
        t.rutas["https://s.test/"] = resp(200, emptyMap(), "<html><body><img src=\"http://m.test/x.png\"></body></html>")
        val out = nav(t, RelojFalso(), PoliticaHttpsOnly(ExcepcionesHttps())).navigate("https://s.test/")
        val p = loaded(out).page
        assertTrue(p.subresources.isNotEmpty())
        assertEquals("blocked-mixed-content", p.subresources[0].outcome)
        assertTrue(t.peticiones.none { it.contains("m.test") })
    }

    @Test
    fun panelHttpAvisoClaroTrasElTitulo() {
        val lineas = PanelIdentidad.lineas("http://a.test/", null)
        assertEquals("Conexion NO cifrada (HTTP)", lineas[0])
        assertEquals(PanelIdentidad.AVISO_SIN_CIFRAR, lineas[1])
    }
}
