package nia.engine.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

import nia.contracts.WallClock

/**
 * Espejo de los 28 escenarios validados con el gemelo JS antes del push
 * (T036 v1, R0093, RES-0051): estados, posicion integrada con reloj y
 * rate, seek clamped, fin/error del backend y recarga/close.
 */
class MediaEngineTest {

    private class FakeClock(var valor: Long) : WallClock {
        override fun nowMillis(): Long = valor
    }

    private class FakeBackend : MediaBackend {
        val sources = mutableListOf<MediaSource>()
        var startCalls = 0
        var pauseCalls = 0
        val seeks = mutableListOf<Long>()
        val volumes = mutableListOf<Double>()
        val rates = mutableListOf<Double>()
        var closeCalls = 0
        override var onPrepared: ((Long) -> Unit)? = null
        override var onEnded: (() -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override fun prepare(source: MediaSource) {
            sources.add(source)
        }
        override fun start() {
            startCalls++
        }
        override fun pause() {
            pauseCalls++
        }
        override fun seekTo(positionMs: Long) {
            seeks.add(positionMs)
        }
        override fun setVolume(volume: Double) {
            volumes.add(volume)
        }
        override fun setRate(rate: Double) {
            rates.add(rate)
        }
        override fun close() {
            closeCalls++
        }
    }

    private fun newEngine(): Triple<MediaEngine, FakeBackend, FakeClock> {
        val backend = FakeBackend()
        val clock = FakeClock(1000)
        return Triple(MediaEngine(backend, clock), backend, clock)
    }

    private fun preparado(engine: MediaEngine, backend: FakeBackend, duration: Long = 60000) {
        engine.load(MediaSource.Remote("https://a.example/x.mp3"))
        backend.onPrepared?.invoke(duration)
    }

    @Test fun loadRemotoQuedaPreparando() {
        val (e, b, _) = newEngine()
        e.load(MediaSource.Remote("https://a.example/x.mp3"))
        assertEquals(MediaState.PREPARING, e.state())
        assertEquals(1, b.sources.size)
        assertEquals("https://a.example/x.mp3", (b.sources[0] as MediaSource.Remote).url)
    }

    @Test fun loadLocalQuedaPreparando() {
        val (e, b, _) = newEngine()
        e.load(MediaSource.Local("content://media/audio/1"))
        assertEquals(MediaState.PREPARING, e.state())
        assertEquals(1, b.sources.size)
    }

    @Test fun loadRemotoInvalidoRechazado() {
        val (e, b, _) = newEngine()
        val ex = assertFailsWith<MediaException> { e.load(MediaSource.Remote("ftp://a.example/x.mp3")) }
        assertEquals("invalid-source", ex.message)
        assertEquals(MediaState.IDLE, e.state())
        assertEquals(0, b.sources.size)
    }

    @Test fun loadLocalInvalidoRechazado() {
        val (e, b, _) = newEngine()
        assertFailsWith<MediaException> { e.load(MediaSource.Local("https://a.example/x.mp3")) }
        assertEquals(MediaState.IDLE, e.state())
        assertEquals(0, b.sources.size)
    }

    @Test fun onPreparedDejaListo() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        assertEquals(MediaState.READY, e.state())
        assertEquals(60000, e.duration())
        assertEquals(0, e.position())
    }

    @Test fun playEnListoArranca() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        assertTrue(e.play())
        assertEquals(MediaState.PLAYING, e.state())
        assertEquals(1, b.startCalls)
    }

    @Test fun posicionAvanzaConReloj() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        assertEquals(1000, e.position())
    }

    @Test fun posicionAvanzaConRate() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.setRate(2.0)
        e.play()
        c.valor = 2000
        assertEquals(2000, e.position())
    }

    @Test fun rateSeAplicaSinSaltoDePosicion() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        e.setRate(2.0)
        c.valor = 3000
        assertEquals(3000, e.position())
        assertEquals(2.0, e.rate())
    }

    @Test fun pausaCongelaPosicion() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        assertTrue(e.pause())
        c.valor = 7000
        assertEquals(1000, e.position())
        assertEquals(1, b.pauseCalls)
    }

    @Test fun reanudarSigueDesdePausa() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        e.pause()
        c.valor = 7000
        e.play()
        c.valor = 8000
        assertEquals(2000, e.position())
    }

    @Test fun playEnPreparandoQuedaPendiente() {
        val (e, b, _) = newEngine()
        e.load(MediaSource.Remote("https://a.example/x.mp3"))
        assertTrue(e.play())
        assertEquals(MediaState.PREPARING, e.state())
        b.onPrepared?.invoke(60000)
        assertEquals(MediaState.PLAYING, e.state())
        assertEquals(1, b.startCalls)
    }

    @Test fun autoplayArrancaAlPreparar() {
        val (e, b, _) = newEngine()
        e.load(MediaSource.Remote("https://a.example/x.mp3"), true)
        b.onPrepared?.invoke(60000)
        assertEquals(MediaState.PLAYING, e.state())
    }

    @Test fun seekClampaBordes() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        assertEquals(0, e.seekTo(-5))
        assertEquals(60000, e.seekTo(60001))
        assertEquals(2, b.seeks.size)
        assertEquals(0, b.seeks[0])
        assertEquals(60000, b.seeks[1])
        assertEquals(60000, e.position())
    }

    @Test fun seekEnReproduccionContinua() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        e.seekTo(10000)
        c.valor = 4000
        assertEquals(12000, e.position())
        assertEquals(1, b.seeks.size)
        assertEquals(10000, b.seeks[0])
        assertEquals(MediaState.PLAYING, e.state())
    }

    @Test fun seekEnPausaQuedaEnDestino() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        e.pause()
        e.seekTo(5000)
        c.valor = 3000
        assertEquals(5000, e.position())
        assertEquals(MediaState.PAUSED, e.state())
    }

    @Test fun seekDesdeFinPasaAPausado() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        e.play()
        b.onEnded?.invoke()
        assertEquals(MediaState.ENDED, e.state())
        assertEquals(60000, e.position())
        e.seekTo(30000)
        assertEquals(MediaState.PAUSED, e.state())
        assertEquals(30000, e.position())
        assertEquals(30000, b.seeks[0])
    }

    @Test fun posicionNuncaPasaDuracion() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 1000 + 3600000
        assertEquals(60000, e.position())
        assertEquals(MediaState.PLAYING, e.state())
    }

    @Test fun onEndedFijaEstado() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        e.play()
        b.onEnded?.invoke()
        assertEquals(MediaState.ENDED, e.state())
        assertEquals(60000, e.position())
    }

    @Test fun playTrasFinReinicia() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        b.onEnded?.invoke()
        e.play()
        assertEquals(MediaState.PLAYING, e.state())
        assertEquals(0, e.position())
        c.valor = 2000
        assertEquals(1000, e.position())
    }

    @Test fun onErrorFijaEstadoFallido() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        b.onError?.invoke("boom")
        assertEquals(MediaState.FAILED, e.state())
        assertEquals("boom", e.lastError())
        c.valor = 7000
        assertEquals(1000, e.position())
    }

    @Test fun volumenClampa() {
        val (e, b, _) = newEngine()
        assertEquals(1.0, e.setVolume(1.5))
        assertEquals(0.0, e.setVolume(-1.0))
        assertEquals(listOf(1.0, 0.0), b.volumes)
    }

    @Test fun rateClampa() {
        val (e, b, _) = newEngine()
        assertEquals(4.0, e.setRate(8.0))
        assertEquals(0.25, e.setRate(0.1))
        assertEquals(listOf(4.0, 0.25), b.rates)
    }

    @Test fun playPauseAlterna() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        e.playPause()
        assertEquals(MediaState.PLAYING, e.state())
        e.playPause()
        assertEquals(MediaState.PAUSED, e.state())
        e.playPause()
        assertEquals(MediaState.PLAYING, e.state())
    }

    @Test fun seekByDesdePosicion() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        assertEquals(1500, e.seekBy(500))
        assertEquals(0, e.seekBy(-2000))
    }

    @Test fun recargaReseteaYCierraAnterior() {
        val (e, b, c) = newEngine()
        preparado(e, b)
        e.play()
        c.valor = 2000
        e.load(MediaSource.Remote("https://a.example/y.mp3"))
        assertEquals(1, b.closeCalls)
        assertEquals(MediaState.PREPARING, e.state())
        assertEquals(0, e.position())
        assertEquals(0, e.duration())
        assertEquals(2, b.sources.size)
    }

    @Test fun closeLiberaYResetea() {
        val (e, b, _) = newEngine()
        preparado(e, b)
        e.play()
        e.close()
        assertEquals(1, b.closeCalls)
        assertEquals(MediaState.IDLE, e.state())
        assertEquals(0, e.duration())
        assertFalse(e.play())
    }

    @Test fun listenerRecibeTransiciones() {
        val (e, b, _) = newEngine()
        val vistos = mutableListOf<MediaState>()
        e.addListener { estado -> vistos.add(estado) }
        e.load(MediaSource.Remote("https://a.example/x.mp3"))
        b.onPrepared?.invoke(60000)
        e.play()
        assertEquals(listOf(MediaState.PREPARING, MediaState.READY, MediaState.PLAYING), vistos)
    }
}
