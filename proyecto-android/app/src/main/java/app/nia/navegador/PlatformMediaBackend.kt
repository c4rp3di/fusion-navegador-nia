package app.nia.navegador

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface

import nia.engine.media.MediaBackend
import nia.engine.media.MediaSource

/**
 * T036 v1 (R0093, RES-0051): MediaBackend de plataforma sobre
 * android.media.MediaPlayer (sin dependencias nuevas). La fuente remota
 * viaja como URL (el transporte lo hace la plataforma; TLS de sistema) y
 * la local como URI de SAF (content) o file; prepareAsync notifica al
 * nucleo via onPrepared (duracion), onEnded y onError. La posicion la
 * integra MediaEngine y este backend la aplica en cada seekTo.
 *
 * T036 v2 (R0102, RES-0055): video: la Surface se conecta con setSurface
 * antes o despues de prepare (null la desconecta), sin dependencias
 * nuevas.
 */
class PlatformMediaBackend(private val context: Context) : MediaBackend {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    override var onPrepared: ((Long) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /** T036 v2: superficie de video (null la desconecta). */
    fun setSurface(s: Surface?) {
        surface = s
        player?.setSurface(s)
    }

    /** T109 v3 (R0388, RES-0155): tamano REAL del video del
     *  MediaPlayer para la proporcion del PiP; null sin video o sin
     *  fuente preparada (JAMAS se inventa). */
    fun tamanoVideo(): Pair<Int, Int>? {
        val p = player ?: return null
        val ancho = p.videoWidth
        val alto = p.videoHeight
        return if (ancho > 0 && alto > 0) ancho to alto else null
    }

    override fun prepare(source: MediaSource) {
        release()
        val mp = MediaPlayer()
        when (source) {
            is MediaSource.Remote -> mp.setDataSource(source.url)
            is MediaSource.Local -> mp.setDataSource(context, Uri.parse(source.uri))
        }
        mp.setOnPreparedListener { p -> onPrepared?.invoke(p.duration.toLong()) }
        mp.setOnCompletionListener { onEnded?.invoke() }
        mp.setOnErrorListener { _, what, extra ->
            onError?.invoke("media-error " + what + "/" + extra)
            true
        }
        surface?.let { mp.setSurface(it) }
        mp.prepareAsync()
        player = mp
    }

    override fun start() {
        player?.start()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.toInt())
    }

    override fun setVolume(volume: Double) {
        player?.setVolume(volume.toFloat(), volume.toFloat())
    }

    override fun setRate(rate: Double) {
        val p = player ?: return
        p.playbackParams = p.playbackParams.setSpeed(rate.toFloat())
    }

    override fun close() {
        release()
    }

    private fun release() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.stop()
        } catch (e: IllegalStateException) {
            // sin fuente preparada o ya liberado: nada que detener
        }
        p.release()
        player = null
    }
}
