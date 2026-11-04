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
 * T028 v4 (R0086, RES-0048): call stack real en la pausa (frames
 * "nombre linea" del mas interno al global) en ambas APIs.
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
    }

    // ---- T028 v2 (R0064, RES-0037): sesion con reanudacion real ----
    // Cada test libera SIEMPRE la sesion en finally y ESPERA su
    // terminacion: el hilo de debug termina de forma asincrona tras
    // stop() y su cleanup (desmontaje del hook global, JsQuotas.end,
    // cleanupRunners) pisaria el estado del test siguiente.

    private fun esperaPausa(s: DebugSession, after: Long, ms: Long = 5000L): DebugSnapshot {
        val p = s.awaitPause(ms, after)
        if (p == null) throw AssertionError("timeout esperando pausa " + (after + 1L))
        return p
    }

    private fun done(s: DebugSession, ms: Long = 5000L): JsEvalResult {
        if (!s.awaitDone(ms)) throw AssertionError("timeout esperando fin de sesion")
        val r = s.result
        if (r == null) throw AssertionError("sin resultado")
        return r
    }

    /** Libera la sesion y ESPERA su terminacion: el hilo de debug termina
     * de forma asincrona tras stop() y su cleanup (desmontaje del hook
     * global, JsQuotas.end, cleanupRunners) pisaria el estado del test
     * siguiente mientras ya evalua. */
    private fun liberar(s: DebugSession, ms: Long = 5000L) {
        s.stop()
        if (!s.awaitDone(ms)) throw AssertionError("la sesion no termino tras stop")
    }

    @Test
    fun resumeContinuaDesdeElPuntoExacto() {
        val s = DebugSession("var c = 1;\nvar d = c + 1;\nd").breakOn(2).start()
        var n = 0L
        try {
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals(2, p.line)
            assertTrue(p.scopes[0].contains("c=1"))
            assertEquals("40", s.inspect("c = 40"))
            s.resume()
            val r = done(s)
            assertFalse(r.interrupted)
            assertEquals("41", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun pausaPublicaSnapshotYEstado() {
        val s = DebugSession("var a = 5;\nvar b = a + 1;").breakOn(2).start()
        var n = 0L
        try {
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals(DebugState.PAUSED, s.state)
            assertEquals(2, p.line)
            assertEquals("breakpoint", p.reason)
            s.stop()
            done(s)
            assertEquals(DebugState.FINISHED, s.state)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun stopInterrumpeLaEvaluacion() {
        val s = DebugSession("var a = 1;\nvar b = 2;\nvar c = 3;").breakOn(2).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            s.stop()
            val r = done(s)
            assertTrue(r.interrupted)
            assertEquals(JsAbortReason.INTERRUPTED, r.abortReason)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun stepInAvanzaSentenciaASentencia() {
        val s = DebugSession("var a = 1;\nvar b = 2;\nvar c = 3;\nc").breakOn(1).start()
        var n = 0L
        try {
            assertEquals(1, esperaPausa(s, n).line)
            n = n + 1L
            s.stepIn()
            assertEquals(2, esperaPausa(s, n).line)
            n = n + 1L
            s.stepIn()
            assertEquals(3, esperaPausa(s, n).line)
            n = n + 1L
        } finally {
            liberar(s)
        }
    }

    @Test
    fun stepOverNoEntraEnLaFuncion() {
        val src = "function f() {\nvar y = 2;\nreturn y;\n}\nvar r = f();\nr"
        val s = DebugSession(src).breakOn(5).start()
        var n = 0L
        try {
            assertEquals(5, esperaPausa(s, n).line)
            n = n + 1L
            s.stepOver()
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals(6, p.line)
            assertEquals(1, p.depth)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun stepInSiEntraEnLaFuncion() {
        val src = "function f() {\nvar y = 7;\nreturn y;\n}\nvar r = f();\nr"
        val s = DebugSession(src).breakOn(5).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            s.stepIn()
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals(2, p.line)
            assertEquals(2, p.depth)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun stepOutVuelveAlLlamador() {
        val src = "function f() {\nvar y = 7;\nreturn y;\n}\nvar r = f();\nr"
        val s = DebugSession(src).breakOn(2).start()
        var n = 0L
        try {
            assertEquals(2, esperaPausa(s, n).depth)
            n = n + 1L
            s.stepOut()
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals(6, p.line)
            assertEquals(1, p.depth)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun pauseFuerzaPausaEnElSiguienteSafepoint() {
        val s = DebugSession("var a = 1;\nvar b = 2;\nb")
        s.pause()
        s.start()
        try {
            val p = esperaPausa(s, 0L)
            assertEquals(1, p.line)
            assertEquals("pause", p.reason)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun breakpointEnBuclePausaEnCadaIteracion() {
        val src = "var t = 0;\nfor (var i = 0; i < 3; i++) {\nt = t + i;\n}\nt"
        val s = DebugSession(src).breakOn(3).start()
        var n = 0L
        try {
            assertTrue(esperaPausa(s, n).scopes[0].contains("t=0"))
            n = n + 1L
            s.resume()
            assertTrue(esperaPausa(s, n).scopes[0].contains("t=0"))
            n = n + 1L
            s.resume()
            assertTrue(esperaPausa(s, n).scopes[0].contains("t=1"))
            n = n + 1L
            s.resume()
            val r = done(s)
            assertEquals("3", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun inspectEvaluaEnElEntornoPausado() {
        val s = DebugSession("var n = 40;\nvar m = n + 2;").breakOn(2).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            assertEquals("42", s.inspect("n + 2"))
            assertEquals("\"hola\"", s.inspect("'hola'"))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun inspectConThrowDevuelveUncaught() {
        val s = DebugSession("var a = 1;\nvar b = 2;").breakOn(2).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            val out = s.inspect("(function () { throw 'boom'; })()")
            assertTrue(out != null && out.startsWith("uncaught"))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun inspectFueraDePausaDevuelveNull() {
        val s = DebugSession("var a = 1;").start()
        try {
            done(s)
            assertNull(s.inspect("a"))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun asyncConBreakpointNoCuelgaLaSesion() {
        val src = "async function f() {\nvar x = await 1;\nreturn x + 1;\n}\nf();\nvar z = 9;"
        val s = DebugSession(src).breakOn(3).start()
        try {
            var guard = 0
            while (s.state != DebugState.FINISHED && guard < 200) {
                if (s.state == DebugState.PAUSED) s.resume()
                Thread.sleep(10)
                guard = guard + 1
            }
            assertEquals(DebugState.FINISHED, s.state)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun comandosTrasFinalSonInofensivos() {
        val s = DebugSession("var a = 1;\nvar b = 2;").breakOn(2).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            s.resume()
            done(s)
            s.resume()
            s.stepIn()
            s.stepOver()
            s.stepOut()
            s.stop()
            assertEquals(DebugState.FINISHED, s.state)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun evaluateWithDomEnLaSesion() {
        val doc = DomDocument.parse("<body><p id='x'>h</p></body>")
        val s = DebugSession("var a = 1;\nvar p = document.getElementById('x');\na").breakOn(2).start(doc)
        var n = 0L
        try {
            assertEquals(2, esperaPausa(s, n).line)
            n = n + 1L
            s.resume()
            val r = done(s)
            assertEquals("1", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun eventoDeInspectorEnLaSesion() {
        val session = InspectorSession(FakeClock())
        val ctx = EventContext("s1", "t1", "f1", "d1", 1L)
        val s = DebugSession("var token = 'abc';\nvar q = 2;", session, ctx).breakOn(2).start()
        var n = 0L
        try {
            esperaPausa(s, n)
            n = n + 1L
            s.stop()
            done(s)
            val evs = session.all()
            assertEquals(1, evs.size)
            val ev = evs[0]
            assertEquals("debugger", ev.kind)
            assertEquals(EventLevel.DEBUG, ev.level)
            assertEquals("2", ev.payload["line"])
            assertEquals("breakpoint", ev.payload["reason"])
            val scopes = ev.payload["scopes"] ?: ""
            assertTrue(scopes.contains("token=[redacted]"))
            assertFalse(scopes.contains("abc"))
        } finally {
            liberar(s)
        }
    }

    // ---- T028 v3 (R0070, RES-0040): breakpoints condicionales + watchpoints ----

    @Test
    fun breakpointCondicionalFalsoNoPausa() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\na + b").breakOn(2, "a > 5")
        val r = d.evaluate()
        assertFalse(r.interrupted)
        assertEquals("3", JsInterpreter.dumpResult(r))
        assertNull(d.lastSnapshot)
        assertNull(JsInterpreter.debugHook)
    }

    @Test
    fun breakpointCondicionalVerdaderoPausa() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\na + b").breakOn(2, "a > 0")
        val r = d.evaluate()
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.DEBUG_PAUSE, r.abortReason)
        val s = snap(d)
        assertEquals(2, s.line)
        assertEquals("breakpoint", s.reason)
    }

    @Test
    fun breakpointCondicionalConThrowNoPausa() {
        val d = JsDebugger("var a = 1;\nvar b = 2;\na + b").breakOn(2, "inexistente > 0")
        val r = d.evaluate()
        assertFalse(r.interrupted)
        assertEquals("3", JsInterpreter.dumpResult(r))
        assertNull(d.lastSnapshot)
    }

    @Test
    fun watchEnV1PausaCuandoCambia() {
        val d = JsDebugger("var x = 1;\nvar y = 2;\nx + y").watch("x")
        val r = d.evaluate()
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.DEBUG_PAUSE, r.abortReason)
        val s = snap(d)
        assertEquals(2, s.line)
        assertEquals("watchpoint", s.reason)
    }

    @Test
    fun condicionalFalsoEnSesionSigue() {
        val s = DebugSession("var a = 1;\nvar b = 2;\na + b").breakOn(2, "a > 5").start()
        try {
            val r = done(s)
            assertEquals("3", JsInterpreter.dumpResult(r))
            assertEquals(0L, s.pauseCount)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun condicionalConThrowEnSesionNoPausa() {
        val s = DebugSession("var a = 1;\nvar b = 2;\na + b").breakOn(2, "inexistente > 0").start()
        try {
            val r = done(s)
            assertEquals("3", JsInterpreter.dumpResult(r))
            assertEquals(0L, s.pauseCount)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun condicionalVerdaderoEnSesionPausa() {
        val s = DebugSession("var a = 1;\nvar b = 2;\na + b").breakOn(2, "a > 0").start()
        var n = 0L
        try {
            val p = esperaPausa(s, n)
            assertEquals(2, p.line)
            assertEquals("breakpoint", p.reason)
            n = n + 1L
            s.resume()
            val r = done(s)
            assertEquals("3", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun condicionalQueLlamaFuncionesNoAnida() {
        val src = "function f() {\nvar q = 7;\nreturn q;\n}\nvar a = 1;\nvar b = f() + 1;\nb"
        val s = DebugSession(src).breakOn(2).breakOn(5, "f() > 0").start()
        var n = 0L
        try {
            var p = esperaPausa(s, n)
            assertEquals(5, p.line)
            assertEquals("breakpoint", p.reason)
            n = n + 1L
            s.resume()
            p = esperaPausa(s, n)
            assertEquals(2, p.line)
            n = n + 1L
            s.resume()
            val r = done(s)
            assertEquals("8", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun watchPausaCuandoElValorCambiaYResume() {
        val s = DebugSession("var x = 1;\nvar y = 2;\nx = 5;\nvar z = x + y;\nz").watch("x").start()
        var n = 0L
        try {
            var p = esperaPausa(s, n)
            assertEquals(2, p.line)
            assertEquals("watchpoint", p.reason)
            n = n + 1L
            s.resume()
            p = esperaPausa(s, n)
            assertEquals(4, p.line)
            assertEquals("watchpoint", p.reason)
            n = n + 1L
            s.resume()
            val r = done(s)
            assertEquals("7", JsInterpreter.dumpResult(r))
        } finally {
            liberar(s)
        }
    }

    @Test
    fun watchConstanteNoPausa() {
        val s = DebugSession("var a = 1;\nvar b = a + 1;\nb").watch("9").start()
        try {
            val r = done(s)
            assertEquals("2", JsInterpreter.dumpResult(r))
            assertEquals(0L, s.pauseCount)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun watchConThrowNoPausa() {
        val s = DebugSession("var a = 1;\nvar b = 2;\nb").watch("inexistente").start()
        try {
            val r = done(s)
            assertEquals("2", JsInterpreter.dumpResult(r))
            assertEquals(0L, s.pauseCount)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun watchYBreakpointALaVezPriorizaBreakpoint() {
        val s = DebugSession("var x = 1;\nvar y = 2;\ny").watch("y").breakOn(2).start()
        var n = 0L
        try {
            var p = esperaPausa(s, n)
            assertEquals(2, p.line)
            assertEquals("breakpoint", p.reason)
            n = n + 1L
            s.resume()
            p = esperaPausa(s, n)
            assertEquals(3, p.line)
            assertEquals("watchpoint", p.reason)
            n = n + 1L
            s.resume()
            done(s)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun callStackAnidadoConBreakpoint() {
        val d = JsDebugger(
            "function f() {\nvar x = 1;\n}\nfunction g() {\nf();\n}\ng();"
        ).breakOn(2)
        d.evaluate()
        val s = snap(d)
        assertEquals(2, s.line)
        assertEquals(listOf("f 2", "g 5", "(global) 7"), s.stack)
        assertEquals(2, s.depth)
    }

    @Test
    fun callStackEnTopLevelSoloGlobal() {
        val d = JsDebugger("var a = 1;\nvar b = 2;").breakOn(2)
        d.evaluate()
        val s = snap(d)
        assertEquals(listOf("(global) 2"), s.stack)
        assertEquals(1, s.depth)
    }

    @Test
    fun callStackArrowAnonimo() {
        val d = JsDebugger(
            "var h = (x) => {\nvar y = x;\nreturn y;\n}\nh(1);"
        ).breakOn(2)
        d.evaluate()
        val s = snap(d)
        assertEquals(listOf("(anonymous) 2", "(global) 5"), s.stack)
    }

    @Test
    fun callStackMetodoDeClase() {
        val d = JsDebugger(
            "class C {\nm(v) {\nvar w = v;\nreturn w;\n}\n}\nnew C().m(7);"
        ).breakOn(3)
        d.evaluate()
        val s = snap(d)
        assertEquals(listOf("m 3", "(global) 7"), s.stack)
    }

    @Test
    fun callStackRecursionConBreakpointCondicional() {
        val d = JsDebugger(
            "function f(n) {\nvar k = n;\nif (n > 1) {\nreturn f(n - 1);\n}\nreturn k;\n}\nf(3);"
        ).breakOn(6, "k == 1")
        d.evaluate()
        val s = snap(d)
        assertEquals(listOf("f 6", "f 4", "f 4", "(global) 8"), s.stack)
        assertEquals(2, s.depth)
    }

    @Test
    fun snapshotToStringIncluyeElStack() {
        val d = JsDebugger("function f() {\nvar x = 1;\n}\nf();").breakOn(2)
        d.evaluate()
        val s = snap(d)
        assertTrue(s.toString().contains("stack=f 2;(global) 4"))
    }

    @Test
    fun sesionStackConStepOverEntreFunciones() {
        val s = DebugSession(
            "function f() {\nvar x = 1;\nvar y = 2;\n}\nfunction g() {\nf();\nvar z = 3;\n}\ng();"
        ).breakOn(2).start()
        var n = 0L
        try {
            var p = esperaPausa(s, n)
            assertEquals(listOf("f 2", "g 6", "(global) 9"), p.stack)
            n = n + 1L
            s.stepOver()
            p = esperaPausa(s, n)
            assertEquals(3, p.line)
            assertEquals(listOf("f 3", "g 6", "(global) 9"), p.stack)
            n = n + 1L
            s.stepOver()
            p = esperaPausa(s, n)
            assertEquals(7, p.line)
            assertEquals(listOf("g 7", "(global) 9"), p.stack)
            n = n + 1L
            s.resume()
            val r = done(s)
            assertFalse(r.interrupted)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun sesionStackConWatchpointDentroDeFuncion() {
        val s = DebugSession(
            "function f() {\nvar x = 1;\nx = 2;\nvar y = 3;\n}\nf();"
        ).watch("x").start()
        var n = 0L
        try {
            var p = esperaPausa(s, n)
            assertEquals("watchpoint", p.reason)
            assertEquals(3, p.line)
            assertEquals(listOf("f 3", "(global) 6"), p.stack)
            n = n + 1L
            s.resume()
            p = esperaPausa(s, n)
            assertEquals(4, p.line)
            assertEquals(listOf("f 4", "(global) 6"), p.stack)
            n = n + 1L
            s.resume()
            done(s)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun sesionInspectNoTocaElStackDeLaPausa() {
        val s = DebugSession(
            "function f() {\nvar x = 1;\nvar y = 2;\n}\nf();"
        ).breakOn(3).start()
        var n = 0L
        try {
            val p = esperaPausa(s, n)
            n = n + 1L
            assertEquals("1", s.inspect("x"))
            assertEquals(listOf("f 3", "(global) 5"), p.stack)
            assertEquals(listOf("f 3", "(global) 5"), s.lastSnapshot?.stack)
            s.resume()
            done(s)
        } finally {
            liberar(s)
        }
    }

    @Test
    fun sesionStackEnTopLevelSoloGlobal() {
        val s = DebugSession("var a = 1;\nvar b = 2;").breakOn(2).start()
        var n = 0L
        try {
            val p = esperaPausa(s, n)
            assertEquals(listOf("(global) 2"), p.stack)
            assertEquals(1, p.depth)
            n = n + 1L
            s.resume()
            done(s)
        } finally {
            liberar(s)
        }
    }
}
