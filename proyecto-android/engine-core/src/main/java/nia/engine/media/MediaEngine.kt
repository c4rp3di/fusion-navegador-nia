package nia.engine.media

import nia.contracts.WallClock

/** Fuente del medio v1: URL remota http/https o URI local file/content. */
sealed interface MediaSource {
    data class Remote(val url: String) : MediaSource
    data class Local(val uri: String) : MediaSource
}

/** Estado del nucleo multimedia v1. El fin del medio lo notifica el backend. */
enum class MediaState { IDLE, PREPARING, READY, PLAYING, PAUSED, ENDED, FAILED }

/**
 * Backend de reproduccion inyectable (T036 v1): el nucleo es puro JVM y
 * prueba su logica con fakes en CI; el adaptador de plataforma
 * (android.media.MediaPlayer) vive en :app.
 */
interface MediaBackend {
    fun prepare(source: MediaSource)
    fun start()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Double)
    fun setRate(rate: Double)
    fun close()

    var onPrepared: ((durationMs: Long) -> Unit)?
    var onEnded: (() -> Unit)?
    var onError: ((message: String) -> Unit)?
}

/** Fuente invalida (esquema no admitido); el estado queda intacto. */
class MediaException(message: String) : Exception(message)

/**
 * Nucleo multimedia del motor (T036 v1, R0093, RES-0051): maquina de
 * estados de audio sobre un MediaBackend inyectable con reloj WallClock.
 * El motor ES la autoridad de la posicion: avanza con el reloj al rate
 * actual, se congela en pausa y error, y se clampa a la duracion; el
 * backend recibe las ordenes (start/pause/seekTo/volumen/rate) y notifica
 * preparacion (duracion), fin y error. Exclusiones v1: solo audio, sin
 * elemento audio del DOM ni HTMLMediaElement para JS (T039), sin subs ni
 * fullscreen (v2+), sin tick de progreso (la UI consulta position()).
 */
class MediaEngine(private val backend: MediaBackend, private val clock: WallClock) {

    private var state: MediaState = MediaState.IDLE
    private var durationMs: Long = 0
    private var positionBase: Long = 0
    private var positionAt: Long = 0
    private var rate: Double = 1.0
    private var volume: Double = 1.0
    private var playWhenReady: Boolean = false
    private var lastErrorValue: String? = null
    private val listeners = ArrayList<(MediaState) -> Unit>()

    init {
        backend.onPrepared = { duration -> handlePrepared(duration) }
        backend.onEnded = { handleEnded() }
        backend.onError = { message -> handleFailed(message) }
    }

    fun state(): MediaState = state

    fun duration(): Long = durationMs

    fun rate(): Double = rate

    fun volume(): Double = volume

    fun lastError(): String? = lastErrorValue

    fun addListener(listener: (MediaState) -> Unit) {
        listeners.add(listener)
    }

    /** Posicion integrada por el motor: base + (ahora - desde) x rate. */
    fun position(): Long {
        val advanced = if (state == MediaState.PLAYING) {
            positionBase + ((clock.nowMillis() - positionAt) * rate).toLong()
        } else {
            positionBase
        }
        return if (durationMs > 0) minOf(advanced, durationMs) else advanced
    }

    /** Carga una fuente (autoplay opcional); la invalida tipifica y no
     * toca el backend ni el estado. Una recarga cierra la fuente previa. */
    fun load(source: MediaSource, autoplay: Boolean = false) {
        validate(source)
        if (state != MediaState.IDLE) backend.close()
        durationMs = 0
        positionBase = 0
        positionAt = clock.nowMillis()
        playWhenReady = autoplay
        lastErrorValue = null
        setState(MediaState.PREPARING)
        backend.prepare(source)
    }

    /** Reproduce: pendiente si aun se prepara y reinicio tras el fin. */
    fun play(): Boolean {
        when (state) {
            MediaState.PREPARING -> playWhenReady = true
            MediaState.READY, MediaState.PAUSED -> startAdvance()
            MediaState.ENDED -> {
                positionBase = 0
                startAdvance()
            }
            MediaState.PLAYING -> return true
            else -> return false
        }
        return true
    }

    fun pause(): Boolean {
        if (state != MediaState.PLAYING) return false
        positionBase = position()
        setState(MediaState.PAUSED)
        backend.pause()
        return true
    }

    fun playPause(): Boolean = if (state == MediaState.PLAYING) pause() else play()

    /** Seek absoluto clamped a 0..duracion; desde ENDED deja el medio
     * en PAUSED y devuelve la posicion efectiva. */
    fun seekTo(positionMs: Long): Long {
        if (state == MediaState.IDLE || state == MediaState.PREPARING || state == MediaState.FAILED) {
            return position()
        }
        val target = if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
        positionBase = target
        if (state == MediaState.PLAYING) {
            positionAt = clock.nowMillis()
        }
        if (state == MediaState.ENDED) {
            setState(MediaState.PAUSED)
        }
        backend.seekTo(target)
        return target
    }

    fun seekBy(deltaMs: Long): Long = seekTo(position() + deltaMs)

    /** Volumen 0..1 clamped; se aplica al backend si hay fuente. */
    fun setVolume(value: Double): Double {
        volume = value.coerceIn(0.0, 1.0)
        backend.setVolume(volume)
        return volume
    }

    /** Rate 0.25..4 clamped; congelar la posicion evita saltos al
     * cambiarlo mientras se reproduce. */
    fun setRate(value: Double): Double {
        if (state == MediaState.PLAYING) {
            positionBase = position()
            positionAt = clock.nowMillis()
        }
        rate = value.coerceIn(0.25, 4.0)
        backend.setRate(rate)
        return rate
    }

    /** Libera el backend y vuelve a IDLE (los listeners se conservan). */
    fun close() {
        backend.close()
        durationMs = 0
        positionBase = 0
        playWhenReady = false
        lastErrorValue = null
        setState(MediaState.IDLE)
    }

    private fun validate(source: MediaSource) {
        val valid = when (source) {
            is MediaSource.Remote ->
                source.url.startsWith("http://") || source.url.startsWith("https://")
            is MediaSource.Local ->
                source.uri.startsWith("file://") || source.uri.startsWith("content://")
        }
        if (!valid) throw MediaException("invalid-source")
    }

    private fun startAdvance() {
        positionBase = position()
        positionAt = clock.nowMillis()
        setState(MediaState.PLAYING)
        backend.start()
    }

    private fun handlePrepared(duration: Long) {
        if (state != MediaState.PREPARING) return
        durationMs = duration.coerceAtLeast(0L)
        setState(MediaState.READY)
        if (playWhenReady) {
            playWhenReady = false
            startAdvance()
        }
    }

    private fun handleEnded() {
        if (state != MediaState.PLAYING) return
        positionBase = durationMs
        setState(MediaState.ENDED)
    }

    private fun handleFailed(message: String) {
        if (state == MediaState.IDLE) return
        lastErrorValue = message
        positionBase = position()
        setState(MediaState.FAILED)
    }

    private fun setState(next: MediaState) {
        state = next
        for (listener in listeners) listener(next)
    }
}
