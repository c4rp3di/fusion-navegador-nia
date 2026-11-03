package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsWorkersTest {

    private fun conWorkers(limits: JsLimits = JsLimits(), body: (JsWorkers) -> Unit) {
        val ws = JsWorkers(limits)
        try {
            body(ws)
        } finally {
            ws.end()
        }
    }

    @Test
    fun spawnCorreElScriptYElWorkerEscribeAlHost() {
        conWorkers { ws ->
            val w = ws.spawn("postMessage(\"hola\")")
            assertTrue(w.running)
            assertEquals("hola", ws.pollMessage(w))
            assertNull(ws.pollMessage(w))
        }
    }

    @Test
    fun mensajesDelHostSeEntreganEnElPump() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { postMessage(\"eco:\" + e); };")
            assertTrue(ws.postMessage(w, "x"))
            assertEquals(1, w.pendingMessages())
            val r = ws.pump(w, 0)
            assertNull(r.abortReason)
            assertEquals("eco:x", ws.pollMessage(w))
            assertEquals(0, w.pendingMessages())
        }
    }

    @Test
    fun mensajesDelHostSeEntreganFifo() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { postMessage(e); };")
            ws.postMessage(w, "uno")
            ws.postMessage(w, "dos")
            ws.pump(w, 0)
            assertEquals("uno", ws.pollMessage(w))
            assertEquals("dos", ws.pollMessage(w))
        }
    }

    @Test
    fun workerSinOnmessageDescartaElMensaje() {
        conWorkers { ws ->
            val w = ws.spawn("var a = 1;")
            ws.postMessage(w, "x")
            ws.pump(w, 0)
            assertTrue(w.running)
            assertEquals(0, w.pendingMessages())
            assertNull(ws.pollMessage(w))
        }
    }

    @Test
    fun onmessageNoFuncionEsErrorYTerminaElWorker() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = 42;")
            ws.postMessage(w, "x")
            val r = ws.pump(w, 0)
            assertTrue(r.thrown)
            assertFalse(w.running)
            assertFalse(ws.postMessage(w, "y"))
        }
    }

    @Test
    fun postMessageDelWorkerRechazaNoString() {
        conWorkers { ws ->
            val w = ws.spawn("postMessage(42);")
            assertFalse(w.running)
            assertNull(ws.pollMessage(w))
        }
    }

    @Test
    fun workersConGlobalsAislados() {
        conWorkers { ws ->
            val a = ws.spawn("var secreto = \"aaa\"; postMessage(secreto);")
            val b = ws.spawn("var secreto = \"bbb\"; postMessage(secreto);")
            assertEquals("aaa", ws.pollMessage(a))
            assertEquals("bbb", ws.pollMessage(b))
        }
    }

    @Test
    fun timersDelWorkerConRelojVirtual() {
        conWorkers { ws ->
            val w = ws.spawn("setTimeout(function () { postMessage(\"t\"); }, 50);")
            assertTrue(ws.hasPendingWork(w))
            ws.pump(w, 49)
            assertNull(ws.pollMessage(w))
            ws.pump(w, 1)
            assertEquals("t", ws.pollMessage(w))
            assertFalse(ws.hasPendingWork(w))
        }
    }

    @Test
    fun intervalFixedRateEnWorker() {
        conWorkers { ws ->
            val w = ws.spawn("var n = 0; setInterval(function () { n = n + 1; postMessage(\"\" + n); }, 10);")
            ws.pump(w, 10)
            assertEquals("1", ws.pollMessage(w))
            ws.pump(w, 10)
            assertEquals("2", ws.pollMessage(w))
            assertEquals(1, w.pendingTimers())
        }
    }

    @Test
    fun clearTimeoutCancelaElTimerDelWorker() {
        conWorkers { ws ->
            val w = ws.spawn("var h = setTimeout(function () { postMessage(\"no\"); }, 10); clearTimeout(h);")
            ws.pump(w, 20)
            assertNull(ws.pollMessage(w))
            assertEquals(0, w.pendingTimers())
        }
    }

    @Test
    fun throwEnElScriptTerminaElWorkerEnSpawn() {
        conWorkers { ws ->
            val w = ws.spawn("throw new Error(\"boom\");")
            assertFalse(w.running)
            assertEquals(0, ws.activeCount())
        }
    }

    @Test
    fun throwEnElHandlerTerminaElWorker() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { throw new Error(\"boom\"); }; postMessage(\"listo\");")
            assertEquals("listo", ws.pollMessage(w))
            ws.postMessage(w, "x")
            val r = ws.pump(w, 0)
            assertTrue(r.thrown)
            assertFalse(w.running)
            assertFalse(ws.postMessage(w, "y"))
        }
    }

    @Test
    fun cuotaDePasosTerminaElWorker() {
        conWorkers(JsLimits(maxSteps = 500L)) { ws ->
            val w = ws.spawn("while (true) { }")
            assertFalse(w.running)
            assertEquals(JsAbortReason.STEP_QUOTA, w.lastAbort())
            assertEquals(0, ws.activeCount())
        }
    }

    @Test
    fun interrupcionCancelaElPumpYTerminaElWorker() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { while (true) { } };")
            ws.postMessage(w, "x")
            JsQuotas.interrupt()
            val r = ws.pump(w, 0)
            assertEquals(JsAbortReason.INTERRUPTED, r.abortReason)
            assertFalse(w.running)
        }
    }

    @Test
    fun terminateCortaElLifecycle() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { postMessage(e); };")
            assertTrue(ws.terminate(w))
            assertFalse(ws.terminate(w))
            assertFalse(ws.postMessage(w, "x"))
            val r = ws.pump(w, 5)
            assertTrue(r.thrown)
            assertFalse(w.running)
        }
    }

    @Test
    fun terminateDeUnWorkerNoMataALosDemas() {
        conWorkers { ws ->
            val a = ws.spawn("var x = 1;")
            val b = ws.spawn("var onmessage = function (e) { postMessage(\"b:\" + e); };")
            assertTrue(ws.terminate(a))
            assertFalse(a.running)
            assertTrue(b.running)
            assertTrue(ws.postMessage(b, "hola"))
            ws.pump(b, 0)
            assertEquals("b:hola", ws.pollMessage(b))
        }
    }

    @Test
    fun endTerminaTodoYPuedeReabrirse() {
        val ws = JsWorkers()
        ws.spawn("var x = 1;")
        ws.spawn("var y = 2;")
        assertEquals(2, ws.activeCount())
        ws.end()
        assertEquals(0, ws.activeCount())
        val w = ws.spawn("postMessage(\"reabierto\");")
        assertTrue(w.running)
        assertEquals("reabierto", ws.pollMessage(w))
        ws.end()
    }

    @Test
    fun pumpEntregaMensajesAntesQueTimers() {
        conWorkers { ws ->
            val w = ws.spawn("var onmessage = function (e) { postMessage(\"m:\" + e); }; setTimeout(function () { postMessage(\"t\"); }, 0);")
            ws.postMessage(w, "x")
            ws.pump(w, 1)
            assertEquals("m:x", ws.pollMessage(w))
            assertEquals("t", ws.pollMessage(w))
        }
    }
}
