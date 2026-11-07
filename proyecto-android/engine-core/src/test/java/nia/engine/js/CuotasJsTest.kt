package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests de cuotas visibles (T100 v1, RES-0104): CuotasJs resuelve los
 * topes del usuario SIEMPRE dentro de los rangos honestos (0 o
 * negativo caen al defecto del motor, bajo el minimo cae al minimo y
 * sobre el tope duro cae al tope duro: la ejecucion sin limite NO
 * existe), el reintento temporal dobla la cuota por intento
 * acumulando hasta el tope duro JAMAS superado y el mapa es honesto
 * con lo que cada tope cuenta y lo que NO mide (alloc != bytes de
 * heap reales; sin cuota de tiempo en v1). Espejo del gemelo TS 12/12
 * en verde ANTES del push.
 */
class CuotasJsTest {

    @Test
    fun resolverDefectoEsElDelMotor() {
        val l = CuotasJs.resolver(100_000_000L, 10_000_000L)
        assertEquals(100_000_000L, l.maxSteps)
        assertEquals(10_000_000L, l.maxAllocs)
    }

    @Test
    fun resolverCeroYNegativoCaenAlDefecto() {
        val l = CuotasJs.resolver(0L, -5L)
        assertEquals(CuotasJs.DEFAULT_PASOS, l.maxSteps)
        assertEquals(CuotasJs.DEFAULT_ALLOCS, l.maxAllocs)
    }

    @Test
    fun resolverBajoElMinimoCaeAlMinimo() {
        val l = CuotasJs.resolver(500L, 50_000L)
        assertEquals(CuotasJs.MIN_PASOS, l.maxSteps)
        assertEquals(CuotasJs.MIN_ALLOCS, l.maxAllocs)
    }

    @Test
    fun resolverSobreElTopeDuroCaeAlTopeDuro() {
        val l = CuotasJs.resolver(5_000_000_000L, 999_999_999L)
        assertEquals(CuotasJs.HARD_CAP_PASOS, l.maxSteps)
        assertEquals(CuotasJs.HARD_CAP_ALLOCS, l.maxAllocs)
    }

    @Test
    fun resolverJamasesDevuelveSinLimite() {
        for (v in listOf(0L, -1L, 999_999L, 1_000_000_001L, 999_999_999_999L)) {
            val l = CuotasJs.resolver(v, v)
            assertTrue(l.maxSteps > 0L && l.maxSteps <= CuotasJs.HARD_CAP_PASOS)
            assertTrue(l.maxAllocs > 0L && l.maxAllocs <= CuotasJs.HARD_CAP_ALLOCS)
        }
    }

    @Test
    fun temporalDuplicaPorReintento() {
        val l = CuotasJs.temporal(1, JsLimits(1_000_000L, 100_000L))
        assertEquals(2_000_000L, l.maxSteps)
        assertEquals(200_000L, l.maxAllocs)
    }

    @Test
    fun temporalAcumulaReintentos() {
        val l = CuotasJs.temporal(2, JsLimits(1_000_000L, 100_000L))
        assertEquals(4_000_000L, l.maxSteps)
        assertEquals(400_000L, l.maxAllocs)
    }

    @Test
    fun temporalNuncaPasaElTopeDuro() {
        val l = CuotasJs.temporal(30, JsLimits(CuotasJs.DEFAULT_PASOS, CuotasJs.DEFAULT_ALLOCS))
        assertEquals(CuotasJs.HARD_CAP_PASOS, l.maxSteps)
        assertEquals(CuotasJs.HARD_CAP_ALLOCS, l.maxAllocs)
    }

    @Test
    fun temporalCeroDevuelveLaBase() {
        val l = CuotasJs.temporal(0, JsLimits(5_000_000L, 500_000L))
        assertEquals(5_000_000L, l.maxSteps)
        assertEquals(500_000L, l.maxAllocs)
    }

    @Test
    fun mapaTieneCincoLineasEnOrden() {
        val m = CuotasJs.mapa(JsLimits(1_000_000L, 100_000L))
        assertEquals(5, m.size)
        assertTrue(m[0].startsWith("pasos: 1000000 "))
        assertTrue(m[1].startsWith("allocs: 100000 "))
        assertTrue(m[2].startsWith("tiempo: "))
        assertTrue(m[3].startsWith("reintento: "))
        assertEquals(CuotasJs.AVISO_SIN_LIMITE, m[4])
    }

    @Test
    fun mapaEsHonestoSobreLoQueNoMide() {
        val m = CuotasJs.mapa(JsLimits(1L, 2L))
        assertTrue(m[0].contains("NO tiempo de CPU"))
        assertTrue(m[1].contains("NO bytes de heap reales"))
        assertTrue(m[2].contains("sin cuota de tiempo"))
    }

    @Test
    fun mapaYResolverSonDeterministas() {
        val a1 = CuotasJs.resolver(0L, 999_999_999_999L)
        val a2 = CuotasJs.resolver(0L, 999_999_999_999L)
        assertEquals(a1.maxSteps, a2.maxSteps)
        assertEquals(a1.maxAllocs, a2.maxAllocs)
        val b1 = CuotasJs.temporal(3, a1)
        val b2 = CuotasJs.temporal(3, a2)
        assertEquals(b1.maxSteps, b2.maxSteps)
        assertEquals(b1.maxAllocs, b2.maxAllocs)
        assertEquals(CuotasJs.mapa(b1), CuotasJs.mapa(b2))
    }
}
