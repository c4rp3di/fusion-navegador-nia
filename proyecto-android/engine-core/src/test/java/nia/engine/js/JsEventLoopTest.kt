package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests del event loop (T025 v1, RES-0032): orden microtasks/tasks
 * reproducible con reloj virtual controlable.
 *
 * Expectativas: el orden sync/microtask/task, el FIFO a igual deadline y
 * el throw que no detiene el loop se validaron contra el oraculo JS
 * nativo del sandbox (setTimeout de delay 0); el checkpoint de microtasks
 * tras cada task y la reanudacion de un await asentado por un timer se
 * fijan por la semantica del estandar HTML (el setTimeout del oraculo no
 * vacia microtasks entre timers), igual que en T024 con Event/EventTarget.
 */
class JsEventLoopTest {

    private fun str(r: JsEvalResult): String = (r.value as JsStr).value

    @Test
    fun ordenSyncMicroYTask() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; o.push('s');" +
                " Promise.resolve().then(function () { o.push('p'); });" +
                " setTimeout(function () { o.push('t'); }, 0); o.join(',')"
        )
        loop.advance(0)
        assertEquals("s,p,t", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun queueMicrotaskAntesDeTask() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('t'); }, 0);" +
                " queueMicrotask(function () { o.push('m'); }); o.join(',')"
        )
        loop.advance(0)
        assertEquals("m,t", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun tasksFifoMismoDeadline() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('t1'); }, 10);" +
                " setTimeout(function () { o.push('t2'); }, 10);" +
                " setTimeout(function () { o.push('t3'); }, 10); o.join(',')"
        )
        loop.advance(10)
        assertEquals("t1,t2,t3", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun tasksPorDeadline() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('a'); }, 30);" +
                " setTimeout(function () { o.push('b'); }, 10); o.join(',')"
        )
        loop.advance(100)
        assertEquals("b,a", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun avancePorPasos() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('a'); }, 10);" +
                " setTimeout(function () { o.push('b'); }, 30); o.join(',')"
        )
        loop.advance(10)
        assertEquals("a", str(loop.eval("o.join(',')")))
        loop.advance(20)
        assertEquals("a,b", str(loop.eval("o.join(',')")))
        assertFalse(loop.hasPendingTasks)
        loop.end()
    }

    @Test
    fun intervalRepiteYSeCancela() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; var h = setInterval(function () { o.push('i'); }, 10); o.join(',')"
        )
        loop.advance(10)
        assertEquals("i", str(loop.eval("o.join(',')")))
        loop.advance(10)
        assertEquals("i,i", str(loop.eval("o.join(',')")))
        loop.eval("clearInterval(h); o.join(',')")
        loop.advance(50)
        assertEquals("i,i", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun clearTimeoutCancelaLaTask() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; var h = setTimeout(function () { o.push('x'); }, 10);" +
                " clearTimeout(h); o.join(',')"
        )
        loop.advance(50)
        assertEquals("", str(loop.eval("o.join(',')")))
        assertFalse(loop.hasPendingTasks)
        loop.end()
    }

    @Test
    fun argsExtraDelCallback() {
        val loop = JsEventLoop()
        loop.evaluate(
            "setTimeout(function (a, b) { return a + ':' + b; }, 10, 1, 'x'); 'q'"
        )
        val r = loop.advance(10)
        assertEquals("1:x", str(r))
        loop.end()
    }

    @Test
    fun awaitResueltoPorSetTimeout() {
        val loop = JsEventLoop()
        val r = loop.evaluate(
            "var o = []; async function f() { o.push('start');" +
                " var v = await new Promise(function (res) { setTimeout(function () { res(42); }, 10); });" +
                " o.push('res ' + v); return 'end'; }" +
                " f(); o.push('sync'); o.join(',')"
        )
        assertEquals("start,sync", str(r))
        loop.advance(10)
        assertEquals("start,sync,res 42", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun awaitRechazadoPorSetTimeout() {
        val loop = JsEventLoop()
        val r = loop.evaluate(
            "var o = []; async function g() { try {" +
                " await new Promise(function (x, rej) { setTimeout(function () { rej('E'); }, 10); });" +
                " o.push('no'); } catch (e) { o.push('caught ' + e); } return 'done'; }" +
                " g(); o.push('sync'); o.join(',')"
        )
        assertEquals("sync", str(r))
        loop.advance(10)
        assertEquals("sync,caught E", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun microtaskDeTaskAntesDeSiguienteTask() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('t1');" +
                " queueMicrotask(function () { o.push('m'); }); }, 10);" +
                " setTimeout(function () { o.push('t2'); }, 10); o.join(',')"
        )
        loop.advance(10)
        assertEquals("t1,m,t2", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun taskConThrowNoDetieneElLoop() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('t1'); throw new Error('x'); }, 10);" +
                " setTimeout(function () { o.push('t2'); }, 10); o.join(',')"
        )
        val r = loop.advance(10)
        assertTrue(r.thrown)
        assertEquals("t1,t2", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun delaysNegativosYNaNSonCero() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('a'); }, -5);" +
                " setTimeout(function () { o.push('b'); }, 0/0); o.join(',')"
        )
        loop.advance(0)
        assertEquals("a,b", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun relojControlableYDeadlineExacto() {
        val loop = JsEventLoop()
        assertEquals(0L, loop.now)
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('k'); }, 10); o.join(',')"
        )
        loop.advance(9)
        assertEquals(9L, loop.now)
        assertTrue(loop.hasPendingTasks)
        assertEquals("", str(loop.eval("o.join(',')")))
        loop.advance(1)
        assertEquals(10L, loop.now)
        assertEquals("k", str(loop.eval("o.join(',')")))
        loop.end()
    }

    @Test
    fun cuotaCortaElPump() {
        val loop = JsEventLoop(JsLimits(maxSteps = 500L))
        loop.evaluate(
            "var o = []; setTimeout(function () { while (true) { } }, 0); o.join(',')"
        )
        val r = loop.advance(0)
        assertTrue(r.interrupted)
        assertEquals(JsAbortReason.STEP_QUOTA, r.abortReason)
        loop.end()
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 + 1")))
    }

    @Test
    fun sesionEndLimpiaYElEvaluateClasicoSigue() {
        val loop = JsEventLoop()
        loop.evaluate(
            "var o = []; setTimeout(function () { o.push('t'); }, 100); o.join(',')"
        )
        assertTrue(loop.hasPendingTasks)
        loop.end()
        assertFalse(loop.hasPendingTasks)
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 + 1")))
        assertFalse(JsInterpreter.evaluate("1 + 1").interrupted)
    }
}
