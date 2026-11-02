package nia.engine.js

import nia.engine.dom.DomDocument
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests de cuotas/heap/interrupcion de JS (T021 v1): toda evaluacion corre
 * con una sesion de cuotas (safepoint por sentencia/expresion contra el
 * tope de pasos de JsLimits; newObj/newArr cuentan una asignacion contra
 * el tope de heap), el abort NO es JsThrown (el try/catch de JavaScript no
 * puede capturarlo y siempre llega al punto de entrada, que lo reporta
 * como evaluacion interrumpida), los contadores se reinician por
 * evaluacion y JsInterpreter.interrupt() pide el corte desde otro hilo
 * (flag volatile; el hilo que ejecuta JS aborta en el siguiente safepoint
 * con INTERRUPTED).
 */
class JsQuotasTest {

    @Test
    fun bucleWhileInfinitoCortaPorPasos() {
        val r = JsInterpreter.evaluate("while (true) { }", JsLimits(maxSteps = 500L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
        assertEquals("interrupted step-quota", JsInterpreter.dumpResult(r))
    }

    @Test
    fun bucleForVacioCortaPorPasos() {
        val r = JsInterpreter.evaluate("for (;;) { }", JsLimits(maxSteps = 500L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
    }

    @Test
    fun doWhileInfinitoCortaPorPasos() {
        val r = JsInterpreter.evaluate("do { } while (true);", JsLimits(maxSteps = 500L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
    }

    @Test
    fun elTryCatchDeJsNoCapturaElAbort() {
        val r = JsInterpreter.evaluate("try { while (true) { } } catch (e) { 'no' }", JsLimits(maxSteps = 500L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
        assertEquals("interrupted step-quota", JsInterpreter.dumpResult(r))
    }

    @Test
    fun programaNormalTerminaDentroDeLaCuota() {
        val r = JsInterpreter.evaluate("var s = 0; for (var i = 0; i < 100; i++) { s += i; } s;", JsLimits(maxSteps = 500_000L))
        assertFalse(r.interrupted)
        assertEquals("4950", JsInterpreter.dumpResult(r))
    }

    @Test
    fun heapDeArraysCortaPorAsignaciones() {
        val r = JsInterpreter.evaluate(
            "var a = []; while (true) { a.push({}); }",
            JsLimits(maxSteps = 100_000_000L, maxAllocs = 500L)
        )
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.HEAP_QUOTA, r.abortReason)
        assertEquals("interrupted heap-quota", JsInterpreter.dumpResult(r))
    }

    @Test
    fun objetosSueltosCuentanParaElHeap() {
        val r = JsInterpreter.evaluate("var x = 0; while (true) { x = { a: 1 }; }", JsLimits(maxAllocs = 300L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.HEAP_QUOTA, r.abortReason)
    }

    @Test
    fun elDrenajeDeMicrotasksTambienSeCorta() {
        val r = JsInterpreter.evaluate(
            "Promise.resolve().then(function () { while (true) { } }); 'x';",
            JsLimits(maxSteps = 800L)
        )
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
    }

    @Test
    fun asyncInfinitaAbortaLaEvaluacion() {
        val r = JsInterpreter.evaluate("(async function () { while (true) { } })();", JsLimits(maxSteps = 600L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
    }

    @Test
    fun generatorInfinitoAbortaAlReanudar() {
        val r = JsInterpreter.evaluate(
            "function* g() { while (true) { yield 1; } } var it = g(); while (true) { it.next(); }",
            JsLimits(maxSteps = 400L)
        )
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
    }

    @Test
    fun interruptDesdeOtroHiloCortaElBucle() {
        val result = AtomicReference<JsEvalResult?>(null)
        val worker = Thread {
            result.set(JsInterpreter.evaluate("while (true) { }"))
        }
        worker.start()
        var stopped = false
        for (i in 1..50) {
            Thread.sleep(20L)
            JsInterpreter.interrupt()
            worker.join(300L)
            if (!worker.isAlive) {
                stopped = true
                break
            }
        }
        assertTrue(stopped)
        val res = assertNotNull(result.get())
        assertTrue(res.interrupted)
        assertEquals(JsAbortReason.INTERRUPTED, res.abortReason)
        assertEquals("interrupted", JsInterpreter.dumpResult(res))
    }

    @Test
    fun laCuotaSeReiniciaEntreEvaluaciones() {
        val a = JsInterpreter.evaluate("while (true) { }", JsLimits(maxSteps = 300L))
        assertTrue(a.interrupted)
        val b = JsInterpreter.evaluate("1 + 2;")
        assertFalse(b.interrupted)
        assertEquals("3", JsInterpreter.dumpResult(b))
    }

    @Test
    fun evaluateSinLimitesUsaDefaultsAmplios() {
        val r = JsInterpreter.evaluate("var s = 0; for (var i = 0; i < 1000; i++) { s += i; } s;")
        assertFalse(r.interrupted)
        assertEquals("499500", JsInterpreter.dumpResult(r))
    }

    @Test
    fun interruptSinEvaluacionNoEnvenenaLaSiguiente() {
        JsInterpreter.interrupt()
        val r = JsInterpreter.evaluate("1 + 1;")
        assertFalse(r.interrupted)
        assertEquals("2", JsInterpreter.dumpResult(r))
    }

    @Test
    fun evaluateWithDomConCuotasCortaElBucle() {
        val doc = DomDocument.parse("<html><body><p id=\"a\">x</p></body></html>")
        val r = JsInterpreter.evaluateWithDom("while (true) { }", doc, JsLimits(maxSteps = 500L))
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
        assertEquals("interrupted step-quota", JsInterpreter.dumpResult(r))
    }
}
