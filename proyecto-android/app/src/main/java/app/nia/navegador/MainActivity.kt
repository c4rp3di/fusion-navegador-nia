package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import android.widget.Toast
import nia.contracts.CancellationToken
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.contracts.valueOrNull
import nia.engine.media.MediaEngine
import nia.engine.media.MediaSource
import nia.engine.download.DownloadEngine
import nia.engine.download.DownloadSink
import nia.engine.download.DownloadState
import nia.engine.download.ReopenableSink
import nia.engine.media.MediaState
import nia.engine.media.OpenSubtitlesClient
import nia.engine.media.OpenSubtitlesConfig
import nia.engine.media.SubtitleFormatException
import nia.engine.media.SubtitleManager
import nia.engine.media.SubtitleParser
import nia.engine.media.SubtitleTrack
import nia.engine.media.TorrentSubtitles
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult
import nia.engine.profile.BrowserProfile
import nia.engine.profile.DataFire
import nia.engine.profile.FireLevel
import nia.engine.qa.GithubGmailMatrix
import nia.engine.qa.YoutubeMatrix
import nia.engine.torrent.AnnounceEvent
import nia.engine.torrent.Benc
import nia.engine.torrent.Bencode
import nia.engine.torrent.DhtClient
import nia.engine.torrent.DhtTransport
import nia.engine.torrent.MagnetParser
import nia.engine.torrent.PeerConnection
import nia.engine.torrent.PeerMetadataClient
import nia.engine.torrent.PeerPoolSource
import nia.engine.torrent.PeerTransport
import nia.engine.torrent.PieceSource
import nia.engine.torrent.PumpResult
import nia.engine.torrent.TorrentFile
import nia.engine.torrent.TorrentMeta
import nia.engine.torrent.TorrentParser
import nia.engine.torrent.TorrentSession
import nia.engine.torrent.TorrentStream
import nia.engine.torrent.TrackerAnnounceRequest
import nia.engine.torrent.TrackerClient
import nia.engine.torrent.TrackerPeer
import nia.engine.torrent.TrackerTransport
import nia.engine.torrent.UdpTrackerClient
import nia.engine.torrent.UdpTransport
import nia.engine.torrent.torrentDesdeInfo
import nia.engine.modules.ModuleRegistry
import nia.engine.modules.PluginEvent
import nia.engine.modules.PluginRuntime
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
 * T046 v2 (R0124, RES-0065): reanudacion con Range y reintentos en el
 * nucleo (la app corre con maxRetries = 1): PlatformDownloadTransport
 * pide la cabecera Range y acepta 206 y 200, el sink reabrible
 * DescargaReabrible reposiciona el destino SAF (wt trunca desde cero,
 * wa continua al final) y el boton Abrir descarga abre con ACTION_VIEW
 * y FLAG_GRANT_READ_URI_PERMISSION el Uri de la ultima descarga
 * completada (aviso si no hay ninguna o ninguna app puede abrirlo).
 * Exclusiones v2: sin gestor persistente de descargas entre sesiones.
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
 *
 * T047 v1 (R0108, RES-0058, ADR-016): Torrent demo del dominio torrent
 * SIN red con cliente propio tras la auditoria de ADR-016 (ninguna
 * candidata cumple): parseo de magnet y .torrent (Bencode canonico) y
 * sesion con piezas verificadas con SHA-1 hasta DONE sobre una fuente
 * PieceSource sintetica; resultado en el TextView de descargas.
 *
 * T048 v1 (R0110, RES-0059): streaming torrent: boton Stream torrent que
 * bombea 3 de 5 piezas de un WAV sintetico, escribe el prefijo legible
 * en cacheDir y lo carga en el reproductor (MediaSource.Local): el
 * archivo parcial reproducible del titulo de T048, servido por
 * TorrentStream sobre las piezas verificadas de la sesion.
 *
 * T049 v1 (R0114, RES-0060): subtitulos locales e incluidos. Parser
 * ASS/SSA en SubtitleParser (seccion [Events] con Format y Dialogue,
 * Text obligatoriamente el ultimo campo, centesimos, etiquetas entre
 * llaves fuera y \N como salto), SubtitleManager de seleccion
 * multi-pista con el desfase delegado a la seleccionada y
 * TorrentSubtitles que carga del torrent los archivos srt/vtt/ass/ssa
 * ya legibles via TorrentStream; boton Subs torrent que cicla la
 * seleccion de las pistas del torrent sintetico y las enchufa al
 * overlay de subs existente. Exclusiones v1: sin estilos de cue, sin
 * deteccion de codificacion y sin subs remotos (T050).
 *
 * T050 v1 (R0116, RES-0061, ADR-017): proveedor OpenSubtitles de
 * nucleo puro JVM sobre FetchManager (transporte inyectable): busqueda
 * por consulta e idiomas, descarga del archivo y cuenta configurable
 * (apiKey siempre; usuario y clave opcionales con login Bearer
 * perezoso); las credenciales nunca salen del proceso (mensajes de
 * error seguros, sin credenciales). Boton Subs OS: demo del flujo
 * buscar, descargar y pista con un transporte falso enlatado SIN
 * red; la red real usa HttpTransport con la Api-Key del usuario.
 *
 * T038 v1 (R0118, RES-0062): matriz P7 GitHub/Gmail del nucleo
 * (ADR-012): catalogo fechado de acciones objetivo (repo, issue,
 * login, bandeja, hilo, busqueda, redaccion y leidos) ejecutadas
 * contra un corpus enlatado con transporte falso SIN red, con
 * limites fechados y las acciones excluidas reservadas al smoke
 * real (T067). Boton Matriz GH/Gmail: corre las acciones completas
 * y muestra el informe.
 *
 * T039 v1 (R0120, RES-0063): matriz P7 YouTube del nucleo (segundo
 * objetivo explicito del navegador): catalogo fechado de acciones
 * objetivo (busqueda, watch con relacionados, comentarios, subs,
 * historial, login, portada y ver-mas-tarde) ejecutadas contra un
 * corpus enlatado con transporte falso SIN red, con limites fechados
 * (play/seek y calidad exigen HTMLMediaElement en el DOM, pendiente
 * del elemento multimedia del DOM con codecs de plataforma segun
 * ADR-015) y protegido/directos aparte (T059 y smoke real T067).
 * Boton Matriz YT: corre las acciones completas y muestra el informe.
 *
 * T047 v2 (R0126, RES-0066, ADR-016): red del torrent SIN red en la
 * demo: announce sobre un tracker falso enlatado y piezas servidas
 * por un peer falso con el protocolo wire completo (handshake,
 * bitfield, unchoke y bloques, una conexion por pieza);
 * PeerPoolSource alimenta el pump de TorrentSession hasta DONE con
 * verificacion SHA-1. Boton Torrent red.
 *
 * T047 v3 (R0129, RES-0067, ADR-016): red torrent ampliada SIN red
 * en la demo: announce UDP (BEP-15) sobre un transporte falso, DHT
 * (BEP-5) con get_peers sobre un transporte falso y metadatos magnet
 * (BEP-9/10) ensamblados desde un peer guionado con ut_metadata;
 * torrentDesdeInfo monta el torrent desde el info dict ensamblado.
 * Boton Torrent magnet.
 *
 * T029/T030 v1 (R0131, RES-0068, ADR-016): runtime de plugins SIN red
 * en la demo: PluginRuntime carga el entry de un modulo instalado
 * (ModuleRegistry de T009) con cuotas de plugin (topes reducidos),
 * inyecta el objeto nia con los hooks que los permisos conceden
 * (onStartup y onNavigate siempre; onRequest exige network.fetch),
 * dispara hooks (fire) con revocacion (unload DENIED) y update con
 * rollback automatico sin recompilar. Boton Plugins demo.
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
    private val downloads = DownloadEngine(PlatformDownloadTransport(), downloadPool, maxConcurrent = 2, maxRetries = 1)
    private lateinit var downloadText: TextView
    private var downloadUri: Uri? = null
    private var downloadId = -1L
    private var descargaCompletadaUri: Uri? = null
    private lateinit var videoView: SurfaceView
    private lateinit var subtitleText: TextView
    private var subs: SubtitleTrack? = null
    private var subsTorrent: SubtitleManager? = null
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
        val abrirDl = Button(this)
        abrirDl.text = "Abrir descarga"
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
        val torrentDemo = Button(this)
        torrentDemo.text = "Torrent demo"
        val torrentRed = Button(this)
        torrentRed.text = "Torrent red"
        val torrentMagnet = Button(this)
        torrentMagnet.text = "Torrent magnet"
        val pluginsDemo = Button(this)
        pluginsDemo.text = "Plugins demo"
        val streamDemo = Button(this)
        streamDemo.text = "Stream torrent"
        val subsTorrentBtn = Button(this)
        subsTorrentBtn.text = "Subs torrent"
        val subsOsBtn = Button(this)
        subsOsBtn.text = "Subs OS"
        val matrizBtn = Button(this)
        matrizBtn.text = "Matriz GH/Gmail"
        val ytBtn = Button(this)
        ytBtn.text = "Matriz YT"
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
        buttons.addView(abrirDl)
        buttons.addView(video)
        buttons.addView(fullscreen)
        buttons.addView(subsBtn)
        buttons.addView(subsMenos)
        buttons.addView(subsMas)
        buttons.addView(incognitoBtn)
        buttons.addView(wipePerfil)
        buttons.addView(fireSesion)
        buttons.addView(fireTotal)
        buttons.addView(torrentDemo)
        buttons.addView(torrentRed)
        buttons.addView(torrentMagnet)
        buttons.addView(pluginsDemo)
        buttons.addView(streamDemo)
        buttons.addView(subsTorrentBtn)
        buttons.addView(subsOsBtn)
        buttons.addView(matrizBtn)
        buttons.addView(ytBtn)
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
        torrentDemo.setOnClickListener {
            runTorrentDemo()
        }
        torrentRed.setOnClickListener {
            runTorrentNetDemo()
        }
        torrentMagnet.setOnClickListener {
            runTorrentMagnetDemo()
        }
        pluginsDemo.setOnClickListener {
            runPluginsDemo()
        }
        streamDemo.setOnClickListener {
            runStreamDemo()
        }
        subsTorrentBtn.setOnClickListener {
            runSubsTorrentDemo()
        }
        subsOsBtn.setOnClickListener {
            runSubsOsDemo()
        }
        matrizBtn.setOnClickListener {
            runMatrizDemo()
        }
        ytBtn.setOnClickListener {
            runMatrizYtDemo()
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
        abrirDl.setOnClickListener {
            val uriAbierta = descargaCompletadaUri
            if (uriAbierta == null) {
                Toast.makeText(this, "sin descarga completada", Toast.LENGTH_SHORT).show()
            } else {
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uriAbierta, "application/octet-stream")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "sin app que abra la descarga", Toast.LENGTH_SHORT).show()
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
        downloads.addListener { item ->
            if (item.id == downloadId && item.state == DownloadState.COMPLETED) {
                descargaCompletadaUri = downloadUri
            }
            downloadText.post { refreshDownloads() }
        }
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
            downloadUri = uri
            downloadId = downloads.enqueue(DEMO_DOWNLOAD_URL, DescargaReabrible(uri, 0L))
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

    /** T046 v2: sink reabrible sobre el documento SAF elegido al
     * descargar: wt trunca y escribe desde cero y wa posiciona al
     * final para reanudar; reopen devuelve un sink NUEVO sobre el
     * mismo Uri. Si el destino no se puede abrir, el primer write
     * falla como IOException y la descarga queda FAILED (honesto). */
    private inner class DescargaReabrible(
        private val uri: Uri,
        private val startByte: Long
    ) : ReopenableSink {
        private var stream: java.io.OutputStream? = abrir()
        private fun abrir(): java.io.OutputStream? = try {
            contentResolver.openOutputStream(uri, if (startByte > 0L) "wa" else "wt")
        } catch (e: java.io.IOException) {
            null
        }
        override fun write(bytes: ByteArray, length: Int) {
            val s = stream ?: throw java.io.IOException("destino cerrado")
            s.write(bytes, 0, length)
        }
        override fun close() {
            val s = stream
            stream = null
            try {
                s?.close()
            } catch (e: java.io.IOException) {
            }
        }
        override fun reopen(startByteReanudar: Long): DownloadSink =
            DescargaReabrible(uri, startByteReanudar)
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

    /** T047 v1 (R0108, RES-0058, ADR-016): demo torrent SIN red (cliente propio). */
    private fun runTorrentDemo() {
        val magnet = MagnetParser.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=demo&tr=http%3A%2F%2Ftracker.example%2Fannounce")
        val data = ByteArray(40) { (it + 1).toByte() }
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("demo.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data, 16))
        info.entries["length"] = Benc.BInt(40)
        val root = Benc.BDict(LinkedHashMap())
        root.entries["announce"] = Benc.BStr("http://tracker.example/announce".toByteArray(Charsets.UTF_8))
        root.entries["info"] = info
        val meta = TorrentParser.parse(Bencode.encode(root))
        val sesion = TorrentSession(meta, emptySet())
        val fuente = object : PieceSource {
            override fun piece(index: Int, length: Int): ByteArray =
                data.copyOfRange(index * 16, index * 16 + length)
        }
        var bomba: PumpResult
        do {
            bomba = sesion.pump(fuente)
        } while (bomba == PumpResult.COMPLETED)
        val progreso = sesion.progress()
        downloadText.text = "torrent: " + meta.name + " - magnet " + magnet.infoHashHex +
            " - piezas " + progreso.completedPieces + "/" + progreso.totalPieces +
            " - trackers " + meta.trackers.size + " - estado " + sesion.state.name
    }

    private fun piezasSha1(data: ByteArray, largo: Int): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(largo, data.size - i)
            md.reset()
            out.write(md.digest(data.copyOfRange(i, i + len)))
            i += len
        }
        return out.toByteArray()
    }

    /** T047 v2 (R0126, RES-0066, ADR-016): demo de la red del torrent
     * SIN red: announce sobre un tracker falso enlatado y piezas
     * servidas por peers falsos con el protocolo wire completo (una
     * conexion por pieza); PeerPoolSource alimenta el pump de
     * TorrentSession hasta DONE con verificacion SHA-1. */
    private fun runTorrentNetDemo() {
        val data = ByteArray(40) { (it + 1).toByte() }
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("red.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data, 16))
        info.entries["length"] = Benc.BInt(40)
        val root = Benc.BDict(LinkedHashMap())
        root.entries["announce"] = Benc.BStr("http://tracker.example/announce".toByteArray(Charsets.UTF_8))
        root.entries["info"] = info
        val meta = TorrentParser.parse(Bencode.encode(root))
        val peerId = "-NIA-0001-0000000000".toByteArray(Charsets.US_ASCII)
        val tracker = object : TrackerTransport {
            override fun get(url: String): ByteArray {
                val r = Benc.BDict(LinkedHashMap())
                r.entries["interval"] = Benc.BInt(60)
                r.entries["peers"] = Benc.BStr(byteArrayOf(127, 0, 0, 1, 0x1a, 0xe1.toByte()))
                return Bencode.encode(r)
            }
        }
        val respuesta = TrackerClient(tracker).announce(
            meta.trackers[0],
            TrackerAnnounceRequest(meta.infoHash, peerId, 6881, 0, 0, meta.totalLength, AnnounceEvent.STARTED)
        )
        var conexion = 0
        val peers = object : PeerTransport {
            override fun connect(peer: TrackerPeer): PeerConnection =
                conexionGuion(conexion++, data, peerId, meta)
        }
        val fuente = PeerPoolSource(respuesta.peers, peers, meta.infoHash, peerId, meta.pieceCount(), 16)
        val sesion = TorrentSession(meta, emptySet())
        var bomba: PumpResult
        do {
            bomba = sesion.pump(fuente)
        } while (bomba == PumpResult.COMPLETED)
        val progreso = sesion.progress()
        downloadText.text = "torrent red: " + respuesta.peers.size + " peer del announce - piezas " +
            progreso.completedPieces + "/" + progreso.totalPieces + " - estado " + sesion.state.name
    }

    /** Guion enlatado de un peer falso para la demo de red del
     * torrent: handshake valido, bitfield, unchoke y los bloques de
     * la pieza pedida (una conexion por pieza). */
    private fun conexionGuion(
        pieza: Int,
        data: ByteArray,
        peerId: ByteArray,
        meta: TorrentMeta
    ): PeerConnection {
        val largoPieza = minOf(16, meta.totalLength.toInt() - pieza * 16)
        val guion = java.io.ByteArrayOutputStream()
        guion.write(19)
        guion.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
        guion.write(ByteArray(8))
        guion.write(meta.infoHash)
        guion.write(peerId)
        wireMensaje(guion, 5, byteArrayOf(0xe0.toByte()))
        wireMensaje(guion, 1, ByteArray(0))
        val payload = java.io.ByteArrayOutputStream()
        wire32(payload, pieza)
        wire32(payload, 0)
        payload.write(data.copyOfRange(pieza * 16, pieza * 16 + largoPieza))
        wireMensaje(guion, 7, payload.toByteArray())
        val bytes = guion.toByteArray()
        return object : PeerConnection {
            override val input: java.io.InputStream = java.io.ByteArrayInputStream(bytes)
            override val output: java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun close() {}
        }
    }

    private fun wire32(out: java.io.ByteArrayOutputStream, valor: Int) {
        out.write((valor shr 24) and 0xff)
        out.write((valor shr 16) and 0xff)
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun wireMensaje(out: java.io.ByteArrayOutputStream, id: Int, payload: ByteArray) {
        wire32(out, 1 + payload.size)
        out.write(id)
        out.write(payload)
    }

    /** T047 v3 (R0129, RES-0067, ADR-016): demo de los metadatos magnet
     * SIN red: announce UDP sobre un transporte falso, get_peers DHT
     * sobre un transporte falso y el info dict ensamblado desde un
     * peer guionado con ut_metadata; torrentDesdeInfo monta el
     * torrent y el TextView muestra el veredicto. */
    private fun runTorrentMagnetDemo() {
        val data = ByteArray(40) { (it + 1).toByte() }
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("red.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data, 16))
        info.entries["length"] = Benc.BInt(40)
        val infoBytes = Bencode.encode(info)
        val meta = torrentDesdeInfo("udp://tracker.example:6969/announce", infoBytes)
        val peerId = "-NIA-0001-0000000000".toByteArray(Charsets.US_ASCII)
        var llamadaUdp = 0
        val udp = object : UdpTransport {
            override fun exchange(ip: String, port: Int, packet: ByteArray): ByteArray? {
                llamadaUdp++
                return if (llamadaUdp == 1) {
                    byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0x11, 0x11, 0x11, 0x11, 0x22, 0x22, 0x22, 0x22)
                } else {
                    byteArrayOf(
                        0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 120,
                        0, 0, 0, 1, 0, 0, 0, 2,
                        127, 0, 0, 1, 0x1a, 0xe1.toByte()
                    )
                }
            }
        }
        val udpPeers = UdpTrackerClient(udp)
            .announce(
                "10.0.0.5", 6969,
                TrackerAnnounceRequest(meta.infoHash, peerId, 6881, 0, 0, meta.totalLength, AnnounceEvent.STARTED)
            )
            .peers
        val selfId = "-NIA-DHT-NODE-000001".toByteArray(Charsets.US_ASCII)
        val dht = object : DhtTransport {
            override fun exchange(ip: String, port: Int, request: ByteArray): ByteArray? {
                val r = Benc.BDict(LinkedHashMap())
                r.entries["id"] = Benc.BStr(selfId)
                r.entries["token"] = Benc.BStr("tk".toByteArray(Charsets.UTF_8))
                r.entries["values"] = Benc.BList(listOf(Benc.BStr(byteArrayOf(10, 0, 1, 9, 0x1b, 0x39))))
                val raiz = Benc.BDict(LinkedHashMap())
                raiz.entries["t"] = Benc.BStr(byteArrayOf(0, 1))
                raiz.entries["y"] = Benc.BStr("r".toByteArray(Charsets.UTF_8))
                raiz.entries["r"] = r
                return Bencode.encode(raiz)
            }
        }
        val dhtPeers = DhtClient(dht, selfId).buscarPeers(meta.infoHash, listOf(TrackerPeer("10.0.0.1", 1)))
        val infoObtenida = PeerMetadataClient(object : PeerTransport {
            override fun connect(peer: TrackerPeer): PeerConnection =
                conexionMagnet(infoBytes, peerId, meta.infoHash)
        }, meta.infoHash, peerId, 32).fetchMetadata(TrackerPeer("127.0.0.1", 6881))
        if (infoObtenida == null) {
            downloadText.text = "magnet: sin metadatos del peer guionado"
            return
        }
        val meta2 = torrentDesdeInfo("udp://tracker.example:6969/announce", infoObtenida)
        val piezasMeta = (infoBytes.size + 31) / 32
        downloadText.text = "magnet: " + meta2.name + " - " + meta2.pieceCount() + " piezas/" +
            meta2.totalLength + " B - UDP " + udpPeers.size + " peer - DHT " + dhtPeers.size +
            " peer - metadatos " + piezasMeta + " piezas"
    }

    /** Peer guionado que sirve los metadatos magnet por piezas de
     * ut_metadata (BEP-9/10) para la demo SIN red. */
    private fun conexionMagnet(infoBytes: ByteArray, peerId: ByteArray, infoHash: ByteArray): PeerConnection {
        val guion = java.io.ByteArrayOutputStream()
        guion.write(19)
        guion.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
        guion.write(ByteArray(8))
        guion.write(infoHash)
        guion.write(peerId)
        val m = Benc.BDict(LinkedHashMap())
        m.entries["ut_metadata"] = Benc.BInt(2)
        val ext = Benc.BDict(LinkedHashMap())
        ext.entries["m"] = m
        wireMensaje(guion, 20, Bencode.encode(ext))
        val total = infoBytes.size
        val piezas = (total + 31) / 32
        for (i in 0 until piezas) {
            val desde = i * 32
            val hasta = minOf(32, total - desde)
            val dict = Benc.BDict(LinkedHashMap())
            dict.entries["msg_type"] = Benc.BInt(1)
            dict.entries["piece"] = Benc.BInt(i.toLong())
            dict.entries["total_size"] = Benc.BInt(total.toLong())
            val payload = java.io.ByteArrayOutputStream()
            payload.write(Bencode.encode(dict))
            payload.write(infoBytes.copyOfRange(desde, desde + hasta))
            wireMensaje(guion, 2, payload.toByteArray())
        }
        val bytes = guion.toByteArray()
        return object : PeerConnection {
            override val input: java.io.InputStream = java.io.ByteArrayInputStream(bytes)
            override val output: java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun close() {}
        }
    }

    /** T029/T030 v1 (R0131, RES-0068, ADR-016): demo del runtime de
     * plugins SIN red: instala demo.adblock en un ModuleRegistry sin
     * broker, carga el entry (que registra onNavigate y onRequest con
     * decision block/allow), dispara ambos hooks, hace update a la
     * v2 (hook que devuelve block-v2) y un update roto que cae en
     * rollback automatico con la sesion v2 intacta. */
    private fun runPluginsDemo() {
        val registry = ModuleRegistry(null)
        val runtime = PluginRuntime(registry)
        registry.install(
            "id = demo.adblock\nversion = 1\nentry = plugin.js\npermissions = network.fetch"
        )
        val carga = runtime.load(
            "demo.adblock",
            "nia.onNavigate(function (u) { return u.indexOf('/ads/') >= 0 ? 'block' : 'allow'; });" +
                "nia.onRequest(function (u) { return u.indexOf('/tracker/') >= 0 ? 'block' : 'allow'; });"
        )
        val sesion = (carga as? Outcome.Success)?.value
        if (sesion == null) {
            downloadText.text = "plugins: la carga del modulo fallo"
            return
        }
        val rNav = sesion.fire(PluginEvent.NAVIGATE, "https://example.com/ads/banner.png")
        val banner = if (rNav is Outcome.Success) rNav.value else "error"
        val rReq = sesion.fire(PluginEvent.REQUEST, "https://example.com/tracker/t.js")
        val tracker = if (rReq is Outcome.Success) rReq.value else "error"
        val actualizado = runtime.update(
            "demo.adblock",
            "id = demo.adblock\nversion = 1\nentry = plugin-v2.js\npermissions = network.fetch",
            "nia.onRequest(function (u) { return 'block-v2'; });"
        )
        val v2 = (actualizado as? Outcome.Success)?.value
        val v2Resultado = if (v2 == null) {
            "error"
        } else {
            val r2 = v2.fire(PluginEvent.REQUEST, "https://example.com/ads/a.png")
            if (r2 is Outcome.Success) r2.value else "error"
        }
        val roto = runtime.update(
            "demo.adblock",
            "id = demo.adblock\nversion = 1\nentry = plugin-v3.js\npermissions = network.fetch",
            "throw new Error('roto');"
        )
        val rollback = if (roto is Outcome.Failure && v2 != null) {
            val r3 = v2.fire(PluginEvent.REQUEST, "https://example.com/ads/a.png")
            if (r3 is Outcome.Success) r3.value else "error"
        } else {
            "sin-rollback"
        }
        downloadText.text = "plugins: demo.adblock nav ads=" + banner +
            " request tracker=" + tracker + " - update v2 " + v2Resultado +
            " - rollback " + rollback
    }

    /** T048 v1 (R0110, RES-0059): demo de streaming del archivo
     * parcial: bombea 3 de 5 piezas del WAV sintetico, escribe el
     * prefijo legible en cacheDir y lo carga en el reproductor. */
    private fun runStreamDemo() {
        val audio = wavSintetico(200)
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("demo.wav".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(40)
        info.entries["pieces"] = Benc.BStr(piezasSha1(audio, 40))
        info.entries["length"] = Benc.BInt(200)
        val root = Benc.BDict(LinkedHashMap())
        root.entries["announce"] = Benc.BStr("http://tracker.example/announce".toByteArray(Charsets.UTF_8))
        root.entries["info"] = info
        val meta = TorrentParser.parse(Bencode.encode(root))
        val sesion = TorrentSession(meta, emptySet())
        val fuente = object : PieceSource {
            override fun piece(index: Int, length: Int): ByteArray? =
                if (index < 3) audio.copyOfRange(index * 40, index * 40 + length) else null
        }
        var bomba: PumpResult
        do {
            bomba = sesion.pump(fuente)
        } while (bomba == PumpResult.COMPLETED)
        val stream = TorrentStream(sesion, 0)
        val chunk = stream.read(0, stream.readableBytes().toInt())
        val archivo = java.io.File(cacheDir, "nia-stream-demo.wav")
        if (chunk != null) {
            archivo.writeBytes(chunk.bytes)
            media.load(MediaSource.Local("file://" + archivo.absolutePath), autoplay = true)
        }
        downloadText.text = "stream: " + meta.name + " - legibles " +
            stream.readableBytes() + "/" + stream.length +
            " - prefijo en cache para el reproductor"
    }

    /** T049 v1 (R0114, RES-0060): demo de subtitulos incluidos en el
     * torrent: cicla la seleccion entre las pistas legibles del torrent
     * sintetico y enchufa la activa al overlay de subs (los botones
     * Subs -0.5s y +0.5s siguen moviendo el desfase de la pista). */
    private fun runSubsTorrentDemo() {
        var gestor = subsTorrent
        if (gestor == null) {
            gestor = crearGestorSubsTorrent()
            subsTorrent = gestor
        }
        val nombres = gestor.nombres()
        if (nombres.isEmpty()) {
            downloadText.text = "subs torrent: sin pistas legibles"
            return
        }
        val actual = nombres.indexOf(gestor.selected() ?: "")
        val siguiente = nombres[(actual + 1) % nombres.size]
        gestor.select(siguiente)
        subs = gestor.selectedTrack()
        refreshMedia()
        downloadText.text = "subs torrent: " + nombres.size + " pistas - " + siguiente +
            " - cue 1.5s: " + (gestor.textAt(1500L) ?: "nada")
    }

    /** Torrent sintetico multi-archivo (video + srt + ass) bombeado a
     * DONE y cargado con TorrentSubtitles: los subs INCLUIDOS en el
     * torrent, nombre de pista = ruta del archivo. */
    private fun crearGestorSubsTorrent(): SubtitleManager {
        val video = ByteArray(24) { (it + 1).toByte() }
        val srt = ("1\n00:00:01,000 --> 00:00:04,000\nHello\n\n" +
            "2\n00:00:05,000 --> 00:00:06,500\nBye\n").toByteArray(Charsets.UTF_8)
        val ass = ("[Script Info]\nTitle: demo\n\n" +
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hola {\\i1}mundo{\\i0}\\Ny bienvenido\n" +
            "Dialogue: 0,0:00:05.00,0:00:06.50,Default,,0,0,0,,Adios\n").toByteArray(Charsets.UTF_8)
        val contenido = video + srt + ass
        val crudos = piezasSha1(contenido, 16)
        val hashes = ArrayList<ByteArray>()
        var i = 0
        while (i < crudos.size) {
            hashes.add(crudos.copyOfRange(i, i + 20))
            i += 20
        }
        val meta = TorrentMeta(
            "demo-subs", 16L, hashes,
            listOf(
                TorrentFile("video.bin", video.size.toLong()),
                TorrentFile("subs-en.srt", srt.size.toLong()),
                TorrentFile("subs-es.ass", ass.size.toLong())
            ),
            contenido.size.toLong(), ByteArray(20), "", emptyList()
        )
        val sesion = TorrentSession(meta, emptySet())
        val fuente = object : PieceSource {
            override fun piece(index: Int, length: Int): ByteArray =
                contenido.copyOfRange(index * 16, index * 16 + length)
        }
        var bomba: PumpResult
        do {
            bomba = sesion.pump(fuente)
        } while (bomba == PumpResult.COMPLETED)
        return TorrentSubtitles.load(sesion)
    }

    /** T050 v1: demo del proveedor OpenSubtitles SIN red (transporte
     * falso enlatado): busca por consulta e idiomas, descarga la
     * primera pista y la enchufa al overlay de subs. */
    private fun runSubsOsDemo() {
        val config = OpenSubtitlesConfig(apiKey = "demo", baseUrl = "http://subs-demo/api/v1")
        val cliente = OpenSubtitlesClient(config, FetchManager(TransporteSubsOs()))
        val hits = cliente.buscar("gran hotel", listOf("es", "en")).valueOrNull
        if (hits == null || hits.isEmpty()) {
            downloadText.text = "subs os: sin resultados"
            return
        }
        val pista = cliente.descargar(hits[0].fileId).valueOrNull
        if (pista == null) {
            downloadText.text = "subs os: descarga fallida"
            return
        }
        subs = SubtitleTrack(SubtitleParser.parse(pista.contenido))
        refreshMedia()
        downloadText.text = "subs os: " + hits.size + " resultados - " +
            pista.nombre + " (" + hits[0].idioma + ") - cue 1.5s: " +
            (subs?.textAt(1500L) ?: "nada")
    }

    private fun wavSintetico(total: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val resto = total - 8
        out.write(byteArrayOf((resto and 0xff).toByte(), ((resto shr 8) and 0xff).toByte(), ((resto shr 16) and 0xff).toByte(), ((resto shr 24) and 0xff).toByte()))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(16, 0, 0, 0))
        out.write(byteArrayOf(1, 0))
        out.write(byteArrayOf(1, 0))
        out.write(byteArrayOf(0x40, 0x1f, 0, 0))
        out.write(byteArrayOf(0x40, 0x1f, 0, 0))
        out.write(byteArrayOf(1, 0))
        out.write(byteArrayOf(8, 0))
        out.write("data".toByteArray(Charsets.US_ASCII))
        val muestras = total - 44
        out.write(byteArrayOf((muestras and 0xff).toByte(), ((muestras shr 8) and 0xff).toByte(), ((muestras shr 16) and 0xff).toByte(), ((muestras shr 24) and 0xff).toByte()))
        for (i in 0 until muestras) {
            out.write(128)
        }
        return out.toByteArray()
    }

    /** Transporte falso de la demo Subs OS: respuestas enlatadas del
     * proveedor OpenSubtitles SIN red (la app real usa HttpTransport
     * con la Api-Key del usuario; las credenciales nunca se loguean). */
    private class TransporteSubsOs : FetchTransport {
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            val cuerpo = when {
                request.url.contains("/subtitles") -> BUSQUEDA_DEMO
                request.url.contains("/download") -> DESCARGA_DEMO
                request.url.contains("gran-hotel.srt") -> SRT_DEMO
                else -> "{}"
            }
            return TransportResult.Success(
                FetchResponse(200, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
            )
        }
    }

    /** T038 v1: demo de la matriz GitHub/Gmail SIN red (transporte falso
     * enlatado): corre las acciones completas del corpus y muestra el
     * informe con el resumen de limites fechados. */
    private fun runMatrizDemo() {
        val matriz = GithubGmailMatrix(TransporteMatriz(), mediaClock)
        val builder = StringBuilder()
        for (r in matriz.correr()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(r.action.id).append(' ').append(if (r.ok) "OK" else "FALLO")
                .append(' ').append(r.detail)
        }
        builder.append('\n').append(matriz.resumen())
        AlertDialog.Builder(this)
            .setTitle("Matriz GH/Gmail")
            .setMessage(builder.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Transporte falso de la demo Matriz GH/Gmail: respuestas enlatadas
     * del corpus SIN red (el smoke con red real es T067). */
    private class TransporteMatriz : FetchTransport {
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            val cuerpo = when {
                request.url.contains("issue-42/c1") -> C1_DEMO
                request.url.contains("issue-42/c2") -> C2_DEMO
                request.url.contains("/gh/session") -> SESSION_DEMO
                request.url.contains("/gm/hilo-7") -> HILO_DEMO
                request.url.contains("/gm/search") -> BUSQUEDA_GM_DEMO
                else -> ""
            }
            return TransportResult.Success(
                FetchResponse(200, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
            )
        }
    }

    /** T039 v1: demo de la matriz YouTube SIN red (transporte falso
     * enlatado): corre las acciones completas del corpus y muestra el
     * informe con el resumen de limites fechados. */
    private fun runMatrizYtDemo() {
        val matriz = YoutubeMatrix(TransporteMatrizYt(), mediaClock)
        val builder = StringBuilder()
        for (r in matriz.correr()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(r.action.id).append(' ').append(if (r.ok) "OK" else "FALLO")
                .append(' ').append(r.detail)
        }
        builder.append('\n').append(matriz.resumen())
        AlertDialog.Builder(this)
            .setTitle("Matriz YT")
            .setMessage(builder.toString())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Transporte falso de la demo Matriz YT: respuestas enlatadas
     * del corpus SIN red (el smoke con red real es T067). */
    private class TransporteMatrizYt : FetchTransport {
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            val cuerpo = when {
                request.url.contains("/yt/search") -> YT_SEARCH_DEMO
                request.url.contains("yt/comentario-7/c1") -> YT_C1_DEMO
                request.url.contains("yt/comentario-7/c2") -> YT_C2_DEMO
                request.url.contains("/yt/session") -> YT_SESSION_DEMO
                request.url.contains("/yt/subs-7.vtt") -> YT_SUBS_DEMO
                else -> ""
            }
            return TransportResult.Success(
                FetchResponse(200, emptyMap(), cuerpo.toByteArray(Charsets.UTF_8))
            )
        }
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
        private const val BUSQUEDA_DEMO = "{\"total\":2,\"data\":[{\"id\":\"111\",\"type\":\"subtitle\",\"attributes\":{\"language\":\"es\",\"release\":\"Gran.Hotel.S01E01\",\"files\":[{\"file_id\":9001,\"cd_number\":1}]}},{\"id\":\"222\",\"type\":\"subtitle\",\"attributes\":{\"language\":\"en\",\"release\":\"Gran.Hotel.S01E01.720p\",\"files\":[{\"file_id\":9002,\"cd_number\":1}]}}]}"
        private const val DESCARGA_DEMO = "{\"link\":\"https://cdn.demo/gran-hotel.srt\",\"file_name\":\"gran-hotel.srt\"}"
        private const val SRT_DEMO = "1\n00:00:01,000 --> 00:00:04,000\nGran hotel\n"
        private const val C1_DEMO = "Ana:Funciona en mi movil"
        private const val C2_DEMO = "Luis:Revisado, merge"
        private const val SESSION_DEMO = "OK"
        private const val HILO_DEMO = "Ana:Presupuesto Q4"
        private const val BUSQUEDA_GM_DEMO = "1"
        private const val YT_SEARCH_DEMO = "2"
        private const val YT_C1_DEMO = "Ana:Gran video, gracias"
        private const val YT_C2_DEMO = "Luis:El seek funciona bien"
        private const val YT_SESSION_DEMO = "OK"
        private const val YT_SUBS_DEMO = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nGran video\n\n00:00:05.000 --> 00:00:08.000\nEl seek funciona\n\n00:00:09.000 --> 00:00:12.000\nTercera linea\n"
    }
}
