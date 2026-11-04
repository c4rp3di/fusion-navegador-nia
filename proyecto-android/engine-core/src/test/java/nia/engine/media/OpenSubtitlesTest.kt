package nia.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.valueOrNull
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult

/**
 * T050 v1 (R0116, RES-0061): espejo del gemelo TS. El transporte falso
 * sirve respuestas enlatadas con formato estado|cuerpo elegidas por
 * substring de URL y graba las peticiones: CI no toca la red y los
 * tests si comprueban cabeceras, cuerpo y orden de las peticiones.
 */
class OpenSubtitlesTest {

    /** Respuesta enlatada de busqueda del proveedor (es y en). */
    private val busquedaJson =
        "{\"total\":2,\"data\":[{\"id\":\"111\",\"type\":\"subtitle\",\"attributes\":{\"language\":\"es\",\"release\":\"Gran.Hotel.S01E01\",\"files\":[{\"file_id\":9001,\"cd_number\":1}]}},{\"id\":\"222\",\"type\":\"subtitle\",\"attributes\":{\"language\":\"en\",\"release\":\"Gran.Hotel.S01E01.720p\",\"files\":[{\"file_id\":9002,\"cd_number\":1}]}}]}"

    private val descargaJson =
        "{\"link\":\"https://cdn.demo/gran-hotel.srt\",\"file_name\":\"gran-hotel.srt\"}"

    private val srtDemo = "1\n00:00:01,000 --> 00:00:04,000\nHola\n"

    /** Transporte falso: guion por substring de URL, graba las peticiones. */
    private class TransporteFalso(private val guion: Map<String, String>) : FetchTransport {
        val peticiones = ArrayList<FetchRequest>()

        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            peticiones.add(request)
            for ((clave, entrada) in guion) {
                if (request.url.contains(clave)) {
                    val estado = entrada.substringBefore('|').toInt()
                    val cuerpo = entrada.substringAfter('|')
                    return TransportResult.Success(
                        FetchResponse(estado, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
                    )
                }
            }
            throw AssertionError("peticion inesperada: " + request.url)
        }
    }

    private fun cliente(
        guion: Map<String, String>,
        cuenta: Boolean = false
    ): Pair<OpenSubtitlesClient, TransporteFalso> {
        val transporte = TransporteFalso(guion)
        val config = if (cuenta) {
            OpenSubtitlesConfig(apiKey = "demo-key", username = "usuario", password = "clave")
        } else {
            OpenSubtitlesConfig(apiKey = "demo-key")
        }
        return Pair(OpenSubtitlesClient(config, FetchManager(transporte)), transporte)
    }

    @Test
    fun buscarAnalizaResultados() {
        val (c, t) = cliente(mapOf("/subtitles" to "200|" + busquedaJson))
        val r = c.buscar("gran hotel", listOf("es", "en"))
        val hits = r.valueOrNull ?: throw AssertionError("busqueda fallo")
        assertEquals(2, hits.size)
        assertEquals("111", hits[0].id)
        assertEquals("Gran.Hotel.S01E01", hits[0].nombre)
        assertEquals("es", hits[0].idioma)
        assertEquals(9001L, hits[0].fileId)
        assertEquals("222", hits[1].id)
        assertEquals("en", hits[1].idioma)
        assertEquals(9002L, hits[1].fileId)
        val get = t.peticiones[0]
        assertEquals("GET", get.method)
        assertTrue(get.url.contains("query=gran%20hotel"))
        assertEquals("demo-key", get.headers["Api-Key"])
    }

    @Test
    fun buscarCodificaYEscapa() {
        val (c, t) = cliente(mapOf("/subtitles" to "200|" + busquedaJson))
        val r = c.buscar("gran hotel 2+")
        r.valueOrNull ?: throw AssertionError("busqueda fallo")
        assertTrue(t.peticiones[0].url.contains("query=gran%20hotel%202%2B"))
    }

    @Test
    fun buscarFiltraIdiomas() {
        val (c1, t1) = cliente(mapOf("/subtitles" to "200|" + busquedaJson))
        val r1 = c1.buscar("gran hotel", listOf("es"))
        r1.valueOrNull ?: throw AssertionError("busqueda 1 fallo")
        assertTrue(t1.peticiones[0].url.contains("languages=es"))
        val (c2, t2) = cliente(mapOf("/subtitles" to "200|" + busquedaJson))
        val r2 = c2.buscar("gran hotel")
        r2.valueOrNull ?: throw AssertionError("busqueda 2 fallo")
        assertFalse(t2.peticiones[0].url.contains("languages"))
    }

    @Test
    fun buscarSinResultados() {
        val (c) = cliente(mapOf("/subtitles" to "200|{\"total\":0,\"data\":[]}"))
        val hits = c.buscar("nada").valueOrNull ?: throw AssertionError("busqueda fallo")
        assertEquals(0, hits.size)
    }

    @Test
    fun buscarConsultaVacia() {
        val (c, t) = cliente(emptyMap())
        val r = c.buscar("   ")
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.INVALID_INPUT, f.error.code)
        assertEquals(0, t.peticiones.size)
    }

    @Test
    fun buscarDenegadoNoFiltraCredenciales() {
        val (c) = cliente(mapOf("/subtitles" to "401|{}"))
        val r = c.buscar("gran hotel")
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.DENIED, f.error.code)
        assertFalse(f.error.safeMessage.contains("demo-key"))
    }

    @Test
    fun buscarCuota() {
        val (c) = cliente(mapOf("/subtitles" to "429|{}"))
        val r = c.buscar("gran hotel")
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.QUOTA, f.error.code)
    }

    @Test
    fun buscarIlegible() {
        val (c) = cliente(mapOf("/subtitles" to "200|no-json"))
        val r = c.buscar("gran hotel")
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.PARSE, f.error.code)
    }

    @Test
    fun cuentaIniciaSesionUnaSolaVez() {
        val (c, t) = cliente(
            mapOf(
                "/login" to "200|{\"token\":\"tok123\"}",
                "/subtitles" to "200|" + busquedaJson
            ),
            cuenta = true
        )
        val hits1 = c.buscar("gran hotel").valueOrNull ?: throw AssertionError("busqueda 1 fallo")
        val hits2 = c.buscar("gran hotel").valueOrNull ?: throw AssertionError("busqueda 2 fallo")
        assertEquals(2, hits1.size)
        assertEquals(2, hits2.size)
        assertEquals(3, t.peticiones.size)
        val login = t.peticiones[0]
        assertEquals("POST", login.method)
        assertTrue(login.url.endsWith("/login"))
        val cuerpoLogin = String(login.body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue(cuerpoLogin.contains("usuario"))
        var logins = 0
        for (p in t.peticiones) {
            if (p.url.contains("/login")) logins += 1
        }
        assertEquals(1, logins)
        assertEquals("Bearer tok123", t.peticiones[2].headers["Authorization"])
        assertTrue(c.conectado())
    }

    @Test
    fun cuentaRechazaCredenciales() {
        val (c, t) = cliente(mapOf("/login" to "403|{}"), cuenta = true)
        val r = c.buscar("gran hotel")
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.DENIED, f.error.code)
        assertFalse(f.error.safeMessage.contains("usuario"))
        assertFalse(f.error.safeMessage.contains("clave"))
        assertEquals(1, t.peticiones.size)
        assertFalse(c.conectado())
    }

    @Test
    fun descargarDevuelvePista() {
        val (c, t) = cliente(
            mapOf(
                "/download" to "200|" + descargaJson,
                "gran-hotel.srt" to "200|" + srtDemo
            )
        )
        val r = c.descargar(9001)
        val pista = r.valueOrNull ?: throw AssertionError("descarga fallo")
        assertEquals("gran-hotel.srt", pista.nombre)
        assertTrue(pista.contenido.contains("Hola"))
        assertEquals(2, t.peticiones.size)
        val post = t.peticiones[0]
        assertEquals("POST", post.method)
        assertEquals("{\"file_id\":9001}", String(post.body ?: ByteArray(0), Charsets.UTF_8))
        assertEquals("application/json", post.headers["Content-Type"])
        assertFalse(post.headers.containsKey("Authorization"))
        val get = t.peticiones[1]
        assertEquals("GET", get.method)
        assertEquals("https://cdn.demo/gran-hotel.srt", get.url)
        assertTrue(get.headers.containsKey("User-Agent"))
    }

    @Test
    fun descargarConCuentaMandaBearer() {
        val (c, t) = cliente(
            mapOf(
                "/login" to "200|{\"token\":\"tok123\"}",
                "/download" to "200|" + descargaJson,
                "gran-hotel.srt" to "200|" + srtDemo
            ),
            cuenta = true
        )
        val pista = c.descargar(9005).valueOrNull ?: throw AssertionError("descarga fallo")
        assertEquals("gran-hotel.srt", pista.nombre)
        assertEquals(3, t.peticiones.size)
        assertEquals("Bearer tok123", t.peticiones[1].headers["Authorization"])
    }

    @Test
    fun descargarRechazado() {
        val (c, t) = cliente(mapOf("/download" to "401|{}"))
        val r = c.descargar(9001)
        val f = r as? Outcome.Failure ?: throw AssertionError("debio fallar")
        assertEquals(ErrorCode.DENIED, f.error.code)
        assertFalse(f.error.safeMessage.contains("demo-key"))
        assertEquals(1, t.peticiones.size)
    }

    @Test
    fun configSinApiKeyLanza() {
        try {
            OpenSubtitlesConfig(apiKey = "   ")
            fail("debio lanzar IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
