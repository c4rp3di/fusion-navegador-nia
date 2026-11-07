package nia.engine.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de versiones (T107 v1, RES-0101): espejo del gemelo TS en verde
 * ANTES del push: los 16 componentes de la aceptacion en orden, estado
 * honesto (lo que no existe se declara, nunca se disfraza), resumen del
 * artefacto con formato exacto y NADA de version inventada.
 */
class VersionesTest {

    @Test
    fun componentesTieneLos16DeLaAceptacionEnOrden() {
        val nombres = Versiones.componentes().map { it.nombre }
        assertEquals(
            "lexer,parser,DOM,CSS,layout,paint,JS,debugger,fetch,cookies,storage,media,torrent,subs,workers,matrices",
            nombres.joinToString(",")
        )
    }

    @Test
    fun losMotoresRealesEstanDisponibles() {
        assertTrue(Versiones.componentes().take(11).all { it.estado == Versiones.DISPONIBLE })
    }

    @Test
    fun losNoExistentesSeDeclaranNoDisponibles() {
        val porNombre = Versiones.componentes().map { it.nombre to it.estado }.toMap()
        for (n in listOf("media", "torrent", "subs", "workers", "matrices")) {
            assertEquals(Versiones.NO_DISPONIBLE, porNombre[n])
        }
    }

    @Test
    fun sinComponentesDuplicados() {
        val nombres = Versiones.componentes().map { it.nombre }
        assertEquals(nombres.size, nombres.toSet().size)
    }

    @Test
    fun estadosSoloDosValoresHonestos() {
        for (c in Versiones.componentes()) {
            assertTrue(c.estado == Versiones.DISPONIBLE || c.estado == Versiones.NO_DISPONIBLE)
        }
    }

    @Test
    fun versionNulaNuncaInventada() {
        for (c in Versiones.componentes()) {
            assertNull(c.version)
        }
    }

    @Test
    fun resumenConTodoLoDelArtefacto() {
        val r = Versiones.resumen("1.2", "abc1234", "0.2.0")
        assertTrue(r.contains("navegador 1.2"))
        assertTrue(r.contains("build: abc1234"))
        assertTrue(r.contains("contracts: 0.2.0"))
        assertTrue(r.contains("revision componentes: R0215"))
    }

    @Test
    fun resumenSinShaDeclaraBuildLocalHonesto() {
        val r = Versiones.resumen("1.2", null, "0.2.0")
        assertTrue(r.contains("build: no disponible (build local sin build-info)"))
    }

    @Test
    fun resumenShaVacioIgualQueNulo() {
        assertEquals(Versiones.resumen("1.2", null, "0.2.0"), Versiones.resumen("1.2", "", "0.2.0"))
    }

    @Test
    fun resumenContieneCadaComponenteConSuEstado() {
        val r = Versiones.resumen("1.2", "abc1234", "0.2.0")
        for (c in Versiones.componentes()) {
            assertTrue(r.contains(c.nombre + ": " + c.estado))
        }
    }

    @Test
    fun resumenDeterminista() {
        assertEquals(Versiones.resumen("1.2", "abc1234", "0.2.0"), Versiones.resumen("1.2", "abc1234", "0.2.0"))
    }

    @Test
    fun resumenTerminaEnMatricesSinSaltoFinal() {
        val r = Versiones.resumen("1.2", "abc1234", "0.2.0")
        assertTrue(r.endsWith("matrices: no disponible"))
        assertEquals(20, r.split("\n").size)
    }
}
