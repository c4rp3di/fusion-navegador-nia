package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import nia.contracts.ContractsVersion
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.engine.bookmarks.BookmarkManager
import nia.engine.bookmarks.MarcadoresHtml
import nia.engine.diag.Capacidades
import nia.engine.diag.Versiones
import nia.engine.download.DownloadEngine
import nia.engine.download.DownloadItem
import nia.engine.download.DownloadSink
import nia.engine.download.DownloadState
import nia.engine.download.ReopenableSink
import nia.engine.errors.ErrorPages
import nia.engine.gestures.AccionBorde
import nia.engine.gestures.AjustesGestos
import nia.engine.gestures.ConfigGestos
import nia.engine.gestures.OcultadorBarra
import nia.engine.gestures.TironRefresh
import nia.engine.history.HistoryManager
import nia.engine.inspector.EventContext
import nia.engine.inspector.InspectorSession
import nia.engine.js.CuotasJs
import nia.engine.js.JsLimits
import nia.engine.lectura.Lector
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.media.MediaEngine
import nia.engine.media.MediaException
import nia.engine.media.MediaPuente
import nia.engine.media.MediaSource
import nia.engine.media.MediaState
import nia.engine.media.OpenSubtitlesClient
import nia.engine.media.OpenSubtitlesConfig
import nia.engine.media.OpenSubtitlesResult
import nia.engine.media.OpenSubtitlesSesion
import nia.engine.media.SubtitleFormatException
import nia.engine.media.SubtitleParser
import nia.engine.media.SubtitleTrack
import nia.engine.modules.AprobacionPaquete
import nia.engine.modules.ContextoModulos
import nia.engine.modules.DiagModulos
import nia.engine.modules.EstadoClave
import nia.engine.modules.EstadoModulo
import nia.engine.modules.FichaModulo
import nia.engine.modules.GestorModulos
import nia.engine.modules.ListasModulos
import nia.engine.modules.ModuleRegistry
import nia.engine.modules.PluginNetFilter
import nia.engine.modules.PluginRuntime
import nia.engine.modules.PreviaPaquete
import nia.engine.paint.PaintOp
import nia.engine.permisos.AmbitoPermiso
import nia.engine.permisos.FilaPermiso
import nia.engine.permisos.GestorPermisos
import nia.engine.permisos.TipoPermiso
import nia.engine.print.DocumentoPaginado
import nia.engine.print.Impresor
import nia.engine.print.Orientacion
import nia.engine.print.PaginaImpresa
import nia.engine.net.FetchManager
import nia.engine.net.GestorCookies
import nia.engine.net.HttpTransport
import nia.engine.net.PanelIdentidad
import nia.engine.net.RegistroRed
import nia.engine.omnibox.OmniboxInput
import nia.engine.omnibox.OmniboxSugerencias
import nia.engine.omnibox.Sugerencia
import nia.engine.profile.BackupInfo
import nia.engine.profile.BackupsPerfil
import nia.engine.profile.FireLevel
import nia.engine.profile.ProfileKind
import nia.engine.settings.OpcionAjuste
import nia.engine.settings.SettingsManager
import nia.engine.settings.TipoOpcion
import nia.engine.tabs.TabCrear
import nia.engine.tabs.TabTarjeta
import nia.engine.tabs.TabsManager
import nia.engine.themes.NiaTokens
import nia.engine.torrent.AnnounceEvent
import nia.engine.torrent.MagnetParser
import nia.engine.torrent.PeerMetadataClient
import nia.engine.torrent.PeerPoolSource
import nia.engine.torrent.PumpResult
import nia.engine.torrent.TorrentEngine
import nia.engine.torrent.TorrentFormatException
import nia.engine.torrent.TorrentMeta
import nia.engine.torrent.TorrentParser
import nia.engine.torrent.TorrentSession
import nia.engine.torrent.TorrentState
import nia.engine.torrent.TrackerAnnounceRequest
import nia.engine.torrent.TrackerClient
import nia.engine.torrent.TrackerPeer
import nia.engine.torrent.torrentDesdeInfo
import app.nia.navegador.ui.NiaProgressBar
import app.nia.navegador.ui.NiaUi
import nia.engine.seleccion.FragmentoUrl
import nia.engine.sitios.AjustesSitio
import nia.engine.sitios.PrefSitio
import nia.engine.sitios.SitiosManager
import nia.engine.sitios.DatosSitio
import nia.engine.url.Origin
import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser
import nia.engine.workers.VisorWorkers

/**
 * BrowserActivity (T072 v2 + T073 v2 + T090 v1 + T074 v1 + T093 v4 + T075 v1 + T087 v1 + T088 v1 + T076 v1 + T086 v1 + T082 v1, RES-0091 + RES-0092, R0191): shell estetico
 * "DDG ligero" sobre el NavController verificado (T071) con las
 * pestanas del TabsManager verificado (T073 v1). El navegador pasa a
 * ser la pantalla inicial (MAIN/LAUNCHER en el manifest) y las paginas
 * se pintan con el motor propio: NavPage.html (crudo de la respuesta
 * final) alimenta PaintView con el CSS del navegador, sin re-fetch.
 *
 * Estetica con los componentes y tokens verificados (T069, ADR-021):
 * colores por NiaUi en modo AUTO (suelo claro del builtin), omnibox
 * redondeado con indicador de identidad (https accent, http warn),
 * progreso visible con NiaProgressBar mientras navega, barra inferior
 * unica (atras, adelante, recargar, stop, menu) y estado en linea
 * propia. El panel de identidad se abre como dialogo desde el menu.
 *
 * La resolucion del omnibox, el fallback DNS con confirmacion, las
 * paginas de error honestas y las sugerencias SIEMPRE locales quedan
 * como en v1 (ADR-022, T083, T084).
 *
 * T073 v2 (R0176): strip horizontal de pestanas sobre el TabsManager
 * (tocar cambia, x cierra, + abre; la activa se invierte fg/bg para
 * distinguirse), nueva incognito y reabrir desde el Menu; un perfil POR
 * TIPO (normal persistido, incognito solo en memoria: cerrar la ultima
 * borra TODO el perfil y vacia sus reabiertas).
 *
 * Usabilidad R0176: el omnibox refleja la URL final cargada, el estado
 * muestra el host en vez del objeto interno, una carga en vuelo solo
 * pinta si su pestana sigue activa y cerrar la ultima pestana abre
 * una nueva (nunca pantalla sin pestanas).
 *
 * T090 v1 (R0177): modo incognito inequivoco en el shell (banner warn
 * visible mientras la activa es privada, chip [priv] en el strip), Fire
 * de sesion y total del perfil del TIPO activo con confirmacion
 * explicita por dialogo (ADR-019), confirmacion antes de cerrar la
 * ultima incognito y traza observable por apartado para alistar las
 * pruebas: cada accion deja una linea en el dialogo Diagnostico y en
 * "adb logcat -s NIA.Shell" (NUNCA una URL: solo el host).
 *
 * T074 v1 (R0178): historial global del perfil NORMAL (HistoryManager)
 * separado del session history de cada pestana (el back/forward vive
 * en el NavController de cada pestana): cada pagina cargada en una
 * pestana NORMAL se registra SIN query ni fragmento (redaccion por
 * diseno) y el incognito JAMAS se registra (D-001); el Menu trae
 * Historial agrupado por dia (tocar abre la URL) y el Fire total del
 * perfil NORMAL tambien borra el historial (T045).
 *
 * T093 v1 (R0179) + v2 (R0181) + v3 (R0182) + v4 (R0183): ajustes modulares con el
 * SettingsManager del motor (armazon v1: registro tipificado,
 * busqueda que abre la opcion, export/import SIN secretos ni no
 * disponibles, recovery y persistencia): el Menu trae Ajustes;
 * toda opcion enlaza capacidad real (diagnostico.fifo: lineas de
 * la traza en caliente) o declara su estado de no disponible
 * (nada falsificado). v2: seccion Limpieza que ABSORBE el Fire
 * del menu (sesion y total del perfil del tipo activo,
 * confirmacion ADR-019) y el borrado del historial por rango
 * (hoy, 7 dias, 30 dias, todo): acciones de un uso que JAMAS
 * dejan valor colgado (la opcion sigue en su defecto y el
 * export nunca guarda una accion).
 *
 * v3: retencion automatica del historial, una preferencia REAL
 * que SI se guarda y se exporta (a diferencia de las acciones
 * de un uso): al arrancar y al cambiar el ajuste se borra del
 * historial lo mas viejo que la ventana elegida (7, 30 o 90
 * dias; nada conserva todo), silenciosa cuando no hay nada
 * que borrar.
 *
 * v4: limpieza dedicada del cache HTTP como accion de un
 * uso con conteo (limpiarCache del TabsManager borra SOLO el
 * cache de los perfiles vivos: cookies, storage e historial
 * viven; no es Fire) y descargas honestas NO disponibles hasta
 * el gestor de descargas del shell (T047/T085).
 *
 * T075 v1 (R0184, RES-0087): cerradas y deshacer. Aviso con
 * accion de Reabrir al cerrar (autodescarte) y lista de
 * recientes en el Menu; las incognito viven en la lista solo
 * dentro de la sesion viva: tras el wipe queda unicamente la
 * recien cerrada (reabrirla es un perfil NUEVO) y los datos
 * privados destruidos JAMAS reviven.
 *
 * T087 v1 + T088 v1 (R0187, RES-0088/RES-0089): interoperabilidad
 * Android. Menu con Compartir pagina (SEND saliente con chooser,
 * SOLO la URL final de la activa; about: no comparte) y SEND/VIEW
 * entrantes con confirmacion por dialogo sobre la resolucion REAL
 * del OmniboxInput: lo que no resuelve JAMAS abre y el incognito
 * NO se filtra; sin App Links ajenos ni peticion de rol (honesto
 * hasta el smoke T067-B en dispositivo).
 *
 * T076 v1 (R0189, RES-0090): pagina de nueva pestana con contenido
 * REAL: accesos = marcadores fijados (pin) del BookmarkManager
 * verificado (T060) con su ciclo de vida cableado por fin (archivo
 * tabulado en filesDir; un corrupto se ignora y el estado vivo
 * NUNCA toca), recientes del historial NORMAL (la mas reciente
 * primero; el incognito JAMAS llega, D-001) y gestion por dialogo
 * en el Menu (anadir con la resolucion REAL del OmniboxInput,
 * quitar sin borrar el marcador, vaciar con confirmacion ADR-019,
 * guardar la pagina actual en favoritos). Sin precarga de webs ni
 * skeleton animado; los enlaces de la pagina NUNCA navegan solos
 * (exclusion honesta hasta el smoke T067-B).
 *
 * T086 v1 (R0191, RES-0091): pantalla de descargas sobre la cola
 * REAL del DownloadEngine verificado (T046) con el transporte de la
 * app: dialogo con estados y bytes reales, cancelar con
 * confirmacion ADR-019, reanudar solo fallidas con sink reabrible
 * (Range real del motor), abrir la completada con ACTION_VIEW y
 * grant, nueva descarga por URL con la resolucion REAL del
 * OmniboxInput y destino SAF (esquema no soportado o URL invalida
 * JAMAS encolan); la pausa NO existe en el motor y se declara no
 * disponible (JAMAS un fallo disfrazado); historial en memoria de
 * sesion y notificaciones pendientes del smoke (T067-B EN ESPERA).
 *
 * T082 v1 (R0191, RES-0092): modo lectura con la heuristica DOM
 * propia del Lector del motor (nia.engine.lectura) sobre el HTML
 * REAL de la pagina activa (NavPage.html crudo, sin re-fetch):
 * pinta titulo y parrafos con los tokens del tema, avisa honesto
 * sin pagina o sin contenido principal y NUNCA ejecuta scripts
 * extraidos (solo se junta texto); volver con Recargar (exclusion
 * honesta: refetch, scroll no preservado).
 *
 * T109 v1 (R0361, RES-0146): restauracion tras proceso muerto. La
 * sesion de pestanas NORMALES (orden, activa, URL y titulo; JAMAS
 * previews, cookies ni datos de formularios) se guarda best-effort en
 * onStop en sesion-pestanas.tsv (escritura atomica tmp+rename; un
 * corrupto se descarta ENTERO y arranca about:nia) y se restaura en
 * onCreate: la activa recarga su URL real al arrancar (sin autoplay:
 * el motor solo reproduce con play explicito) y el resto recarga al
 * cambiar de pestana. El incognito JAMAS se persiste ni restaura.
 * Exclusiones v1 honestas: sin PiP, multiwindow/split, audio
 * focus/rutas/auriculares/notificacion/media session ni reproduccion
 * background (todo eso es T065), sin scroll, posicion de media ni
 * session history restaurados, y la sesion vive solo si onStop llego
 * a correr (una muerte con la activity visible pierde la anterior).
 *
 * T065 v1 (R0364, RES-0147): multimedia con audio focus REAL del
 * sistema: pedirAudioFocus al abrir el player (AudioFocusRequest en
 * API 26+, legacy debajo), LOSS pausa definitiva (sin auto-resume),
 * LOSS_TRANSIENT pausa y reanuda al GAIN, CAN_DUCK baja a 0.2 y
 * restaura su volumen previo, todo sobre el MediaEngine REAL. Ciclo
 * de vida seguro: onDestroy libera player y focus (sin reproduccion
 * ni focus huerfanos) y la pantalla apagada NO pausa el audio
 * (SurfaceView desacoplada: el video muere, el audio sigue mientras
 * el proceso viva). Exclusiones v1 honestas: sin foreground service,
 * media session ni controles del sistema/notificacion (v2+): el
 * proceso en background puede morir y el audio con el; sin
 * re-request de focus por cada play (v2) y sin PiP (T109 v2).
 *
 * T109 v2 (R0366, RES-0148): politica de media de T109: la
 * reproduccion in-page queda ATADA a su pestana (pestanaMedia): cerrar
 * esa pestana para el player (sin reproduccion huerfana) y el Fire
 * TOTAL del tipo de esa pestana tambien lo para; el Fire de sesion NO
 * toca la reproduccion (politica explicita). El player standalone
 * (menu 37) no esta atado a pestana alguna y sigue igual.
 *
 * Exclusiones v6: sin grupos ni previews pintadas, la sesion de
 * pestanas NORMALES ya se restaura tras proceso muerto (T109 v1; el
 * incognito JAMAS: vive solo en memoria), Fire solo del perfil del tipo
 * activo (absorbido por Ajustes Limpieza en v2; sin gestor
 * multi-perfil, T054/T093),
 * panel de identidad solo de la ultima pagina cargada, sin tema
 * conmutable en la UI (T070), sugerencias en dialogo (no desplegable)
 * y el pintado respeta SOLO lo que el motor CSS/layout soporta hoy
 * (paginas reales simples, honesto). El historial del shell es un
 * dialogo simple agrupado por dia (la busqueda de texto llega con un
 * subhito siguiente); el borrado por rango y el Fire viven en
 * Ajustes Limpieza (v2).
 */
class BrowserActivity : Activity() {

    private val clock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    /** T072/3.5 (R0322, RES-0130): el perfil normal vive en filesDir
     *  (hallazgo 3.5 del informe externo: Android puede PURGAR cacheDir
     *  y el perfil persistido moriria con el; TODO lo demas ya estaba en
     *  filesDir). Si queda un perfil viejo en cacheDir y filesDir no tiene
     *  ninguno, se MIGRA con rename (una sola via; si el rename falla, se
     *  empieza de cero: honesto, no se pierde nada que la purga no fuera
     *  a perder igual). El incognito vive SOLO en memoria (intacto). */
    private val perfilNormal: File by lazy {
        val nuevo = File(filesDir, "perfil-navegador")
        val viejo = File(cacheDir, "perfil-navegador")
        if (!nuevo.exists() && viejo.exists()) viejo.renameTo(nuevo)
        nuevo
    }

    private val tabs by lazy {
        TabsManager(
            clock, HttpTransport(), perfilNormal,
            filtroDePerfil = { perfil, tabId ->
                PluginNetFilter(
                    runtimeModulos, registroModulos, perfil.configModulos,
                    { _ -> contextoModulos.actual(tabId) },
                    { id, nombre -> gestorModulos.recursoSustituible(id, nombre) },
                    diagModulos,
                    // T054 v1 (R0284, RES-0122): el filtro consulta las
                    // excepciones de sitio del host antes de disparar.
                    { id, origen -> gestorModulos.estaExceptuado(id, origen) }
                )
            }
        )
    }
    /** Marcadores del motor (T060, verificado) con el ciclo de vida que
     * T076 cablea por fin: el estado vive en el BookmarkManager y
     * viaja a un archivo tabulado en filesDir; un archivo corrupto
     * se ignora (importar NUNCA toca el estado vivo). */
    private val marcadores by lazy {
        val m = BookmarkManager()
        val f = File(filesDir, "marcadores.tsv")
        if (f.exists()) {
            val r = m.importar(f.readText())
            if (r is Outcome.Failure) {
                traza("accesos", "marcadores corruptos ignorados")
            }
        }
        m
    }

    /** Historial global SOLO del perfil NORMAL (T074): el incognito
     * jamas se registra y la URL se guarda sin query (redaccion).
     * Persiste en filesDir; un archivo corrupto se ignora. */
    private val historial by lazy { HistoryManager(clock, File(filesDir, "historial-normal.tsv")) }

    /** T080 v1 (RES-0097): preferencias por sitio persistidas en
     *  sitios.tsv (clave = origen, NUNCA la URL completa). */
    private val sitios by lazy { SitiosManager(File(filesDir, "sitios.tsv")) }

    /** T112 v2 (R0379, RES-0152): backups del perfil NORMAL con el
     *  BackupsPerfil del motor (v1, verificado): SOLO areas de la
     *  lista permitida (marcadores, historial, ajustes, sitios);
     *  cookies, storage, IndexedDb y secretos JAMAS entran. */
    private val backupsPerfil by lazy {
        BackupsPerfil(
            File(filesDir, "backups-perfil"),
            setOf("marcadores", "historial", "ajustes", "sitios")
        )
    }

    /** Staging de la restauracion (T112 v2): aplicar lo que hay
     *  aqui es SIEMPRE decision explicita del usuario. */
    private val stagingCopias by lazy { File(filesDir, "backups-staging") }

    /** T089 v1 (RES-0109): broker de permisos por sitio del motor
     *  (permisos.tsv, solo decisiones permanentes): sin backend
     *  registrado NINGUNA API se anuncia habilitada. */
    private val permisos by lazy { GestorPermisos(File(filesDir, "permisos.tsv")) }

    /** T102 v1 (R0236, RES-0110): visor de workers/SW del motor. Los
     * gestores reales existen (T034) pero la navegacion todavia no
     * registra workers ni SW de pagina (T071 v1): el dialogo declara
     * el estado REAL, nada fabricado. */
    private val visorWorkers by lazy { VisorWorkers(null, null) }

    /** T094 v1 (R0226, RES-0106, ADR-005): registro y runtime de
     *  modulos del motor (T009/T029/T030) con el gestor del shell;
     *  el alcance por sitio persiste en modulos.tsv (lineas
     *  corruptas ignoradas, ADR-005). */
    private val registroModulos by lazy { ModuleRegistry() }
    private val runtimeModulos by lazy { PluginRuntime(registroModulos) }
    /** T008/C10 v2 (R0267, RES-0118): sesion del inspector del shell
     *  que recibe el diagnostico de los modulos (la redaccion en la
     *  ingesta es la del SecretRedactor del host, E4 C10). */
    private val inspectorModulos by lazy { InspectorSession(clock) }

    /** C10 v2 (R0267, RES-0118): identidad de los fires reales: la
     *  sesion del navegador, la pestana que navega y el documento
     *  acunado por navegacion (el frame es SIEMPRE main: el motor v1
     *  no tiene frame tree). */
    private val contextoModulos by lazy { ContextoModulos("shell-" + clock.nowMillis()) }

    /** C3 v1 (R0271, RES-0119): canal de diagnostico compartido por
     *  el gestor y el filtro de red del shell; los recursos
     *  sustituibles los resuelve el gestor del paquete instalado. */
    private val diagModulos by lazy { DiagModulos(inspectorModulos) }

    private val gestorModulos: GestorModulos by lazy {
        GestorModulos(
            registroModulos, runtimeModulos, File(filesDir, "modulos.tsv"), null,
            tabs.perfil(ProfileKind.NORMAL)?.configModulos, diagModulos
        )
    }

    /** C7 v1 (R0255, RES-0114): registro de listas del host por modulo
     *  (TSV en filesDir/listas-modulos con write-through best-effort);
     *  el modulo JAMAS descarga (E4): el host trae las listas con cuotas. */
    private val listasModulos by lazy {
        ListasModulos(File(filesDir, "listas-modulos"))
    }

    /** T052+T094/C9 (R0249/R0253, RES-0112/RES-0113): filtro de red del
     *  shell montado sobre el MISMO runtime y registro del gestor y
     *  construido POR PERFIL en tabs (factory): el hook onRequest de los
     *  modulos activos decide ANTES de red/cache/SW en cada pestana
     *  (documento y subrecursos) y un veredicto "block" corta la peticion
     *  con DENIED; con C9 el filtro consulta la config de modulos DEL
     *  PERFIL: un modulo con general.enabled="false" se abstiene SOLO en
     *  el perfil donde el usuario lo apago (el incognito vive su propia
     *  config en memoria). */

    /** T106 v1 (RES-0100): estado de red contextual por pestana. */
    private val red = RegistroRed()

    /** T080 v1: escala de texto del sitio actual (1.0 = 16px base). */
    private var textoSitio = 1.0

    /** Cola de descargas del navegador (T086 v1): DownloadEngine real
     * del motor (T046) sobre el transporte de la app con reintentos y
     * reanudacion; el historial de la cola vive en la sesion y las
     * notificaciones quedan para el smoke en dispositivo. */
    private val poolDescargas = Executors.newFixedThreadPool(2)
    private val descargas = DownloadEngine(PlatformDownloadTransport(), poolDescargas, maxConcurrent = 2, maxRetries = 1)

    /** T103 v1 (R0349, RES-0142): gestor de sesiones torrent del shell
     *  sobre el motor T047 verificado; pool de UN hilo (cuota CPU/red
     *  honesta de v1: UNA bajada a la vez) y peer id aleatorio por
     *  sesion de app (sin siembra: uploaded siempre 0 y sin puerto de
     *  escucha). */
    private val poolTorrents = Executors.newFixedThreadPool(1)

    /** T092 v1 (R0355, RES-0144): media del shell sobre el nucleo y el
     *  backend REALES: MediaEngine del motor (T036, autoridad de la
     *  posicion y la duracion) sobre PlatformMediaBackend
     *  (MediaPlayer de plataforma, sin codecs caseros). */
    private val backendMedia = PlatformMediaBackend(this)
    private val media = MediaEngine(backendMedia, clock)
    private var playerAbierto = false
    private var volumenMedia = 1.0
    private var pasoVelocidadMedia = 1
    private val velocidadesMedia = doubleArrayOf(0.5, 1.0, 1.5, 2.0)

    /** T065 v1 (R0364, RES-0147): audio focus REAL del sistema; nada
     *  de focus residente sin reproduccion (solo vive con el player). */
    private val gestorAudio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var listenerFocus: AudioManager.OnAudioFocusChangeListener? = null
    private var peticionFocus: AudioFocusRequest? = null
    private var pausadoPorFocus = false
    private var enDuck = false
    private var volumenPreDuck = 1.0

    /** T109 v2 (R0366, RES-0148): pestana que origino la media in-page
     *  (null si el player es standalone): cerrar la pestana o el Fire
     *  TOTAL de su tipo paran la reproduccion (politica de T109). */
    private var pestanaMedia: Long? = null

    /** T104 v1 (R0376, RES-0151): pista de subs viva (efimera),
     * overlay del player, sesion de OpenSubtitles y descargadas
     * persistentes en el almacen privado. Los secretos JAMAS se
     * loguean ni exportan (politica de T104). */
    private var pistaSubs: SubtitleTrack? = null
    private var vistaSubs: TextView? = null
    private val sesionSubsOs by lazy { OpenSubtitlesSesion(File(filesDir, "subs-os-sesion.tsv")) }
    private val descargasSubsOs by lazy { File(filesDir, "subs-os") }

    /** T092 v2 (R0358, RES-0145): puente REAL de las paginas (TK-111-1). */
    private val puenteMediaPagina = PuenteMediaPagina()
    private val torrents = TorrentEngine()
    private val peerIdTorrent = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
    private var guardarTorrentPendiente: Pair<String, Int>? = null

    /** T072 (R0323, RES-0131): pool acotado para las navegaciones del
     *  shell (resto del hallazgo 3.1 del informe externo: un Thread
     *  nuevo por navegacion, SIN tope). Dos hilos: la politica del
     *  TabsManager navega SOLO la pestana activa y dos dan holgura
     *  sin crecer JAMAS con las navegaciones. El Stop y cerrar siguen
     *  cancelando de verdad por generacion (T011 v5). */
    private val poolNavegacion = Executors.newFixedThreadPool(2)
    private var descargaPendienteUrl: String? = null
    private val urisDescargas = HashMap<Long, Uri>()

    /** Ajustes del navegador (T093 v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 + v10): armazon del SettingsManager
     * del motor con busqueda, export/import sin secretos y
     * persistencia; cada opcion enlaza capacidad real o declara su
     * estado de no disponible (JAMAS se falsifica). */
    private val ajustes by lazy {
        val s = SettingsManager(File(filesDir, "ajustes.tsv"))
        s.registrar(
            OpcionAjuste(
                "diagnostico.fifo", "Diagnostico",
                "Lineas de traza",
                "Cuantas lineas guarda el dialogo Diagnostico (10 a 200)",
                TipoOpcion.ENTERO, "40", 10L, 200L
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.historial", "Limpieza",
                "Borrar historial",
                "Borra el rango elegido del historial de pestanas normales (hoy, 7 dias, 30 dias o todo) tras confirmar; la opcion vuelve a nada",
                TipoOpcion.ELECCION, "nada",
                elecciones = listOf("nada", "hoy", "7-dias", "30-dias", "todo")
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.fire-sesion", "Limpieza",
                "Fire de sesion",
                "Borra el sessionStorage del perfil del tipo de pestana activa tras confirmar (ADR-019)",
                TipoOpcion.ELECCION, "conservar",
                elecciones = listOf("conservar", "ejecutar")
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.fire-total", "Limpieza",
                "Fire total",
                "Borra TODO el estado del perfil del tipo activo tras confirmar (ADR-019); el historial muere si el tipo es normal",
                TipoOpcion.ELECCION, "conservar",
                elecciones = listOf("conservar", "ejecutar")
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.retencion", "Limpieza",
                "Retencion del historial",
                "Al arrancar y al cambiar este ajuste se borra del historial lo mas viejo que la ventana (7, 30 o 90 dias; nada conserva todo)",
                TipoOpcion.ELECCION, "nada",
                elecciones = listOf("nada", "7-dias", "30-dias", "90-dias")
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.cache", "Limpieza",
                "Borrar cache",
                "Borra SOLO el cache HTTP de los perfiles vivos con conteo (cookies, storage e historial viven; no es Fire)",
                TipoOpcion.ELECCION, "conservar",
                elecciones = listOf("conservar", "ejecutar")
            )
        )
        s.registrar(
            OpcionAjuste(
                "limpieza.descargas", "Limpieza",
                "Limpiar descargas",
                "Retira las terminadas de la cola con conteo",
                TipoOpcion.ELECCION, "conservar", elecciones = listOf("conservar", "ejecutar")
            )
        )
        s.registrar(
            OpcionAjuste(
                "apariencia.tema", "Apariencia",
                "Tema de colores",
                "Claro, oscuro o seguir al sistema",
                TipoOpcion.ELECCION, "auto", elecciones = listOf("auto", "claro", "oscuro")
            )
        )
        // T093 v10 (R0343, RES-0139): buscador del omnibox REAL sobre la
        // base inyectable del OmniboxInput (ADR-022 intacto: las reglas de
        // resolucion NO cambian, solo la base de busqueda elegida).
        s.registrar(
            OpcionAjuste(
                "buscador.predeterminado", "Busqueda",
                "Buscador del omnibox",
                "Motor de la busqueda del omnibox y del fallback de DNS (duckduckgo o wikipedia)",
                TipoOpcion.ELECCION, "duckduckgo", elecciones = listOf("duckduckgo", "wikipedia")
            )
        )
        s.registrar(
            OpcionAjuste(
                "privacidad.https-only", "Privacidad",
                "HTTPS-only",
                "Bloquear el http salvo excepciones del panel",
                TipoOpcion.BOOL, "false", disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "js.cuotas.pasos", "JS",
                "Cuota de pasos",
                "Sentencias/expresiones por evaluacion; tope duro NO eliminable",
                TipoOpcion.ENTERO, "100000000", 1000000L, 1000000000L
            )
        )
        s.registrar(
            OpcionAjuste(
                "js.cuotas.allocs", "JS",
                "Cuota de allocs",
                "Objetos/arrays por evaluacion; NO son bytes de heap reales",
                TipoOpcion.ENTERO, "10000000", 100000L, 100000000L
            )
        )
        // T093 v5 (R0329, RES-0133): seccion Contenido; los defectos
        // globales SOLO mandan donde el sitio NO tiene preferencia.
        s.registrar(
            OpcionAjuste(
                "contenido.texto", "Contenido",
                "Texto por defecto",
                "Escala base (75 a 150 %) cuando el sitio no tiene preferencia",
                TipoOpcion.ENTERO, "100", 75L, 150L
            )
        )
        s.registrar(
            OpcionAjuste(
                "contenido.escritorio", "Contenido",
                "Escritorio por defecto",
                "Viewport 720 px cuando el sitio no tiene preferencia",
                TipoOpcion.BOOL, "false"
            )
        )
        s.registrar(
            OpcionAjuste(
                "contenido.sitios", "Contenido",
                "Borrar preferencias de sitios",
                "Borra zoom, texto y escritorio de todos los sitios con conteo",
                TipoOpcion.ELECCION, "nada", elecciones = listOf("nada", "ejecutar")
            )
        )
        // T093 v8 (R0338, RES-0137): seccion Red - la busqueda al
        // fallar el DNS es REAL (cablea el dialogo del manejaFallo);
        // DNT y limite del cache son honestas: el motor aun no las trae.
        s.registrar(
            OpcionAjuste(
                "red.busqueda-dns", "Red",
                "Busqueda al fallar el DNS",
                "Proponer buscar el texto en DuckDuckGo cuando un dominio implicito no resuelve; apagada va directa a la pagina de error",
                TipoOpcion.BOOL, "true"
            )
        )
        s.registrar(
            OpcionAjuste(
                "red.dnt", "Red",
                "Do Not Track (DNT)",
                "El motor aun no envia cabeceras DNT en sus peticiones; llega con el proximo hito de fetch",
                TipoOpcion.BOOL, "false", disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "red.cache-limite", "Red",
                "Limite del cache HTTP",
                "El cache vive con presupuesto LRU fijo del motor (T026 v3); ajustar su tamano llega con una version siguiente",
                TipoOpcion.TEXTO, "fijo", disponible = false
            )
        )
        // T093 v9 (R0340, RES-0138): secciones Media y Perfiles
        // honestas: T092 v1 anade el player standalone (menu 37) sobre
        // el backend REAL de plataforma; el motor de media (T036) sigue
        // SIN integrarse en las paginas del navegador (puente
        // HTMLMediaElement = TK-111-1 en v2) y el gestor de perfiles
        // multiples requiere aceptacion especifica de aislamiento (T112).
        s.registrar(
            OpcionAjuste(
                "media.autoplay", "Media",
                "Reproduccion automatica",
                "El MediaEngine del motor (T036) no se integra en las paginas del navegador (player standalone en el menu desde T092 v1); sin autoplay que regular",
                TipoOpcion.BOOL, "true", disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "media.subtitulos", "Media",
                "Subtitulos",
                "Los subtitulos WebVTT/SRT viven en el MediaEngine del motor (T036 v2); su UI en el navegador llega con T104",
                TipoOpcion.BOOL, "false", disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "perfiles.gestor", "Perfiles",
                "Gestor de perfiles",
                "Multiples perfiles con aislamiento llegan con T112 y requieren aceptacion especifica; hoy hay perfil normal persistido e incognito en memoria",
                TipoOpcion.BOOL, "false", disponible = false
            )
        )
        // T112 v2 (R0379, RES-0152): seccion Copias sobre el
        // BackupsPerfil del motor (v1 verificado): crear y gestionar
        // son acciones de un uso (patron Limpieza) y JAMAS viajan
        // cookies, storage, IndexedDb ni secretos.
        s.registrar(
            OpcionAjuste(
                "copias.crear", "Copias",
                "Crear copia del perfil",
                "Copia de marcadores, historial, ajustes y sitios (sin secretos)",
                TipoOpcion.ELECCION, "nada", elecciones = listOf("nada", "ejecutar")
            )
        )
        s.registrar(
            OpcionAjuste(
                "copias.gestionar", "Copias",
                "Copias guardadas",
                "Lista las copias validas: restaurar o borrar",
                TipoOpcion.ELECCION, "nada", elecciones = listOf("nada", "ejecutar")
            )
        )
        // T078 v1 (R0199, RES-0094): la seccion Gestos del motor; los
        // ids, los rangos y los defectos viven en AjustesGestos.
        AjustesGestos.registrar(s)
        s.recargar()
        s
    }

    private val colores by lazy { NiaUi.colores(this, modoTema()) }

    /** T093 v6 (R0332, RES-0134): modo de tema del ajuste REAL
     *  apariencia.tema (auto/claro/oscuro); el defecto auto sigue
     *  al sistema como siempre (T069, ADR-021). */
    private fun modoTema(): NiaTokens.Modo = when (ajustes.valor("apariencia.tema") ?: "auto") {
        "claro" -> NiaTokens.Modo.CLARO
        "oscuro" -> NiaTokens.Modo.OSCURO
        else -> NiaTokens.Modo.AUTO
    }

    /** T093 v10 (R0343, RES-0139): base de busqueda REAL del ajuste
     *  buscador.predeterminado; el defecto sigue siendo DuckDuckGo HTML
     *  sin JS (ADR-022) y la resolucion del omnibox NO cambia. */
    private fun baseBusqueda(): String =
        if (ajustes.valor("buscador.predeterminado") == "wikipedia") {
            "https://es.wikipedia.org/w/index.php?search="
        } else {
            OmniboxInput.BASE_BUSQUEDA_DEFECTO
        }

    private lateinit var omnibox: EditText
    private lateinit var indicador: TextView
    private lateinit var estado: TextView
    private lateinit var progreso: NiaProgressBar
    private lateinit var pagina: PaintView
    private lateinit var scroll: ScrollTiron
    private lateinit var filaOmnibox: LinearLayout
    /** T077 v1 (R0195, RES-0093): la barra de URL se oculta al bajar y
     * reaparece al subir o al llegar al borde superior (histeresis de
     * la logica del motor, sin parpadeo con micro-scrolls). */
    private val ocultadorBarra = OcultadorBarra()

    /** T078 v1 (R0199, RES-0094): cfg de gestos VIVA reconstruida
     * desde Ajustes (aplicarGestos) y tiron de pull-to-refresh del
     * motor alimentado por el ScrollTiron. */
    private var cfgGestos = ConfigGestos()
    private var tiron = TironRefresh()
    private lateinit var filaPestanas: LinearLayout
    private var panelIdentidad: String = ""

    /** Traza del shell por apartado (T090): ultimas 40 lineas visibles
     * en el dialogo Diagnostico y en logcat (tag NIA.Shell). */
    private val traza = ArrayList<String>()

    /** Banner inequivoco de modo incognito (T090). */
    private lateinit var bannerIncognito: TextView
    private lateinit var avisoCierre: LinearLayout
    private lateinit var avisoTexto: TextView

    /** T081 v1 (R0204, RES-0096): barra temporal de buscar en pagina. */
    private lateinit var barraBusqueda: LinearLayout
    private lateinit var campoBusqueda: EditText
    private lateinit var contadorBusqueda: TextView
    private val esconderAviso = Runnable { avisoCierre.visibility = LinearLayout.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // T094 v5 (R0296, RES-0124): persistencia de los contadores del
        // filtro (TSV en filesDir; el guardado es best-effort en onPause,
        // sin I/O al bloquear; lineas corruptas ignoradas al cargar).
        PluginNetFilter.persistirContadoresEn(File(filesDir, "filtro-contadores.tsv"))
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        raiz.setBackgroundColor(NiaUi.color(colores.bg))

        // T109 v1 (R0361, RES-0146): restaura la sesion NORMAL si
        // existe; sin sesion (o corrupta) arranca una unica about:nia.
        restaurarSesion()

        val espacio = NiaUi.dp(this, NiaTokens.ESPACIADO[3])

        filaOmnibox = LinearLayout(this)
        filaOmnibox.orientation = LinearLayout.HORIZONTAL
        filaOmnibox.setPadding(espacio, espacio, espacio, 0)

        indicador = TextView(this)
        indicador.text = "?"
        indicador.textSize = NiaTokens.TIPOGRAFIA[4].sp.toFloat()
        indicador.setTextColor(NiaUi.color(colores.fg))
        val lpIndicador = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lpIndicador.gravity = Gravity.CENTER_VERTICAL
        lpIndicador.rightMargin = espacio
        filaOmnibox.addView(indicador, lpIndicador)

        omnibox = EditText(this)
        omnibox.hint = "Busca o escribe una URL (R0191)"
        val fondoOmnibox = GradientDrawable()
        fondoOmnibox.setColor(NiaUi.color(colores.surface))
        fondoOmnibox.cornerRadius = NiaUi.dp(this, NiaTokens.RADIOS[4]).toFloat()
        omnibox.background = fondoOmnibox
        omnibox.setTextColor(NiaUi.color(colores.fg))
        omnibox.setHintTextColor(NiaUi.color(colores.fg))
        omnibox.setInputType(EditorInfo.TYPE_CLASS_TEXT)
        omnibox.imeOptions = EditorInfo.IME_ACTION_GO
        omnibox.setPadding(espacio, espacio / 2, espacio, espacio / 2)
        omnibox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                resolverYnavegar()
                true
            } else {
                false
            }
        }
        filaOmnibox.addView(
            omnibox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        raiz.addView(filaOmnibox)

        progreso = NiaProgressBar(this, colores)
        raiz.addView(
            progreso,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        estado = TextView(this)
        estado.text = "listo (R0191)"
        descargas.addListener { item ->
            runOnUiThread {
                estado.text = "descarga #" + item.id + ": " + item.state.name.lowercase() +
                    " " + item.bytesDownloaded + " bytes"
            }
        }
        estado.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        estado.setTextColor(NiaUi.color(colores.fg))
        estado.setPadding(espacio, 0, espacio, 0)
        raiz.addView(estado)

        bannerIncognito = TextView(this)
        bannerIncognito.text = "MODO INCOGNITO: este perfil vive solo en memoria y se borra al cerrar la ultima pestana"
        bannerIncognito.textSize = NiaTokens.TIPOGRAFIA[1].sp.toFloat()
        bannerIncognito.setTextColor(NiaUi.color(colores.bg))
        bannerIncognito.setBackgroundColor(NiaUi.color(colores.warn))
        bannerIncognito.setPadding(espacio, espacio / 2, espacio, espacio / 2)
        bannerIncognito.visibility = TextView.GONE
        raiz.addView(bannerIncognito)

        val scrollPestanas = HorizontalScrollView(this)
        scrollPestanas.setBackgroundColor(NiaUi.color(colores.surface))
        scrollPestanas.setFillViewport(true)
        filaPestanas = LinearLayout(this)
        filaPestanas.orientation = LinearLayout.HORIZONTAL
        filaPestanas.setPadding(espacio / 2, espacio / 2, espacio / 2, espacio / 2)
        scrollPestanas.addView(
            filaPestanas,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        raiz.addView(
            scrollPestanas,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        pagina = PaintView(this)
        pagina.loadPagina(paginaNueva(), cssNavegador(), true)
        scroll = ScrollTiron(this)
        scroll.setBackgroundColor(NiaUi.color(colores.bg))
        scroll.addView(
            pagina,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // T077 v1 (R0195, RES-0093): gestos base cableados a capacidades
        // REALES del shell. El swipe de borde decide en el MOTOR
        // (RastreadorBorde del PaintView) y ejecuta el mismo back/forward
        // de la barra (rutas equivalentes); la barra de URL se oculta con
        // el scroll (OcultadorBarra del motor); el long-press resuelve el
        // contenido con el hitTest del motor y abre el menu propio;
        // pull-to-refresh OFF por defecto (PoliticaPullRefresh, T078 lo
        // configurara). Nada de esto roba gestos del sistema: el borde
        // solo nace en banda propia y el vertical manda siempre.
        pagina.setOnGestoBorde { accion ->
            when (accion) {
                AccionBorde.ATRAS -> {
                    val nc = navActual()
                    if (nc == null || !nc.canGoBack()) {
                        estado.text = "gesto atras: sin historial"
                    } else {
                        estado.text = "gesto atras"
                        navegarOutcome { n -> n.back() }
                    }
                }
                AccionBorde.ADELANTE -> {
                    val nc = navActual()
                    if (nc == null || !nc.canGoForward()) {
                        estado.text = "gesto adelante: sin historial"
                    } else {
                        estado.text = "gesto adelante"
                        navegarOutcome { n -> n.forward() }
                    }
                }
                else -> {}
            }
        }
        pagina.setOnToqueLargo { info -> mostrarMenuToque(info) }
        pagina.setOnSeleccionMenu { texto -> mostrarMenuSeleccion(texto) }
        scroll.setOnScrollChangeListener { _, _, nuevaY, _, viejaY ->
            // T078 v1: con la barra fija (gestos.barra off) el
            // ocultador se ignora y la barra queda SIEMPRE visible.
            val mostrar = if (cfgGestos.barra) {
                ocultadorBarra.scroll((nuevaY - viejaY).toDouble(), nuevaY <= 0)
            } else {
                true
            }
            filaOmnibox.visibility = if (mostrar) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        // T078 v1 (R0199, RES-0094): el tiron de pull-to-refresh se
        // decide en el MOTOR (TironRefresh con umbral y tiempo de
        // Ajustes); el ScrollTiron observa el sobrescroll REAL del
        // borde superior y al soltar se recarga por la MISMA ruta del
        // boton (rutas equivalentes). OFF por defecto.
        scroll.onTiron = { dy ->
            tiron.mover(dy, System.currentTimeMillis())
        }
        scroll.onSoltar = {
            if (tiron.soltar(System.currentTimeMillis())) {
                estado.text = "tiron: recargando"
                traza("gestos", "tiron: recarga por pull-to-refresh")
                recargar()
            }
        }

        // T078 v1: aplica la cfg PERSISTIDA de gestos desde el arranque.
        aplicarGestos()

        // T075 v1 (R0184): aviso de deshacer al cerrar una pestana.
        avisoCierre = LinearLayout(this)
        avisoCierre.orientation = LinearLayout.HORIZONTAL
        avisoCierre.setBackgroundColor(NiaUi.color(colores.accent))
        avisoTexto = TextView(this)
        avisoTexto.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        avisoTexto.setTextColor(NiaUi.color(colores.bg))
        avisoTexto.setPadding(espacio, espacio / 2, espacio, espacio / 2)
        avisoCierre.addView(
            avisoTexto,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        avisoCierre.addView(boton("Reabrir") {
            avisoCierre.removeCallbacks(esconderAviso)
            avisoCierre.visibility = LinearLayout.GONE
            reabrirPestana()
        })
        avisoCierre.visibility = LinearLayout.GONE
        raiz.addView(avisoCierre)

        // T081 v1 (R0204, RES-0096): barra TEMPORAL de buscar en la
        // pagina: vive justo encima de la barra inferior, GONE por
        // defecto. La consulta es LOCAL y sincrona sobre los
        // fragmentos del motor: cada cambio de texto REEMPLAZA la
        // consulta anterior (nunca queda una consulta vieja activa).
        barraBusqueda = LinearLayout(this)
        barraBusqueda.orientation = LinearLayout.HORIZONTAL
        barraBusqueda.setBackgroundColor(NiaUi.color(colores.surface))
        campoBusqueda = EditText(this)
        campoBusqueda.hint = "buscar en la pagina"
        campoBusqueda.setSingleLine()
        campoBusqueda.imeOptions = EditorInfo.IME_ACTION_SEARCH
        campoBusqueda.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        campoBusqueda.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                ejecutarBusqueda()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        barraBusqueda.addView(
            campoBusqueda,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        contadorBusqueda = TextView(this)
        contadorBusqueda.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        contadorBusqueda.setTextColor(NiaUi.color(colores.fg))
        contadorBusqueda.setPadding(espacio, 0, 0, 0)
        barraBusqueda.addView(contadorBusqueda)
        barraBusqueda.addView(boton("Ant") { irAlMatch(-1) })
        barraBusqueda.addView(boton("Sig") { irAlMatch(1) })
        barraBusqueda.addView(boton("x") { cerrarBuscarEnPagina() })
        barraBusqueda.visibility = LinearLayout.GONE
        raiz.addView(barraBusqueda)

        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        // R0172 (smoke): fondo propio para que la barra se vea
        // aunque tema y fondo se confundan.
        barra.setBackgroundColor(NiaUi.color(colores.surface))
        barra.addView(boton("Atras") {
            val nc = navActual()
            if (nc == null || !nc.canGoBack()) {
                estado.text = "sin historial para atras"
            } else {
                navegarOutcome { n -> n.back() }
            }
        })
        barra.addView(boton("Adelante") {
            val nc = navActual()
            if (nc == null || !nc.canGoForward()) {
                estado.text = "sin historial para adelante"
            } else {
                navegarOutcome { n -> n.forward() }
            }
        })
        barra.addView(boton("Recargar") { recargar() })
        barra.addView(boton("Stop") {
            navActual()?.invalidateGeneration()
            progreso.setFraccion(0f)
            estado.text = "navegacion cancelada por generacion"
        })
        barra.addView(boton("Menu") { mostrarMenu() })
        raiz.addView(barra)

        setContentView(raiz)
        traza("shell", "arranque R0191 con " + tabs.contar() + " pestana(s)")
        aplicarRetencion()
        pintarStrip()
        // T109 v1 (R0361, RES-0146): la activa restaurada recarga su URL
        // REAL al arrancar (sin autoplay: el motor solo reproduce con
        // play explicito); un intent externo pendiente pide confirmacion
        // encima y manda al aceptar.
        val idRestaurada = tabs.activa()
        val urlRestaurada = idRestaurada?.let { tabs.tarjeta(it)?.url }
        if (urlRestaurada != null && (urlRestaurada.startsWith("http://") || urlRestaurada.startsWith("https://"))) {
            estado.text = "sesion restaurada: recargando"
            navegar(urlRestaurada, false, null)
        } else if (tabs.contar() > 1) {
            estado.text = "sesion restaurada"
        }
        manejarIntentExterno(intent)
    }

    /** Strip de pestanas: una tarjeta por pestana viva y el boton + al final. */
    private fun pintarStrip() {
        filaPestanas.removeAllViews()
        for (tarjeta in tabs.tarjetas()) {
            filaPestanas.addView(chipDe(tarjeta))
        }
        filaPestanas.addView(botonMas())
        pintarModo()
    }

    /** Tarjeta del strip: tocar cambia de pestana, x cierra; la activa se
     * invierte (fondo fg, texto bg) para distinguirse sin dudas. */
    private fun chipDe(t: TabTarjeta): LinearLayout {
        val espacio = NiaUi.dp(this, NiaTokens.ESPACIADO[2])
        val activa = t.id == tabs.activa()
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.setPadding(espacio, espacio / 2, espacio, espacio / 2)
        chip.setMinimumHeight(NiaUi.dp(this, 44))
        val fondo = GradientDrawable()
        fondo.setColor(NiaUi.color(if (activa) colores.fg else colores.surface))
        fondo.cornerRadius = NiaUi.dp(this, NiaTokens.RADIOS[3]).toFloat()
        chip.background = fondo
        val titulo = TextView(this)
        titulo.text = etiquetaDe(t)
        titulo.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        titulo.setTextColor(NiaUi.color(if (activa) colores.bg else colores.fg))
        chip.addView(
            titulo,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val cerrar = TextView(this)
        cerrar.text = "x"
        cerrar.textSize = NiaTokens.TIPOGRAFIA[3].sp.toFloat()
        cerrar.setPadding(espacio, 0, espacio, 0)
        cerrar.setTextColor(NiaUi.color(if (activa) colores.bg else colores.fg))
        cerrar.setOnClickListener { cerrarPestana(t.id) }
        chip.addView(
            cerrar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        chip.setOnClickListener { cambiarPestana(t.id) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = NiaUi.dp(this, NiaTokens.ESPACIADO[2])
        chip.layoutParams = lp
        return chip
    }

    /** Boton + del strip: nueva pestana normal. */
    private fun botonMas(): TextView {
        val mas = TextView(this)
        mas.text = "+"
        mas.textSize = NiaTokens.TIPOGRAFIA[3].sp.toFloat()
        mas.setGravity(Gravity.CENTER)
        mas.setMinimumHeight(NiaUi.dp(this, 44))
        mas.setMinimumWidth(NiaUi.dp(this, 44))
        mas.setPadding(NiaUi.dp(this, NiaTokens.ESPACIADO[2]), 0, NiaUi.dp(this, NiaTokens.ESPACIADO[2]), 0)
        val fondo = GradientDrawable()
        fondo.setColor(NiaUi.color(colores.surface))
        fondo.cornerRadius = NiaUi.dp(this, NiaTokens.RADIOS[3]).toFloat()
        mas.background = fondo
        mas.setTextColor(NiaUi.color(colores.accent))
        mas.setOnClickListener { nuevaPestana(ProfileKind.NORMAL) }
        return mas
    }

    private fun etiquetaDe(t: TabTarjeta): String {
        val titulo = if (t.title.length > 16) t.title.substring(0, 15) + "..." else t.title
        return if (t.kind == ProfileKind.INCOGNITO) "[priv] " + titulo else titulo
    }

    private fun boton(etiqueta: String, alPulsar: () -> Unit): Button {
        val b = Button(this)
        b.text = etiqueta
        b.setTextColor(NiaUi.color(colores.fg))
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        b.setOnClickListener { alPulsar() }
        return b
    }

    private fun resolverYnavegar() {
        val texto = omnibox.text.toString()
        when (val r = OmniboxInput.resolver(texto, baseBusqueda())) {
            is OmniboxInput.Resolucion.Vacio -> estado.text = "omnibox vacio"
            is OmniboxInput.Resolucion.EsquemaNoSoportado ->
                estado.text = "esquema no soportado: " + r.esquema
            is OmniboxInput.Resolucion.ErrorDeParseo ->
                estado.text = "URL invalida: " + r.motivo
            is OmniboxInput.Resolucion.Buscar -> navegar(r.url, false, null)
            is OmniboxInput.Resolucion.Navegar -> navegar(r.url, r.dominioImplicito, texto)
        }
    }

    private fun recargar() {
        val nc = navActual()
        val actual = nc?.currentPage()?.url
        if (actual == null) {
            estado.text = "sin pagina actual"
        } else if (actual.startsWith("about:")) {
            pintar(paginaNueva())
        } else {
            navegar(actual, false, null)
        }
    }

    private fun navegar(url: String, dominioImplicito: Boolean, textoOriginal: String?) {
        val id = tabs.activa()
        val nc = navActual()
        if (nc == null) {
            estado.text = "sin pestana activa"
            return
        }
        nc.invalidateGeneration()
        nc.cuotasScript = CuotasJs.temporal(reintentosCuotas, cuotasResueltas())
        nc.puenteMedia = puenteMediaPagina
        resumenModulos = ""
        progreso.setFraccion(0.2f)
        traza("nav", "navegar " + hostDe(url))
        val ctxModulo = if (id == null) null else contextoModulos.nuevaNavegacion(id)
        poolNavegacion.execute {
            val inicio = clock.nowMillis()
            val o = nc.navigate(url)
            scriptsInterrumpidos = contarScriptsInterrumpidos(o)
            if (scriptsInterrumpidos == 0) reintentosCuotas = 0
            dispararModulos(o, ctxModulo)
            val latencia = clock.nowMillis() - inicio
            runOnUiThread {
                progreso.setFraccion(0f)
                when (o) {
                    is NavOutcome.Loaded -> id?.let { red.registrar(it, o.page.url, "http", latencia, true, null, "", clock.nowMillis()) }
                    is NavOutcome.Failed -> id?.let { red.registrar(it, o.page?.url ?: url, "http", latencia, false, o.code.name, o.message, clock.nowMillis()) }
                }
                refrescaTarjeta(id, o)
                if (tabs.activa() == id) {
                    when (o) {
                        is NavOutcome.Loaded -> pintaPagina(o)
                        is NavOutcome.Failed -> manejaFallo(o, dominioImplicito, textoOriginal)
                    }
                    estado.append(" | red " + latencia + "ms")
                    if (resumenModulos.isNotEmpty()) estado.append(" | " + resumenModulos)
                }
            }
        }
    }

    private fun navegarOutcome(accion: (NavController) -> NavOutcome?) {
        val id = tabs.activa()
        val nc = navActual()
        if (nc == null) {
            estado.text = "sin pestana activa"
            return
        }
        nc.invalidateGeneration()
        nc.cuotasScript = CuotasJs.temporal(reintentosCuotas, cuotasResueltas())
        nc.puenteMedia = puenteMediaPagina
        resumenModulos = ""
        progreso.setFraccion(0.2f)
        traza("nav", "back/forward")
        val ctxModulo = if (id == null) null else contextoModulos.nuevaNavegacion(id)
        poolNavegacion.execute {
            val inicio = clock.nowMillis()
            val o = accion(nc)
            scriptsInterrumpidos = contarScriptsInterrumpidos(o)
            dispararModulos(o, ctxModulo)
            if (scriptsInterrumpidos == 0) reintentosCuotas = 0
            val latencia = clock.nowMillis() - inicio
            runOnUiThread {
                progreso.setFraccion(0f)
                if (o == null) {
                    estado.text = "sin resultado de navegacion"
                    return@runOnUiThread
                }
                when (o) {
                    is NavOutcome.Loaded -> id?.let { red.registrar(it, o.page.url, "http", latencia, true, null, "", clock.nowMillis()) }
                    is NavOutcome.Failed -> id?.let { red.registrar(it, o.page?.url ?: "", "http", latencia, false, o.code.name, o.message, clock.nowMillis()) }
                }
                refrescaTarjeta(id, o)
                if (tabs.activa() == id) {
                    when (o) {
                        is NavOutcome.Loaded -> pintaPagina(o)
                        is NavOutcome.Failed -> manejaFallo(o, false, null)
                    }
                    estado.append(" | red " + latencia + "ms")
                    if (resumenModulos.isNotEmpty()) estado.append(" | " + resumenModulos)
                }
            }
        }
    }

    private fun manejaFallo(f: NavOutcome.Failed, dominioImplicito: Boolean, textoOriginal: String?) {
        traza("nav", "fallo " + f.code)
        if (dominioImplicito && textoOriginal != null &&
            ajustes.valor("red.busqueda-dns") != "false" &&
            f.code == ErrorCode.NETWORK && f.message.contains("DNS")
        ) {
            val url = baseBusqueda() + OmniboxInput.codificarQuery(textoOriginal)
            AlertDialog.Builder(this)
                .setTitle("Dominio no encontrado")
                .setMessage("La navegacion fallo de DNS. Buscar el texto en el buscador?")
                .setPositiveButton("Buscar") { _, _ ->
                    omnibox.setText(url)
                    navegar(url, false, null)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val error = ErrorPages.desdeFallo(f.code, f.message, f.page?.url)
        if (error == null) {
            estado.text = "navegacion cancelada"
        } else {
            var linea = error.titulo + ": " + error.mensaje + " - " + error.detalleRedactado
            val u = error.urlRedactada
            if (u != null) {
                linea += " - " + u
            }
            estado.text = linea
            pintar(
                "<html><body><h1>" + textoHtml(error.titulo) + "</h1><p>" +
                    textoHtml(error.mensaje) + "</p><p>" + textoHtml(error.detalleRedactado) + "</p></body></html>"
            )
        }
    }

    private fun mostrarMenu() {
        val opciones = arrayOf(
            "Nueva pestana",
            "Nueva pestana incognito",
            "Reabrir pestana",
            "Cerradas (recientes)",
            "Sugerencias",
            "Identidad de la pagina",
            "Compartir pagina",
            "Descargas (cola del motor)",
            "Modo lectura",
            "Accesos de la pagina nueva",
            "Historial (solo pestanas normales)",
            "Ajustes (busca y cambia)",
            "Diagnostico (traza del shell)",
            "Demos del motor",
            "Buscar en la pagina",
            "Zoom + (acercar)",
            "Zoom - (alejar)",
            "Zoom 100%",
            "Texto + (agrandar)",
            "Texto - (reducir)",
            "Escritorio (viewport 720)",
            "Restaurar sitio",
            "Datos del sitio",
            "Cookies",
            "Red (pestana actual)",
            "Versiones",
            "Importar marcadores (HTML)",
            "Exportar marcadores (HTML)",
            "Capacidades",
            "Cuotas JS",
            "Modulos",
            "Imprimir / PDF",
            "Permisos por sitio",
            "Workers / SW",
            "DevTools (paneles declarados)",
            "Torrents (sesiones del motor)",
            "Reproducir media (URL o archivo)"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> nuevaPestana(ProfileKind.NORMAL)
                    1 -> nuevaPestana(ProfileKind.INCOGNITO)
                    2 -> reabrirPestana()
                    3 -> mostrarCerradas()
                    4 -> mostrarSugerencias()
                    5 -> mostrarIdentidad()
                    6 -> compartirPagina()
                    7 -> mostrarDescargas()
                    8 -> modoLectura()
                    9 -> mostrarAccesos()
                    10 -> mostrarHistorial()
                    11 -> mostrarAjustes()
                    12 -> mostrarDiagnostico()
                    13 -> startActivity(Intent(this, MainActivity::class.java))
                    14 -> abrirBuscarEnPagina()
                    15 -> zoomMas()
                    16 -> zoomMenos()
                    17 -> {
                        pagina.fijarZoom(1.0)
                        guardarZoomSitio()
                        estado.text = "zoom 100%"
                    }
                    18 -> textoMas()
                    19 -> textoMenos()
                    20 -> alternarEscritorio()
                    21 -> restaurarSitio()
                    22 -> mostrarDatosSitio()
                    23 -> gestorCookies()
                    24 -> mostrarRed()
                    25 -> mostrarVersiones()
                    26 -> importarMarcadoresHtml()
                    27 -> exportarMarcadoresHtml()
                    28 -> mostrarCapacidades()
                    29 -> mostrarCuotasJs()
                    30 -> mostrarModulos()
                    31 -> imprimirPagina()
                    32 -> mostrarPermisos()
                    33 -> mostrarWorkersSw()
                    34 -> abrirDevToolsModulos()
                    35 -> mostrarTorrents()
                    36 -> reproducirMedia()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T089 v1 (RES-0109): dialogo de permisos POR SITIO sobre el
     *  GestorPermisos del motor: estado REAL de disponibilidad (sin
     *  backend NINGUNA API se anuncia habilitada), decisiones
     *  guardadas con revocacion por sitio y revocacion total con
     *  confirmacion (ADR-019). Revocar sube la generacion: las
     *  respuestas de dialogos en vuelo quedan STALE y el acceso se
     *  corta EN SEGUIDA (la tabla del gestor es la unica fuente). */
    private fun mostrarPermisos() {
        val filas = permisos.listar()
        val disponibles = TipoPermiso.values().filter { permisos.disponible(it) }
        val mensaje = if (disponibles.isEmpty()) {
            "Ningun backend de permisos existe en esta version: camara, microfono, geolocalizacion, notificaciones y portapapeles NO se anuncian habilitadas y ninguna pagina puede solicitarlos."
        } else {
            "Backends disponibles: " + disponibles.joinToString(", ") { nombrePermiso(it) } + "."
        }
        traza("permisos", "abiertos con " + filas.size + " decisiones")
        AlertDialog.Builder(this)
            .setTitle("Permisos por sitio")
            .setMessage(mensaje)
            .setItems(etiquetasPermisos(filas).toTypedArray()) { _, cual ->
                if (filas.isEmpty()) return@setItems
                detallePermiso(filas[cual])
            }
            .setPositiveButton("Revocar todo") { _, _ -> confirmarRevocarTodo() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T089 v1: una linea por decision (origen, tipo, veredicto y ambito). */
    private fun etiquetasPermisos(filas: List<FilaPermiso>): List<String> {
        if (filas.isEmpty()) return listOf("(sin decisiones guardadas)")
        val etiquetas = ArrayList<String>()
        for (f in filas) {
            etiquetas.add(
                f.clave + " | " + nombrePermiso(f.tipo) + " | " +
                    (if (f.permitido) "permitido" else "denegado") + " (" +
                    (if (f.ambito == AmbitoPermiso.SESION) "sesion" else "permanente") + ")"
            )
        }
        return etiquetas
    }

    /** T089 v1: detalle de una decision con revocacion inmediata. */
    private fun detallePermiso(fila: FilaPermiso) {
        AlertDialog.Builder(this)
            .setTitle(nombrePermiso(fila.tipo) + " - " + fila.clave)
            .setMessage(
                "Decision: " + (if (fila.permitido) "permitido" else "denegado") +
                    " (" + (if (fila.ambito == AmbitoPermiso.SESION) "hasta el fin de sesion" else "permanente") +
                    "). Revocar la devuelve a preguntar y corta el acceso de inmediato."
            )
            .setPositiveButton("Revocar") { _, _ ->
                val ok = permisos.revocar(fila.clave, fila.tipo)
                estado.text = if (ok) "permiso revocado" else "sin permiso que revocar"
                traza("permisos", "revocado " + fila.clave + " " + fila.tipo.name)
                mostrarPermisos()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T089 v1: revocacion total con confirmacion (ADR-019). */
    private fun confirmarRevocarTodo() {
        AlertDialog.Builder(this)
            .setTitle("Revocar todos los permisos")
            .setMessage("Se borran TODAS las decisiones de permisos de todos los sitios (vuelven a preguntar). Seguro?")
            .setPositiveButton("Revocar todo") { _, _ ->
                val n = permisos.revocarTodo()
                estado.text = "permisos revocados (" + n + ")"
                traza("permisos", "revocadas todas (" + n + ")")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** T089 v1: nombre legible de un tipo de permiso. */
    private fun nombrePermiso(t: TipoPermiso): String = when (t) {
        TipoPermiso.CAMARA -> "camara"
        TipoPermiso.MICROFONO -> "microfono"
        TipoPermiso.GEOLOCALIZACION -> "geolocalizacion"
        TipoPermiso.NOTIFICACIONES -> "notificaciones"
        TipoPermiso.PORTAPAPELES -> "portapapeles"
    }

    /** T102 v1: dialogo del visor de workers y service workers. Hoy
     * declara el estado REAL: los gestores del motor (T034) existen y
     * estan cubiertos por tests, pero los scripts de pagina de la
     * navegacion todavia no registran workers ni SW (T071 v1); cuando
     * se cableen, este mismo dialogo listara workers vivos, scopes con
     * su lifecycle y caches con tamanos MEDIDOS. */
    private fun mostrarWorkersSw() {
        val lineas = visorWorkers.texto()
        traza("workers", "visor abierto con " + lineas.size + " lineas")
        AlertDialog.Builder(this)
            .setTitle("Workers / SW")
            .setMessage(lineas.joinToString("\n"))
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T077 v1 (R0195, RES-0093): rutas equivalentes POR MENU de los
     * gestos de zoom (la pinza y el doble tap viven en el PaintView);
     * el zoom es REAL del motor (re-maqueteo, no bitmap). */
    /** T081 v1 (R0204, RES-0096): abre la barra temporal de buscar
     *  en la pagina y centra la primera coincidencia. */
    private fun abrirBuscarEnPagina() {
        barraBusqueda.visibility = LinearLayout.VISIBLE
        campoBusqueda.requestFocus()
        ejecutarBusqueda()
        estado.text = "buscar en la pagina"
    }

    /** T081 v1: cierra la barra y limpia los resaltados del motor. */
    private fun cerrarBuscarEnPagina() {
        barraBusqueda.visibility = LinearLayout.GONE
        pagina.limpiarBusqueda()
        contadorBusqueda.text = ""
        estado.text = "busqueda cerrada"
    }

    /** T081 v1: consulta local sobre los fragmentos del documento;
     *  sin consulta solo limpia. Sincrona y acotada: no hay nada
     *  que cancelar ni repasar sin fin. */
    private fun ejecutarBusqueda() {
        val consulta = campoBusqueda.text.toString()
        if (consulta.trim().isEmpty()) {
            pagina.limpiarBusqueda()
            contadorBusqueda.text = ""
            return
        }
        pagina.buscarEnPagina(consulta)
        refrescarContadorBusqueda()
        irAMatchActual()
    }

    /** T081 v1: N de M en la barra; 0/0 cuando no hay coincidencias. */
    private fun refrescarContadorBusqueda() {
        val total = pagina.totalMatches()
        if (total == 0) {
            contadorBusqueda.text = "0/0"
        } else {
            contadorBusqueda.text = pagina.matchActual().toString() + "/" + total
        }
    }

    /** T081 v1: siguiente (+1) o anterior (-1) con vuelta circular;
     *  refresca el N de M y desplaza el scroll al match activo. */
    private fun irAlMatch(delta: Int) {
        if (pagina.totalMatches() == 0) return
        if (delta >= 0) pagina.siguienteMatch() else pagina.anteriorMatch()
        refrescarContadorBusqueda()
        irAMatchActual()
    }

    /** T081 v1: scroll suave hasta dejar el match activo visible. */
    private fun irAMatchActual() {
        val y = pagina.yMatchActualPx() ?: return
        scroll.smoothScrollTo(0, (y - 200f).coerceAtLeast(0f).toInt())
    }

    /** T080 v1 (RES-0097): clave de ORIGEN del sitio actual o null
     *  sin URL/origen definido (about:, opacos): SIN origen NO se
     *  persiste nada y aplican los defectos. */
    private fun claveSitioActual(): String? {
        val url = navActual()?.currentPage()?.url ?: return null
        return claveSitio(url)
    }

    /** T080 v1: clave (scheme://host:puerto efectivo) de una URL. */
    private fun claveSitio(url: String): String? {
        return try {
            when (val o = Origin.of(UrlParser.parse(url))) {
                is Origin.Tuple -> o.scheme + "://" + o.host + ":" + o.port
                else -> null
            }
        } catch (e: UrlParseException) {
            null
        }
    }

    /** T080 v1: aplica al motor las preferencias del sitio (viewport
     *  de base, zoom y escala de texto para el CSS); SIN origen
     *  aplica los defectos. La recarga conserva las preferencias.
     *  T093 v5 (R0329, RES-0133): sin preferencia por sitio el
     *  defecto viene de Contenido (texto y escritorio globales);
     *  la preferencia del sitio SIGUE mandando. */
    private fun aplicarSitio(url: String) {
        val clave = claveSitio(url)
        val pref = if (clave == null) null else sitios.obtener(clave)
        val efectiva = if (pref != null && pref != PrefSitio()) pref
            else PrefSitio(texto = textoBaseGlobal(), escritorio = escritorioBaseGlobal())
        textoSitio = efectiva.texto
        pagina.fijarBaseViewport(AjustesSitio.viewportBase(efectiva.escritorio))
        pagina.fijarZoom(efectiva.zoom)
    }

    /** T093 v5: escala de texto por defecto de Contenido, clampada
     *  al rango honesto del sitio. */
    private fun textoBaseGlobal(): Double =
        AjustesSitio.limitarTexto((ajustes.valor("contenido.texto")?.toIntOrNull() ?: 100) / 100.0)

    /** T093 v5: modo escritorio por defecto de Contenido. */
    private fun escritorioBaseGlobal(): Boolean =
        (ajustes.valor("contenido.escritorio") ?: "false") == "true"

    /** T093 v5: accion de un uso que borra TODAS las preferencias
     *  por sitio con conteo; los sitios vuelven a los defectos de
     *  Contenido al recargar. */
    private fun aplicarBorradoSitios() {
        val n = sitios.borrarTodo()
        ajustes.asignar("contenido.sitios", "nada")
        recargar()
        estado.text = "preferencias de " + n + " sitios borradas"
        traza("ajustes", "borradas " + n + " preferencias por sitio")
    }

    /** T080 v1: persiste el zoom actual como preferencia del sitio. */
    private fun guardarZoomSitio() {
        val clave = claveSitioActual() ?: return
        val p = sitios.obtener(clave)
        sitios.fijar(clave, p.copy(zoom = pagina.zoomActual()))
    }

    /** T080 v1: agranda el texto del sitio y recarga (re-maquetar). */
    private fun textoMas() {
        cambiarTexto(1.1)
    }

    /** T080 v1: reduce el texto del sitio y recarga. */
    private fun textoMenos() {
        cambiarTexto(0.9)
    }

    /** T080 v1: cambia la escala de texto; con origen se persiste,
     *  sin origen solo escala en vivo (nada que guardar). */
    private fun cambiarTexto(factor: Double) {
        val clave = claveSitioActual()
        if (clave == null) {
            textoSitio = AjustesSitio.limitarTexto(textoSitio * factor)
        } else {
            val p = sitios.obtener(clave)
            val nuevo = AjustesSitio.limitarTexto(p.texto * factor)
            textoSitio = nuevo
            sitios.fijar(clave, p.copy(texto = nuevo))
        }
        recargar()
        estado.text = "texto " + (textoSitio * 100).toInt() + "%"
    }

    /** T080 v1: alterna el modo escritorio del sitio (viewport 720;
     *  el UA real NO se toca: no se simulan capacidades ausentes). */
    private fun alternarEscritorio() {
        val clave = claveSitioActual() ?: return
        val p = sitios.obtener(clave)
        sitios.fijar(clave, p.copy(escritorio = !p.escritorio))
        recargar()
        estado.text = if (sitios.obtener(clave).escritorio) "escritorio 720px" else "movil 360px"
    }

    /** T080 v1: restaura el sitio a los defectos (zoom/texto/escritorio). */
    private fun restaurarSitio() {
        val clave = claveSitioActual() ?: return
        sitios.borrar(clave)
        textoSitio = 1.0
        recargar()
        estado.text = "sitio restaurado"
    }

    /** T085 v1 (RES-0098): origen REAL de la pagina activa: el
     *  mismo Origin del commit del motor, sin re-parsing. */
    private fun origenSitioActual(): Origin.Tuple? {
        val p = navActual()?.currentPage() ?: return null
        return p.origin as? Origin.Tuple
    }

    /** T085 v1: dialogo con los DATOS REALES del sitio (cookies y
     *  cache del perfil vivo de la pestana) y borrado SOLO del
     *  origen actual; el resto de origenes queda intacto. */
    private fun mostrarDatosSitio() {
        val o = origenSitioActual()
        if (o == null) {
            estado.text = "sin origen en la pagina actual"
            return
        }
        val perfil = tabs.perfil(tipoActivo())
        if (perfil == null) {
            estado.text = "sin perfil vivo"
            return
        }
        val clave = o.scheme + "://" + o.host + ":" + o.port
        val pref = sitios.obtener(clave)
        val conteo = DatosSitio.conteo(perfil.cookies, perfil.cache, o.scheme, o.host, o.port)
        val mensaje = "cookies=" + conteo.cookies + " cache=" + conteo.cache +
            " | preferencias: zoom=" + (pref.zoom * 100).toInt() + "%" +
            " texto=" + (pref.texto * 100).toInt() + "%" +
            (if (pref.escritorio) " escritorio" else " movil")
        AlertDialog.Builder(this)
            .setTitle("Datos del sitio")
            .setMessage(mensaje)
            .setPositiveButton("Borrar datos del sitio") { _, _ ->
                val borrado = DatosSitio.borrar(perfil.cookies, perfil.cache, o.scheme, o.host, o.port)
                traza("datos-sitio", "borrado cookies=" + borrado.cookies + " cache=" + borrado.cache)
                estado.text = "sitio borrado: cookies=" + borrado.cookies + " cache=" + borrado.cache
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T101 v1 (RES-0099): gestor de cookies del perfil de la
     *  pestana: filas REALES del CookieJar con los SECRETOS OCULTOS
     *  por defecto (revelar es consentimiento explicito), borrado
     *  individual por identidad compuesta (nombre, dominio, ruta,
     *  host-only) con confirmacion y borrado total con confirmacion
     *  ADR-019; el borrado por sitio vive en Datos del sitio (T085). */
    private fun gestorCookies(mostrarValores: Boolean = false) {
        val perfil = tabs.perfil(tipoActivo())
        if (perfil == null) {
            estado.text = "sin perfil vivo"
            return
        }
        val jar = perfil.cookies
        val ahora = clock.nowMillis()
        val filas = GestorCookies.filas(jar, mostrarValores, ahora)
        val lineas = ArrayList<String>()
        for (f in filas) lineas.add(GestorCookies.texto(f))
        if (lineas.isEmpty()) lineas.add("(sin cookies)")
        traza("cookies", "gestor abierto con " + filas.size + " cookies")
        AlertDialog.Builder(this)
            .setTitle("Cookies (" + filas.size + ")")
            .setItems(lineas.toTypedArray()) { _, cual ->
                if (filas.isEmpty()) return@setItems
                val f = filas[cual]
                AlertDialog.Builder(this)
                    .setTitle("Borrar cookie")
                    .setMessage(GestorCookies.texto(f))
                    .setPositiveButton("Borrar") { _, _ ->
                        val ok = jar.borrarUna(f.nombre, f.dominio, f.ruta, f.hostOnly)
                        estado.text = if (ok) "cookie borrada: " + f.nombre else "cookie no encontrada"
                        traza("cookies", "borrada " + f.nombre + "@" + f.dominio + f.ruta + (if (ok) "" else " (no encontrada)"))
                        gestorCookies(mostrarValores)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setPositiveButton(if (mostrarValores) "Ocultar valores" else "Mostrar valores") { _, _ ->
                gestorCookies(!mostrarValores)
            }
            .setNeutralButton("Borrar todas") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Borrar todas las cookies")
                    .setMessage("Se borraran TODAS las cookies del perfil de la pestana. Borrar?")
                    .setPositiveButton("Borrar") { _, _ ->
                        val n = jar.clear()
                        estado.text = "cookies borradas (" + n + ")"
                        traza("cookies", "borradas todas (" + n + ")")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T106 v1 (RES-0100): estado de red de la pestana ACTIVA: la
     *  ultima navegacion de esa pestana con sus secretos YA redactados
     *  en la ingesta (SecretRedactor, T008) y la latencia real medida
     *  alrededor de la navegacion. Contextual por pestana: red.de(id)
     *  NUNCA muestra la red de otra pestana. */
    private fun mostrarRed() {
        val id = tabs.activa()
        val linea = id?.let { red.de(it) }
        traza("red", "estado de red pestana " + id + ": " + (linea?.texto() ?: "sin navegacion"))
        AlertDialog.Builder(this)
            .setTitle("Estado de red (pestana)")
            .setMessage(linea?.texto() ?: "(sin navegacion en esta pestana)")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** T107 v1 (RES-0101): versiones del artefacto INSTALADO (nada de
     *  estado remoto): version real de la app leida del PackageManager,
     *  sha del build EMBEBIDO en el APK por el workflow (asset
     *  build-info.txt; un build local sin el asset lo declara), contracts
     *  con su ContractsVersion real y componentes con estado honesto. */
    private fun mostrarVersiones() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val sha = try {
            assets.open("build-info.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            null
        }
        val texto = Versiones.resumen(version, sha, ContractsVersion.toString())
        traza("diag", "versiones: navegador " + version + ", sha " + (sha ?: "build local"))
        AlertDialog.Builder(this)
            .setTitle("Versiones")
            .setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** T099 v1 (RES-0102): importa marcadores Netscape HTML (el formato
     *  de Firefox y Chrome) via SAF. El parseo valida TODO a temporales
     *  antes de tocar la coleccion y la mezcla deduplica por url
     *  reutilizando carpetas por nombre. */
    private fun importarMarcadoresHtml() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("text/html")
        startActivityForResult(i, REQUEST_IMPORT_MARCADORES)
        traza("marcadores", "importar html: pidiendo documento")
    }

    /** T099 v1 (RES-0102): exporta la coleccion viva a Netscape HTML
     *  via SAF (crear documento); los pins no viajan (exclusion v1). */
    private fun exportarMarcadoresHtml() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("text/html")
        i.putExtra(Intent.EXTRA_TITLE, "marcadores.html")
        startActivityForResult(i, REQUEST_EXPORT_MARCADORES)
        traza("marcadores", "exportar html: pidiendo destino")
    }

    /** T108 v1 (RES-0103): capacidades del build instalado con los
     *  estados del harness (PASS/NOT_RUN/FAIL separados por alcance y
     *  evidencia). El registro embebido (asset del workflow) manda;
     *  sin el todo es NOT_RUN honesto y la UI NUNCA cambia un
     *  resultado medido por optimismo. */
    private fun mostrarCapacidades() {
        val texto = try {
            assets.open("capacidades.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val caps = Capacidades.registro(texto)
        traza("diag", "capacidades: " + caps.count { it.estado == nia.contracts.TestStatus.PASS } + " PASS embebidas")
        AlertDialog.Builder(this)
            .setTitle("Capacidades")
            .setMessage(Capacidades.resumen(caps))
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** T100 v1 (RES-0104): reintentos temporales de cuota concedidos
     * y scripts interrumpidos de la ultima carga. El reintento dobla
     * la cuota de la recarga hasta el tope duro; una carga que termina
     * sin scripts interrumpidos los restablece (temporal de verdad). */
    @Volatile
    private var reintentosCuotas = 0

    @Volatile
    private var scriptsInterrumpidos = 0

    /** T094 v1: resumen de la ultima ronda de onNavigate a modulos
     * (escrito en el hilo de navegacion, leido en la UI). */
    @Volatile
    private var resumenModulos = ""

    /** C6 v1 (R0276, RES-0120): hoja de estilos ganadora del disparo de
     *  navegacion; la consume el PINTADO del documento (vive y muere
     *  con la navegacion, una hoja por documento). */
    private var hojaParaPintar: String? = null

    /** C5 v1 (R0280, RES-0121): script de documento ganador del disparo
     *  de navegacion; lo consume el PINTADO del documento y su sesion
     *  del event loop vive y muere con la navegacion (cancelacion). */
    private var scriptParaPintar: String? = null

    /** T053 v1 (R0289, RES-0123): lista cosmetica ganadora del disparo
     *  de navegacion y el HOST al que aplica; la consume el PINTADO del
     *  documento (vive y muere con la navegacion, una lista por
     *  documento). */
    private var cosmeticosParaPintar: String? = null
    private var hostCosmeticos: String? = null

    /** Dialogo de cuotas visibles (T100): mapa honesto de la
     * interrupcion (pasos/allocs/tiempo y lo que NO miden), cuotas
     * activas tras los reintentos, conteo de scripts interrumpidos y
     * acciones de reintento temporal (recargar con la cuota doblada)
     * y de restablecer. La ejecucion sin limite NO existe. */
    private fun mostrarCuotasJs() {
        val activas = CuotasJs.temporal(reintentosCuotas, cuotasResueltas())
        val sb = StringBuilder()
        for (linea in CuotasJs.mapa(activas)) {
            sb.append(linea).append('\n')
        }
        sb.append("reintentos activos: " + reintentosCuotas + " (cada uno dobla la cuota de la recarga)")
        sb.append('\n')
        sb.append("scripts interrumpidos en la ultima carga: " + scriptsInterrumpidos)
        traza("cuotas", "cuotas js pasos=" + activas.maxSteps + " allocs=" + activas.maxAllocs + " reintentos=" + reintentosCuotas)
        AlertDialog.Builder(this)
            .setTitle("Cuotas JS")
            .setMessage(sb.toString())
            .setPositiveButton("Reintentar con mas cuota") { _, _ ->
                reintentosCuotas += 1
                estado.text = "reintento " + reintentosCuotas + ": recargando con mas cuota (temporal)"
                traza("cuotas", "reintento " + reintentosCuotas)
                recargar()
            }
            .setNeutralButton("Restablecer reintentos") { _, _ ->
                reintentosCuotas = 0
                estado.text = "reintentos de cuota restablecidos"
                traza("cuotas", "reintentos restablecidos")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Cuotas resueltas de los ajustes (T100): el valor del usuario
     * ACOTADO por los rangos honestos del motor; JAMAS sin limite. */
    private fun cuotasResueltas(): JsLimits {
        val pasos = ajustes.valor("js.cuotas.pasos")?.toLongOrNull() ?: CuotasJs.DEFAULT_PASOS
        val allocs = ajustes.valor("js.cuotas.allocs")?.toLongOrNull() ?: CuotasJs.DEFAULT_ALLOCS
        return CuotasJs.resolver(pasos, allocs)
    }

    /** Cuenta los scripts interrumpidos de una carga (T100): el dump
     * del motor reporta "interrupted ..." cuando un script aborto por
     * cuotas o por Stop (T021); null o fallo cuentan 0. */
    private fun contarScriptsInterrumpidos(o: NavOutcome?): Int {
        if (o !is NavOutcome.Loaded) return 0
        var n = 0
        for (s in o.page.scripts) {
            if (s.output.contains("interrupted")) n += 1
        }
        return n
    }

    /** T094 v5 (R0296, RES-0124): abre el panel DevTools con el reporte
     *  DECLARATIVO de los modulos: viaja por el intent (vivo al abrir,
     *  sin estado global; la demo de MainActivity sigue sin modulos). */
    private fun abrirDevToolsModulos() {
        val abrir = Intent(this, DevToolsActivity::class.java)
        abrir.putExtra("modulos", reporteModulos())
        traza("modulos", "devtools declarativo abierto con " + gestorModulos.fichas().size + " modulo(s)")
        startActivity(abrir)
    }

    /** T094 v5: reporte declarativo vivo por modulo: estado, version,
     *  permisos, actividad del gestor, contadores del filtro y el
     *  ui-model declarado (ajustes con su valor REAL de la config del
     *  perfil normal, acciones como catalogo SIN ejecucion y paneles
     *  id+titulo: la interactividad requiere reabrir T030). */
    private fun reporteModulos(): String {
        val fichas = gestorModulos.fichas()
        val config = tabs.perfil(ProfileKind.NORMAL)?.configModulos
        val sb = StringBuilder()
        if (fichas.isEmpty()) {
            sb.append("(sin modulos instalados)").append('\n')
            return sb.toString()
        }
        for (f in fichas) {
            sb.append("modulo ").append(f.id)
                .append(" - ").append(textoEstadoModulo(f)).append('\n')
            sb.append("  version: ").append(f.version)
                .append("; permisos: ")
                .append(if (f.permissions.isEmpty()) "ninguno" else f.permissions.joinToString(", "))
                .append('\n')
            sb.append("  actividad: disparos=").append(f.disparos)
                .append(" errores=").append(f.errores).append('\n')
            if (f.ultimoVeredicto.isNotEmpty()) {
                sb.append("  ultimo veredicto: ").append(f.ultimoVeredicto).append('\n')
            }
            sb.append("  filtro: bloqueos=").append(PluginNetFilter.bloqueosDe(f.id))
                .append(" sustituciones=").append(PluginNetFilter.sustitucionesDe(f.id))
                .append('\n')
            sb.append("  alcance: ")
                .append(if (f.alcanceGlobal) "global" else "solo " + f.alcanceOrigenes.size + " origen(es)")
                .append("; excepciones: ").append(f.alcanceExcepciones.size).append('\n')
            val ui = gestorModulos.uiModelDe(f.id)
            if (ui == null || ui.vacio) {
                sb.append("  ui-model: sin declaraciones").append('\n')
            } else {
                for (a in ui.ajustes) {
                    val valor = config?.leer(f.id, "uim." + a.id)
                        ?: (if (a.defecto) "true" else "false")
                    sb.append("  ajuste ").append(a.id).append(": ").append(a.titulo)
                        .append(" = ").append(if (valor == "true") "si" else "no").append('\n')
                }
                for (ac in ui.acciones) {
                    sb.append("  accion ").append(ac.id).append(": ").append(ac.titulo)
                        .append(" (catalogo sin ejecucion)").append('\n')
                }
                for (p in ui.paneles) {
                    sb.append("  panel ").append(p.id).append(": ").append(p.titulo).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** T094 v1 (R0226, RES-0106, ADR-005): gestor de modulos del
     *  shell sobre el registro (T009) y el runtime (T029/T030) del     *  motor. Lista fichas con estado y actividad; sin modulos
     *  ofrece instalar el demo embebido (el canal .niamod con firma
     *  llega con el instalador T095); tocar una ficha abre el
     *  detalle con las acciones reales del runtime. */
    private fun mostrarModulos() {
        val fichas = gestorModulos.fichas()
        traza("modulos", "gestor abierto con " + fichas.size + " modulo(s)")
        if (fichas.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Modulos")
                .setMessage(
                    "Sin modulos instalados. El instalador de paquetes .niamod con firma llega con la tarea de instalacion (T095); por ahora puede instalarse el modulo demo embebido, que observa navegaciones sin bloquear nada (ADR-005)."
                )
                .setPositiveButton("Instalar demo") { _, _ -> instalarDemoModulo() }
                .setNeutralButton("Instalar .niamod") { _, _ -> pedirNiamod() }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        val lineas = ArrayList<String>()
        for (f in fichas) {
            lineas.add(f.id + " - " + textoEstadoModulo(f) + " (" + f.disparos + " disparos)")
        }
        AlertDialog.Builder(this)
            .setTitle("Modulos (" + fichas.size + ")")
            .setItems(lineas.toTypedArray()) { _, cual -> detalleModulo(fichas[cual].id) }
            .setNeutralButton("Instalar .niamod") { _, _ -> pedirNiamod() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Texto honesto del estado (T094, ADR-005): DESACTIVADO sin
     *  sesion, ACTIVO con hooks, ERROR con fallos contados (fail-open:
     *  sigue activo, un modulo roto JAMAS rompe la navegacion). */
    private fun textoEstadoModulo(f: FichaModulo): String = when (f.estado) {
        EstadoModulo.DESACTIVADO -> "desactivado"
        EstadoModulo.ACTIVO -> "activo"
        EstadoModulo.ERROR -> "activo con errores"
    }

    private fun instalarDemoModulo() {
        val r = gestorModulos.instalarDemo()
        if (r is Outcome.Failure) {
            estado.text = "no se pudo instalar el demo: " + r.error.safeMessage
            traza("modulos", "instalar demo fallo")
            return
        }
        estado.text = "modulo demo instalado y activo"
        traza("modulos", "demo instalado y activo")
    }

    /** Detalle del modulo (T094): metadatos del manifiesto, hooks
     *  registrados, actividad explicada (disparos/errores/ultimo
     *  veredicto), alcance y estado honesto de firma/actualizacion
     *  (el canal .niamod llega con T095); las acciones viven en un
     *  dialogo propio para no mezclar lectura y ejecucion. */
    private fun detalleModulo(id: String) {
        val f = gestorModulos.ficha(id)
        if (f == null) {
            estado.text = "modulo no encontrado: " + id
            return
        }
        val sb = StringBuilder()
        sb.append("id: ").append(f.id).append('\n')
        sb.append("nombre: ").append(f.name).append('\n')
        if (f.description.isNotEmpty()) {
            sb.append("descripcion: ").append(f.description).append('\n')
        }
        sb.append("version: ").append(f.version).append(" (api-version 1)").append('\n')
        sb.append("entry: ").append(f.entry).append('\n')
        sb.append("permisos: ")
            .append(if (f.permissions.isEmpty()) "ninguno" else f.permissions.joinToString(", "))
            .append('\n')
        sb.append("categorias: ")
            .append(if (f.categories.isEmpty()) "sin declarar" else f.categories.joinToString(", "))
            .append('\n')
        sb.append("capabilities: ")
            .append(if (f.capabilities.isEmpty()) "sin declarar" else f.capabilities.joinToString(", "))
            .append('\n')
        sb.append("estado: ").append(textoEstadoModulo(f)).append('\n')
        val configModulos = tabs.perfil(ProfileKind.NORMAL)?.configModulos
        sb.append("habilitado (ajustes): ")
            .append(
                if (configModulos == null) "sin perfil normal vivo"
                else if (configModulos.enabled(f.id)) "si" else "no"
            )
            .append('\n')
        val fuentesLista = listasModulos.fuentes(f.id)
        val listasVencidas = listasModulos.haceFaltaActualizar(f.id, clock.nowMillis(), configModulos)
        sb.append("listas: ")
            .append(fuentesLista.size)
            .append(" fuente(s) (actualizables: ")
            .append(if (listasVencidas.isEmpty()) "no" else "si")
            .append(")")
            .append('\n')
        val uiDeclarado = gestorModulos.uiModelDe(id)
        if (uiDeclarado != null && !uiDeclarado.vacio) {
            sb.append("ajustes declarados: ").append(uiDeclarado.ajustes.size)
                .append(" (bool); acciones: ").append(uiDeclarado.acciones.size)
                .append("; paneles: ").append(uiDeclarado.paneles.size).append('\n')
        }
        sb.append("hooks: startup=").append(f.hooksStartup)
            .append(" navigate=").append(f.hooksNavigate)
            .append(" request=").append(f.hooksRequest).append('\n')
        sb.append("actividad: disparos=").append(f.disparos)
            .append(" errores=").append(f.errores).append('\n')
        if (f.ultimoVeredicto.isNotEmpty()) {
            sb.append("ultimo veredicto: ").append(f.ultimoVeredicto).append('\n')
        }
        sb.append("alcance: ")
            .append(if (f.alcanceGlobal) "global" else "solo " + f.alcanceOrigenes.size + " origen(es)")
            .append('\n')
        sb.append("excepciones: ").append(f.alcanceExcepciones.size).append(" origen(es)")
        if (f.alcanceExcepciones.isNotEmpty()) {
            sb.append(" (").append(f.alcanceExcepciones.joinToString(", ")).append(")")
        }
        sb.append('\n')
        // T054 v1: estadisticas del filtro (proceso, en memoria).
        sb.append("filtro: bloqueos=").append(PluginNetFilter.bloqueosDe(f.id))
            .append(" sustituciones=").append(PluginNetFilter.sustitucionesDe(f.id))
            .append('\n')
        sb.append("firma: no (paquete demo in-process; el canal .niamod llega con T095)")
        AlertDialog.Builder(this)
            .setTitle("Modulo: " + f.id)
            .setMessage(sb.toString())
            .setPositiveButton("Acciones") { _, _ -> accionesModulo(id) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Acciones del modulo (T094): activar/desactivar, alcance
     *  global/sitio y excepciones de sitio (es del HOST, ADR-005;
     *  T054) y desinstalar con confirmacion explicita (ADR-019). */
    private fun accionesModulo(id: String) {
        val acciones = arrayOf(
            "Activar o desactivar",
            "Habilitar o deshabilitar (ajustes)",
            "Anadir o quitar lista (ajustes)",
            "Actualizar listas ahora",
            "Solo en este sitio",
            "Hacer global",
            "Exceptuar este sitio",
            "Quitar excepcion de este sitio",
            "Desinstalar",
            "Ajustes declarados (uimodel)"
        )
        AlertDialog.Builder(this)
            .setTitle("Acciones del modulo: " + id)
            .setItems(acciones) { _, cual ->
                when (cual) {
                    0 -> alternarModulo(id)
                    1 -> alternarHabilitadoModulo(id)
                    2 -> anadirListaModulo(id)
                    3 -> actualizarListasModulo(id)
                    4 -> limitarModuloASitio(id)
                    5 -> globalizarModulo(id)
                    6 -> exceptuarSitioModulo(id)
                    7 -> quitarExcepcionModulo(id)
                    8 -> confirmarDesinstalarModulo(id)
                    9 -> ajustesDeclaradosModulo(id)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun alternarModulo(id: String) {
        val f = gestorModulos.ficha(id) ?: return
        if (f.estado == EstadoModulo.DESACTIVADO) {
            val r = gestorModulos.activar(id)
            if (r is Outcome.Failure) {
                estado.text = "no se pudo activar: " + r.error.safeMessage
                traza("modulos", "activar fallo: " + id)
                return
            }
            estado.text = "modulo activo: " + id
            traza("modulos", "activado: " + id)
        } else {
            val r = gestorModulos.desactivar(id)
            if (r is Outcome.Failure) {
                estado.text = "no se pudo desactivar: " + r.error.safeMessage
                traza("modulos", "desactivar fallo: " + id)
                return
            }
            estado.text = "modulo desactivado: " + id
            traza("modulos", "desactivado: " + id)
        }
    }

    /** C11 v1 (R0253, RES-0113): ajuste "habilitado" del modulo
     *  (general.enabled, C9): se escribe en la config del PERFIL NORMAL
     *  (persistida con el perfil) y el filtro de red la consulta ANTES
     *  de disparar el hook; DISTINTO de activar/desactivar (sesion del
     *  gestor, que SIEMPRE gana). */
    private fun alternarHabilitadoModulo(id: String) {
        val config = tabs.perfil(ProfileKind.NORMAL)?.configModulos
        if (config == null) {
            estado.text = "sin perfil normal vivo: abre una pestana normal primero"
            return
        }
        val nuevo = if (config.enabled(id)) "false" else "true"
        val r = config.escribir(id, "general.enabled", nuevo)
        if (r is Outcome.Failure) {
            estado.text = "no se pudo escribir el ajuste: " + r.error.safeMessage
            traza("modulos", "ajuste general.enabled fallo: " + id)
            return
        }
        estado.text = "modulo " + (if (nuevo == "false") "deshabilitado" else "habilitado") + " (ajustes): " + id
        traza("modulos", "general.enabled=" + nuevo + " para " + id)
    }

    /** C11 v2 (R0260, RES-0116): ajustes DECLARADOS por el modulo en
     *  los .json de ui-model (uimodel/1, solo bool): se muestran con su
     *  valor y al tocarlos se alterna en la config del PERFIL NORMAL
     *  (via C9, clave "uim.<id>"); DISTINTO de habilitar/deshabilitar
     *  (general.enabled). El entry NO puede leer estos ajustes hoy
     *  (api-version 1, E4): el consumo llega cuando reabra T030. */
    private fun ajustesDeclaradosModulo(id: String) {
        val ui = gestorModulos.uiModelDe(id)
        if (ui == null || ui.vacio) {
            estado.text = "sin ajustes declarados: " + id
            return
        }
        val config = tabs.perfil(ProfileKind.NORMAL)?.configModulos
        if (config == null) {
            estado.text = "sin perfil normal vivo: abre una pestana normal primero"
            return
        }
        val lineas = ui.ajustes.map { a ->
            val valor = config.leer(id, "uim." + a.id)
                ?: (if (a.defecto) "true" else "false")
            a.titulo + ": " + (if (valor == "true") "si" else "no")
        }
        AlertDialog.Builder(this)
            .setTitle("Ajustes declarados: " + id)
            .setItems(lineas.toTypedArray()) { _, cual ->
                val a = ui.ajustes[cual]
                val clave = "uim." + a.id
                val actual = config.leer(id, clave)
                    ?: (if (a.defecto) "true" else "false")
                val nuevo = if (actual == "true") "false" else "true"
                val r = config.escribir(id, clave, nuevo)
                if (r is Outcome.Failure) {
                    estado.text = "no se pudo escribir el ajuste: " + r.error.safeMessage
                    traza("modulos", "ajuste " + clave + " fallo: " + id)
                    return@setItems
                }
                estado.text = a.titulo + " = " +
                    (if (nuevo == "true") "si" else "no") + " (uimodel): " + id
                traza("modulos", clave + "=" + nuevo + " para " + id)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** C7 v1 (R0255, RES-0114): anade o QUITA una fuente de listas del
     *  modulo: la URL debe ser https sin credenciales y el intervalo
     *  queda en la propuesta A MEDIR del E4 (24 h); el host trae las
     *  listas, el modulo JAMAS descarga por su cuenta. */
    private fun anadirListaModulo(id: String) {
        val campo = EditText(this)
        campo.hint = "https://servidor.example/lista.txt"
        AlertDialog.Builder(this)
            .setTitle("Anadir o quitar lista")
            .setMessage("Pega la URL https de la lista; si ya esta registrada se QUITA.")
            .setView(campo)
            .setPositiveButton("Aceptar") { _, _ ->
                val url = campo.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (listasModulos.fuentes(id).any { it.url == url }) {
                    estado.text = "lista quitada: " + hostDe(url)
                    traza("modulos", "lista quitada de " + id)
                    return@setPositiveButton
                }
                when (val r = listasModulos.registrar(id, url)) {
                    is Outcome.Failure -> {
                        estado.text = "lista rechazada: " + r.error.safeMessage
                        traza("modulos", "lista rechazada para " + id)
                    }
                    is Outcome.Success -> {
                        estado.text = "lista anadida (cada " + ListasModulos.INTERVALO_DEFECTO_HORAS + " h): " + hostDe(url)
                        traza("modulos", "lista anadida a " + id)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** C7+C8 v1: descarga las listas del modulo con las cuotas del host
     *  (la denegacion por cuota se DISTINGUE del error de red en el
     *  informe) y persiste el estado de actualizacion en la config del
     *  perfil normal (C9); en segundo plano (nunca en el hilo de UI).
     *  El contenido alimentara al matcher del host (T051): el entry NO
     *  puede leerlo hoy (api-version 1, E4). */
    private fun actualizarListasModulo(id: String) {
        val config = tabs.perfil(ProfileKind.NORMAL)?.configModulos
        poolDescargas.execute {
            val informe = listasModulos.actualizarAhora(id, HttpTransport(), config, clock.nowMillis())
            runOnUiThread {
                if (informe.resultados.isEmpty()) {
                    estado.text = "sin listas registradas para este modulo"
                    traza("modulos", "actualizacion de listas sin fuentes: " + id)
                } else {
                    estado.text = "listas: " + informe.resumen()
                    traza("modulos", "listas de " + id + ": " + informe.resumen())
                }
            }
        }
    }

    private fun limitarModuloASitio(id: String) {
        val o = origenSitioActual()
        if (o == null) {
            estado.text = "sin origen en la pagina actual"
            return
        }
        val clave = o.scheme + "://" + o.host + ":" + o.port
        val r = gestorModulos.soloEnEsteSitio(id, clave)
        if (r is Outcome.Failure) {
            estado.text = "no se pudo limitar: " + r.error.safeMessage
            return
        }
        estado.text = "modulo solo en " + o.host
        traza("modulos", "alcance sitio: " + id + " (" + o.host + ")")
    }

    private fun globalizarModulo(id: String) {
        val r = gestorModulos.hacerGlobal(id)
        if (r is Outcome.Failure) {
            estado.text = "no se pudo globalizar: " + r.error.safeMessage
            return
        }
        estado.text = "modulo global: " + id
        traza("modulos", "alcance global: " + id)
    }

    /** T054 v1 (R0284, RES-0122): excepcion de sitio del HOST
     *  (ADR-005): el modulo de alcance global se abstiene en el
     *  origen REAL de la pagina actual, tanto en onNavigate como
     *  en el filtro de red. */
    private fun exceptuarSitioModulo(id: String) {
        val o = origenSitioActual()
        if (o == null) {
            estado.text = "sin origen en la pagina actual"
            return
        }
        val clave = o.scheme + "://" + o.host + ":" + o.port
        val r = gestorModulos.exceptuarSitio(id, clave)
        if (r is Outcome.Failure) {
            estado.text = "no se pudo exceptuar: " + r.error.safeMessage
            return
        }
        estado.text = "modulo exceptuado en " + o.host
        traza("modulos", "excepcion sitio: " + id + " (" + o.host + ")")
    }

    /** T054 v1: retira la excepcion del origen actual (si estaba). */
    private fun quitarExcepcionModulo(id: String) {
        val o = origenSitioActual()
        if (o == null) {
            estado.text = "sin origen en la pagina actual"
            return
        }
        val clave = o.scheme + "://" + o.host + ":" + o.port
        val r = gestorModulos.permitirSitio(id, clave)
        if (r is Outcome.Failure) {
            estado.text = "no se pudo quitar: " + r.error.safeMessage
            return
        }
        estado.text = "excepcion retirada en " + o.host
        traza("modulos", "excepcion retirada: " + id + " (" + o.host + ")")
    }

    private fun confirmarDesinstalarModulo(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Desinstalar modulo")
            .setMessage("Se retira el modulo " + id + " por completo: sesion, permisos del broker, alcance y contadores. Desinstalar?")
            .setPositiveButton("Desinstalar") { _, _ ->
                val r = gestorModulos.desinstalar(id)
                if (r is Outcome.Failure) {
                    estado.text = "no se pudo desinstalar: " + r.error.safeMessage
                    traza("modulos", "desinstalar fallo: " + id)
                    return@setPositiveButton
                }
                estado.text = "modulo desinstalado: " + id
                traza("modulos", "desinstalado: " + id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** T094 v1: notifica onNavigate a los modulos activos con alcance
     *  en el origen de la carga (fail-open, ADR-005); corre en el hilo
     *  de navegacion y solo deja el resumen en un campo volatile para
     *  el estado de la UI: JAMAS lanza y JAMAS toca la traza desde el
     *  hilo de fondo. C10 v2 (R0267, RES-0118): ctx lleva el
     *  EventContext real de la navegacion para el diagnostico del
     *  modulo; sin contexto nia.diag se abstiene. */
    private fun dispararModulos(o: NavOutcome?, ctx: EventContext? = null) {
        if (o !is NavOutcome.Loaded) return
        val t = o.page.origin as? Origin.Tuple ?: return
        val clave = t.scheme + "://" + t.host + ":" + t.port
        val res = gestorModulos.dispararNavegar(clave, o.page.url, ctx)
        // C6 v1 (R0276, RES-0120): la hoja del documento la resuelve el
        // gestor tras el disparo (una por documento; gana el primero).
        hojaParaPintar = gestorModulos.hojaParaNavegacion(clave, ctx)?.css
        // C5 v1 (R0280, RES-0121): el script del documento lo resuelve el
        // gestor tras el disparo (uno por documento; gana el primero).
        scriptParaPintar = gestorModulos.scriptParaNavegacion(clave, ctx)?.js
        // T053 v1 (R0289, RES-0123): la lista cosmetica del documento la
        // resuelve el gestor tras el disparo (una por documento; gana el
        // primero) y su alcance es el HOST del origen.
        cosmeticosParaPintar = gestorModulos.cosmeticosParaNavegacion(clave, ctx)?.texto
        hostCosmeticos = t.host
        if (res.disparados > 0) {
            resumenModulos = "modulos " + res.disparados + " notificado(s), errores: " + res.errores
        }
    }

    /** T095 v1 (RES-0107, ADR-005): pide un paquete .niamod por SAF. */
    private fun pedirNiamod() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("*/*")
        startActivityForResult(i, REQUEST_INSTALAR_NIAMOD)
        traza("modulos", "instalar niamod: pidiendo documento")
    }

    /** Previa del paquete (T095, ADR-028): el usuario ve id, permisos,
     *  huellas y estado de confianza ANTES de confirmar; los permisos
     *  nuevos de un update se destacan porque exigen reconfirmacion. */
    private fun instalarDesdeNiamod(bytes: ByteArray) {
        val previa = when (val p = gestorModulos.previsualizarPaquete(bytes)) {
            is Outcome.Failure -> {
                estado.text = "paquete rechazado: " + p.error.safeMessage
                traza("modulos", "niamod rechazado: " + p.error.code.name)
                return
            }
            is Outcome.Success -> p.value
        }
        val sb = StringBuilder()
        sb.append("id: ").append(previa.id)
        sb.append("\nnombre: ").append(previa.name)
        sb.append("\nversion: ").append(previa.version)
        sb.append("\nentry: ").append(previa.entry)
        sb.append("\npermisos: ").append(previa.permisos.joinToString(", "))
        if (previa.permisosNuevos.isNotEmpty()) {
            sb.append("\nPERMISOS NUEVOS: ").append(previa.permisosNuevos.joinToString(", "))
        }
        sb.append("\nhuella paquete: ").append(previa.huellaPaquete)
        sb.append("\nhuella clave: ").append(previa.huellaClave)
        if (previa.ajustesDeclarados > 0 || previa.accionesDeclaradas > 0 ||
            previa.panelesDeclarados > 0
        ) {
            sb.append("\nui-model: ajustes ").append(previa.ajustesDeclarados)
                .append(", acciones ").append(previa.accionesDeclaradas)
                .append(", paneles ").append(previa.panelesDeclarados)
        }
        if (previa.archivosDatos > 0) {
            sb.append("\ndatos: ").append(previa.archivosDatos)
                .append(" archivo(s), ").append(previa.bytesDatos).append(" bytes")
        }
        sb.append("\n").append(textoConfianza(previa.estadoClave))
        AlertDialog.Builder(this)
            .setTitle("Instalar modulo")
            .setMessage(sb.toString())
            .setPositiveButton("Confirmar") { _, _ -> confirmarNiamod(bytes, previa) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Texto honesto de la confianza (T095, ADR-005/ADR-028). */
    private fun textoConfianza(estado: EstadoClave): String = when (estado) {
        EstadoClave.NUEVA_AUTORIA -> "confianza: autor nuevo (confirmar ancla su clave)"
        EstadoClave.MISMA_CLAVE -> "confianza: clave anclada (mismo autor)"
        EstadoClave.ROTACION_PENDIENTE -> "confianza: rotacion declarada por el autor (next-key)"
        EstadoClave.CAMBIO_MANUAL -> "confianza: CAMBIO DE CLAVE SIN next-key (reaprobacion manual)"
    }

    /** Instala con la aprobacion que vio la previa (T095, ADR-028). */
    private fun confirmarNiamod(bytes: ByteArray, previa: PreviaPaquete) {
        val r = gestorModulos.instalarPaquete(bytes, AprobacionPaquete(previa.huellaClave, true))
        if (r is Outcome.Failure) {
            estado.text = "no se pudo instalar: " + r.error.safeMessage
            traza("modulos", "niamod rechazado al instalar: " + r.error.code.name)
            return
        }
        estado.text = "modulo instalado: " + previa.id
        traza("modulos", "niamod instalado: " + previa.id)
        mostrarModulos()
    }

    /** T098 (RES-0108): imprimir o guardar en PDF la pagina activa.
     *  El documento sale del render del motor re-maquetado al ancho de
     *  hoja (escenaParaImprimir), JAMAS de una captura de la vista. */
    private fun imprimirPagina() {
        val opcionesImpresion = arrayOf("Imprimir", "Guardar PDF")
        AlertDialog.Builder(this)
            .setTitle("Imprimir pagina")
            .setItems(opcionesImpresion) { _, cual ->
                when (cual) {
                    0 -> imprimirSistema()
                    1 -> pedirGuardarPdf()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Documento paginado de la pagina activa (A4, margen por defecto). */
    private fun documentoImpreso(orientacion: Orientacion): DocumentoPaginado {
        val ancho = if (orientacion == Orientacion.VERTICAL) {
            Impresor.A4_ANCHO_PT
        } else {
            Impresor.A4_ALTO_PT
        }
        val anchoUtil = ancho - 2 * Impresor.MARGEN_DEFECTO_PT
        val escena = pagina.escenaParaImprimir(anchoUtil)
        return Impresor.paginar(escena, orientacion)
    }

    /** Print framework del sistema: preview paginada del propio Android. */
    private fun imprimirSistema() {
        try {
            val doc = documentoImpreso(Orientacion.VERTICAL)
            val pm = getSystemService(PrintManager::class.java)
            pm.print("navegador-nia", AdaptadorImpresion(doc), null)
            estado.text = "Enviando a imprimir (" + doc.numeroPaginas + " paginas)"
            traza("imprimir", "print framework con " + doc.numeroPaginas + " paginas")
        } catch (e: Exception) {
            estado.text = "no se pudo abrir el dialogo de impresion"
            traza("imprimir", "error imprimiendo: " + e.javaClass.simpleName)
        }
    }

    /** Destino del PDF por SAF (ACTION_CREATE_DOCUMENT, application/pdf). */
    private fun pedirGuardarPdf() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("application/pdf")
        intent.putExtra(Intent.EXTRA_TITLE, "pagina.pdf")
        startActivityForResult(intent, REQUEST_GUARDAR_PDF)
    }

    /** Pinta una pagina de ops sobre un canvas (PDF o impresion). */
    private fun pintarOps(canvas: Canvas, hoja: PaginaImpresa) {
        val brocha = Paint()
        for (op in hoja.ops) {
            when (op) {
                is PaintOp.DrawRect -> {
                    brocha.color = parseColorImpresion(op.color)
                    canvas.drawRect(
                        op.x.toFloat(), op.y.toFloat(),
                        (op.x + op.width).toFloat(), (op.y + op.height).toFloat(),
                        brocha
                    )
                }
                is PaintOp.DrawText -> {
                    brocha.color = parseColorImpresion(op.color)
                    brocha.textSize = op.fontSize.toFloat()
                    canvas.drawText(
                        op.text,
                        op.x.toFloat(),
                        (op.y + op.fontSize * 0.8).toFloat(),
                        brocha
                    )
                }
            }
        }
    }

    /** Color de impresion con el mismo fallback claro del PaintView. */
    private fun parseColorImpresion(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.rgb(176, 176, 192)
        }

    /** Adaptador del print framework: PDF pagina a pagina con
     *  cancelacion comprobada antes de cada pagina; sin bitmaps. */
    private inner class AdaptadorImpresion(private val doc: DocumentoPaginado) :
        PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("pagina.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(doc.numeroPaginas)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback
        ) {
            val pdf = PdfDocument()
            try {
                for (hoja in doc.paginas) {
                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                        return
                    }
                    val info = PdfDocument.PageInfo.Builder(
                        doc.anchoPt.toInt(), doc.altoPt.toInt(), hoja.numero + 1
                    ).create()
                    val paginaPdf = pdf.startPage(info)
                    pintarOps(paginaPdf.canvas, hoja)
                    pdf.finishPage(paginaPdf)
                }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(null)
            } finally {
                pdf.close()
            }
        }
    }

    private fun zoomMas() {
        pagina.fijarZoom(pagina.zoomActual() * 1.25)
        guardarZoomSitio()
        estado.text = "zoom " + (pagina.zoomActual() * 100).toInt() + "%"
    }

    private fun zoomMenos() {
        pagina.fijarZoom(pagina.zoomActual() / 1.25)
        guardarZoomSitio()
        estado.text = "zoom " + (pagina.zoomActual() * 100).toInt() + "%"
    }

    /** T078 v1 (R0199, RES-0094): reconstruye la cfg de gestos desde
     * Ajustes y la aplica EN CALIENTE (al arrancar y con cada cambio
     * de la seccion Gestos): toggles por gesto, banda y umbral del
     * borde, umbral y tiempo del tiron; recovery a los defectos del
     * motor si algo falta. Con gestos.barra off la barra vuelve a
     * verse y queda fija. */
    private fun aplicarGestos() {
        cfgGestos = AjustesGestos.config(ajustes)
        pagina.actualizarGestos(cfgGestos)
        tiron = TironRefresh(cfgGestos)
        if (!cfgGestos.barra) {
            ocultadorBarra.reset()
            filaOmnibox.visibility = LinearLayout.VISIBLE
        }
        traza(
            "gestos",
            "cfg: swipe=" + cfgGestos.swipe + " dobleTap=" + cfgGestos.dobleTap +
                " pinza=" + cfgGestos.pinza + " barra=" + cfgGestos.barra +
                " pull=" + cfgGestos.pullToRefresh + " borde=" + cfgGestos.bordePx +
                " umbral=" + cfgGestos.umbralSwipePx
        )
    }

    private fun mostrarIdentidad() {
        AlertDialog.Builder(this)
            .setTitle("Identidad")
            .setMessage(if (panelIdentidad.isEmpty()) "sin pagina cargada" else panelIdentidad)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun mostrarSugerencias() {
        val texto = omnibox.text.toString()
        val historial = navActual()?.historyUrls()?.asReversed() ?: emptyList()
        val lista = OmniboxSugerencias.sugerencias(texto, marcadores, historial, false)
        if (lista.isEmpty()) {
            estado.text = "sin sugerencias locales"
            return
        }
        val etiquetas = ArrayList<String>(lista.size)
        for (s in lista) {
            etiquetas.add(s.origen.name.lowercase() + ": " + s.titulo + " - " + s.url)
        }
        AlertDialog.Builder(this)
            .setTitle("Sugerencias")
            .setItems(etiquetas.toTypedArray()) { _, cual ->
                val s: Sugerencia = lista[cual]
                omnibox.setText(s.url)
                navegar(s.url, false, null)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** R0171: pinta con el motor y, si una pagina real revienta algo del
     * pipeline, el shell NO se cierra: se conserva la pagina previa y el
     * estado cuenta que fallo (honesto, sin WebView). */
    private fun pintar(html: String) {
        val hoja = hojaParaPintar
        hojaParaPintar = null
        val script = scriptParaPintar
        scriptParaPintar = null
        val cosmeticos = cosmeticosParaPintar
        cosmeticosParaPintar = null
        val hostCos = hostCosmeticos
        hostCosmeticos = null
        try {
            pagina.cargarHojaModulo(hoja)
            pagina.cargarScriptModulo(script)
            pagina.cargarCosmeticosModulo(cosmeticos, hostCos)
            pagina.loadPagina(html, cssNavegador(), true)
            scroll.scrollTo(0, 0)
        } catch (e: Exception) {
            estado.text = "el motor no pudo pintar la pagina: " + e
        }
    }

    private fun pintaPagina(o: NavOutcome.Loaded) {
        val page = o.page
        estado.text = "LOADED " + page.status + " - " + hostDe(page.url) + " - " + page.url + " | ops=" + pagina.opsCount()
        traza("nav", "loaded " + page.status + " " + hostDe(page.url))
        registrarVisita(page.url)
        omnibox.setText(page.url)
        val panel = StringBuilder()
        for (linea in PanelIdentidad.lineas(page.url, page.tls)) {
            panel.append(linea)
            panel.append('\n')
        }
        for (evento in page.events) {
            if (evento.url.startsWith("excepcion-http:")) {
                panel.append("Excepcion HTTP concedida por el usuario para ")
                panel.append(evento.url.substring(15))
                panel.append('\n')
            }
        }
        panelIdentidad = panel.toString()
        aplicarSitio(page.url)
        val cifrada = page.url.startsWith("https")
        indicador.text = if (cifrada) "https" else "http"
        indicador.setTextColor(NiaUi.color(if (cifrada) colores.accent else colores.warn))
        val html = page.html
        if (html != null) {
            pintar(html)
        } else {
            // C6 v1: sin html la hoja ganada se descarta (vive con el documento)
            hojaParaPintar = null
            // C5 v1: sin html el script ganado se descarta (vive con el documento)
            scriptParaPintar = null
            // T053 v1: sin html la lista cosmetica ganada se descarta
            // (vive con el documento)
            cosmeticosParaPintar = null
            hostCosmeticos = null
        }
    }

    private fun navActual(): NavController? {
        val id = tabs.activa()
        if (id == null) {
            return null
        }
        return tabs.nav(id)
    }

    private fun nuevaPestana(kind: ProfileKind) {
        when (val r = tabs.crear(kind, "about:nia", "Nueva pestana", null)) {
            is TabCrear.Ok -> {
                tabs.activar(r.id)
                omnibox.setText("")
                traza("tabs", "nueva " + kind + " id=" + r.id)
                pintar(paginaNueva())
                estado.text = if (kind == ProfileKind.INCOGNITO) {
                    "pestana incognito: cookies solo en memoria, perfil borrado al cerrar la ultima"
                } else {
                    "nueva pestana"
                }
                pintarStrip()
            }
            is TabCrear.PresupuestoExcedido -> estado.text = "demasiadas pestanas abiertas"
            is TabCrear.NoExiste -> estado.text = "no se pudo crear la pestana"
        }
    }

    /** T109 v1 (R0361, RES-0146): linea de sesion persistida: marca de
     *  activa, URL y titulo. JAMAS cookies, previews ni formularios. */
    private data class LineaSesion(val activa: Boolean, val url: String, val titulo: String)

    /** Lee sesion-pestanas.tsv (todo-o-nada: una sola linea corrupta
     *  descarta el archivo ENTERO y arranca about:nia). Solo URLs
     *  http/https viajan; las about: no se guardan. Tope 40 lineas. */
    private fun leerSesionPestanas(): List<LineaSesion> {
        return try {
            val f = File(filesDir, "sesion-pestanas.tsv")
            if (!f.exists()) {
                emptyList()
            } else {
                val out = ArrayList<LineaSesion>()
                var n = 0
                for (cruda in f.readText(Charsets.UTF_8).split('\n')) {
                    val linea = cruda.trimEnd('\r')
                    if (linea.isEmpty()) continue
                    n += 1
                    if (n > 40) return emptyList()
                    val partes = linea.split('\t')
                    if (partes.size != 3) return emptyList()
                    val marca = partes[0]
                    val url = partes[1]
                    val marcaValida = marca == "0" || marca == "1"
                    if (!marcaValida ||
                        (!url.startsWith("http://") && !url.startsWith("https://"))
                    ) {
                        return emptyList()
                    }
                    out.add(LineaSesion(marca == "1", url, partes[2]))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Guarda la sesion NORMAL (orden, activa, URL y titulo) en
     *  sesion-pestanas.tsv con escritura atomica (tmp+rename; si el
     *  rename falla se escribe directo). El incognito JAMAS entra y
     *  una URL con tab o salto JAMAS viaja (esa pestana se pierde de
     *  la sesion; no corrompe el archivo). */
    private fun guardarSesionPestanas() {
        try {
            val f = File(filesDir, "sesion-pestanas.tsv")
            val tmp = File(filesDir, "sesion-pestanas.tsv.tmp")
            val sb = StringBuilder()
            val idActiva = tabs.activa()
            for (t in tabs.tarjetas(ProfileKind.NORMAL)) {
                val url = t.url
                if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
                if (url.contains('\t') || url.contains('\n') || url.contains('\r')) continue
                val titulo = t.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                sb.append(if (t.id == idActiva) "1" else "0")
                    .append('\t').append(url)
                    .append('\t').append(titulo)
                    .append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(sb.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            traza("tabs", "no se pudo guardar la sesion de pestanas")
        }
    }

    /** T109 v1: crea las pestanas de la sesion restaurada respetando
     *  orden y activa (crear NO navega: solo la activa recarga al
     *  arrancar y el resto al cambiar); sin sesion arranca about:nia. */
    private fun restaurarSesion() {
        val lineas = leerSesionPestanas()
        if (lineas.isEmpty()) {
            tabs.crear(ProfileKind.NORMAL, "about:nia", "Nueva pestana", null)
            return
        }
        var ultima: Long? = null
        var activa: Long? = null
        for (l in lineas) {
            val r = tabs.crear(ProfileKind.NORMAL, l.url, l.titulo, null)
            if (r is TabCrear.Ok) {
                ultima = r.id
                if (l.activa) activa = r.id
            } else {
                break
            }
        }
        val id = activa ?: ultima
        if (id != null) {
            tabs.activar(id)
            traza("tabs", "sesion restaurada con " + tabs.contar() + " pestana(s)")
        }
    }

    private fun cambiarPestana(id: Long) {
        if (!tabs.activar(id)) {
            return
        }
        pintarStrip()
        traza("tabs", "activa id=" + id)
        val nc = tabs.nav(id)
        val page = nc?.currentPage()
        if (page == null) {
            // T109 v1 (R0361, RES-0146): pestana restaurada sin pagina
            // viva: recarga la URL de su tarjeta (el motor no navega ni
            // reproduce solo; sin autoplay inesperado).
            val urlTarjeta = tabs.tarjeta(id)?.url
            if (urlTarjeta != null && (urlTarjeta.startsWith("http://") || urlTarjeta.startsWith("https://"))) {
                estado.text = "pestana restaurada: recargando"
                navegar(urlTarjeta, false, null)
                return
            }
            omnibox.setText("")
            pintar(paginaNueva())
            estado.text = "pestana activa sin pagina"
            return
        }
        val cifrada = page.url.startsWith("https")
        indicador.text = if (cifrada) "https" else "http"
        indicador.setTextColor(NiaUi.color(if (cifrada) colores.accent else colores.warn))
        omnibox.setText(page.url)
        aplicarSitio(page.url)
        estado.text = "LOADED " + page.status + " - " + hostDe(page.url) + " - " + page.url + " | ops=" + pagina.opsCount()
        val html = page.html
        if (html != null) {
            pintar(html)
        }
    }

    private fun cerrarPestana(id: Long) {
        val t = tabs.tarjeta(id)
        if (t != null && t.kind == ProfileKind.INCOGNITO &&
            tabs.contar(ProfileKind.INCOGNITO) == 1
        ) {
            AlertDialog.Builder(this)
                .setTitle("Cerrar la ultima incognito")
                .setMessage("Se borrara TODO el perfil incognito (cookies, storage, cache) de la memoria. Cerrar?")
                .setPositiveButton("Cerrar") { _, _ -> cerrarPestanaYa(id) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        cerrarPestanaYa(id)
    }

    private fun cerrarPestanaYa(id: Long) {
        val titulo = tabs.tarjeta(id)?.title ?: ""
        val cierre = tabs.cerrar(id) ?: return
        red.borrar(id)
        // T109 v2 (R0366, RES-0148): la politica de media para la
        // reproduccion in-page al morir su pestana (nunca huerfana).
        val mediaParada = playerAbierto && id == pestanaMedia
        if (mediaParada) {
            cerrarPlayer()
            traza("media", "pestana del medio cerrada: player parado")
        }
        val mensaje = if (cierre.perfilBorrado) {
            traza("tabs", "ultima incognito cerrada: perfil borrado (wipe)")
            "ultima incognito cerrada: perfil borrado de memoria"
        } else {
            traza("tabs", "pestana cerrada id=" + id)
            "pestana cerrada"
        }
        val activa = tabs.activa()
        if (activa == null) {
            nuevaPestana(ProfileKind.NORMAL)
        } else {
            cambiarPestana(activa)
        }
        estado.text = if (mediaParada) mensaje + " | media de la pagina parada" else mensaje
        avisoCierre.removeCallbacks(esconderAviso)
        if (cierre.perfilBorrado) {
            avisoCierre.visibility = LinearLayout.GONE
        } else {
            avisoTexto.text = "Pestana cerrada" + (if (titulo.isEmpty()) "" else ": " + titulo)
            avisoCierre.visibility = LinearLayout.VISIBLE
            avisoCierre.postDelayed(esconderAviso, 5000)
            traza("tabs", "aviso de deshacer listo para id=" + id)
        }
    }

    private fun reabrirPestana() {
        reabrirResultado(tabs.reabrir())
    }

    /** Menu de cerradas recientes (T075 v1): la mas reciente arriba y
     * la elegida se reabre con el motor; las incognito destruidas
     * JAMAS aparecen (el motor las retira al morir el perfil). */
    private fun mostrarCerradas() {
        val cerradas = tabs.reabiertas()
        if (cerradas.isEmpty()) {
            estado.text = "sin pestanas cerradas recientes"
            return
        }
        val recientes = cerradas.asReversed()
        val etiquetas = recientes.map { c ->
            val titulo = if (c.title.length > 16) c.title.substring(0, 15) + "..." else c.title
            val priv = if (c.kind == ProfileKind.INCOGNITO) "[priv] " else ""
            priv + titulo + " - " + c.url
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Cerradas recientes")
            .setItems(etiquetas) { _, cual ->
                reabrirResultado(tabs.reabrirEn(cerradas.size - 1 - cual))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Ejecuta un reabrir (del menu o de la lista): activa, pinta y navega. */
    private fun reabrirResultado(r: TabCrear) {
        when (r) {
            is TabCrear.Ok -> {
                tabs.activar(r.id)
                pintarStrip()
                traza("tabs", "reabierta id=" + r.id)
                val url = tabs.tarjeta(r.id)?.url
                if (url == null || url.startsWith("about:")) {
                    omnibox.setText("")
                    pintar(paginaNueva())
                    estado.text = "pestana reabierta"
                } else {
                    estado.text = "pestana reabierta: recargando"
                    navegar(url, false, null)
                }
            }
            is TabCrear.PresupuestoExcedido -> estado.text = "demasiadas pestanas abiertas"
            is TabCrear.NoExiste -> estado.text = "nada que reabrir"
        }
    }

    /** Host legible de una URL para la linea de estado (hallazgo R0174-a). */
    private fun hostDe(url: String): String {
        val pos = url.indexOf("://")
        val resto = if (pos >= 0) url.substring(pos + 3) else url
        val fin = resto.indexOf('/')
        if (fin >= 0) {
            return resto.substring(0, fin)
        }
        val q = resto.indexOf('?')
        return if (q >= 0) resto.substring(0, q) else resto
    }

    /** T087 v1 (R0187): compartir la pagina actual. SOLO la URL final
     * de la pestana activa viaja al chooser (about: no comparte) y
     * el incognito NO se filtra: decide quien recibe. */
    private fun compartirPagina() {
        val url = navActual()?.currentPage()?.url
        if (url == null || url.startsWith("about:")) {
            estado.text = "sin pagina actual para compartir"
            return
        }
        traza("share", "compartir " + hostDe(url))
        val envio = Intent(Intent.ACTION_SEND)
        envio.type = "text/plain"
        envio.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(envio, "Compartir"))
    }

    /** T077 v1 (R0195, RES-0093): menu propio ANCLADO al contenido bajo
     * el toque largo (enlace/imagen/texto resueltos con el hitTest del
     * motor). El portapapeles es explicito y solo copia lo resuelto;
     * Abrir pasa por el mismo camino que el omnibox (navegar), nunca un
     * esquema privilegiado; Cancelar no hace nada. */
    private fun mostrarMenuToque(info: PaintView.InfoToqueLargo) {
        val opciones = when (info.tipo) {
            "enlace" -> arrayOf(
                "Abrir enlace",
                "Copiar enlace",
                "Copiar texto del enlace",
                "Compartir enlace"
            )
            "imagen" -> arrayOf(
                "Abrir imagen",
                "Copiar URL de imagen",
                "Compartir imagen"
            )
            else -> arrayOf("Copiar texto")
        }
        AlertDialog.Builder(this)
            .setTitle("Toque largo (" + info.tipo + ")")
            .setMessage(if (info.url.isEmpty()) recorta(info.texto) else info.url)
            .setItems(opciones) { _, cual ->
                when (info.tipo) {
                    "enlace" -> when (cual) {
                        0 -> navegar(info.url, false, info.url)
                        1 -> copiarAlPortapapeles(info.url)
                        2 -> copiarAlPortapapeles(info.texto)
                        3 -> compartirTexto(info.url)
                    }
                    "imagen" -> when (cual) {
                        0 -> navegar(info.url, false, info.url)
                        1 -> copiarAlPortapapeles(info.url)
                        2 -> compartirTexto(info.url)
                    }
                    else -> copiarAlPortapapeles(info.texto)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** T079 v1 (RES-0095): menu propio de la seleccion de texto:
     *  Copiar / Compartir / Buscar / Guardar en favoritos con el
     *  fragmento :~:text=. Todo explicito, sin caminos privilegiados. */
    private fun mostrarMenuSeleccion(texto: String) {
        if (texto.isEmpty()) return
        val opciones = arrayOf(
            "Copiar",
            "Compartir",
            "Buscar",
            "Guardar en favoritos con fragmento"
        )
        AlertDialog.Builder(this)
            .setTitle("Seleccion de texto")
            .setMessage(recorta(texto))
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> {
                        copiarAlPortapapeles(texto)
                        pagina.limpiarSeleccion()
                    }
                    1 -> {
                        compartirTexto(texto)
                        pagina.limpiarSeleccion()
                    }
                    2 -> {
                        omnibox.setText(texto)
                        resolverYnavegar()
                        pagina.limpiarSeleccion()
                    }
                    else -> guardarFavoritoConFragmento(texto)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** T079 v1: guarda la pagina actual en favoritos con el fragmento
     *  de texto (:~:text=); si el texto no es anclable se guarda la URL
     *  sola y se avisa (fallback honesto). */
    private fun guardarFavoritoConFragmento(texto: String) {
        val url = navActual()?.currentPage()?.url
        if (url == null || url.startsWith("about:")) {
            estado.text = "sin pagina actual para guardar"
            return
        }
        val urlCon = FragmentoUrl.crear(url, texto)
        val bm = when (val r = marcadores.add(tituloDe(texto), urlCon)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> null
        }
        if (bm == null) {
            estado.text = "favorito no creado"
            return
        }
        marcadores.pin(bm.id)
        if (urlCon == url) {
            estado.text = "favorito sin fragmento (texto no anclable): " + hostDe(url)
            traza("accesos", "favorito fallback " + hostDe(url))
        } else {
            estado.text = "favorito con fragmento: " + hostDe(url)
            traza("accesos", "favorito con fragmento " + hostDe(url))
        }
        guardarMarcadores()
        repintarSiNueva()
    }

    /** Titulo del favorito: el texto seleccionado recortado a 60
     *  chars sin partir nunca un surrogate par. */
    private fun tituloDe(texto: String): String {
        var fin = if (texto.length > 60) 60 else texto.length
        if (fin > 0 && fin < texto.length && texto[fin - 1].isHighSurrogate()) fin--
        return texto.substring(0, fin)
    }

    /** Texto recortado para el dialogo del toque largo. */
    private fun recorta(s: String): String =
        if (s.length > 120) s.substring(0, 120) + "..." else s

    /** Copia EXPLICITAMENTE al portapapeles del sistema; vacio no copia. */
    private fun copiarAlPortapapeles(texto: String) {
        if (texto.isEmpty()) {
            estado.text = "nada que copiar"
            return
        }
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Navegador NIA", texto))
        estado.text = "copiado al portapapeles (" + texto.length + " chars)"
        traza("gestos", "copiado al portapapeles")
    }

    /** Comparte un texto resuelto (enlace/imagen del toque largo). */
    private fun compartirTexto(texto: String) {
        if (texto.isEmpty()) {
            estado.text = "sin nada para compartir"
            return
        }
        val envio = Intent(Intent.ACTION_SEND)
        envio.type = "text/plain"
        envio.putExtra(Intent.EXTRA_TEXT, texto)
        startActivity(Intent.createChooser(envio, "Compartir"))
    }

    /** T087/T088 v1 (R0187): SEND/VIEW entrantes. El texto o URI
     * externa se resuelve con el OmniboxInput REAL y pide
     * confirmacion ANTES de abrir; lo que no resuelve JAMAS abre. */
    private fun manejarIntentExterno(envio: Intent) {
        val accion = envio.action ?: return
        if (accion != Intent.ACTION_SEND && accion != Intent.ACTION_VIEW) return
        val texto = if (accion == Intent.ACTION_SEND) envio.getStringExtra(Intent.EXTRA_TEXT) else envio.dataString
        if (texto == null || texto.trim().isEmpty()) {
            estado.text = "externo sin texto para abrir"
            return
        }
        when (val r = OmniboxInput.resolver(texto, baseBusqueda())) {
            is OmniboxInput.Resolucion.Navegar ->
                confirmarExterno("Abrir enlace externo?", r.url, r.dominioImplicito, texto)
            is OmniboxInput.Resolucion.Buscar ->
                confirmarExterno("Buscar texto externo?", r.url, false, texto)
            is OmniboxInput.Resolucion.EsquemaNoSoportado -> {
                estado.text = "externo con esquema no soportado: " + r.esquema
                traza("externo", "esquema no soportado")
            }
            is OmniboxInput.Resolucion.ErrorDeParseo -> {
                estado.text = "externo invalido: " + r.motivo
                traza("externo", "url invalida")
            }
            is OmniboxInput.Resolucion.Vacio -> estado.text = "externo vacio"
        }
    }

    /** T087/T088 v1 (R0187): dialogo ADR-019 para abrir lo externo.
     * Cancelar JAMAS navega; aceptar abre en la pestana activa, sea
     * normal o incognito (sin filtro automatico). */
    private fun confirmarExterno(titulo: String, url: String, dominioImplicito: Boolean, textoOriginal: String) {
        traza("externo", "confirmar " + hostDe(url))
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(url)
            .setPositiveButton("Abrir") { _, _ ->
                navegar(url, dominioImplicito, textoOriginal)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                estado.text = "externo cancelado"
            }
            .show()
    }

    /** T076 v1 (R0189): gestion de accesos de la pagina nueva: los
     * accesos son marcadores fijados (pin) del motor T060 y la
     * pagina los pinta junto a los recientes no privados. */
    private fun mostrarAccesos() {
        traza("accesos", "accesos abierto con " + marcadores.accesosRapidos().size)
        AlertDialog.Builder(this)
            .setTitle("Accesos de la pagina nueva")
            .setItems(arrayOf("Anadir un acceso", "Quitar un acceso", "Vaciar accesos", "Guardar esta pagina en favoritos")) { _, cual ->
                when (cual) {
                    0 -> pedirAcceso()
                    1 -> elegirQuitarAcceso()
                    2 -> confirmarVaciarAccesos()
                    3 -> guardarEnFavoritos()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Pide titulo y URL; la URL se valida con la resolucion REAL
     * del OmniboxInput y el acceso se fija (pin) sin abrir nada. */
    private fun pedirAcceso() {
        val campoTitulo = EditText(this)
        campoTitulo.hint = "Titulo del acceso"
        val campoUrl = EditText(this)
        campoUrl.hint = "URL (http o https)"
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.addView(campoTitulo)
        caja.addView(campoUrl)
        AlertDialog.Builder(this)
            .setTitle("Anadir un acceso")
            .setView(caja)
            .setPositiveButton("Anadir") { _, _ ->
                val titulo = campoTitulo.text.toString().trim()
                val texto = campoUrl.text.toString().trim()
                if (titulo.isEmpty() || texto.isEmpty()) {
                    estado.text = "acceso sin titulo o sin url"
                    return@setPositiveButton
                }
                when (val r = OmniboxInput.resolver(texto, baseBusqueda())) {
                    is OmniboxInput.Resolucion.Navegar -> fijarAcceso(titulo, r.url)
                    is OmniboxInput.Resolucion.Buscar -> fijarAcceso(titulo, r.url)
                    is OmniboxInput.Resolucion.EsquemaNoSoportado ->
                        estado.text = "acceso con esquema no soportado: " + r.esquema
                    is OmniboxInput.Resolucion.ErrorDeParseo ->
                        estado.text = "acceso invalido: " + r.motivo
                    is OmniboxInput.Resolucion.Vacio -> estado.text = "acceso vacio"
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Crea el marcador, lo fija como acceso y persiste (T076). */
    private fun fijarAcceso(titulo: String, url: String) {
        val bm = when (val r = marcadores.add(titulo, url)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> null
        }
        if (bm == null) {
            estado.text = "acceso no creado (titulo o url no validos)"
            traza("accesos", "no creado")
            return
        }
        val p = marcadores.pin(bm.id)
        if (p is Outcome.Failure) {
            estado.text = "acceso creado sin fijar"
            traza("accesos", "pin fallo")
        } else {
            estado.text = "acceso anadido: " + bm.titulo
            traza("accesos", "anadido " + hostDe(bm.url))
        }
        guardarMarcadores()
        repintarSiNueva()
    }

    /** Lista los accesos fijados para quitar uno (solo el pin: el
     * marcador vive y la sugerencia del omnibox lo sigue usando). */
    private fun elegirQuitarAcceso() {
        val pins = marcadores.accesosRapidos()
        if (pins.isEmpty()) {
            estado.text = "sin accesos que quitar"
            return
        }
        val lineas = pins.map { it.titulo + " - " + hostDe(it.url) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quitar un acceso")
            .setItems(lineas) { _, cual ->
                val r = marcadores.unpin(pins[cual].id)
                if (r is Outcome.Failure) {
                    estado.text = "acceso no quitado"
                } else {
                    estado.text = "acceso quitado: " + pins[cual].titulo
                    traza("accesos", "quitado " + hostDe(pins[cual].url))
                }
                guardarMarcadores()
                repintarSiNueva()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Vacia los accesos con confirmacion (ADR-019): los marcadores
     * vivos NUNCA se borran, solo se desfijan. */
    private fun confirmarVaciarAccesos() {
        AlertDialog.Builder(this)
            .setTitle("Vaciar accesos")
            .setMessage("Se quitaran todos los accesos de la pagina nueva. Los marcadores vivos NO se borran. Vaciar?")
            .setPositiveButton("Vaciar") { _, _ ->
                val antes = marcadores.accesosRapidos().size
                for (bm in marcadores.accesosRapidos()) {
                    marcadores.unpin(bm.id)
                }
                estado.text = "accesos vaciados (" + antes + ")"
                traza("accesos", "vaciados (" + antes + ")")
                guardarMarcadores()
                repintarSiNueva()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Guarda la pagina ACTUAL en favoritos y la fija como acceso
     * (about: JAMAS se guarda: no es una pagina real). */
    private fun guardarEnFavoritos() {
        val url = navActual()?.currentPage()?.url
        if (url == null || url.startsWith("about:")) {
            estado.text = "sin pagina actual para guardar"
            return
        }
        val bm = when (val r = marcadores.add(hostDe(url), url)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> null
        }
        if (bm == null) {
            estado.text = "favorito no creado"
            return
        }
        marcadores.pin(bm.id)
        estado.text = "guardado en favoritos: " + hostDe(url)
        traza("accesos", "guardado " + hostDe(url))
        guardarMarcadores()
        repintarSiNueva()
    }

    /** Persiste los marcadores tras cada cambio (T076): un fallo de
     * escritura JAMAS rompe el estado vivo (solo avisa). */
    private fun guardarMarcadores() {
        try {
            File(filesDir, "marcadores.tsv").writeText(marcadores.exportar())
        } catch (e: Exception) {
            estado.text = "no se pudieron guardar los marcadores"
            traza("accesos", "escritura fallo")
        }
    }

    /** Repinta la pagina nueva si la activa es about: (T076). */
    private fun repintarSiNueva() {
        val actual = navActual()?.currentPage()?.url
        if (actual != null && actual.startsWith("about:")) {
            pintar(paginaNueva())
        }
    }

    /** T103 v1 (R0349, RES-0142): pantalla de torrents del shell sobre
     * el motor T047 verificado con transportes REALES de plataforma
     * (PlatformTrackerTransport y PlatformPeerTransport en
     * PlatformTorrent.kt), NUNCA demos enlatadas: anadir .torrent por
     * SAF con seleccion de archivos ANTES de transferir o magnet real
     * (announce + metadatos ut_metadata de peers reales), progreso por
     * piezas verificadas SHA-1, Pausar/Reanudar/Cancelar con
     * confirmacion ADR-019, borrado de la sesion y guardado explicito
     * a SAF del archivo elegido desde las piezas completas. Cuota
     * CPU/red honesta: UNA bajada a la vez (pool de 1) y sin siembra
     * (solo descarga); las piezas viven en memoria (v1 sin escritura
     * automatica a disco) y el progreso es global por sesion. */
    private fun mostrarTorrents() {
        val sesiones = torrents.list()
        val lineas: Array<String> = if (sesiones.isEmpty()) {
            arrayOf("(sin torrents todavia)")
        } else {
            sesiones.map { s ->
                val p = s.progress()
                s.meta.name + " " + s.state.name.lowercase() + " " +
                    p.completedPieces + "/" + p.totalPieces + " piezas " +
                    p.downloadedBytes + " B"
            }.toTypedArray()
        }
        AlertDialog.Builder(this)
            .setTitle("Torrents")
            .setItems(lineas) { _, cual ->
                if (sesiones.isEmpty()) {
                    return@setItems
                }
                val s = sesiones[cual]
                when (s.state) {
                    TorrentState.QUEUED, TorrentState.RUNNING ->
                        elegirAccionTorrent(s.meta.infoHashHex, s.meta.name)
                    TorrentState.PAUSED -> elegirAccionPausadaTorrent(s.meta.infoHashHex)
                    TorrentState.DONE -> elegirArchivoGuardar(s.meta.infoHashHex)
                    TorrentState.CANCELLED -> confirmarBorrarTorrent(s.meta.infoHashHex)
                }
            }
            .setNeutralButton("Anadir") { _, _ -> elegirOrigenTorrent() }
            .setPositiveButton("Actualizar") { _, _ -> mostrarTorrents() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Accion sobre un torrent vivo: Pausar (REAL del motor, no un
     *  fallo disfrazado), Reintentar (relanza la bajada, util cuando
     *  se quedo sin peers) o Cancelar (terminal, ADR-019). */
    private fun elegirAccionTorrent(hash: String, nombre: String) {
        AlertDialog.Builder(this)
            .setTitle("Torrent: " + nombre)
            .setItems(arrayOf("Pausar", "Reintentar", "Cancelar")) { _, cual ->
                when (cual) {
                    0 -> {
                        torrents.find(hash)?.pause()
                        estado.text = "torrent pausado"
                        traza("torrents", "pausar " + hash.take(8))
                        mostrarTorrents()
                    }
                    1 -> relanzarTorrent(hash)
                    2 -> confirmarCancelarTorrent(hash)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Accion sobre un torrent pausado: Reanudar (relanza la bajada
     *  con announce started), Cancelar o Borrar de la lista. */
    private fun elegirAccionPausadaTorrent(hash: String) {
        AlertDialog.Builder(this)
            .setTitle("Torrent pausado")
            .setItems(arrayOf("Reanudar", "Cancelar", "Borrar")) { _, cual ->
                when (cual) {
                    0 -> relanzarTorrent(hash)
                    1 -> confirmarCancelarTorrent(hash)
                    2 -> confirmarBorrarTorrent(hash)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Reanudar/Reintentar: resume la sesion y relanza la bajada en
     *  el pool de UN hilo (la cuota de v1). */
    private fun relanzarTorrent(hash: String) {
        val s = torrents.find(hash)
        if (s == null) {
            estado.text = "torrent no esta en la lista"
            return
        }
        s.resume()
        estado.text = "torrent reanudado"
        traza("torrents", "reanudar " + hash.take(8))
        poolTorrents.execute { bajarTorrent(s) }
        mostrarTorrents()
    }

    /** Cancelar es terminal y pide confirmacion ADR-019; el announce
     *  stopped lo manda la propia bajada al ver CANCELLED. */
    private fun confirmarCancelarTorrent(hash: String) {
        AlertDialog.Builder(this)
            .setTitle("Cancelar torrent")
            .setMessage("Cancelar corta la descarga para siempre (para pausar, Pausar).")
            .setPositiveButton("Si") { _, _ ->
                val s = torrents.find(hash)
                val viva = s != null && s.state != TorrentState.DONE &&
                    s.state != TorrentState.CANCELLED
                s?.cancel()
                estado.text = "torrent cancelado"
                traza("torrents", "cancelar " + hash.take(8) + " " + viva)
                mostrarTorrents()
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** Borrar retira la sesion del gestor (con sus piezas en memoria);
     *  lo ya guardado a SAF sigue en su sitio. */
    private fun confirmarBorrarTorrent(hash: String) {
        AlertDialog.Builder(this)
            .setTitle("Borrar torrent")
            .setMessage("Borrar retira la sesion y sus piezas en memoria (lo guardado a SAF sigue).")
            .setPositiveButton("Si") { _, _ ->
                val ok = torrents.delete(hash)
                estado.text = if (ok) "torrent borrado de la lista" else "torrent no estaba"
                traza("torrents", "borrar " + hash.take(8) + " " + ok)
                mostrarTorrents()
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** Guardar a SAF UN archivo de los elegidos, ensamblando desde las
     *  piezas completas verificadas de la sesion (solo en DONE). */
    private fun elegirArchivoGuardar(hash: String) {
        val s = torrents.find(hash) ?: return
        val indices = s.meta.files.indices.filter { s.isFileNeeded(it) }
        val nombres = indices.map { s.meta.files[it].path }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Guardar archivo del torrent")
            .setItems(nombres) { _, cual ->
                guardarTorrentPendiente = Pair(hash, indices[cual])
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.setType("application/octet-stream")
                intent.putExtra(Intent.EXTRA_TITLE, nombres[cual].split('/').last())
                startActivityForResult(intent, REQUEST_GUARDAR_TORRENT)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Origen del torrent nuevo: archivo .torrent por SAF o enlace
     *  magnet pegado como texto. */
    private fun elegirOrigenTorrent() {
        AlertDialog.Builder(this)
            .setTitle("Anadir torrent")
            .setItems(arrayOf("Archivo .torrent (SAF)", "Enlace magnet (texto)")) { _, cual ->
                if (cual == 0) anadirArchivoTorrent() else anadirMagnet()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Abrir un .torrent real del almacenamiento (SAF). */
    private fun anadirArchivoTorrent() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("application/x-bittorrent")
        startActivityForResult(intent, REQUEST_TORRENT)
    }

    /** Pedir el magnet por texto: los metadatos NO estan en el
     *  enlace, se traen de peers reales (BEP-9) en segundo plano. */
    private fun anadirMagnet() {
        val campo = EditText(this)
        campo.hint = "magnet:?xt=urn:btih:..."
        AlertDialog.Builder(this)
            .setTitle("Anadir magnet")
            .setView(campo)
            .setPositiveButton("Anadir") { _, _ ->
                val texto = campo.text.toString().trim()
                poolTorrents.execute { crearDesdeMagnet(texto) }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Magnet REAL en segundo plano: announce a los trackers del
     *  enlace, metadatos ut_metadata de los peers (BEP-9) y torrent
     *  montado desde el info dict; sin trackers o sin peers con
     *  metadatos avisa honesto (v1 sin DHT). */
    private fun crearDesdeMagnet(uri: String) {
        try {
            val info = MagnetParser.parse(uri)
            if (info.trackers.isEmpty()) {
                avisarTorrent("magnet sin trackers (v1 sin DHT)")
                return
            }
            val hashBytes = unhex(info.infoHashHex)
            val cliente = TrackerClient(PlatformTrackerTransport())
            var peers: List<TrackerPeer> = emptyList()
            for (t in info.trackers) {
                try {
                    peers = cliente.announce(
                        t,
                        TrackerAnnounceRequest(hashBytes, peerIdTorrent, 0, 0L, 0L, 0L, AnnounceEvent.NONE)
                    ).peers
                    break
                } catch (e: Exception) {
                }
            }
            if (peers.isEmpty()) {
                avisarTorrent("magnet sin peers para los metadatos")
                return
            }
            val clienteMeta = PeerMetadataClient(PlatformPeerTransport(), hashBytes, peerIdTorrent)
            var infoDict: ByteArray? = null
            for (p in peers) {
                infoDict = clienteMeta.fetchMetadata(p)
                if (infoDict != null) break
            }
            if (infoDict == null) {
                avisarTorrent("magnet sin metadatos (ningun peer con ut_metadata)")
                return
            }
            val torrent = torrentDesdeInfo(info.trackers[0], infoDict)
            runOnUiThread { pedirSeleccionTorrent(torrent) }
        } catch (e: TorrentFormatException) {
            avisarTorrent("magnet invalido: " + e.message)
        }
    }

    /** Seleccion de archivos ANTES de transferir (cuota disco de v1:
     *  solo lo elegido se baja y vive en memoria); un solo archivo no
     *  pregunta. */
    private fun pedirSeleccionTorrent(meta: TorrentMeta) {
        if (meta.files.size == 1) {
            arrancarTorrent(meta, meta.files.indices.toSet())
            return
        }
        val nombres = meta.files.map { it.path + " (" + it.length + " B)" }.toTypedArray()
        val marcas = BooleanArray(meta.files.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Elegir archivos de " + meta.name)
            .setMultiChoiceItems(nombres, marcas) { _, i, ok -> marcas[i] = ok }
            .setPositiveButton("Descargar") { _, _ ->
                val sel = meta.files.indices.filter { marcas[it] }.toSet()
                if (sel.isEmpty()) {
                    estado.text = "torrent sin archivos elegidos (nada)"
                    return@setPositiveButton
                }
                arrancarTorrent(meta, sel)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Crear la sesion en el gestor y lanzar la bajada en el pool de
     *  UN hilo (cuota CPU/red de v1). */
    private fun arrancarTorrent(meta: TorrentMeta, seleccion: Set<Int>) {
        if (torrents.find(meta.infoHashHex) != null) {
            estado.text = "torrent ya esta en la lista"
            return
        }
        val session = torrents.add(meta, seleccion)
        estado.text = "torrent en cola: " + meta.name
        traza("torrents", "sesion " + meta.infoHashHex.take(8) + " " + meta.name)
        poolTorrents.execute { bajarTorrent(session) }
    }

    /** La bajada REAL en el pool: announce started a los trackers HTTP
     *  del torrent, piezas desde los peers (PeerPoolSource) con la
     *  verificacion SHA-1 del motor, re-announce al quedarse sin
     *  piezas (hasta 3 intentos con espera) y parada honesta si
     *  ningun peer tiene datos o los datos no verifican (5 piezas
     *  rechazadas); pausa, cancelacion y final mandan su evento al
     *  tracker (stopped/completed). */
    private fun bajarTorrent(session: TorrentSession) {
        val meta = session.meta
        if (meta.trackers.isEmpty()) {
            avisarTorrent("torrent sin trackers (v1 sin DHT)")
            return
        }
        val cliente = TrackerClient(PlatformTrackerTransport())
        val peers = ArrayList<TrackerPeer>()
        val left = meta.files.indices
            .filter { session.isFileNeeded(it) }
            .sumOf { meta.files[it].length }
        fun pedir(evento: AnnounceEvent): Boolean {
            for (t in meta.trackers) {
                try {
                    val respuesta = cliente.announce(
                        t,
                        TrackerAnnounceRequest(meta.infoHash, peerIdTorrent, 0, 0L, 0L, left, evento)
                    )
                    peers.clear()
                    peers.addAll(respuesta.peers)
                    return true
                } catch (e: Exception) {
                }
            }
            return false
        }
        session.start()
        if (!pedir(AnnounceEvent.STARTED)) {
            avisarTorrent("torrent sin trackers alcanzables")
            return
        }
        val fuente = PeerPoolSource(peers, PlatformPeerTransport(), meta.infoHash, peerIdTorrent, meta.pieceCount())
        var sinPiezas = 0
        var rechazadas = 0
        while (true) {
            when (session.pump(fuente)) {
                PumpResult.COMPLETED -> {
                    rechazadas = 0
                    sinPiezas = 0
                }
                PumpResult.UNAVAILABLE -> {
                    sinPiezas += 1
                    if (sinPiezas >= 3) {
                        avisarTorrent("torrent sin peers con piezas (Reintentar)")
                        return
                    }
                    Thread.sleep(4000L)
                    pedir(AnnounceEvent.NONE)
                }
                PumpResult.REJECTED -> {
                    rechazadas += 1
                    if (rechazadas >= 5) {
                        avisarTorrent("torrent con piezas corruptas de todos los peers")
                        return
                    }
                }
                PumpResult.DONE -> {
                    pedir(AnnounceEvent.COMPLETED)
                    avisarTorrent("torrent completado: " + meta.name)
                    return
                }
                PumpResult.PAUSED -> {
                    pedir(AnnounceEvent.STOPPED)
                    avisarTorrent("torrent pausado")
                    return
                }
                PumpResult.CANCELLED -> {
                    pedir(AnnounceEvent.STOPPED)
                    avisarTorrent("torrent cancelado")
                    return
                }
            }
        }
    }

    /** Aviso de la bajada desde el hilo del pool: SIEMPRE al hilo de
     *  UI (estado + traza), nunca red desde el hilo de UI. */
    private fun avisarTorrent(mensaje: String) {
        runOnUiThread {
            estado.text = mensaje
            traza("torrents", mensaje)
        }
    }

    /** T092 v1 (R0355, RES-0144): player standalone sobre el backend
     *  REAL de plataforma. Origen: URL http/https directa (SIN
     *  descarga obligatoria) o archivo local por SAF (audio o video). */
    private fun reproducirMedia() {
        AlertDialog.Builder(this)
            .setTitle("Reproducir media")
            .setItems(arrayOf("URL http/https", "Archivo local (SAF)")) { _, cual ->
                if (cual == 0) pedirUrlMedia() else elegirArchivoMedia()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** URL de media por texto: la reproduce EN DIRECTO, sin bajarla. */
    private fun pedirUrlMedia() {
        val campo = EditText(this)
        campo.hint = "https://ejemplo.com/audio.mp3"
        AlertDialog.Builder(this)
            .setTitle("URL de media")
            .setView(campo)
            .setPositiveButton("Reproducir") { _, _ ->
                val texto = campo.text.toString().trim()
                if (!texto.startsWith("http://") && !texto.startsWith("https://")) {
                    estado.text = "media: la url debe empezar por http:// o https://"
                    return@setPositiveButton
                }
                abrirPlayer("url", texto)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Archivo local de audio o video por SAF. */
    private fun elegirArchivoMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        startActivityForResult(intent, REQUEST_MEDIA)
    }

    /** Player con controles propios: el MediaEngine ES la autoridad
     *  de la posicion (reloj integrado al rate actual) y la duracion
     *  viene del onPrepared REAL del backend; NUNCA se inventa una
     *  duracion ni se da por lista la fuente antes de tiempo. */
    private fun abrirPlayer(origen: String, fuente: String) {
        // T109 v2 (R0366, RES-0148): el standalone NO esta atado a
        // pestana; "pagina" la fija el puente antes de llegar aqui.
        if (origen != "pagina") pestanaMedia = null
        val contenedor = LinearLayout(this)
        contenedor.orientation = LinearLayout.VERTICAL
        val superficie = SurfaceView(this)
        contenedor.addView(
            superficie,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val relojTexto = TextView(this)
        contenedor.addView(relojTexto)
        val barra = SeekBar(this)
        contenedor.addView(barra)
        val botones = LinearLayout(this)
        botones.orientation = LinearLayout.HORIZONTAL
        val bPlay = Button(this)
        bPlay.text = "Reproducir"
        val bAtras = Button(this)
        bAtras.text = "-10s"
        val bAdelante = Button(this)
        bAdelante.text = "+10s"
        val bVolumen = Button(this)
        bVolumen.text = "Volumen"
        val bVel = Button(this)
        bVel.text = "x1"
        // T104 v1: botones de subtitulos del player.
        val bSubsOs = Button(this)
        bSubsOs.text = "Subs OS"
        val bSubsMenos = Button(this)
        bSubsMenos.text = "Subs -0.5s"
        val bSubsMas = Button(this)
        bSubsMas.text = "Subs +0.5s"
        val bSubsFuera = Button(this)
        bSubsFuera.text = "Sin subs"
        botones.addView(bPlay)
        botones.addView(bAtras)
        botones.addView(bAdelante)
        botones.addView(bVolumen)
        botones.addView(bVel)
        botones.addView(bSubsOs)
        botones.addView(bSubsMenos)
        botones.addView(bSubsMas)
        botones.addView(bSubsFuera)
        contenedor.addView(botones)
        // T104 v1: overlay de subs del player (cue activo).
        val subsTexto = TextView(this)
        contenedor.addView(subsTexto)
        vistaSubs = subsTexto
        bPlay.setOnClickListener {
            media.playPause()
            refrescarPlayer(bPlay, relojTexto, barra)
        }
        bAtras.setOnClickListener {
            media.seekBy(-10000L)
            refrescarPlayer(bPlay, relojTexto, barra)
        }
        bAdelante.setOnClickListener {
            media.seekBy(10000L)
            refrescarPlayer(bPlay, relojTexto, barra)
        }
        bVolumen.setOnClickListener {
            volumenMedia = if (volumenMedia > 0.0) 0.0 else 1.0
            media.setVolume(volumenMedia)
            bVolumen.text = if (volumenMedia > 0.0) "Volumen" else "Silencio"
        }
        bVel.setOnClickListener {
            pasoVelocidadMedia = (pasoVelocidadMedia + 1) % velocidadesMedia.size
            val v = velocidadesMedia[pasoVelocidadMedia]
            media.setRate(v)
            bVel.text = "x" + v
        }
        bSubsOs.setOnClickListener { pedirSubsOs() }
        bSubsMenos.setOnClickListener {
            pistaSubs?.shift(-500L)
            pintarSubs()
        }
        bSubsMas.setOnClickListener {
            pistaSubs?.shift(500L)
            pintarSubs()
        }
        bSubsFuera.setOnClickListener {
            pistaSubs = null
            pintarSubs()
        }
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, progreso: Int, desdeUsuario: Boolean) {}
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {
                media.seekTo(b?.progress?.toLong() ?: 0L)
                refrescarPlayer(bPlay, relojTexto, barra)
            }
        })
        superficie.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                backendMedia.setSurface(h.surface)
            }

            override fun surfaceChanged(h: SurfaceHolder, formato: Int, ancho: Int, alto: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                backendMedia.setSurface(null)
            }
        })
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Player (" + origen + ")")
            .setView(contenedor)
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo.setOnDismissListener { cerrarPlayer() }
        pedirAudioFocus()
        try {
            val fuenteMedia = if (origen == "url") {
                MediaSource.Remote(fuente)
            } else {
                MediaSource.Local(fuente)
            }
            media.load(fuenteMedia, autoplay = true)
        } catch (e: MediaException) {
            estado.text = "media: fuente invalida (" + e.message + ")"
            return
        }
        estado.text = "media: preparando (" + origen + ")"
        traza("media", "player abierto (" + origen + ")")
        dialogo.show()
        playerAbierto = true
        refrescarPlayer(bPlay, relojTexto, barra)
        relojTexto.postDelayed(object : Runnable {
            override fun run() {
                if (!playerAbierto) return
                refrescarPlayer(bPlay, relojTexto, barra)
                pintarSubs()
                relojTexto.postDelayed(this, 300L)
            }
        }, 300L)
    }

    /** Refresco del player: etiqueta, reloj y barra desde el motor. */
    private fun refrescarPlayer(bPlay: Button, relojTexto: TextView, barra: SeekBar) {
        val est = media.state()
        bPlay.text = if (est == MediaState.PLAYING) "Pausa" else "Reproducir"
        val posSeg = media.position() / 1000
        val durSeg = media.duration() / 1000
        var etiqueta = nombreEstadoMedia(est)
        if (est == MediaState.FAILED) {
            val err = media.lastError()
            if (err != null) etiqueta += ": " + err
        }
        relojTexto.text = formatoTiempo(posSeg) + " / " + formatoTiempo(durSeg) + " (" + etiqueta + ")"
        if (durSeg > 0) {
            barra.max = durSeg.toInt()
            if (!barra.isPressed) barra.progress = posSeg.toInt().coerceAtMost(durSeg.toInt())
        } else {
            barra.max = 0
            if (!barra.isPressed) barra.progress = 0
        }
    }

    private fun formatoTiempo(seg: Long): String =
        (seg / 60).toString() + ":" + (seg % 60).toString().padStart(2, '0')

    private fun nombreEstadoMedia(est: MediaState): String = when (est) {
        MediaState.IDLE -> "inactivo"
        MediaState.PREPARING -> "preparando"
        MediaState.READY -> "listo"
        MediaState.PLAYING -> "reproduciendo"
        MediaState.PAUSED -> "en pausa"
        MediaState.ENDED -> "terminado"
        MediaState.FAILED -> "fallo"
    }

    /** T092 v2 (R0358, RES-0145): puente HTMLMediaElement REAL
     *  (TK-111-1): los video/audio de las paginas hablan con el
     *  MediaEngine del shell; play abre el player v1 SOBRE EL MISMO
     *  backend REAL. Las ordenes que tocan vistas van al hilo de UI;
     *  las consultas (posicion/duracion/estado) leen el nucleo desde
     *  el hilo de los scripts. Exclusion v2: UN medio activo y sin
     *  eventos asincronos hacia la pagina (v3). */
    private inner class PuenteMediaPagina : MediaPuente {
        override fun reproducir(url: String, esVideo: Boolean) {
            runOnUiThread {
                // T109 v2 (R0366, RES-0148): la media in-page queda
                // atada a la pestana activa que la pidio.
                pestanaMedia = tabs.activa()
                abrirPlayer("pagina", url)
            }
        }

        override fun pausar() {
            runOnUiThread {
                if (media.state() == MediaState.PLAYING) media.pause()
            }
        }

        override fun posicionMs(): Long =
            if (playerAbierto) media.position() else 0L

        override fun duracionMs(): Long =
            if (playerAbierto) media.duration() else 0L

        override fun enPausa(): Boolean = media.state() != MediaState.PLAYING

        override fun volumen(): Double = volumenMedia

        override fun fijarVolumen(v: Double) {
            runOnUiThread { volumenMedia = media.setVolume(v) }
        }

        override fun velocidad(): Double = media.rate()

        override fun fijarVelocidad(rate: Double) {
            runOnUiThread { media.setRate(rate) }
        }

        override fun buscar(ms: Long) {
            runOnUiThread { media.seekTo(ms) }
        }
    }

    /** Cierre del player: corta el refresco y libera el backend REAL. */
    private fun cerrarPlayer() {
        playerAbierto = false
        media.close()
        soltarAudioFocus()
        pestanaMedia = null
        // T104 v1: la pista efimera muere con el player; las
        // descargadas persistentes siguen en el almacen privado.
        pistaSubs = null
        vistaSubs = null
        traza("media", "player cerrado")
    }

    /** T104 v1: pinta el cue activo de la pista en el overlay del
     *  player, sincronizado con la posicion del nucleo (el tick de
     *  300 ms repinta tras el seek). */
    private fun pintarSubs() {
        val vista = vistaSubs ?: return
        val pista = pistaSubs
        vista.text = pista?.textAt(media.position()) ?: ""
    }

    /** T104 v1 (R0376, RES-0151): dialogo Subs OS del player: cuenta
     *  (apiKey obligatoria, usuario y clave opcionales), Probar
     *  conexion, busqueda con seleccion de idioma, resultados,
     *  descarga a pista y descargadas persistentes con borrado. Los
     *  secretos viven SOLO en el almacen privado (OpenSubtitlesSesion)
     *  y JAMAS salen en traza, export ni DevTools. */
    private fun pedirSubsOs() {
        if (!playerAbierto) return
        if (sesionSubsOs.cargar() == null) {
            pedirCuentaSubsOs()
            return
        }
        val opciones = arrayOf(
            "Buscar pista",
            "Descargadas (" + contarDescargasSubsOs() + ")",
            "Probar conexion",
            "Olvidar cuenta"
        )
        AlertDialog.Builder(this)
            .setTitle("Subs OS")
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> pedirBusquedaSubsOs()
                    1 -> pedirDescargadasSubsOs()
                    2 -> probarSubsOs()
                    3 -> {
                        sesionSubsOs.borrar()
                        estado.text = "subs os: cuenta olvidada"
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Cuenta del proveedor: apiKey obligatoria, usuario y clave
     *  opcionales. Guarda via OpenSubtitlesSesion (politica de
     *  secretos) y prueba la conexion; el resultado NUNCA contiene
     *  credenciales. */
    private fun pedirCuentaSubsOs() {
        val cajaApi = EditText(this)
        cajaApi.hint = "API key (obligatoria)"
        val cajaUsuario = EditText(this)
        cajaUsuario.hint = "Usuario (opcional)"
        val cajaClave = EditText(this)
        cajaClave.hint = "Clave (opcional)"
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.addView(cajaApi)
        caja.addView(cajaUsuario)
        caja.addView(cajaClave)
        AlertDialog.Builder(this)
            .setTitle("Cuenta OpenSubtitles")
            .setView(caja)
            .setPositiveButton("Guardar y probar") { _, _ ->
                val api = cajaApi.text.toString().trim()
                if (api.isEmpty()) {
                    estado.text = "subs os: falta la API key"
                    return@setPositiveButton
                }
                val usuario = cajaUsuario.text.toString().trim()
                val clave = cajaClave.text.toString()
                val config = try {
                    OpenSubtitlesConfig(api, usuario.ifEmpty { null }, clave.ifEmpty { null })
                } catch (invalida: IllegalArgumentException) {
                    estado.text = "subs os: API key no valida"
                    return@setPositiveButton
                }
                sesionSubsOs.guardar(config, null)
                probarSubsOs()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Probar conexion en el hilo de fondo (la red JAMAS en el de
     *  UI): fallos auth/cuota/offline honestos y SIN credenciales. */
    private fun probarSubsOs() {
        val guardada = sesionSubsOs.cargar()
        if (guardada == null) {
            estado.text = "subs os: sin cuenta guardada"
            return
        }
        estado.text = "subs os: probando conexion..."
        poolNavegacion.execute {
            val cliente = OpenSubtitlesClient(guardada.config, fetchSubsOs(), guardada.bearer)
            val r = cliente.probarConexion()
            runOnUiThread {
                if (r is Outcome.Success) {
                    estado.text = "subs os: conexion OK"
                } else if (r is Outcome.Failure) {
                    estado.text = "subs os: " + r.error.safeMessage
                }
            }
        }
    }

    /** Busqueda con seleccion de idioma (consulta + idiomas). */
    private fun pedirBusquedaSubsOs() {
        val cajaConsulta = EditText(this)
        cajaConsulta.hint = "Buscar subtitulos de..."
        val cajaIdiomas = EditText(this)
        cajaIdiomas.hint = "Idiomas (es,en)"
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.addView(cajaConsulta)
        caja.addView(cajaIdiomas)
        AlertDialog.Builder(this)
            .setTitle("Buscar subtitulos")
            .setView(caja)
            .setPositiveButton("Buscar") { _, _ ->
                val consulta = cajaConsulta.text.toString().trim()
                if (consulta.isEmpty()) {
                    estado.text = "subs os: consulta vacia"
                    return@setPositiveButton
                }
                buscarSubsOs(consulta, cajaIdiomas.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Busca en el hilo de fondo y ofrece los resultados. */
    private fun buscarSubsOs(consulta: String, idiomasCrudo: String) {
        val guardada = sesionSubsOs.cargar()
        if (guardada == null) {
            estado.text = "subs os: sin cuenta guardada"
            return
        }
        val idiomas = idiomasCrudo.split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        estado.text = "subs os: buscando..."
        poolNavegacion.execute {
            val cliente = OpenSubtitlesClient(guardada.config, fetchSubsOs(), guardada.bearer)
            val r = cliente.buscar(consulta, idiomas)
            runOnUiThread {
                if (r is Outcome.Failure) {
                    estado.text = "subs os: " + r.error.safeMessage
                    return@runOnUiThread
                }
                val hits = (r as? Outcome.Success)?.value ?: return@runOnUiThread
                if (hits.isEmpty()) {
                    estado.text = "subs os: sin resultados"
                    return@runOnUiThread
                }
                pedirResultadoSubsOs(hits)
            }
        }
    }

    /** Resultados: elegir uno lo descarga a pista. */
    private fun pedirResultadoSubsOs(hits: List<OpenSubtitlesResult>) {
        val nombres = ArrayList<String>()
        for (h in hits) nombres.add(h.nombre + " (" + h.idioma + ")")
        AlertDialog.Builder(this)
            .setTitle("Resultados (" + hits.size + ")")
            .setItems(nombres.toTypedArray()) { _, cual ->
                descargarSubsOs(hits[cual])
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Descarga en el hilo de fondo, guarda PERSISTENTE en el
     *  almacen privado y enchufa la pista al player. */
    private fun descargarSubsOs(hit: OpenSubtitlesResult) {
        val guardada = sesionSubsOs.cargar()
        if (guardada == null) {
            estado.text = "subs os: sin cuenta guardada"
            return
        }
        estado.text = "subs os: descargando..."
        poolNavegacion.execute {
            val cliente = OpenSubtitlesClient(guardada.config, fetchSubsOs(), guardada.bearer)
            val r = cliente.descargar(hit.fileId)
            runOnUiThread {
                if (r is Outcome.Failure) {
                    estado.text = "subs os: " + r.error.safeMessage
                    return@runOnUiThread
                }
                val pista = (r as? Outcome.Success)?.value ?: return@runOnUiThread
                guardarDescargaSubsOs(hit, pista.contenido)
                enchufarSubsOs(hit.nombre + " (" + hit.idioma + ")", pista.contenido)
            }
        }
    }

    /** Descarga persistente best-effort: si el disco falla, la pista
     *  efimera del player sigue viva (honesto). */
    private fun guardarDescargaSubsOs(hit: OpenSubtitlesResult, contenido: String) {
        try {
            descargasSubsOs.mkdirs()
            File(descargasSubsOs, hit.fileId.toString() + "-" + hit.idioma + ".txt")
                .writeText(contenido)
        } catch (noEscribible: Exception) {
            traza("media", "subs os: descarga no persistida")
        }
    }

    private fun contarDescargasSubsOs(): Int =
        descargasSubsOs.listFiles()?.size ?: 0

    /** Descargadas persistentes: elegir carga la pista; Borrar todas
     *  las OLVIDA del disco (T104: con borrado). */
    private fun pedirDescargadasSubsOs() {
        val archivos = descargasSubsOs.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (archivos.isEmpty()) {
            estado.text = "subs os: sin descargadas"
            return
        }
        val nombres = ArrayList<String>()
        for (a in archivos) nombres.add(a.name.removeSuffix(".txt"))
        AlertDialog.Builder(this)
            .setTitle("Descargadas")
            .setItems(nombres.toTypedArray()) { _, cual ->
                val texto = try {
                    archivos[cual].readText()
                } catch (ilegible: Exception) {
                    null
                }
                if (texto == null) {
                    estado.text = "subs os: descargada ilegible"
                } else {
                    enchufarSubsOs(nombres[cual], texto)
                }
            }
            .setNeutralButton("Borrar todas") { _, _ ->
                for (a in archivos) a.delete()
                estado.text = "subs os: descargadas borradas"
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Enchufa la pista al player: parse todo-o-nada, desfase con
     *  Subs -0.5s/+0.5s y sync con la posicion del nucleo. */
    private fun enchufarSubsOs(nombre: String, contenido: String) {
        val pista = try {
            SubtitleTrack(SubtitleParser.parse(contenido))
        } catch (ilegal: SubtitleFormatException) {
            estado.text = "subs os: archivo de subtitulos ilegible"
            return
        }
        pistaSubs = pista
        pintarSubs()
        estado.text = "subs os: pista activa (" + nombre + ")"
        traza("media", "subs os activa")
    }

    /** Cliente OS sobre el transporte REAL del dispositivo. */
    private fun fetchSubsOs(): FetchManager = FetchManager(HttpTransport())

    /** T109 v2 (R0366, RES-0148): tipo de la pestana que origino la
     *  media in-page; null si el player es standalone o sin pestana. */
    private fun tipoPestanaMedia(): ProfileKind? =
        pestanaMedia?.let { id -> tabs.tarjeta(id)?.kind }

    /** T065 v1 (R0364, RES-0147): pide el audio focus REAL del sistema
     *  (USAGE_MEDIA en API 26+ con AudioFocusRequest; STREAM_MUSIC
     *  legacy debajo) al abrir el player. Si el sistema lo niega se
     *  avisa honesto y la reproduccion sigue (el SO decide el
     *  mezclado): JAMAS se finge el focus. */
    private fun pedirAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { cambio ->
            manejarCambioFocus(cambio)
        }
        listenerFocus = listener
        val res: Int
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            peticionFocus = req
            res = gestorAudio.requestAudioFocus(req)
        } else {
            res = gestorAudio.requestAudioFocus(
                listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            traza("media", "audio focus concedido")
        } else {
            estado.text = "media: sin audio focus (el sistema decide el mezclado)"
            traza("media", "audio focus denegado")
        }
    }

    /** Suelta el audio focus si se pidio y resetea la pausa/duck por
     *  focus: SIN focus residente sin reproduccion (R0137). */
    private fun soltarAudioFocus() {
        val listener = listenerFocus
        if (listener != null) {
            val req = peticionFocus
            if (Build.VERSION.SDK_INT >= 26 && req != null) {
                gestorAudio.abandonAudioFocusRequest(req)
            } else {
                gestorAudio.abandonAudioFocus(listener)
            }
        }
        listenerFocus = null
        peticionFocus = null
        pausadoPorFocus = false
        enDuck = false
    }

    /** Politica de focus (T065 v1): LOSS pausa definitiva (sin
     *  auto-resume), TRANSIENT pausa y reanuda al GAIN, CAN_DUCK baja
     *  a 0.2 y restaura su volumen previo; todo sobre el MediaEngine
     *  REAL (estado y volumen del motor). */
    private fun manejarCambioFocus(cambio: Int) {
        when (cambio) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausadoPorFocus = false
                if (media.state() == MediaState.PLAYING) {
                    media.pause()
                    estado.text = "media: pausada (audio focus perdido)"
                    traza("media", "audio focus perdido: pausa definitiva")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (media.state() == MediaState.PLAYING) {
                    pausadoPorFocus = true
                    media.pause()
                    estado.text = "media: pausada (audio focus transitorio)"
                    traza("media", "audio focus transitorio: pausa")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!enDuck && media.state() == MediaState.PLAYING) {
                    enDuck = true
                    volumenPreDuck = volumenMedia
                    media.setVolume(0.2)
                    traza("media", "audio focus duck: volumen 0.2")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (enDuck) {
                    enDuck = false
                    volumenMedia = media.setVolume(volumenPreDuck)
                    traza("media", "audio focus ganado: volumen restaurado")
                } else if (pausadoPorFocus && media.state() == MediaState.PAUSED) {
                    pausadoPorFocus = false
                    media.play()
                    estado.text = "media: reanudada (audio focus ganado)"
                    traza("media", "audio focus ganado: reanudada")
                }
            }
        }
    }

    /** Bytes del infohash hex (40 chars) del magnet. */
    private fun unhex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    /** T086 v1 (R0191, RES-0091) + v2 (R0347, RES-0141): pantalla de
     * descargas sobre la cola REAL del DownloadEngine verificado
     * (T046): estados y bytes reales, cancelar con confirmacion
     * ADR-019, abrir la completada con ACTION_VIEW y grant y el
     * historial en la sesion. v2: pausa REAL del motor v4 (pausar de
     * QUEUED/RUNNING y reanudar de PAUSED/FAILED por Range); JAMAS un
     * fallo disfrazado. */
    private fun mostrarDescargas() {
        val items = descargas.list()
        val lineas: Array<String> = if (items.isEmpty()) {
            arrayOf("(sin descargas todavia)")
        } else {
            items.map { item ->
                "#" + item.id + " " + item.state.name.lowercase() + " " +
                    item.bytesDownloaded + "/" +
                    (if (item.totalBytes >= 0) item.totalBytes.toString() else "?")
            }.toTypedArray()
        }
        AlertDialog.Builder(this)
            .setTitle("Descargas")
            .setItems(lineas) { _, cual ->
                if (items.isEmpty()) {
                    return@setItems
                }
                val item = items[cual]
                when (item.state) {
                    DownloadState.QUEUED, DownloadState.RUNNING -> elegirAccionDescarga(item.id)
                    DownloadState.PAUSED -> elegirAccionPausada(item.id)
                    DownloadState.FAILED -> reanudarDescarga(item.id)
                    DownloadState.COMPLETED -> abrirDescarga(item.id)
                    DownloadState.CANCELLED -> estado.text = "descarga #" + item.id + " cancelada (terminal)"
                }
            }
            .setNeutralButton("Nueva descarga") { _, _ -> pedirDescarga() }
            .setPositiveButton("Actualizar") { _, _ -> mostrarDescargas() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Cancelar corta de verdad: es terminal y pide confirmacion
     * ADR-019 (la pausa REAL es Pausar, no un fallo disfrazado). */
    private fun confirmarCancelarDescarga(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Cancelar descarga")
            .setMessage("Cancelar corta la descarga #" + id + " para siempre (para pausar, Pausar).")
            .setPositiveButton("Si") { _, _ ->
                val ok = descargas.cancel(id)
                estado.text = if (ok) {
                    "descarga #" + id + " cancelada"
                } else {
                    "descarga #" + id + " ya termino"
                }
                traza("descargas", "cancelar #" + id + " " + ok)
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** T086 v2: accion sobre una descarga viva: pausar (REAL del
     *  motor v4, con sus bytes) o cancelar (terminal con
     *  confirmacion ADR-019). */
    private fun elegirAccionDescarga(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Descarga #" + id)
            .setItems(arrayOf("Pausar", "Cancelar")) { _, cual ->
                if (cual == 0) pausarDescarga(id) else confirmarCancelarDescarga(id)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** T086 v2: pausar es REAL desde el motor v4: la descarga queda
     *  PAUSED con sus bytes y reanuda por Range desde ese byte. */
    private fun pausarDescarga(id: Long) {
        val ok = descargas.pausar(id)
        estado.text = if (ok) {
            "descarga #" + id + " pausada"
        } else {
            "descarga #" + id + " ya termino o esta pausada"
        }
        traza("descargas", "pausar #" + id + " " + ok)
    }

    /** T086 v2: accion sobre una descarga pausada: reanudar (por
     *  Range desde el byte pausado) o cancelar de verdad (terminal). */
    private fun elegirAccionPausada(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Descarga pausada #" + id)
            .setItems(arrayOf("Reanudar", "Cancelar")) { _, cual ->
                if (cual == 0) reanudarDescarga(id) else confirmarCancelarDescarga(id)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Reanudar sigue el contrato REAL del motor: FAILED o PAUSED
     * con bytes y sink reabrible (Range desde el byte que fallo o
     * pauso); el resto avisa honesto. */
    private fun reanudarDescarga(id: Long) {
        val ok = descargas.resume(id)
        estado.text = if (ok) {
            "descarga #" + id + " reanudada (sigue por Range)"
        } else {
            "descarga #" + id + " no reanudable (sin bytes o destino no reabrible)"
        }
        traza("descargas", "reanudar #" + id + " " + ok)
    }

    /** Abre la descarga completada con el Uri SAF registrado al
     * encolar; sin app que la abra, aviso honesto. */
    private fun abrirDescarga(id: Long) {
        val uri = urisDescargas[id]
        if (uri == null) {
            estado.text = "descarga #" + id + " sin destino que abrir"
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/octet-stream")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
            estado.text = "descarga #" + id + " abierta"
        } catch (e: ActivityNotFoundException) {
            estado.text = "sin app que abra la descarga #" + id
        }
    }

    /** Nueva descarga: la URL se valida con la resolucion REAL del
     * OmniboxInput y el destino se elige con SAF; lo que no resuelve
     * a http/https JAMAS llega a la cola. */
    private fun pedirDescarga() {
        val campo = EditText(this)
        campo.hint = "URL de la descarga (http o https)"
        AlertDialog.Builder(this)
            .setTitle("Nueva descarga")
            .setView(campo)
            .setPositiveButton("Elegir destino") { _, _ ->
                val texto = campo.text.toString().trim()
                when (val r = OmniboxInput.resolver(texto, baseBusqueda())) {
                    is OmniboxInput.Resolucion.Navegar -> pedirDestinoSaf(r.url)
                    is OmniboxInput.Resolucion.Buscar -> pedirDestinoSaf(r.url)
                    is OmniboxInput.Resolucion.EsquemaNoSoportado ->
                        estado.text = "descarga con esquema no soportado: " + r.esquema
                    is OmniboxInput.Resolucion.ErrorDeParseo ->
                        estado.text = "descarga invalida: " + r.motivo
                    is OmniboxInput.Resolucion.Vacio -> estado.text = "descarga vacia"
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Pide el documento SAF para la descarga pendiente. */
    private fun pedirDestinoSaf(url: String) {
        descargaPendienteUrl = url
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("application/octet-stream")
        i.putExtra(Intent.EXTRA_TITLE, "nia-descarga.bin")
        startActivityForResult(i, REQUEST_DESCARGA)
        traza("descargas", "destino SAF para " + hostDe(url))
    }

    /** El documento SAF elegido alimenta el sink reabrible del motor. */
    /** T094 v5 (R0296, RES-0124): guarda los contadores del filtro al
     *  salir de primer plano (best-effort: solo un kill del proceso pierde
     *  lo contado desde el ultimo guardado, sin doble contabilidad). */
    override fun onPause() {
        PluginNetFilter.guardarContadoresSiPendientes()
        super.onPause()
    }

    /** T109 v1 (R0361, RES-0146): la sesion de pestanas NORMALES se
     *  guarda al dejar de ser visible (best-effort en onStop: una
     *  muerte ANTES de onStop deja la sesion anterior, JAMAS inventa). */
    override fun onStop() {
        guardarSesionPestanas()
        super.onStop()
    }

    /** T065 v1 (R0364, RES-0147): ciclo de vida seguro: al destruir la
     *  activity el player y el audio focus se liberan (JAMAS queda
     *  reproduccion huerfana ni focus colgado). */
    override fun onDestroy() {
        if (playerAbierto) cerrarPlayer()
        soltarAudioFocus()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DESCARGA && resultCode == RESULT_OK) {
            val uri = data?.data
            val url = descargaPendienteUrl
            if (uri == null || url == null) {
                estado.text = "descarga sin destino o sin url (cancelada)"
                return
            }
            descargaPendienteUrl = null
            val id = descargas.enqueue(url, DescargaReabrible(uri, 0L))
            urisDescargas[id] = uri
            estado.text = "descarga #" + id + " en cola: " + hostDe(url)
            traza("descargas", "encolada #" + id + " " + hostDe(url))
        }
        if (requestCode == REQUEST_IMPORT_MARCADORES && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "importar marcadores: sin documento"
                return
            }
            try {
                val texto = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                when (val r = MarcadoresHtml.parsear(texto)) {
                    is Outcome.Success -> {
                        val mezcla = MarcadoresHtml.mezclar(marcadores, r.value)
                        guardarMarcadores()
                        estado.text = "importados " + mezcla.agregados + " (duplicados " +
                            mezcla.duplicados + ", rechazados " + r.value.rechazados + ")"
                        traza("marcadores", "importar html: +" + mezcla.agregados +
                            " dup " + mezcla.duplicados + " carp " + mezcla.carpetas +
                            " fallo " + mezcla.fallidos)
                    }
                    is Outcome.Failure -> {
                        estado.text = "importar marcadores: html no valido (" + r.error.code.name + ")"
                        traza("marcadores", "importar html rechazado: " + r.error.code.name)
                    }
                }
            } catch (e: Exception) {
                estado.text = "importar marcadores: no se pudo leer el documento"
                traza("marcadores", "importar html fallo al leer")
            }
        }
        if (requestCode == REQUEST_GUARDAR_PDF && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "guardar PDF: sin destino"
                traza("imprimir", "guardar pdf sin uri")
                return
            }
            try {
                val doc = documentoImpreso(Orientacion.VERTICAL)
                val pdf = PdfDocument()
                try {
                    for (hoja in doc.paginas) {
                        val info = PdfDocument.PageInfo.Builder(
                            doc.anchoPt.toInt(), doc.altoPt.toInt(), hoja.numero + 1
                        ).create()
                        val paginaPdf = pdf.startPage(info)
                        pintarOps(paginaPdf.canvas, hoja)
                        pdf.finishPage(paginaPdf)
                    }
                    val salida = contentResolver.openOutputStream(uri, "wt")
                    if (salida == null) {
                        estado.text = "guardar PDF: no se pudo abrir el destino"
                        traza("imprimir", "openOutputStream nulo")
                    } else {
                        salida.use { pdf.writeTo(it) }
                        val plural = if (doc.numeroPaginas == 1) "pagina" else "paginas"
                        estado.text = "PDF guardado (" + doc.numeroPaginas + " " + plural + ")"
                        traza("imprimir", "pdf guardado con " + doc.numeroPaginas + " " + plural)
                    }
                } finally {
                    pdf.close()
                }
            } catch (e: Exception) {
                estado.text = "no se pudo escribir el PDF"
                traza("imprimir", "error escribiendo pdf: " + e.javaClass.simpleName)
            }
        }
        if (requestCode == REQUEST_MEDIA && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "media: sin documento"
                return
            }
            abrirPlayer("archivo", uri.toString())
        }
        if (requestCode == REQUEST_TORRENT && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "torrent: sin documento"
                return
            }
            try {
                val entrada = contentResolver.openInputStream(uri)
                if (entrada == null) {
                    estado.text = "torrent: no se pudo leer el documento"
                    return
                }
                val meta = entrada.use { TorrentParser.parse(it.readBytes()) }
                pedirSeleccionTorrent(meta)
            } catch (e: Exception) {
                estado.text = "torrent: archivo invalido (" + (e.message ?: e.javaClass.simpleName) + ")"
                traza("torrents", "parse fallo: " + e.javaClass.simpleName)
            }
        }
        if (requestCode == REQUEST_GUARDAR_TORRENT && resultCode == RESULT_OK) {
            val uri = data?.data
            val pend = guardarTorrentPendiente
            guardarTorrentPendiente = null
            if (uri == null || pend == null) {
                estado.text = "guardar torrent: sin destino"
                return
            }
            val s = torrents.find(pend.first)
            if (s == null) {
                estado.text = "guardar torrent: sesion desaparecida"
                return
            }
            try {
                val meta = s.meta
                val archivo = meta.files[pend.second]
                val salida = contentResolver.openOutputStream(uri, "wt")
                if (salida == null) {
                    estado.text = "guardar torrent: no se pudo abrir el destino"
                    return
                }
                salida.use { out ->
                    var offset = meta.fileOffset(pend.second)
                    val fin = offset + archivo.length
                    while (offset < fin) {
                        val p = (offset / meta.pieceLength).toInt()
                        val data = s.pieceData(p)
                        if (data == null) {
                            throw IllegalStateException("pieza " + p + " no disponible")
                        }
                        val dentro = (offset % meta.pieceLength).toInt()
                        val tomar = minOf(data.size - dentro, (fin - offset).toInt())
                        out.write(data, dentro, tomar)
                        offset += tomar
                    }
                }
                estado.text = "archivo del torrent guardado (" + archivo.length + " B)"
                traza("torrents", "guardado " + archivo.path + " " + archivo.length + " B")
            } catch (e: Exception) {
                estado.text = "guardar torrent: fallo al escribir"
                traza("torrents", "guardar fallo: " + e.javaClass.simpleName)
            }
        }
        if (requestCode == REQUEST_INSTALAR_NIAMOD && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "instalar modulo: sin documento"
                return
            }
            try {
                val entrada = contentResolver.openInputStream(uri)
                if (entrada == null) {
                    estado.text = "instalar modulo: no se pudo leer el documento"
                    return
                }
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                entrada.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (baos.size() > 4 * 1024 * 1024) {
                            estado.text = "paquete demasiado grande (tope 4 MiB)"
                            traza("modulos", "niamod rechazado: demasiado grande")
                            return
                        }
                        baos.write(buf, 0, n)
                    }
                }
                instalarDesdeNiamod(baos.toByteArray())
            } catch (e: Exception) {
                estado.text = "instalar modulo: no se pudo leer el documento"
                traza("modulos", "niamod fallo al leer")
            }
        }
        if (requestCode == REQUEST_EXPORT_MARCADORES && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri == null) {
                estado.text = "exportar marcadores: sin destino"
                return
            }
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { s ->
                    s.write(MarcadoresHtml.escribir(marcadores).toByteArray())
                }
                estado.text = "marcadores exportados a html"
                traza("marcadores", "exportar html ok")
            } catch (e: Exception) {
                estado.text = "exportar marcadores: no se pudo escribir"
                traza("marcadores", "exportar html fallo al escribir")
            }
        }
    }

    /** Sink reabrible sobre el documento SAF (patron T046 v2): wt
     * trunca desde cero, wa posiciona al final para reanudar y
     * reopen devuelve un sink NUEVO sobre el mismo Uri; si el destino
     * no abre, el primer write falla y la descarga queda FAILED. */
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

    /** T082 v1 (R0191, RES-0092): modo lectura con la heuristica DOM
     * propia del Lector del motor sobre el HTML REAL de la pagina
     * activa (sin re-fetch); los scripts extraidos NUNCA se ejecutan
     * y el documento vivo no se toca: volver con Recargar (exclusion
     * honesta: refetch, scroll no preservado). */
    private fun modoLectura() {
        val page = navActual()?.currentPage()
        val html = page?.html
        if (page == null || html == null || page.url.startsWith("about:")) {
            estado.text = "sin pagina que leer"
            traza("lectura", "sin pagina")
            return
        }
        val lectura = Lector.leer(html)
        if (lectura.parrafos.isEmpty()) {
            estado.text = "sin contenido principal que leer"
            traza("lectura", "sin parrafos " + hostDe(page.url))
            return
        }
        val titulo = if (lectura.titulo.isEmpty()) hostDe(page.url) else lectura.titulo
        val sb = StringBuilder()
        sb.append("<html><body><h1>")
        sb.append(textoHtml(titulo))
        sb.append("</h1>")
        for (p in lectura.parrafos) {
            sb.append("<p>")
            sb.append(textoHtml(p))
            sb.append("</p>")
        }
        sb.append("<p>(modo lectura: Recargar vuelve a la pagina)</p>")
        sb.append("</body></html>")
        pintar(sb.toString())
        estado.text = "modo lectura: " + lectura.parrafos.size + " parrafos"
        traza("lectura", "pintada " + lectura.parrafos.size + " parrafos " + hostDe(page.url))
    }

    /** La tarjeta de la pestana refleja la URL cargada; la carga en vuelo
     * solo pinta si su pestana sigue activa (R0176). */
    private fun refrescaTarjeta(id: Long?, o: NavOutcome) {
        if (id == null) {
            return
        }
        val url = when (o) {
            is NavOutcome.Loaded -> o.page.url
            is NavOutcome.Failed -> o.page?.url
        }
        if (url != null) {
            tabs.actualizar(id, hostDe(url), url, null)
        }
        pintarStrip()
    }

    /** Traza observable por apartado (T090): cada accion del shell deja
     * una linea con su apartado, visible en el dialogo Diagnostico y en
     * logcat con "adb logcat -s NIA.Shell". Las URLs NUNCA entran en la
     * traza: el shell solo traza el host (privacidad primero). */
    private fun traza(apartado: String, evento: String) {
        val linea = apartado + " " + evento
        traza.add(linea)
        val limite = ajustes.valor("diagnostico.fifo")?.toLongOrNull() ?: 40L
        while (traza.size > limite) {
            traza.removeAt(0)
        }
        Log.d("NIA.Shell", linea)
    }

    /** Banner inequivoco de modo incognito (T090): visible SOLO con la
     * activa privada; nunca depende del contenido de la pagina. */
    private fun pintarModo() {
        val id = tabs.activa()
        val kind = if (id == null) null else tabs.tarjeta(id)?.kind
        bannerIncognito.visibility =
            if (kind == ProfileKind.INCOGNITO) TextView.VISIBLE else TextView.GONE
    }

    /** Seccion Limpieza (T093 v2): acciones de un uso elegidas de
     * lista cerrada. Elegir un valor distinto del defecto pide
     * confirmacion y despues la ejecuta; la opcion NUNCA cambia de
     * valor (sigue su defecto: el export JAMAS guarda una accion
     * colgada) y el Fire conserva su confirmacion ADR-019. */
    private fun elegirLimpieza(op: OpcionAjuste) {
        AlertDialog.Builder(this)
            .setTitle(op.titulo)
            .setMessage(op.descripcion)
            .setItems(op.elecciones.toTypedArray()) { _, cual ->
                aplicarLimpieza(op, op.elecciones[cual])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Confirma y ejecuta la accion de Limpieza (T093 v2); el valor
     * de defecto (nada/conservar) no ejecuta nada. */
    private fun aplicarLimpieza(op: OpcionAjuste, eleccion: String) {
        if (eleccion == op.defecto) {
            estado.text = op.titulo + " sin cambios"
            traza("limpieza", "sin cambios " + op.id)
            return
        }
        val tipo = tipoActivo()
        val nombre = if (tipo == ProfileKind.INCOGNITO) "incognito" else "normal"
        val mensaje = when (op.id) {
            "limpieza.historial" ->
                "Se borrara el historial de pestanas normales (" + eleccion + "). Borrar?"
            "limpieza.fire-sesion" ->
                "Borrar el sessionStorage del perfil " + nombre + "? (cookies, cache y storage local quedan)"
            "limpieza.fire-total" ->
                "Borrar TODO el estado del perfil " + nombre + " (cookies, cache, storage, IndexedDB y el archivo en disco) y vaciar sus reabiertas?"
            "limpieza.cache" ->
                "Borrar solo el cache HTTP de los perfiles vivos (normal e incognito)? Las cookies y el historial viven"
            "limpieza.descargas" ->
                "Retirar de la cola las descargas terminadas (completadas, canceladas y fallidas)? Las vivas siguen"
            else -> return
        }
        AlertDialog.Builder(this)
            .setTitle(op.titulo)
            .setMessage(mensaje)
            .setPositiveButton("Borrar") { _, _ ->
                ejecutarLimpieza(op, eleccion, tipo)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Ejecuta la accion ya confirmada (T093 v2): el rango del
     * historial es inclusivo hasta ahora (86400000L = un dia en
     * millis, dia UTC de HistoryManager.diaDe) y el Fire reusa
     * ejecutarFire (el total del perfil NORMAL borra tambien el
     * historial, consistencia T045). */
    private fun ejecutarLimpieza(op: OpcionAjuste, eleccion: String, tipo: ProfileKind) {
        if (op.id == "limpieza.historial") {
            val ahora = clock.nowMillis()
            val n = if (eleccion == "todo") {
                historial.limpiar()
            } else {
                val desde = if (eleccion == "hoy") {
                    HistoryManager.diaDe(ahora) * 86400000L
                } else {
                    ahora - (if (eleccion == "7-dias") 7L else 30L) * 86400000L
                }
                historial.borrarRango(desde, ahora)
            }
            estado.text = "historial borrado (" + n + ", " + eleccion + ")"
            traza("limpieza", "historial " + eleccion + " (" + n + ")")
            return
        }
        if (op.id == "limpieza.cache") {
            val n = tabs.limpiarCache()
            estado.text = "cache borrado (" + n + " entradas)"
            traza("limpieza", "cache (" + n + ")")
            return
        }
        if (op.id == "limpieza.descargas") {
            val ids = descargas.limpiarTerminadas()
            for (id in ids) {
                urisDescargas.remove(id)
            }
            estado.text = "descargas retiradas (" + ids.size + ")"
            traza("limpieza", "descargas (" + ids.size + ")")
            return
        }
        ejecutarFire(tipo, if (op.id == "limpieza.fire-sesion") FireLevel.SESION else FireLevel.TOTAL)
        traza("limpieza", "fire " + op.id)
    }

    /** Retencion automatica del historial (T093 v3): preferencia
     * REAL que SI se guarda y se exporta (a diferencia de las
     * acciones de un uso); al arrancar y al cambiarla se borra del
     * historial lo mas viejo que la ventana elegida, inclusiva
     * hasta ahora (borrarRango [0, corte] del HistoryManager) y
     * silenciosa cuando no hay nada que borrar. */
    private fun aplicarRetencion() {
        val eleccion = ajustes.valor("limpieza.retencion") ?: "nada"
        if (eleccion == "nada") {
            return
        }
        val dias = if (eleccion == "7-dias") 7L else if (eleccion == "30-dias") 30L else 90L
        val corte = clock.nowMillis() - dias * 86400000L
        val n = historial.borrarRango(0L, corte)
        if (n > 0) {
            estado.text = "retencion " + eleccion + ": " + n + " entradas viejas borradas"
            traza("limpieza", "retencion " + eleccion + " (" + n + ")")
        }
    }

    /** Seccion Copias (T112 v2): acciones de un uso sobre el
     *  BackupsPerfil del motor (v1, verificado); como en Limpieza,
     *  la opcion JAMAS queda colgada fuera de su defecto. */
    private fun elegirCopias(op: OpcionAjuste) {
        AlertDialog.Builder(this)
            .setTitle(op.titulo)
            .setMessage(op.descripcion)
            .setItems(op.elecciones.toTypedArray()) { _, cual ->
                aplicarCopias(op, op.elecciones[cual])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Ejecuta la accion de Copias (T112 v2); el defecto (nada) no
     *  ejecuta nada, como en Limpieza. */
    private fun aplicarCopias(op: OpcionAjuste, eleccion: String) {
        if (eleccion == op.defecto) {
            estado.text = op.titulo + " sin cambios"
            traza("copias", "sin cambios " + op.id)
            return
        }
        if (op.id == "copias.crear") {
            confirmarCrearCopia()
        } else {
            gestionarCopias()
        }
    }

    /** Pide confirmacion y crea la copia (T112 v2): SOLO areas de
     *  la lista permitida con el export REAL de cada gestor;
     *  cookies, storage, IndexedDb y secretos JAMAS viajan. */
    private fun confirmarCrearCopia() {
        AlertDialog.Builder(this)
            .setTitle("Crear copia del perfil")
            .setMessage(
                "Crea una copia de marcadores, historial, ajustes y sitios " +
                    "(JAMAS cookies, storage ni secretos). Cuota 5: la mas vieja se rota. Crear?"
            )
            .setPositiveButton("Crear") { _, _ -> crearCopia() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun crearCopia() {
        val areas = LinkedHashMap<String, String>()
        areas["marcadores"] = marcadores.exportar()
        areas["historial"] = historial.exportar()
        areas["ajustes"] = ajustes.exportar()
        areas["sitios"] = sitios.exportar()
        val r = backupsPerfil.crear(areas)
        if (r is Outcome.Success) {
            val info = r.value
            estado.text = "copia v" + info.version + " creada (" +
                backupsPerfil.total() + "/" + BackupsPerfil.MAX_BACKUPS + ")"
            traza("copias", "creada v" + info.version + " (" +
                backupsPerfil.total() + "/" + BackupsPerfil.MAX_BACKUPS + ")")
        } else if (r is Outcome.Failure) {
            estado.text = "copia no creada: " + r.error.safeMessage
            traza("copias", "crear fallo: " + r.error.safeMessage)
        }
    }

    /** Lista las copias validas (T112 v2): las corruptas JAMAS
     *  aparecen (v1) y el listado es version/areas/bytes, SIN
     *  contenido del perfil. */
    private fun gestionarCopias() {
        val lista = backupsPerfil.listar()
        if (lista.isEmpty()) {
            estado.text = "sin copias guardadas"
            traza("copias", "gestionar: ninguna")
            return
        }
        val lineas = ArrayList<String>()
        for (info in lista) {
            lineas.add("v" + info.version + " - " + info.areas.size + " areas - " + info.bytes + " B")
        }
        AlertDialog.Builder(this)
            .setTitle("Copias (" + lista.size + "/" + BackupsPerfil.MAX_BACKUPS + ")")
            .setItems(lineas.toTypedArray()) { _, cual -> elegirCopia(lista[cual]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Toca una copia (T112 v2): restaurar a staging (aplicar es
     *  SIEMPRE otro paso explicito) o borrar con confirmacion. */
    private fun elegirCopia(info: BackupInfo) {
        AlertDialog.Builder(this)
            .setTitle("Copia v" + info.version)
            .setItems(arrayOf("Restaurar (staging)", "Borrar")) { _, cual ->
                if (cual == 0) {
                    confirmarRestaurarCopia(info)
                } else {
                    confirmarBorrarCopia(info)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Restaura a staging SIN tocar el perfil vivo (T112 v2): el
     *  staging se limpia antes y aplicar es SIEMPRE explicito. */
    private fun confirmarRestaurarCopia(info: BackupInfo) {
        AlertDialog.Builder(this)
            .setTitle("Restaurar copia v" + info.version)
            .setMessage(
                "Escribe la copia v" + info.version + " a staging (" +
                    info.areas.size + " areas). El perfil vivo NO se toca: aplicar sera otro paso. Restaurar?"
            )
            .setPositiveButton("Restaurar") { _, _ -> restaurarCopia(info) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun restaurarCopia(info: BackupInfo) {
        limpiarStaging()
        val r = backupsPerfil.restaurar(info.nombre, stagingCopias)
        if (r is Outcome.Success) {
            val aplicables = areasStaging()
            estado.text = "staging v" + info.version + " (" + aplicables.size + " areas)"
            traza("copias", "staging v" + info.version + " (" + aplicables.size + " areas)")
            AlertDialog.Builder(this)
                .setTitle("Aplicar al perfil?")
                .setMessage(
                    "El staging de la copia v" + info.version + " trae " +
                        aplicables.size + " areas (" + aplicables.joinToString(", ") +
                        "). Aplicar reemplaza esas areas por las de la copia. Aplicar?"
                )
                .setPositiveButton("Aplicar") { _, _ -> aplicarStaging() }
                .setNegativeButton("Descartar") { _, _ ->
                    limpiarStaging()
                    estado.text = "staging descartado"
                    traza("copias", "staging descartado")
                }
                .show()
        } else if (r is Outcome.Failure) {
            limpiarStaging()
            estado.text = "no restaurada: " + r.error.safeMessage
            traza("copias", "restaurar fallo: " + r.error.safeMessage)
        }
    }

    /** Borra una copia con confirmacion (T112 v2); borrar sirve
     *  tambien para erradicar lo que listar no muestra (v1). */
    private fun confirmarBorrarCopia(info: BackupInfo) {
        AlertDialog.Builder(this)
            .setTitle("Borrar copia v" + info.version + "?")
            .setMessage("La copia v" + info.version + " (" + info.bytes + " B) se borrara del disco. Borrar?")
            .setPositiveButton("Borrar") { _, _ ->
                val r = backupsPerfil.borrar(info.nombre)
                if (r is Outcome.Success) {
                    estado.text = "copia v" + info.version + " borrada (" +
                        backupsPerfil.total() + " vivas)"
                    traza("copias", "borrada v" + info.version)
                } else if (r is Outcome.Failure) {
                    estado.text = "no borrada: " + r.error.safeMessage
                    traza("copias", "borrar fallo: " + r.error.safeMessage)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Areas con archivo en el staging (T112 v2): SOLO areas de la
     *  lista permitida, en orden fijo. */
    private fun areasStaging(): List<String> {
        val res = ArrayList<String>()
        for (area in listOf("marcadores", "historial", "ajustes", "sitios")) {
            if (File(stagingCopias, area + ".tsv").isFile) {
                res.add(area)
            }
        }
        return res
    }

    /** Aplica el staging al perfil vivo (T112 v2): cada area es
     *  TODO-o-NADA en su gestor REAL (un fallo no toca esa area),
     *  el informe va por nombre de area y NUNCA se imprime
     *  contenido del perfil; el staging queda limpio. */
    private fun aplicarStaging() {
        val ok = ArrayList<String>()
        val mal = ArrayList<String>()
        for (area in areasStaging()) {
            val f = File(stagingCopias, area + ".tsv")
            val texto = try {
                f.readText()
            } catch (ilegible: Exception) {
                mal.add(area)
                continue
            }
            if (aplicarAreaCopia(area, texto)) {
                ok.add(area)
            } else {
                mal.add(area)
            }
        }
        limpiarStaging()
        if (mal.isEmpty()) {
            estado.text = "copias aplicadas: " + ok.joinToString(", ")
        } else {
            estado.text = "aplicadas: " +
                (if (ok.isEmpty()) "ninguna" else ok.joinToString(", ")) +
                "; fallidas: " + mal.joinToString(", ")
        }
        traza("copias", "aplicar (" + ok.size + " ok, " + mal.size + " fallo)")
        if (ok.contains("ajustes")) {
            aplicarGestos()
            recreate()
        } else if (ok.contains("sitios")) {
            recargar()
        }
    }

    /** Aplica UN area del staging a su gestor (T112 v2): el gestor
     *  decide TODO-o-NADA y persiste el; los marcadores se
     *  persisten con el ciclo del shell (guardarMarcadores). */
    private fun aplicarAreaCopia(area: String, texto: String): Boolean {
        when (area) {
            "marcadores" -> {
                if (marcadores.importar(texto) is Outcome.Failure) return false
                guardarMarcadores()
                return true
            }
            "historial" -> return historial.importar(texto)
            "ajustes" -> return ajustes.importar(texto)
            "sitios" -> return sitios.importar(texto)
        }
        return false
    }

    /** Borra TODO el staging (T112 v2): nada de la copia queda en
     *  disco tras aplicar, descartar o fallar. */
    private fun limpiarStaging() {
        val archivos = stagingCopias.listFiles() ?: return
        for (f in archivos) {
            f.delete()
        }
    }

    /** Tipo de perfil de la pestana activa (normal si no hay). */
    private fun tipoActivo(): ProfileKind {
        val id = tabs.activa()
        val kind = if (id == null) null else tabs.tarjeta(id)?.kind
        return kind ?: ProfileKind.NORMAL
    }

    private fun ejecutarFire(tipo: ProfileKind, nivel: FireLevel) {
        val reporte = tabs.fire(nivel, tipo)
        if (reporte == null) {
            estado.text = "sin perfil vivo de tipo " + tipo
            traza("fire", "sin perfil vivo " + tipo)
            return
        }
        val resumen = if (nivel == FireLevel.SESION) {
            "sessions=" + reporte.sessionPartitions
        } else {
            "cookies=" + reporte.cookies + " cache=" + reporte.cacheEntries +
                " storage=" + reporte.storagePartitions + " idb=" + reporte.idbPartitions
        }
        val linea = if (nivel == FireLevel.SESION) "sesion" else "total"
        traza("fire", linea + " " + tipo.toString().lowercase() + " " + resumen)
        // T109 v2 (R0366, RES-0148): el Fire TOTAL del tipo de la
        // pestana que origino la media in-page TAMBIEN la para; el de
        // sesion NO toca la reproduccion (politica explicita).
        if (playerAbierto && nivel == FireLevel.TOTAL && tipo == tipoPestanaMedia()) {
            cerrarPlayer()
            traza("media", "fire total paro la media in-page")
            estado.text = "fire " + linea + " aplicado: " + resumen + " | media de la pagina parada"
        } else {
            estado.text = "fire " + linea + " aplicado: " + resumen
        }
        if (nivel == FireLevel.TOTAL && tipo == ProfileKind.NORMAL) {
            val borradas = historial.limpiar()
            traza("hist", "fire total: historial limpiado (" + borradas + ")")
        }
    }

    /** Dialogo de diagnostico (T090): la traza del shell linea a linea,
     * la mas reciente primero, para alistar las pruebas sin adb. */
    private fun mostrarDiagnostico() {
        val lineas: Array<String> = if (traza.isEmpty()) {
            arrayOf("(sin traza todavia)")
        } else {
            traza.toTypedArray().reversedArray()
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnostico (logcat: adb logcat -s NIA.Shell)")
            .setItems(lineas) { _, _ -> }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Registra la visita en el historial SOLO si la pestana activa es
     * NORMAL (T074): el incognito JAMAS entra al historial (D-001). */
    private fun registrarVisita(url: String) {
        val id = tabs.activa()
        val kind = if (id == null) null else tabs.tarjeta(id)?.kind
        if (kind != ProfileKind.NORMAL) {
            return
        }
        if (historial.registrar(url, hostDe(url))) {
            traza("hist", "registrado " + hostDe(url))
        }
    }

    /** Dialogo de historial (T074 v1): entradas del perfil NORMAL
     * agrupadas por dia UTC, la mas reciente primero; tocar una
     * entrada la abre y Borrar todo pide confirmacion (la busqueda
     * de texto y los rangos de fechas llegan con Ajustes, T093). */
    private fun mostrarHistorial() {
        val lista = historial.entradas()
        val lineas = ArrayList<String>()
        val urls = ArrayList<String?>()
        var diaAnterior = -1L
        for (e in lista) {
            val dia = HistoryManager.diaDe(e.fecha)
            if (dia != diaAnterior) {
                lineas.add("-- dia " + dia + " --")
                urls.add(null)
                diaAnterior = dia
            }
            lineas.add(e.titulo + " - " + hostDe(e.url) + " (" + e.visitas + ")")
            urls.add(e.url)
        }
        if (lineas.isEmpty()) {
            lineas.add("(historial vacio)")
            urls.add(null)
        }
        traza("hist", "historial abierto con " + historial.contar() + " entradas")
        AlertDialog.Builder(this)
            .setTitle("Historial (solo pestanas normales)")
            .setItems(lineas.toTypedArray()) { _, cual ->
                val url = urls[cual]
                if (url != null) {
                    navegar(url, false, null)
                }
            }
            .setPositiveButton("Borrar todo") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Borrar el historial")
                    .setMessage("Se borrara TODO el historial de pestanas normales. Borrar?")
                    .setPositiveButton("Borrar") { _, _ ->
                        val n = historial.limpiar()
                        estado.text = "historial borrado (" + n + ")"
                        traza("hist", "historial borrado (" + n + ")")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Dialogo de ajustes (T093): busca por nombre, seccion o
     * descripcion y ABRE la opcion; la seccion Limpieza trae el
     * Fire, el borrado del historial por rango (v2), la
     * retencion automatica al arrancar (v3) y el borrado del
     * cache HTTP con conteo (v4); las demas secciones llegan
     * con los subhitos siguientes. */
    private fun mostrarAjustes() {
        val campo = EditText(this)
        campo.hint = "Buscar ajuste (texto, seccion o descripcion)"
        traza("ajustes", "ajustes abiertos con " + ajustes.contar() + " opciones")
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setMessage("Busca por nombre, seccion o descripcion; tocar una opcion la abre.")
            .setView(campo)
            .setPositiveButton("Buscar") { _, _ ->
                val q = campo.text.toString()
                val resultados = ajustes.buscar(q)
                if (resultados.isEmpty()) {
                    estado.text = "sin ajustes para: " + q
                    traza("ajustes", "sin resultados para la busqueda")
                    return@setPositiveButton
                }
                val lineas = ArrayList<String>()
                for (op in resultados) {
                    val marca = if (op.disponible) ajustes.valor(op.id) else "no disponible"
                    lineas.add(op.seccion + " / " + op.titulo + " = " + marca)
                }
                AlertDialog.Builder(this)
                    .setTitle("Ajustes (" + resultados.size + ")")
                    .setItems(lineas.toTypedArray()) { _, cual ->
                        tocarAjuste(resultados[cual])
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Aplica el cambio de un ajuste (T093): la ELECCION de la
     * seccion Limpieza es una accion de un uso (v2, elegirLimpieza)
     * salvo limpieza.retencion, preferencia REAL (v3) que SI se
     * asigna y se aplica al elegir; BOOL alterna al tocar; ENTERO
     * y TEXTO piden el valor; la ELECCION elige de la lista; lo no
     * disponible se avisa y JAMAS cambia. */
    private fun tocarAjuste(op: OpcionAjuste) {
        if (!op.disponible) {
            estado.text = "no disponible: " + op.titulo
            traza("ajustes", "no disponible " + op.id)
            return
        }
        when (op.tipo) {
            TipoOpcion.BOOL -> {
                val nuevo = if (ajustes.valor(op.id) == "true") "false" else "true"
                if (ajustes.asignar(op.id, nuevo)) {
                    estado.text = op.titulo + " = " + ajustes.valor(op.id)
                    traza("ajustes", "cambiado " + op.id + " a " + nuevo)
                    if (op.id.startsWith("gestos.")) aplicarGestos()
                    if (op.id.startsWith("contenido.")) recargar()
                }
            }
            TipoOpcion.ENTERO -> pedirAjuste(op, "entre " + op.minimo + " y " + op.maximo)
            TipoOpcion.TEXTO -> pedirAjuste(op, op.descripcion)
            TipoOpcion.ELECCION -> {
                if (op.seccion == "Limpieza" && op.id != "limpieza.retencion") {
                    elegirLimpieza(op)
                } else if (op.seccion == "Copias") {
                    elegirCopias(op)
                } else if (op.id == "contenido.sitios") {
                    aplicarBorradoSitios()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(op.titulo)
                        .setItems(op.elecciones.toTypedArray()) { _, cual ->
                            val nuevo = op.elecciones[cual]
                            if (ajustes.asignar(op.id, nuevo)) {
                                estado.text = op.titulo + " = " + nuevo
                                traza("ajustes", "cambiado " + op.id + " a " + nuevo)
                                if (op.id == "limpieza.retencion") {
                                    aplicarRetencion()
                                }
                                if (op.id == "apariencia.tema") {
                                    recreate()
                                }
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }
    }

    /** Pide el valor de un ajuste ENTERO o TEXTO (T093); el valor
     * invalido se rechaza y el estado lo avisa. */
    private fun pedirAjuste(op: OpcionAjuste, mensaje: String) {
        val campo = EditText(this)
        campo.setText(ajustes.valor(op.id))
        AlertDialog.Builder(this)
            .setTitle(op.titulo)
            .setMessage(mensaje)
            .setView(campo)
            .setPositiveButton("Aceptar") { _, _ ->
                val nuevo = campo.text.toString()
                if (ajustes.asignar(op.id, nuevo)) {
                    estado.text = op.titulo + " = " + ajustes.valor(op.id)
                    traza("ajustes", "cambiado " + op.id + " a " + nuevo)
                    if (op.id.startsWith("gestos.")) aplicarGestos()
                    if (op.id.startsWith("contenido.")) recargar()
                } else {
                    estado.text = "valor invalido para " + op.titulo
                    traza("ajustes", "valor invalido para " + op.id)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Texto seguro para el HTML de la pagina de error (sin <, > ni &). */
    private fun textoHtml(s: String): String =
        s.replace('<', ' ').replace('>', ' ').replace('&', ' ')

    private fun paginaNueva(): String {
        val sb = StringBuilder()
        sb.append("<html><body><h1>Navegador NIA (R0191)</h1>")
        sb.append("<p>Motor propio, sin WebView. Busca o escribe una URL arriba. Toca una pestana para cambiar, x para cerrar, + para abrir.</p>")
        sb.append("<h2>Accesos</h2>")
        val pins = marcadores.accesosRapidos()
        if (pins.isEmpty()) {
            sb.append("<p>Sin accesos: anade o guarda uno desde el Menu (Accesos de la pagina nueva).</p>")
        } else {
            for (bm in pins) {
                sb.append("<p>" + textoHtml(bm.titulo) + " - " + hostDe(bm.url) + "</p>")
            }
        }
        sb.append("<h2>Recientes (solo pestanas normales)</h2>")
        val recientes = historial.entradas()
        if (recientes.isEmpty()) {
            sb.append("<p>Sin paginas recientes todavia.</p>")
        } else {
            var n = 0
            for (e in recientes) {
                if (n >= 6) break
                sb.append("<p>" + textoHtml(e.titulo) + " - " + hostDe(e.url) + "</p>")
                n += 1
            }
        }
        sb.append("<p>Los accesos y la nueva incognito se gestionan en el Menu; las paginas reales se pintan con el layout del motor.</p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** CSS del navegador: tokens del tema activo (T069/ADR-021); inputs
     * con fondo oscuro para que el value pintado sea legible. */
    private fun cssNavegador(): String =
        "body{background-color:" + colores.bg + ";color:" + colores.fg + ";font-size:" + (16 * textoSitio).toInt() + "px;padding:12px}" +
            "h1{font-size:24px;color:" + colores.accent + "}" +
            "h2{font-size:20px;color:" + colores.accent + "}" +
            "p{margin-top:8px}" +
            "input{display:block;padding:10px;border-width:1px;border-color:" + colores.surface +
            ";background-color:#23233f;color:#f0eef8;font-size:14px;margin-top:8px}"

    /** T078 v1 (R0199, RES-0094): ScrollView que expone el
     * SOBRESCROLL REAL del borde superior al TironRefresh del motor
     * (deltaY > 0 = tirar hacia abajo) y avisa al soltar; NUNCA roba
     * el scroll: solo observa. */
    private class ScrollTiron(context: Context) : ScrollView(context) {
        var onTiron: ((Double) -> Unit)? = null
        var onSoltar: (() -> Unit)? = null

        override fun overScrollBy(
            deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
            scrollRangeX: Int, scrollRangeY: Int, maxOverScrollX: Int,
            maxOverScrollY: Int, isTouchEvent: Boolean
        ): Boolean {
            if (isTouchEvent && scrollY <= 0 && deltaY < 0) {
                onTiron?.invoke(-deltaY.toDouble())
            }
            return super.overScrollBy(
                deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, isTouchEvent
            )
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                onSoltar?.invoke()
            }
            return super.onTouchEvent(ev)
        }
    }

    companion object {
        private const val REQUEST_DESCARGA = 46
        private const val REQUEST_IMPORT_MARCADORES = 47
        private const val REQUEST_EXPORT_MARCADORES = 48
        private const val REQUEST_INSTALAR_NIAMOD = 49
        private const val REQUEST_GUARDAR_PDF = 50
        private const val REQUEST_TORRENT = 51
        private const val REQUEST_GUARDAR_TORRENT = 52
        private const val REQUEST_MEDIA = 53
    }
}
