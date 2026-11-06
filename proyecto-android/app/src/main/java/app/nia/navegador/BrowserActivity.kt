package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import nia.contracts.ErrorCode
import nia.contracts.WallClock
import nia.engine.bookmarks.BookmarkManager
import nia.engine.errors.ErrorPages
import nia.engine.history.HistoryManager
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.net.HttpTransport
import nia.engine.net.PanelIdentidad
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

/**
 * BrowserActivity (T072 v2 + T073 v2 + T090 v1 + T074 v1 + T093 v1, RES-0083, R0179): shell estetico
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
 * T093 v1 (R0179): ajustes modulares con el SettingsManager del
 * motor (armazon v1: registro tipificado, busqueda que abre la
 * opcion, export/import SIN secretos ni no disponibles, recovery
 * y persistencia): el Menu trae Ajustes; toda opcion enlaza
 * capacidad real (diagnostico.fifo: lineas de la traza en
 * caliente) o declara su estado de no disponible (nada
 * falsificado).
 *
 * Exclusiones v6: sin grupos ni previews pintadas, sin persistencia
 * de la sesion de pestanas (el incognito JAMAS se restaura tras un
 * proceso muerto: vive solo en memoria), Fire solo del perfil del tipo
 * activo (sin gestor multi-perfil ni Ajustes Limpieza, T054/T093),
 * panel de identidad solo de la ultima pagina cargada, sin tema
 * conmutable en la UI (T070), sugerencias en dialogo (no desplegable)
 * y el pintado respeta SOLO lo que el motor CSS/layout soporta hoy
 * (paginas reales simples, honesto). El historial del shell es un
 * dialogo simple agrupado por dia: la busqueda de texto, el borrado
 * por rango y los Ajustes de Limpieza llegan con T093.
 */
class BrowserActivity : Activity() {

    private val clock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    private val tabs by lazy { TabsManager(clock, HttpTransport(), File(cacheDir, "perfil-navegador")) }
    private val marcadores = BookmarkManager()

    /** Historial global SOLO del perfil NORMAL (T074): el incognito
     * jamas se registra y la URL se guarda sin query (redaccion).
     * Persiste en filesDir; un archivo corrupto se ignora. */
    private val historial by lazy { HistoryManager(clock, File(filesDir, "historial-normal.tsv")) }

    /** Ajustes del navegador (T093 v1): armazon del SettingsManager
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
        s.recargar()
        s
    }

    private val colores by lazy { NiaUi.colores(this, NiaTokens.Modo.AUTO) }

    private lateinit var omnibox: EditText
    private lateinit var indicador: TextView
    private lateinit var estado: TextView
    private lateinit var progreso: NiaProgressBar
    private lateinit var pagina: PaintView
    private lateinit var scroll: ScrollView
    private lateinit var filaPestanas: LinearLayout
    private var panelIdentidad: String = ""

    /** Traza del shell por apartado (T090): ultimas 40 lineas visibles
     * en el dialogo Diagnostico y en logcat (tag NIA.Shell). */
    private val traza = ArrayList<String>()

    /** Banner inequivoco de modo incognito (T090). */
    private lateinit var bannerIncognito: TextView

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

        val filaOmnibox = LinearLayout(this)
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
        omnibox.hint = "Busca o escribe una URL (R0179)"
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
        estado.text = "listo (R0179)"
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
        scroll = ScrollView(this)
        scroll.setBackgroundColor(NiaUi.color(colores.bg))
        scroll.addView(
            pagina,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

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
        traza("shell", "arranque R0179 con " + tabs.contar() + " pestana(s)")
        pintarStrip()
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
            val o = nc.navigate(url)
            runOnUiThread {
                progreso.setFraccion(0f)
                refrescaTarjeta(id, o)
                if (tabs.activa() == id) {
                    when (o) {
                        is NavOutcome.Loaded -> pintaPagina(o)
                        is NavOutcome.Failed -> manejaFallo(o, dominioImplicito, textoOriginal)
                    }
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
            val o = accion(nc)
            runOnUiThread {
                progreso.setFraccion(0f)
                if (o == null) {
                    estado.text = "sin resultado de navegacion"
                    return@runOnUiThread
                }
                refrescaTarjeta(id, o)
                if (tabs.activa() == id) {
                    when (o) {
                        is NavOutcome.Loaded -> pintaPagina(o)
                        is NavOutcome.Failed -> manejaFallo(o, false, null)
                    }
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
            "Sugerencias",
            "Identidad de la pagina",
            "Historial (solo pestanas normales)",
            "Ajustes (busca y cambia)",
            "Fire sesion (perfil del tipo activo)",
            "Fire total (perfil del tipo activo)",
            "Diagnostico (traza del shell)",
            "Demos del motor"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> nuevaPestana(ProfileKind.NORMAL)
                    1 -> nuevaPestana(ProfileKind.INCOGNITO)
                    2 -> reabrirPestana()
                    3 -> mostrarSugerencias()
                    4 -> mostrarIdentidad()
                    5 -> mostrarHistorial()
                    6 -> mostrarAjustes()
                    7 -> confirmarFire(FireLevel.SESION)
                    8 -> confirmarFire(FireLevel.TOTAL)
                    9 -> mostrarDiagnostico()
                    10 -> startActivity(Intent(this, MainActivity::class.java))
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
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
        val cierre = tabs.cerrar(id) ?: return
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
    }

    private fun reabrirPestana() {
        when (val r = tabs.reabrir()) {
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

    /** Fire del perfil del TIPO de la pestana activa (T090) con
     * confirmacion explicita por dialogo (ADR-019). */
    private fun confirmarFire(nivel: FireLevel) {
        val id = tabs.activa()
        val kind = if (id == null) null else tabs.tarjeta(id)?.kind
        val tipo = kind ?: ProfileKind.NORMAL
        val nombre = if (tipo == ProfileKind.INCOGNITO) "incognito" else "normal"
        val titulo = if (nivel == FireLevel.SESION) "Fire de sesion" else "Fire total"
        val mensaje = if (nivel == FireLevel.SESION) {
            "Borrar el sessionStorage del perfil " + nombre + "? (cookies, cache y storage local quedan)"
        } else {
            "Borrar TODO el estado del perfil " + nombre + " (cookies, cache, storage, IndexedDB y el archivo en disco) y vaciar sus reabiertas?"
        }
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Borrar") { _, _ -> ejecutarFire(tipo, nivel) }
            .setNegativeButton("Cancelar", null)
            .show()
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

    /** Dialogo de ajustes (T093 v1): busca por nombre, seccion o
     * descripcion y ABRE la opcion; la seccion Limpieza y las demas
     * secciones llegan con los subhitos siguientes. */
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

    /** Aplica el cambio de un ajuste (T093): BOOL alterna al tocar;
     * ENTERO y TEXTO piden el valor; ELECCION elige de la lista; lo
     * no disponible se avisa y JAMAS cambia. */
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
                }
            }
            TipoOpcion.ENTERO -> pedirAjuste(op, "entre " + op.minimo + " y " + op.maximo)
            TipoOpcion.TEXTO -> pedirAjuste(op, op.descripcion)
            TipoOpcion.ELECCION -> {
                AlertDialog.Builder(this)
                    .setTitle(op.titulo)
                    .setItems(op.elecciones.toTypedArray()) { _, cual ->
                        val nuevo = op.elecciones[cual]
                        if (ajustes.asignar(op.id, nuevo)) {
                            estado.text = op.titulo + " = " + nuevo
                            traza("ajustes", "cambiado " + op.id + " a " + nuevo)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
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

    private fun paginaNueva(): String =
        "<html><body><h1>Navegador NIA (R0179)</h1>" +
            "<p>Motor propio, sin WebView. Busca o escribe una URL arriba.</p>" +
            "<p>Pestanas arriba: toca una para cambiar, x para cerrar, + para abrir. Incognito y reabrir en el Menu.</p>" +
            "<p>El Menu trae Historial (solo pestanas normales), Ajustes (busca y cambia), Fire de sesion y total con confirmacion, y Diagnostico: la traza del shell (tambien en adb logcat -s NIA.Shell).</p>" +
            "<p>Las paginas reales se pintan con el layout del motor; hoy se ven simples (exclusion honesta del shell).</p>" +
            "</body></html>"

    /** CSS del navegador: tokens del tema activo (T069/ADR-021); inputs
     * con fondo oscuro para que el value pintado sea legible. */
    private fun cssNavegador(): String =
        "body{background-color:" + colores.bg + ";color:" + colores.fg + ";font-size:16px;padding:12px}" +
            "h1{font-size:24px;color:" + colores.accent + "}" +
            "h2{font-size:20px;color:" + colores.accent + "}" +
            "p{margin-top:8px}" +
            "input{display:block;padding:10px;border-width:1px;border-color:" + colores.surface +
            ";background-color:#23233f;color:#f0eef8;font-size:14px;margin-top:8px}"
}
