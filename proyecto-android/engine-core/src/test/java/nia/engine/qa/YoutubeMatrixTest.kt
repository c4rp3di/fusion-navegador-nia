package nia.engine.qa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nia.contracts.CancellationToken
import nia.contracts.WallClock
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult

/**
 * Tests de la matriz P7 YouTube (T039 v1, RES-0063): el catalogo
 * fechado del objetivo YouTube y la ejecucion de las acciones
 * COMPLETA contra el corpus enlatado con transporte falso SIN red.
 * Las expectativas de los detalles vienen del gemelo TS validado en
 * 2 corridas (15/15 OK en ambas) y de las tareas ya verificadas que
 * la matriz ejercita de punta a punta: T012 HTML, T014 CSS, T024
 * querySelector y eventos, T027 forms y fetch, T032 tablas, T033
 * storage, T016 paint y T036/T049 subtitulos.
 */
class YoutubeMatrixTest {

    private class RelojFalso : WallClock {
        var now = 1000L
        override fun nowMillis(): Long = now
    }

    /** Transporte falso del corpus: respuestas enlatadas por URL, SIN red. */
    private class TransporteCorpusYt : FetchTransport {
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            val cuerpo = when {
                request.url.contains("/yt/search") -> "2"
                request.url.contains("comentario-7/c1") -> "Ana:Gran video, gracias"
                request.url.contains("comentario-7/c2") -> "Luis:El seek funciona bien"
                request.url.contains("/yt/session") -> "OK"
                request.url.contains("/yt/subs-7.vtt") -> VTT_CORPUS
                else -> ""
            }
            return TransportResult.Success(
                FetchResponse(200, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
            )
        }
    }

    private fun matriz(): YoutubeMatrix = YoutubeMatrix(TransporteCorpusYt(), RelojFalso())

    private fun runDe(m: YoutubeMatrix, id: String): ActionRun =
        m.correr().firstOrNull { it.action.id == id } ?: throw AssertionError("sin run " + id)

    @Test
    fun catalogoDoceAcciones() {
        assertEquals(12, matriz().acciones().size)
    }

    @Test
    fun catalogoConteosPorEstado() {
        val lista = matriz().acciones()
        assertEquals(8, lista.count { it.state == ActionState.COMPLETA })
        assertEquals(2, lista.count { it.state == ActionState.LIMITE })
        assertEquals(2, lista.count { it.state == ActionState.EXCLUIDA })
    }

    @Test
    fun limitesYExcluidasFechadas() {
        assertTrue(
            matriz().acciones().filter { it.state != ActionState.COMPLETA }
                .all { it.note.contains("2026-11-04") }
        )
    }

    @Test
    fun correrEjecutaSoloLasCompletas() {
        assertEquals(8, matriz().correr().size)
    }

    @Test
    fun todasLasCompletasOk() {
        assertTrue(matriz().correr().all { it.ok })
    }

    @Test
    fun detalleYtBusqueda() {
        assertEquals("resultados=2", runDe(matriz(), "yt-busqueda").detail)
    }

    @Test
    fun detalleYtWatch() {
        assertEquals("filas=3,tabla=si,paint=si", runDe(matriz(), "yt-watch").detail)
    }

    @Test
    fun detalleYtComentarios() {
        assertEquals("comentarios=2", runDe(matriz(), "yt-comentarios").detail)
    }

    @Test
    fun detalleYtSubs() {
        assertEquals("cues=3", runDe(matriz(), "yt-subs").detail)
    }

    @Test
    fun detalleYtHistorial() {
        assertEquals("historial=2", runDe(matriz(), "yt-historial").detail)
    }

    @Test
    fun detalleYtLogin() {
        assertEquals("login=OK", runDe(matriz(), "yt-login").detail)
    }

    @Test
    fun detalleYtGrid() {
        assertEquals("videos=4,tabla=si,paint=si", runDe(matriz(), "yt-grid").detail)
    }

    @Test
    fun detalleYtMasTarde() {
        assertEquals("lista=2", runDe(matriz(), "yt-mas-tarde").detail)
    }

    @Test
    fun resumenDeLaMatriz() {
        assertEquals("ok=8 limites=2 excluidas=2", matriz().resumen())
    }

    @Test
    fun idsUnicosYSitios() {
        val lista = matriz().acciones()
        assertEquals(12, lista.map { it.id }.toSet().size)
        assertTrue(lista.all { it.site == "youtube.com" })
    }

    companion object {
        private const val VTT_CORPUS = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nGran video\n\n00:00:05.000 --> 00:00:08.000\nEl seek funciona\n\n00:00:09.000 --> 00:00:12.000\nTercera linea\n"
    }
}
