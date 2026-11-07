package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import nia.contracts.ContractsVersion
import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.engine.bookmarks.BookmarkManager
import nia.engine.bookmarks.MarcadoresHtml
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
import nia.engine.lectura.Lector
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.net.GestorCookies
import nia.engine.net.HttpTransport
import nia.engine.net.PanelIdentidad
import nia.engine.net.RegistroRed
import nia.engine.omnibox.OmniboxInput
import nia.engine.omnibox.OmniboxSugerencias
import nia.engine.omnibox.Sugerencia
import nia.engine.profile.FireLevel
import nia.engine.profile.ProfileKind
import nia.engine.settings.OpcionAjuste
import nia.engine.settings.SettingsManager
import nia.engine.settings.TipoOpcion
import nia.engine.tabs.TabCrear
import nia.engine.tabs.TabTarjeta
import nia.engine.tabs.TabsManager
import nia.engine.themes.NiaTokens
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
 * Exclusiones v6: sin grupos ni previews pintadas, sin persistencia
 * de la sesion de pestanas (el incognito JAMAS se restaura tras un
 * proceso muerto: vive solo en memoria), Fire solo del perfil del tipo
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

    private val tabs by lazy { TabsManager(clock, HttpTransport(), File(cacheDir, "perfil-navegador")) }
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
    private var descargaPendienteUrl: String? = null
    private val urisDescargas = HashMap<Long, Uri>()

    /** Ajustes del navegador (T093 v1 + v2 + v3 + v4): armazon del SettingsManager
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
                "NO disponible todavia: llega con el gestor de descargas del shell (T047/T085)",
                TipoOpcion.ELECCION, "conservar", elecciones = listOf("conservar", "ejecutar"),
                disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "apariencia.tema", "Apariencia",
                "Tema de colores",
                "Tema claro u oscuro (persistir el activo llega con T031 v2)",
                TipoOpcion.ELECCION, "claro", elecciones = listOf("claro", "oscuro"),
                disponible = false
            )
        )
        s.registrar(
            OpcionAjuste(
                "buscador.predeterminado", "Busqueda",
                "Buscador del omnibox",
                "Hoy DuckDuckGo por ADR-022 (elegir llega con T054/T094)",
                TipoOpcion.TEXTO, "duckduckgo", disponible = false
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
        // T078 v1 (R0199, RES-0094): la seccion Gestos del motor; los
        // ids, los rangos y los defectos viven en AjustesGestos.
        AjustesGestos.registrar(s)
        s.recargar()
        s
    }

    private val colores by lazy { NiaUi.colores(this, NiaTokens.Modo.AUTO) }

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
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        raiz.setBackgroundColor(NiaUi.color(colores.bg))

        tabs.crear(ProfileKind.NORMAL, "about:nia", "Nueva pestana", null)

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
        when (val r = OmniboxInput.resolver(texto)) {
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
        progreso.setFraccion(0.2f)
        traza("nav", "navegar " + hostDe(url))
        Thread {
            val inicio = clock.nowMillis()
            val o = nc.navigate(url)
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
                }
            }
        }.start()
    }

    private fun navegarOutcome(accion: (NavController) -> NavOutcome?) {
        val id = tabs.activa()
        val nc = navActual()
        if (nc == null) {
            estado.text = "sin pestana activa"
            return
        }
        nc.invalidateGeneration()
        progreso.setFraccion(0.2f)
        traza("nav", "back/forward")
        Thread {
            val inicio = clock.nowMillis()
            val o = accion(nc)
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
                }
            }
        }.start()
    }

    private fun manejaFallo(f: NavOutcome.Failed, dominioImplicito: Boolean, textoOriginal: String?) {
        traza("nav", "fallo " + f.code)
        if (dominioImplicito && textoOriginal != null &&
            f.code == ErrorCode.NETWORK && f.message.contains("DNS")
        ) {
            val url = OmniboxInput.BASE_BUSQUEDA_DEFECTO + OmniboxInput.codificarQuery(textoOriginal)
            AlertDialog.Builder(this)
                .setTitle("Dominio no encontrado")
                .setMessage("La navegacion fallo de DNS. Buscar el texto en DuckDuckGo?")
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
            "Exportar marcadores (HTML)"
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
                }
            }
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
     *  aplica los defectos. La recarga conserva las preferencias. */
    private fun aplicarSitio(url: String) {
        val clave = claveSitio(url)
        val pref = if (clave == null) PrefSitio() else sitios.obtener(clave)
        textoSitio = pref.texto
        pagina.fijarBaseViewport(AjustesSitio.viewportBase(pref.escritorio))
        pagina.fijarZoom(pref.zoom)
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
        try {
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

    private fun cambiarPestana(id: Long) {
        if (!tabs.activar(id)) {
            return
        }
        pintarStrip()
        traza("tabs", "activa id=" + id)
        val nc = tabs.nav(id)
        val page = nc?.currentPage()
        if (page == null) {
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
        estado.text = mensaje
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
        when (val r = OmniboxInput.resolver(texto)) {
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
                when (val r = OmniboxInput.resolver(texto)) {
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

    /** T086 v1 (R0191, RES-0091): pantalla de descargas sobre la cola
     * REAL del DownloadEngine verificado (T046): estados y bytes
     * reales, cancelar con confirmacion ADR-019, reanudar solo
     * fallidas con sink reabrible, abrir la completada con
     * ACTION_VIEW y grant; la pausa NO existe en el motor (JAMAS un
     * fallo disfrazado) y el historial vive en la sesion. */
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
                    DownloadState.QUEUED, DownloadState.RUNNING -> confirmarCancelarDescarga(item.id)
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

    /** Cancelar corta de verdad: la pausa NO existe en el motor y no
     * se disfraza (cancelar es terminal y pide confirmacion). */
    private fun confirmarCancelarDescarga(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Cancelar descarga")
            .setMessage("Cancelar corta la descarga #" + id + " (no hay pausa en el motor).")
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

    /** Reanudar sigue el contrato REAL del motor: solo FAILED con
     * bytes y sink reabrible (Range); el resto avisa honesto. */
    private fun reanudarDescarga(id: Long) {
        val ok = descargas.resume(id)
        estado.text = if (ok) {
            "descarga #" + id + " reanudada desde donde fallo"
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
                when (val r = OmniboxInput.resolver(texto)) {
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
        estado.text = "fire " + linea + " aplicado: " + resumen
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
                }
            }
            TipoOpcion.ENTERO -> pedirAjuste(op, "entre " + op.minimo + " y " + op.maximo)
            TipoOpcion.TEXTO -> pedirAjuste(op, op.descripcion)
            TipoOpcion.ELECCION -> {
                if (op.seccion == "Limpieza" && op.id != "limpieza.retencion") {
                    elegirLimpieza(op)
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
    }
}

