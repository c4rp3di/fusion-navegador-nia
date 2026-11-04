package app.nia.navegador

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import nia.contracts.WallClock
import nia.engine.media.MediaEngine
import nia.engine.media.MediaSource
import nia.engine.download.DownloadEngine
import nia.engine.download.DownloadSink
import nia.engine.download.DownloadState
import nia.engine.media.MediaState
import java.util.concurrent.Executors

/**
 * NIA (T016, R0021): la actividad delega en PaintView, que renderiza la
 * escena del motor propio (HTML -> CSS -> layout -> display list) y resuelve
 * toques con hitTest del motor. Sin WebView y sin layout XML.
 *
 * R0041 (T017 v2): boton DevTools que abre DevToolsActivity, el panel del
 * inspector propio (arbol con nodeIds, estilos computados y causas).
 *
 * R0062 (T027 v2): boton Fetch demo que abre FetchDemoActivity, la demo
 * interactiva de forms/fetch con cancelacion.
 *
 * T035 v1 (R0088, RES-0049): botones Abrir y Guardar con el Storage Access
 * Framework, sin permisos de almacenamiento. Abrir (ACTION_OPEN_DOCUMENT
 * text/html) lee el archivo y PaintView lo parsea con el motor (los <style>
 * del documento se suman al CSS de la demo); Guardar (ACTION_CREATE_DOCUMENT)
 * escribe el HTML fuente cargado, no el DOM mutado (exclusion v1).
 *
 * T036 v1 (R0093, RES-0051): audio con el nucleo propio MediaEngine
 * (nia.engine.media, estados y seek contra reloj) sobre el MediaPlayer de
 * plataforma via PlatformMediaBackend (sin dependencias nuevas). Botones
 * Audio (SAF de audio), Audio URL (demo https), Play/Pausa y seek -10s/+10s
 * con posicion y estado en un TextView. Exclusiones v1: solo audio (sin
 * video), sin audio-element del DOM ni HTMLMediaElement JS (T039), sin subs
 * ni fullscreen (v2+).
 *
 * T046 v1 (R0097, RES-0053): descargas reales con cola: el nucleo
 * DownloadEngine (nia.engine.download) corre hasta dos descargas a la
 * vez con cola FIFO, progreso por chunks y cancelacion cooperativa
 * (CancellationToken de T011) sobre un DownloadTransport inyectable; el
 * adaptador PlatformDownloadTransport usa HttpURLConnection (misma pila
 * de T011 v2, sin dependencias nuevas) y el destino se elige con SAF
 * (ACTION_CREATE_DOCUMENT octet-stream). Botones Descargar y Cancelar
 * descargas con la lista de items y su progreso en un TextView.
 * Exclusiones v1: sin reanudacion, sin reintentos y sin apertura del
 * archivo descargado (v2).
 */
class MainActivity : Activity() {

    private lateinit var frame: FrameLayout
    private lateinit var mediaText: TextView
    private val mediaClock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }
    private val media = MediaEngine(PlatformMediaBackend(this), mediaClock)
    private val downloadPool = Executors.newFixedThreadPool(2)
    private val downloads = DownloadEngine(PlatformDownloadTransport(), downloadPool, maxConcurrent = 2)
    private lateinit var downloadText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        frame.addView(PaintView(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val devtools = Button(this)
        devtools.text = "DevTools"
        val demo = Button(this)
        demo.text = "Fetch demo"
        val abrir = Button(this)
        abrir.text = "Abrir"
        val guardar = Button(this)
        guardar.text = "Guardar"
        val audio = Button(this)
        audio.text = "Audio"
        val audioUrl = Button(this)
        audioUrl.text = "Audio URL"
        val playPause = Button(this)
        playPause.text = "Play/Pausa"
        val menos10 = Button(this)
        menos10.text = "-10s"
        val mas10 = Button(this)
        mas10.text = "+10s"
        val descargar = Button(this)
        descargar.text = "Descargar"
        val cancelarDl = Button(this)
        cancelarDl.text = "Cancelar descargas"
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        buttons.addView(devtools)
        buttons.addView(demo)
        buttons.addView(abrir)
        buttons.addView(guardar)
        buttons.addView(audio)
        buttons.addView(audioUrl)
        buttons.addView(playPause)
        buttons.addView(menos10)
        buttons.addView(mas10)
        buttons.addView(descargar)
        buttons.addView(cancelarDl)
        frame.addView(buttons, params)
        devtools.setOnClickListener {
            startActivity(Intent(this, DevToolsActivity::class.java))
        }
        demo.setOnClickListener {
            startActivity(Intent(this, FetchDemoActivity::class.java))
        }
        abrir.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("text/html")
            startActivityForResult(i, REQUEST_ABRIR)
        }
        guardar.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("text/html")
            i.putExtra(Intent.EXTRA_TITLE, "pagina.html")
            startActivityForResult(i, REQUEST_GUARDAR)
        }
        audio.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("audio/*")
            startActivityForResult(i, REQUEST_AUDIO)
        }
        audioUrl.setOnClickListener {
            media.load(MediaSource.Remote(DEMO_AUDIO_URL), autoplay = true)
        }
        playPause.setOnClickListener { media.playPause() }
        menos10.setOnClickListener { media.seekBy(-10000) }
        mas10.setOnClickListener { media.seekBy(10000) }
        descargar.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("application/octet-stream")
            i.putExtra(Intent.EXTRA_TITLE, "nia-descarga.bin")
            startActivityForResult(i, REQUEST_DOWNLOAD)
        }
        cancelarDl.setOnClickListener {
            for (item in downloads.list()) {
                if (item.state == DownloadState.RUNNING || item.state == DownloadState.QUEUED) {
                    downloads.cancel(item.id)
                }
            }
        }
        mediaText = TextView(this)
        frame.addView(mediaText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ))
        media.addListener { refreshMedia() }
        downloadText = TextView(this)
        frame.addView(downloadText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ))
        downloads.addListener { downloadText.post { refreshDownloads() } }
        refreshMedia()
        setContentView(frame)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        val view = frame.getChildAt(0) as? PaintView ?: return
        if (requestCode == REQUEST_ABRIR && resultCode == RESULT_OK) {
            val html = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: java.io.IOException) {
                null
            } ?: return
            view.load(html)
        }
        if (requestCode == REQUEST_GUARDAR && resultCode == RESULT_OK) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(view.currentHtml()) }
            } catch (e: java.io.IOException) {
            }
        }
        if (requestCode == REQUEST_AUDIO && resultCode == RESULT_OK) {
            media.load(MediaSource.Local(uri.toString()), autoplay = true)
        }
        if (requestCode == REQUEST_DOWNLOAD && resultCode == RESULT_OK) {
            val stream = try {
                contentResolver.openOutputStream(uri)
            } catch (e: java.io.IOException) {
                null
            } ?: return
            downloads.enqueue(DEMO_DOWNLOAD_URL, object : DownloadSink {
                override fun write(bytes: ByteArray, length: Int) {
                    stream.write(bytes, 0, length)
                }
                override fun close() {
                    try {
                        stream.close()
                    } catch (e: java.io.IOException) {
                    }
                }
            })
        }
    }

    /** T036 v1: posicion/duracion/estado del nucleo multimedia en la UI
     * (se re-agenda mientras se reproduce). */
    private fun refreshMedia() {
        val texto = "" + media.position() / 1000 + "s / " + media.duration() / 1000 +
            "s - " + media.state().name.lowercase()
        mediaText.text = texto
        if (media.state() == MediaState.PLAYING) {
            mediaText.postDelayed(this::refreshMedia, 500)
        }
    }

    /** T046 v1: lista de descargas con estado y progreso en la UI. */
    private fun refreshDownloads() {
        val builder = StringBuilder()
        for (item in downloads.list()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append('#').append(item.id).append(' ')
                .append(item.state.name.lowercase()).append(' ')
                .append(item.bytesDownloaded).append('/')
            if (item.totalBytes >= 0) builder.append(item.totalBytes)
            else builder.append('?')
        }
        downloadText.text = builder.toString()
    }

    companion object {
        private const val REQUEST_ABRIR = 41
        private const val REQUEST_GUARDAR = 42
        private const val REQUEST_AUDIO = 43
        private const val REQUEST_DOWNLOAD = 44
        private const val DEMO_AUDIO_URL = "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg"
        private const val DEMO_DOWNLOAD_URL = "https://raw.githubusercontent.com/c4rp3di/fusion-navegador-nia/main/README.md"
    }
}
