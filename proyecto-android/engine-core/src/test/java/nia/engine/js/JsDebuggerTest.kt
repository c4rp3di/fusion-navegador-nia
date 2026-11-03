package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nia.contracts.WallClock
import nia.engine.dom.DomDocument
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession

/**
 * Tests del debugger JS (T028 v1, RES-0034): breakpoints por linea,
 * step budget, snapshot con scopes y profundidad, pausa como abort NO
 * capturable por el try/catch de JavaScript (patron T021), evento del
 * inspector con redaccion en la ingesta, cuotas intactas con el hook
 * activo y desmontaje del hook al terminar. La API es PROPIA del motor:
 * las expectativas vienen del diseno de la ficha RES-0034 (pausa
 * inmediata sin bloquear, reanudacion fuera de v1).
 */
class JsDebuggerTest {

    private class FakeClock : WallClock {
        var now = 1000L
        override fun nowMillis(): Long = now
    }

    private fun snap(d: JsDebugger): DebugSnapshot {
        val s = d.lastSnapshot
        if (s == null) throw AssertionError("sin snapshot")
        return s
    }

    @Test
    fun breakpointPorLineaPausaConSnapshot() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\nvar c = a + b;").breakOn(3)
        val r = d.evaluate()
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.DEBUG_PAUSE, r.abortReason)
        assertEquals("interrupted debugger", JsInterpreter.dumpResult(r))
        val s = snap(d)
        assertEquals(3, s.line)
        assertEquals("breakpoint", s.reason)
        assertNull(JsInterpreter.debugHook)
    }

    @Test
    fun snapshotMuestraBindingsDelScope() {
        val d = JsDebugger("var x = 5;\nvar y = 6;").breakOn(2)
        d.evaluate()
        val s = snap(d)
        assertEquals(1, s.depth)
        assertEquals(1, s.scopes.size)
        assertTrue(s.scopes[0].contains("x=5"))
    }

    @Test
    fun scopesEncadenadosYClausura() {
        val d = JsDebugger(
            "var x = 1;\nfunction f() {\nvar y = 2;\nreturn x + y;\n}\nvar r = f();"
        ).breakOn(4)
        d.evaluate()
        val s = snap(d)
        assertEquals(4, s.line)
        assertEquals(2, s.depth)
        assertTrue(s.scopes[0].contains("y=2"))
        assertFalse(s.scopes[0].contains("x=1"))
        assertTrue(s.scopes[1].contains("x=1"))
    }

    @Test
    fun stepBudgetPausaTrasN() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\nvar c = 3;\nvar e = 4;").stepBudget(2)
        d.evaluate()
        val s = snap(d)
        assertEquals(3, s.line)
        assertEquals("step", s.reason)
    }

    @Test
    fun sinBreakpointNiPresupuestoEvaluaNormal() {
        val d = JsDebugger("var a = 1;\na + 1")
        val r = d.evaluate()
        assertFalse(r.interrupted)
        assertEquals("2", JsInterpreter.dumpResult(r))
        assertNull(d.lastSnapshot)
        assertNull(JsInterpreter.debugHook)
    }

    @Test
    fun profundidadConFuncionesAnidadas() {
        val d = JsDebugger(
            "function f() {\nfunction g() {\nreturn 7;\n}\nreturn g();\n}\nvar r = f();"
        ).breakOn(3)
        d.evaluate()
        val s = snap(d)
        assertEquals(3, s.line)
        assertEquals(3, s.depth)
        assertEquals(3, s.scopes.size)
        assertTrue(s.scopes[0].contains("g=(fun g)"))
        assertFalse(s.scopes[0].contains("r=undefined"))
        assertTrue(s.scopes[2].contains("r=undefined"))
    }

    @Test
    fun tryCatchDeJsNoCapturaLaPausa() {
        val d = JsDebugger(
            "var o = 'fuera';\ntry {\nvar x = 1;\n} catch (e) {\nvar o = 'capturado';\n}\nvar z = 9;"
        ).breakOn(3)
        val r = d.evaluate()
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.DEBUG_PAUSE, r.abortReason)
        assertEquals(3, snap(d).line)
    }

    @Test
    fun pausaEnAsyncNoEnvenenaLaSiguienteEvaluacion() {
        val d = JsDebugger(
            "async function f() {\nvar x = await 1;\nreturn x + 1;\n}\nf();"
        ).breakOn(3)
        val r = d.evaluate()
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.DEBUG_PAUSE, r.abortReason)
        assertEquals(3, snap(d).line)
        val r2 = JsDebugger("2 + 3").evaluate()
        assertEquals("5", JsInterpreter.dumpResult(r2))
        assertNull(JsInterpreter.debugHook)
    }

    @Test
    fun eventoDeInspectorRedactadoEnLaIngesta() {
        val session = InspectorSession(FakeClock())
        val ctx = EventContext("s1", "t1", "f1", "d1", 1L)
        val d = JsDebugger("var token = 'abc';\nvar q = 2;", session, ctx).breakOn(2)
        d.evaluate()
        val evs = session.all()
        assertEquals(1, evs.size)
        val ev = evs[0]
        assertEquals("debugger", ev.kind)
        assertEquals(EventLevel.DEBUG, ev.level)
        assertEquals("s1", ev.context.sessionId)
        assertEquals("2", ev.payload["line"])
        assertEquals("breakpoint", ev.payload["reason"])
        assertEquals("1", ev.payload["depth"])
        val scopes = ev.payload["scopes"] ?: ""
        assertTrue(scopes.contains("token=[redacted]"))
        assertFalse(scopes.contains("abc"))
    }

    @Test
    fun evaluateWithDomConBreakpoint() {
        val doc = DomDocument.parse("<body><p id='x'>h</p></body>")
        val d = JsDebugger("var a = 1;\nvar p = document.getElementById('x');").breakOn(2)
        val r = d.evaluateWithDom(doc)
        assertTrue(r.interrupted)
        assertEquals(2, snap(d).line)
    }

    @Test
    fun multiplesBreakpointsGanaElPrimeroEjecutado() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\nvar c = 3;").breakOn(3).breakOn(2)
        d.evaluate()
        assertEquals(2, snap(d).line)
    }

    @Test
    fun lineaVaciaNoPausaYCompletaNormal() {
        val d = JsDebugger("var a = 1;\n\nvar b = 2;").breakOn(2)
        val r = d.evaluate()
        assertFalse(r.interrupted)
        assertNull(d.lastSnapshot)
        assertEquals("undefined", JsInterpreter.dumpResult(r))
    }

    @Test
    fun breakpointEnBuclePausaEnLaPrimeraIteracion() {
        val d = JsDebugger(
            "var t = 0;\nfor (var i = 0; i < 3; i++) {\nt = t + i;\n}"
        ).breakOn(3)
        d.evaluate()
        val s = snap(d)
        assertEquals(3, s.line)
        assertTrue(s.scopes[0].contains("t=0"))
        assertTrue(s.scopes[0].contains("i=0"))
    }

    @Test
    fun cuotasFuncionanConElHookActivo() {
        val d = JsDebugger("var i = 0;\nwhile (true) { i = i + 1; }")
        val r = d.evaluate(JsLimits(maxSteps = 50L))
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
        assertNull(d.lastSnapshot)
    }

    @Test
    fun dumpCanonicoDeValoresEnElScope() {
        val d = JsDebugger(
            "var n = 1.5;\nvar s = 'hola';\nvar b = true;\nvar u;\nvar z = n;"
        ).breakOn(5)
        d.evaluate()
        val sc = snap(d).scopes[0]
        assertTrue(sc.contains("n=1.5"))
        assertTrue(sc.contains("hola"))
        assertTrue(sc.contains("b=true"))
        assertTrue(sc.contains("u=undefined"))
        assertTrue(sc.contains("z=undefined"))
    }

    @Test
    fun stepBudgetMayorQueElProgramaNoPausa() {
        val d = JsDebugger("var a = 1;\nvar b = 2;").stepBudget(10)
        val r = d.evaluate()
        assertFalse(r.interrupted)
        assertEquals("undefined", JsInterpreter.dumpResult(r))
        assertNull(d.lastSnapshot)
    }
}
