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
 * Tests de la matriz P7 GitHub/Gmail (T038 v1, RES-0062): el catalogo
 * fechado de ADR-012 y la ejecucion de las acciones COMPLETA contra el
 * corpus enlatado con transporte falso SIN red. Las expectativas de los
 * detalles vienen del gemelo TS validado en 2 corridas (15/15 OK en
 * ambas) y de las tareas ya verificadas que la matriz ejercita de punta
 * a punta: T012 HTML, T014 CSS, T024 querySelector y eventos, T027
 * forms y fetch, T032 tablas, T033 storage y T016 paint.
 */
class SiteMatrixTest {

    private class RelojFalso : WallClock {
        var now = 1000L
        override fun nowMillis(): Long = now
    }

    /** Transporte falso del corpus: respuestas enlatadas por URL, SIN red. */
    private class TransporteCorpus : FetchTransport {
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            val cuerpo = when {
                request.url.contains("issue-42/c1") -> "Ana:Funciona en mi movil"
                request.url.contains("issue-42/c2") -> "Luis:Revisado, merge"
                request.url.contains("/gh/session") -> "OK"
                request.url.contains("/gm/hilo-7") -> "Ana:Presupuesto Q4"
                request.url.contains("/gm/search") -> "1"
                else -> ""
            }
            return TransportResult.Success(
                FetchResponse(200, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
            )
        }
    }

    private fun matriz(): GithubGmailMatrix = GithubGmailMatrix(TransporteCorpus(), RelojFalso())

    private fun runDe(m: GithubGmailMatrix, id: String): ActionRun =
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
    fun detalleGhRepo() {
        assertEquals("filas=3,tabla=si,paint=si", runDe(matriz(), "gh-repo").detail)
    }

    @Test
    fun detalleGhIssue() {
        assertEquals("comentarios=2", runDe(matriz(), "gh-issue").detail)
    }

    @Test
    fun detalleGhLogin() {
        assertEquals("login=OK", runDe(matriz(), "gh-login").detail)
    }

    @Test
    fun detalleGmBandeja() {
        assertEquals("hilos=2,tabla=si,paint=si", runDe(matriz(), "gm-bandeja").detail)
    }

    @Test
    fun detalleGmHilo() {
        assertEquals("hilo=Ana:Presupuesto Q4", runDe(matriz(), "gm-hilo").detail)
    }

    @Test
    fun detalleGmBusqueda() {
        assertEquals("resultados=1", runDe(matriz(), "gm-busqueda").detail)
    }

    @Test
    fun detalleGmRedaccion() {
        assertEquals("borrador=guardado", runDe(matriz(), "gm-redaccion").detail)
    }

    @Test
    fun detalleGmLeidos() {
        assertEquals("leidos=2", runDe(matriz(), "gm-leidos").detail)
    }

    @Test
    fun resumenDeLaMatriz() {
        assertEquals("ok=8 limites=2 excluidas=2", matriz().resumen())
    }

    @Test
    fun idsUnicosYSitios() {
        val lista = matriz().acciones()
        assertEquals(12, lista.map { it.id }.toSet().size)
        assertTrue(lista.all { it.site == "github.com" || it.site == "mail.google.com" })
    }
}
