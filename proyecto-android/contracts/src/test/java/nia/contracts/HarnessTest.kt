package nia.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarnessTest {

    @Test
    fun suiteSinFallosEsPass() {
        val r = SuiteResult("ids", passed = 10, failed = 0, skipped = 1)
        assertEquals(TestStatus.PASS, r.status)
    }

    @Test
    fun suiteConFallosEsFailAunqueElRestoPase() {
        val r = SuiteResult("errors", passed = 5, failed = 1, skipped = 0)
        assertEquals(TestStatus.FAIL, r.status)
    }

    @Test
    fun suiteSinCasosEjecutadosEsNotRun() {
        val r = SuiteResult("engine", 0, 0, 0)
        assertEquals(TestStatus.NOT_RUN, r.status)
    }

    @Test
    fun suiteBloqueadaEsBlocked() {
        val r = SuiteResult("gpu", passed = 0, failed = 0, skipped = 0, blocked = 2)
        assertEquals(TestStatus.BLOCKED, r.status)
    }

    @Test
    fun contadoresNegativosRechazados() {
        assertFailsWith<IllegalArgumentException> { SuiteResult("x", passed = -1, failed = 0, skipped = 0) }
        assertFailsWith<IllegalArgumentException> { SuiteResult("x", 0, 0, 0, blocked = -1) }
    }

    @Test
    fun resumenSeparaCadaEstado() {
        val r = SuiteResult("contracts", 3, 1, 2, blocked = 1, unsupported = 1)
        val s = r.summary()
        assertTrue(s.startsWith("contracts:"))
        assertTrue(s.contains("PASS 3"))
        assertTrue(s.contains("FAIL 1"))
        assertTrue(s.contains("SKIPPED 2"))
        assertTrue(s.contains("BLOCKED 1"))
        assertTrue(s.contains("UNSUPPORTED 1"))
    }

    @Test
    fun reporteGlobalNoInventaPass() {
        HarnessReport.clear()
        HarnessReport.add(SuiteResult("a", 2, 0, 0))
        HarnessReport.add(SuiteResult("b", 0, 0, 0))
        assertEquals(TestStatus.NOT_RUN, HarnessReport.overall())
        assertTrue(HarnessReport.report().contains("NOT_RUN"))
        HarnessReport.clear()
    }

    @Test
    fun reporteVacioEsNotRun() {
        HarnessReport.clear()
        assertEquals(TestStatus.NOT_RUN, HarnessReport.overall())
        assertTrue(HarnessReport.report().contains("suites 0"))
        HarnessReport.clear()
    }

    @Test
    fun capacidadesDeclaradasNuncaMientenSobreElMotor() {
        val caps = Capabilities.baseline()
        assertTrue(caps.any { it.id == "contracts.ids" && it.status == TestStatus.PASS })
        assertTrue(caps.filter { it.id.startsWith("engine.") }.all { it.status == TestStatus.NOT_RUN })
        assertFailsWith<IllegalArgumentException> { Capability(" ", TestStatus.PASS) }
    }
}
