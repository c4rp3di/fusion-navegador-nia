package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
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
import nia.engine.media.SubtitleFormatException
import nia.engine.media.SubtitleParser
import nia.engine.media.SubtitleTrack
import nia.engine.profile.BrowserProfile
import nia.engine.profile.DataFire
import nia.engine.profile.FireLevel
import nia.engine.storage.StorageScope
import nia.engine.url.Origin
import nia.engine.url.Site
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
 *
 * T036 v2 (R0102, RES-0055): video local (SAF de video) sobre SurfaceView
 * (el backend conecta la Surface al MediaPlayer), boton Fullscreen que
 * alterna FLAG_FULLSCREEN de la ventana y subs v1 (ADR-017): parser
 * SubtitleParser de WebVTT/SRT con pista SubtitleTrack de desfase
 * manual, cargada por SAF, cuyo cue activo se pinta sobre el video
 * sincronizado con la posicion del nucleo. Exclusiones v2: subs solo de
 * archivo externo (los del contenedor, T049), sin estilos de cue y
 * fullscreen sin rotacion ni PiP.
 *
 * T044 v1 (R0104, RES-0056): modo incognito (ADR-019) con BrowserProfile
 * (nia.engine.profile): cada perfil es dueno de sus cookies, cache HTTP,
 * storage particionado e IndexedDB (aislados entre perfiles); el normal
 * persiste en disco y el incognito vive solo en memoria. Botones Incognito
 * y Wipe perfil con el estado del perfil activo en un TextView.
 *
 * T045 v1 (R0106, RES-0057): Fire con niveles SESION y TOTAL (ADR-019)
 * sobre el perfil activo: DataFire (nia.engine.profile) borra el
 * sessionStorage o TODO el estado con conteo (incluido el archivo
 * persistido en disco) con confirmacion explicita (AlertDialog).
 * Botones Fire sesion y Fire total; marcadores nunca por defecto.
 */
class MainActivity : Activity() {

    private lateinit var frame: FrameLayout
    private lateinit var mediaText: TextView
    private val mediaClock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }
    private val videoBackend = PlatformMediaBackend(this)
    private val media = MediaEngine(videoBackend, mediaClock)
    private val downloadPool = Executors.newFixedThreadPool(2)
    private val downloads = DownloadEngine(PlatformDownloadTransport(), downloadPool, maxConcurrent = 2)
    private lateinit var downloadText: TextView
    private lateinit var videoView: SurfaceView
    private lateinit var subtitleText: TextView
    private var subs: SubtitleTrack? = null
    private var fullscreenOn = false
    private var profile: BrowserProfile? = null
    private lateinit var profileText: TextView

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
        val video = Button(this)
        video.text = "Video"
        val fullscreen = Button(this)
        fullscreen.text = "Fullscreen"
        val subsBtn = Button(this)
        subsBtn.text = "Subs"
        val subsMenos = Button(this)
        subsMenos.text = "Subs -0.5s"
        val subsMas = Button(this)
        subsMas.text = "Subs +0.5s"
        val incognitoBtn = Button(this)
        incognitoBtn.text = "Incognito"
        val wipePerfil = Button(this)
        wipePerfil.text = "Wipe perfil"
        val fireSesion = Button(this)
        fireSesion.text = "Fire sesion"
        val fireTotal = Button(this)
        fireTotal.text = "Fire total"
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
        buttons.addView(video)
        buttons.addView(fullscreen)
        buttons.addView(subsBtn)
        buttons.addView(subsMenos)
        buttons.addView(subsMas)
        buttons.addView(incognitoBtn)
        buttons.addView(wipePerfil)
        buttons.addView(fireSesion)
        buttons.addView(fireTotal)
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
        video.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("video/*")
            startActivityForResult(i, REQUEST_VIDEO)
        }
        fullscreen.setOnClickListener {
            fullscreenOn = !fullscreenOn
            if (fullscreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        subsBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("*/*")
            startActivityForResult(i, REQUEST_SUBS)
        }
        subsMenos.setOnClickListener { subs?.shift(-500L) }
        subsMas.setOnClickListener { subs?.shift(500L) }
        incognitoBtn.setOnClickListener {
            val p = BrowserProfile.incognito(mediaClock)
            p.cookies.storeFromResponse("example.com", true, "demo=1")
            p.storage.area(
                StorageScope.LOCAL,
                Site("https", "example.com"),
                Origin.Tuple("https", "example.com", 443)
            ).setItem("demo", "1")
            profile = p
            refreshProfile()
        }
        wipePerfil.setOnClickListener {
            profile?.wipe()
            refreshProfile()
        }
        fireSesion.setOnClickListener {
            confirmar("Fire sesion", "Borrar el sessionStorage del perfil activo? (cookies, cache, storage LOCAL e IndexedDB sobreviven)") {
                profile?.let { DataFire.apply(FireLevel.SESION, it) }
                refreshProfile()
            }
        }
        fireTotal.setOnClickListener {
            confirmar("Fire total", "Borrar TODO el estado del perfil activo (cookies, cache, storage, IndexedDB y el archivo persistido)?") {
                profile?.let { DataFire.apply(FireLevel.TOTAL, it) }
                refreshProfile()
            }
        }
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
        videoView = SurfaceView(this)
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                videoBackend.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoBackend.setSurface(null)
            }
        })
        frame.addView(videoView, FrameLayout.LayoutParams(480, 320, Gravity.CENTER))
        subtitleText = TextView(this)
        frame.addView(subtitleText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        profileText = TextView(this)
        frame.addView(profileText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        refreshProfile()
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
        if (requestCode == REQUEST_VIDEO && resultCode == RESULT_OK) {
            media.load(MediaSource.Local(uri.toString()), autoplay = true)
        }
        if (requestCode == REQUEST_SUBS && resultCode == RESULT_OK) {
            val texto = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: java.io.IOException) {
                null
            } ?: return
            subs = try {
                SubtitleTrack(SubtitleParser.parse(texto))
            } catch (e: SubtitleFormatException) {
                null
            }
            refreshMedia()
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
        val pista = subs
        if (pista != null) {
            subtitleText.text = pista.textAt(media.position()) ?: ""
        }
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

    /** T044 v1: estado del perfil activo en la UI (modo y motores). */
    private fun refreshProfile() {
        val p = profile
        if (p == null) {
            profileText.text = "perfil: ninguno"
            return
        }
        profileText.text = "perfil: " + p.kind.name.lowercase() +
            " - cookies " + p.cookies.count() +
            " - particiones storage " + p.storage.partitionCount() +
            " - idb " + p.idb.partitionCount()
    }

    /** T045 v1 (R0106, RES-0057): confirmacion explicita de ADR-019 para Fire. */
    private fun confirmar(titulo: String, mensaje: String, accion: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Si") { _, _ -> accion() }
            .setNegativeButton("No", null)
            .show()
    }

    companion object {
        private const val REQUEST_ABRIR = 41
        private const val REQUEST_GUARDAR = 42
        private const val REQUEST_AUDIO = 43
        private const val REQUEST_DOWNLOAD = 44
        private const val REQUEST_VIDEO = 45
        private const val REQUEST_SUBS = 46
        private const val DEMO_AUDIO_URL = "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg"
        private const val DEMO_DOWNLOAD_URL = "https://raw.githubusercontent.com/c4rp3di/fusion-navegador-nia/main/README.md"
    }
}
