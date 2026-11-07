package nia.engine.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.TestStatus

/**
 * Tests de capacidades (T108 v1, RES-0103): espejo del gemelo TS en
 * verde ANTES del push: catalogo fijo, todo NOT_RUN honesto sin
 * registro embebido, registro ilegible sin maquillar, fuera de
 * catalogo ignorado, actualizar() que JAMAS sobrescribe una medida
 * (anti-optimismo) y resumen con alcances separados y el aviso de
 * subset SIEMPRE.
 */
class CapacidadesTest {

    private fun registroEmbebido(): String =
        "http.fetch=PASS#ci#tests unitarios del build abc\n" +
        "html.parser=PASS#ci#tests unitarios del build abc\n" +
        "css.cascade=PASS#ci#tests unitarios del build abc\n" +
        "js.cuotas=PASS#ci#tests unitarios del build abc\n" +
        "cookies.persistencia=PASS#ci#tests unitarios del build abc\n" +
        "marcadores.html=PASS#ci#tests unitarios del build abc"

    @Test
    fun catalogoOrdenadoDeLaAceptacion() {
        assertEquals(
            "http.fetch,html.parser,css.cascade,js.cuotas,cookies.persistencia,marcadores.html",
            Capacidades.catalogo().joinToString(",")
        )
    }

    @Test
    fun sinRegistroTodoNotRunHonesto() {
        val r = Capacidades.registro(null)
        assertEquals(6, r.size)
        for (c in r) {
            assertEquals(TestStatus.NOT_RUN, c.estado)
            assertEquals(Capacidades.ALCANCE_NINGUNO, c.alcance)
            assertEquals(Capacidades.SIN_REGISTRO, c.evidencia)
        }
    }

    @Test
    fun registroVacioIgualQueNulo() {
        assertEquals(Capacidades.registro(null), Capacidades.registro(""))
    }

    @Test
    fun registroIlegibleNoInventaPass() {
        for (texto in listOf(
            "esto no es un registro",
            "http.fetch=VERDE#ci#x",
            "http.fetch=PASS#ci",
            "http.fetch=PASS#ci#"
        )) {
            val r = Capacidades.registro(texto)
            assertEquals(6, r.size)
            for (c in r) {
                assertEquals(TestStatus.NOT_RUN, c.estado)
                assertEquals(Capacidades.ILEGIBLE, c.evidencia)
            }
        }
    }

    @Test
    fun parseaElRegistroEmbebido() {
        val r = Capacidades.registro(registroEmbebido())
        assertEquals(6, r.size)
        assertEquals(
            Capacidades.catalogo().joinToString(","),
            r.map { it.nombre }.joinToString(",")
        )
        for (c in r) {
            assertEquals(TestStatus.PASS, c.estado)
            assertEquals("ci", c.alcance)
            assertEquals("tests unitarios del build abc", c.evidencia)
        }
    }

    @Test
    fun lineaFueraDeCatalogoIgnorada() {
        val r = Capacidades.registro("coso.raro=PASS#ci#evidencia")
        assertEquals(6, r.size)
        assertTrue(r.none { it.nombre == "coso.raro" })
        for (c in r) {
            assertEquals(TestStatus.NOT_RUN, c.estado)
            assertEquals(Capacidades.SIN_REGISTRO, c.evidencia)
        }
    }

    @Test
    fun actualizarSoloLlenaNotRun() {
        val base = Capacidades.registro(null)
        val nueva = Capacidad("http.fetch", TestStatus.PASS, Capacidades.ALCANCE_DISPOSITIVO, "smoke 1")
        val r = Capacidades.actualizar(base, nueva)
        val hf = r.first { it.nombre == "http.fetch" }
        val hp = r.first { it.nombre == "html.parser" }
        assertEquals(TestStatus.PASS, hf.estado)
        assertEquals(Capacidades.ALCANCE_DISPOSITIVO, hf.alcance)
        assertEquals("smoke 1", hf.evidencia)
        assertEquals(TestStatus.NOT_RUN, hp.estado)
    }

    @Test
    fun uiNoCambiaResultadoMedido() {
        val medido = Capacidades.registro(registroEmbebido())
        val r = Capacidades.actualizar(medido, Capacidad("http.fetch", TestStatus.FAIL, Capacidades.ALCANCE_DISPOSITIVO, "x"))
        val hf = r.first { it.nombre == "http.fetch" }
        assertEquals(TestStatus.PASS, hf.estado)
        assertEquals("ci", hf.alcance)
    }

    @Test
    fun notRunNoSeRellenaConNotRun() {
        val base = Capacidades.registro(null)
        val r = Capacidades.actualizar(base, Capacidad("http.fetch", TestStatus.NOT_RUN, Capacidades.ALCANCE_DISPOSITIVO, "x"))
        assertEquals(base, r)
    }

    @Test
    fun resumenFormatoExacto() {
        val r = Capacidades.resumen(Capacidades.registro(registroEmbebido())).split("\n")
        assertEquals(9, r.size)
        assertEquals("capacidades del build: 6", r[0])
        assertEquals("http.fetch: PASS (ci) - tests unitarios del build abc", r[1])
        assertEquals("marcadores.html: PASS (ci) - tests unitarios del build abc", r[6])
        assertEquals("estados: PASS 6, FAIL 0, NOT_RUN 0, BLOCKED 0, UNSUPPORTED 0", r[7])
        assertEquals(Capacidades.AVISO_SUBSET, r[8])
    }

    @Test
    fun resumenBaseHonestoConAlcancesSeparados() {
        val r = Capacidades.resumen(Capacidades.registro(null))
        assertTrue(r.contains("(ninguno)"))
        assertTrue(r.contains("estados: PASS 0, FAIL 0, NOT_RUN 6, BLOCKED 0, UNSUPPORTED 0"))
        assertTrue(r.contains(Capacidades.AVISO_SUBSET))
        val embebido = Capacidades.resumen(Capacidades.registro(registroEmbebido()))
        assertTrue(embebido.contains("(ci)"))
    }

    @Test
    fun resumenDeterminista() {
        assertEquals(
            Capacidades.resumen(Capacidades.registro(registroEmbebido())),
            Capacidades.resumen(Capacidades.registro(registroEmbebido()))
        )
    }
}
