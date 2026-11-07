package nia.engine.workers

import org.junit.Assert.*
import org.junit.Test
import nia.engine.js.JsLimits
import nia.engine.js.JsServiceWorkers
import nia.engine.js.JsWorkers

/**
 * Espejo del gemelo TS de T102 v1 (47 checks en verde antes del push):
 * el visor refleja SOLO estados y operaciones del backend REAL (T034) -
 * lifecycle observable, recursos acotados, nada inventado. Los tests
 * crean workers y SWs DE VERDAD con scripts del motor.
 */
class VisorWorkersTest {

    @Test
    fun sinGestoresDeclaraHonestamente() {
        val v = VisorWorkers(null, null)
        assertTrue(v.sinGestores())
        val t = v.texto()
        assertEquals(2, t.size)
        assertTrue(t[0].contains("sin cablear"))
        assertTrue(v.filasWorkers().isEmpty())
        assertTrue(v.filasSw().isEmpty())
        assertTrue(v.filasCaches().isEmpty())
        assertFalse(v.pararWorker(1L))
        assertFalse(v.pararSw("https://a.test/"))
        assertFalse(v.quitarSw("https://a.test/"))
        assertFalse(v.borrarCache("v1"))
    }

    @Test
    fun workerVivoObservable() {
        val m = JsWorkers()
        val v = VisorWorkers(m, null)
        val a = m.spawn("onmessage = function (m) { postMessage('eco') }")
        val b = m.spawn("setTimeout(function () {}, 5000)")
        assertTrue(m.postMessage(a, "hola"))
        val f = v.filasWorkers()
        assertEquals(2, f.size)
        assertEquals(a.id, f[0].id)
        assertEquals("corriendo", f[0].estado)
        assertEquals(1, f[0].mensajesPendientes)
        assertEquals(0, f[0].timersVivos)
        assertEquals(1, f[1].timersVivos)
        assertEquals(0, f[1].mensajesPendientes)
        assertFalse(f[0].abort)
    }

    @Test
    fun pararWorkerTerminaYNoRepite() {
        val m = JsWorkers()
        val v = VisorWorkers(m, null)
        val a = m.spawn("onmessage = function (m) { }")
        assertTrue(v.pararWorker(a.id))
        assertEquals("terminado", v.filasWorkers()[0].estado)
        assertFalse(v.pararWorker(a.id))
        assertFalse(v.pararWorker(999L))
        assertFalse(m.postMessage(a, "x"))
    }

    @Test
    fun workerMuerePorThrow() {
        val m = JsWorkers()
        val v = VisorWorkers(m, null)
        m.spawn("throw new Error('boom')")
        val f = v.filasWorkers()
        assertEquals(1, f.size)
        assertEquals("terminado", f[0].estado)
        assertFalse(f[0].abort)
    }

    @Test
    fun workerAbortadoPorCuotaMarcaAbort() {
        val m = JsWorkers(JsLimits(200, 1000))
        val v = VisorWorkers(m, null)
        m.spawn("while (true) { }")
        val f = v.filasWorkers()
        assertEquals("terminado", f[0].estado)
        assertTrue(f[0].abort)
    }

    @Test
    fun swLifecycleObservable() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        val sw = m.register("https://a.test/app", "oninstall = function () { }; onactivate = function () { }")
        assertEquals("installing", v.filasSw()[0].estado)
        m.pump(sw)
        assertEquals("installed", v.filasSw()[0].estado)
        m.pump(sw)
        assertEquals("active", v.filasSw()[0].estado)
    }

    @Test
    fun swSinHandlersAvanza() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        val sw = m.register("https://b.test/", "")
        m.pump(sw)
        m.pump(sw)
        assertEquals("active", v.filasSw()[0].estado)
    }

    @Test
    fun scopesEnOrdenYQuitar() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        m.register("https://a.test/app", "")
        m.register("https://b.test/", "")
        m.register("https://c.test/", "")
        assertEquals(
            "https://a.test/app|https://b.test/|https://c.test/",
            v.filasSw().joinToString("|") { it.scope }
        )
        assertTrue(v.quitarSw("https://b.test/"))
        assertEquals(2, v.filasSw().size)
        assertEquals("https://a.test/app", v.filasSw()[0].scope)
        assertFalse(v.quitarSw("https://b.test/"))
    }

    @Test
    fun pararSwDejaRegistradoTerminado() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        m.register("https://a.test/app", "")
        assertTrue(v.pararSw("https://a.test/app"))
        assertEquals("terminated", v.filasSw()[0].estado)
        assertEquals(1, v.filasSw().size)
        assertFalse(v.pararSw("https://a.test/app"))
    }

    @Test
    fun mensajesDelSwVisibles() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        val sw = m.register("https://c.test/", "")
        assertTrue(m.postMessage(sw, "hola"))
        assertEquals(1, v.filasSw()[0].mensajesPendientes)
        m.pump(sw)
        m.pump(sw)
        m.pump(sw)
        assertEquals("active", v.filasSw()[0].estado)
        assertEquals(0, v.filasSw()[0].mensajesPendientes)
    }

    @Test
    fun cachesMedidosEnCaracteres() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        m.register(
            "https://d.test/",
            "caches.open('v1').put('https://d.test/app/a', 'hola'); caches.open('v1').put('https://d.test/app/b', 'adios'); caches.open('v2').put('x', 'y')"
        )
        val fc = v.filasCaches()
        assertEquals(2, fc.size)
        assertEquals("v1", fc[0].nombre)
        assertEquals(2, fc[0].entradas)
        val esperado = ("https://d.test/app/a".length + "hola".length + "https://d.test/app/b".length + "adios".length).toLong()
        assertEquals(esperado, fc[0].caracteres)
        assertEquals(1, fc[1].entradas)
        assertEquals((1 + "y".length).toLong(), fc[1].caracteres)
    }

    @Test
    fun borrarCacheCoherente() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        m.register("https://d.test/", "caches.open('v1').put('a', 'hola'); caches.open('v2').put('b', 'x')")
        assertTrue(v.borrarCache("v1"))
        assertEquals("v2", v.filasCaches()[0].nombre)
        assertFalse(v.borrarCache("v1"))
        assertFalse(v.borrarCache("nope"))
        val sw2 = m.register("https://d.test/dos", "postMessage(caches.has('v1') ? 'si' : 'no')")
        assertEquals("no", m.pollMessage(sw2))
    }

    @Test
    fun cachesAunqueSwTerminado() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        val sw = m.register("https://d.test/", "caches.open('v1').put('a', 'hola')")
        m.pump(sw)
        m.pump(sw)
        assertTrue(m.terminate(sw))
        val fc = v.filasCaches()
        assertEquals(1, fc.size)
        assertEquals("v1", fc[0].nombre)
    }

    @Test
    fun swMuerePorThrowDeFetchSinAbort() {
        val m = JsServiceWorkers()
        val v = VisorWorkers(null, m)
        val sw = m.register("https://e.test/", "onfetch = function (e) { throw new Error('x') }")
        m.pump(sw)
        m.pump(sw)
        val r = m.intercept("GET", "https://e.test/datos")
        assertTrue(r is JsServiceWorkers.Intercept.Threw)
        val f = v.filasSw()[0]
        assertEquals("terminated", f.estado)
        assertFalse(f.abort)
    }

    @Test
    fun textoConContenidoYEstadosReales() {
        val mw = JsWorkers()
        val ms = JsServiceWorkers()
        val v = VisorWorkers(mw, ms)
        mw.spawn("onmessage = function (m) { }")
        ms.register("https://f.test/", "caches.open('cacheF').put('u', 'cuerpo')")
        val t = v.texto()
        assertEquals("Workers dedicados: 1 corriendo de 1", t[0])
        assertEquals("  #1 corriendo, 0 mensajes, 0 timers", t[1])
        assertEquals("Service workers: 1 registro(s)", t[2])
        assertEquals("  https://f.test/ -> installing, 0 mensajes", t[3])
        assertTrue(t[4].contains("MEDIDOS"))
        assertEquals("  cacheF: 1 entrada(s), 7 caracteres", t[5])
    }
}
