package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.WallClock
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas identicas,
 * huella 15.15.391ac5be): DiagModulos de C10 v1 (R0258, RES-0115) contra
 * el InspectorSession REAL del host (T008): cuotas por modulo con
 * descarte distinguible, resumen primero en los informes de listas,
 * la DISTINCION cuota/fallo explicita y el SecretRedactor del HOST
 * mandando en la ingesta (el crudo NO se persiste).
 */
class DiagModulosTest {

    private class FakeClock : WallClock {
        var t = 1000L
        override fun nowMillis(): Long {
            val r = t
            t += 1
            return r
        }
    }

    private fun ctx(generation: Long = 1): EventContext =
        EventContext("s1", "t1", "f1", "d-" + generation, generation)

    private fun informe(ok: Int = 0, cuota: Int = 0, fallo: Int = 0): ListasInforme {
        val resultados = ArrayList<ListaResultado>()
        repeat(ok) { n ->
            resultados.add(ListaResultado.Ok("ok-" + n, "https://host.test/ok" + n + ".txt", 10))
        }
        repeat(cuota) { n ->
            resultados.add(ListaResultado.Cuota("cuota-" + n, "https://host.test/cuota" + n + ".txt", "sobre el tope"))
        }
        repeat(fallo) { n ->
            resultados.add(ListaResultado.Fallo("fallo-" + n, "https://host.test/fallo" + n + ".txt", "estado 500"))
        }
        return ListasInforme(resultados)
    }

    @Test
    fun eventoSeEscribeConKindYNivelDelCanal() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        assertTrue(d.evento("demo.uno", EventLevel.WARNING, "cuidado", ctx()))
        val e = s.all()[0]
        assertEquals("modulo", e.kind)
        assertEquals(EventLevel.WARNING, e.level)
        assertEquals("demo.uno", e.payload["modulo"])
        assertEquals("cuidado", e.payload["texto"])
        assertEquals("t1", e.context.tabId)
    }

    @Test
    fun secuenciaMonotonaCompartidaEntreModulos() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.evento("demo.a", EventLevel.INFO, "a", ctx())
        d.evento("demo.b", EventLevel.INFO, "b", ctx())
        d.evento("demo.a", EventLevel.INFO, "a2", ctx())
        assertEquals(listOf(1L, 2L, 3L), s.all().map { it.seq })
    }

    @Test
    fun cuotaPorModuloDescartaYCuenta() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s, 3)
        assertTrue(d.evento("demo.uno", EventLevel.INFO, "a", ctx()))
        assertTrue(d.evento("demo.uno", EventLevel.INFO, "b", ctx()))
        assertTrue(d.evento("demo.uno", EventLevel.INFO, "c", ctx()))
        assertFalse("sobre la cuota se descarta", d.evento("demo.uno", EventLevel.INFO, "d", ctx()))
        assertEquals(3, d.escritos("demo.uno"))
        assertEquals(1, d.descartados("demo.uno"))
        assertEquals(3, s.all().size)
    }

    @Test
    fun cuotaIndependientePorModulo() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s, 1)
        assertTrue(d.evento("demo.a", EventLevel.INFO, "a", ctx()))
        assertFalse(d.evento("demo.a", EventLevel.INFO, "a2", ctx()))
        assertTrue("otro modulo conserva su cuota", d.evento("demo.b", EventLevel.INFO, "b", ctx()))
    }

    @Test
    fun idInvalidoSeRechazaSinEventos() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        assertFalse(d.evento("demo cinco", EventLevel.INFO, "x", ctx()))
        assertFalse(d.evento("", EventLevel.INFO, "x", ctx()))
        assertEquals(0, s.all().size)
        assertEquals(0, d.escritos("demo cinco"))
        assertEquals(0, d.descartados("demo cinco"))
    }

    @Test
    fun textoLargoSeTrunca() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.evento("demo.uno", EventLevel.INFO, "x".repeat(300), ctx())
        assertEquals(DiagModulos.MAX_LARGO_TEXTO, s.all()[0].payload["texto"]!!.length)
    }

    @Test
    fun elHostRedactaBearerEnLaIngesta() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.evento("demo.uno", EventLevel.ERROR, "Authorization: Bearer abc123", ctx())
        val texto = s.all()[0].payload["texto"]!!
        assertTrue(texto.contains("Bearer [redacted]"))
        assertFalse("el crudo no se persiste", texto.contains("abc123"))
    }

    @Test
    fun urlConUserinfoRedactadaPorElHost() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        val conCredenciales = ListasInforme(listOf(ListaResultado.Ok("n1", "https://u:p@host.test/l.txt", 5)))
        d.informeListas("demo.uno", conCredenciales, ctx())
        assertEquals("https://[redacted]@host.test/l.txt", s.all()[1].payload["url"])
    }

    @Test
    fun informeListasDistingueCuotaDeFallo() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.informeListas("demo.uno", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        val eventos = s.all()
        assertEquals(4, eventos.size)
        assertEquals("modulo.listas", eventos[0].kind)
        assertTrue(eventos[0].payload["resumen"]!!.contains("1 ok, 1 por cuota, 1 con fallo"))
        val porResultado = eventos.subList(1, 4).associate { it.payload["resultado"] to it }
        assertEquals(EventLevel.INFO, porResultado["ok"]!!.level)
        assertEquals(EventLevel.WARNING, porResultado["cuota"]!!.level)
        assertEquals(EventLevel.ERROR, porResultado["fallo"]!!.level)
        assertTrue(porResultado["cuota"]!!.payload["motivo"]!!.contains("sobre el tope"))
        assertTrue(porResultado["fallo"]!!.payload["motivo"]!!.contains("estado 500"))
    }

    @Test
    fun informeListasVacioNoEscribe() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.informeListas("demo.uno", ListasInforme(emptyList()), ctx())
        assertEquals(0, s.all().size)
        assertEquals(0, d.escritos("demo.uno"))
    }

    @Test
    fun informeListasResumenPrimeroBajoCuota() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s, 1)
        d.informeListas("demo.uno", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        assertEquals("la sintesis siempre viaja", 1, s.all().size)
        assertTrue(s.all()[0].payload.containsKey("resumen"))
        assertEquals(1, d.escritos("demo.uno"))
        assertEquals(3, d.descartados("demo.uno"))
    }

    @Test
    fun contadoresDeModuloInexistenteEnCero() {
        val d = DiagModulos(InspectorSession(FakeClock()))
        assertEquals(0, d.escritos("nadie"))
        assertEquals(0, d.descartados("nadie"))
    }

    @Test
    fun exportSanitizadoDelCanal() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s)
        d.informeListas("demo.uno", informe(cuota = 1), ctx())
        d.evento("demo.uno", EventLevel.ERROR, "token=hunter2", ctx())
        val export = s.export()
        assertTrue(export.contains("modulo.listas"))
        assertTrue(export.contains("resultado=cuota"))
        assertFalse(export.contains("hunter2"))
    }

    @Test
    fun informesRepetidosConsumenLaCuota() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s, 4)
        d.informeListas("demo.uno", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        assertEquals(4, s.all().size)
        d.informeListas("demo.uno", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        assertEquals("el segundo informe cae entero por cuota", 4, s.all().size)
        assertEquals(4, d.descartados("demo.uno"))
    }

    @Test
    fun cuotasSeparadasPorModuloEnInformes() {
        val s = InspectorSession(FakeClock())
        val d = DiagModulos(s, 4)
        d.informeListas("demo.a", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        d.informeListas("demo.a", informe(fallo = 1), ctx())
        assertEquals("a lleno su cuota", 2, d.descartados("demo.a"))
        d.informeListas("demo.b", informe(ok = 1, cuota = 1, fallo = 1), ctx())
        assertEquals("b conserva su cuota", 4, d.escritos("demo.b"))
        assertEquals(8, s.all().size)
    }
}
