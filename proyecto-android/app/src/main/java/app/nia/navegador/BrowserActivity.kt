package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.net.HttpTransport
import nia.engine.net.PanelIdentidad
import nia.engine.omnibox.OmniboxInput
import nia.engine.omnibox.OmniboxSugerencias
import nia.engine.omnibox.Sugerencia
import nia.engine.profile.ProfileKind
import nia.engine.tabs.TabCrear
import nia.engine.tabs.TabTarjeta
import nia.engine.tabs.TabsManager
import nia.engine.themes.NiaTokens
import app.nia.navegador.ui.NiaProgressBar
import app.nia.navegador.ui.NiaUi

/**
 * BrowserActivity (T072 v2 + T073 v2, RES-0080, R0176): shell estetico
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
 * Exclusiones v3: sin grupos ni previews pintadas, sin persistencia
 * de la sesion de pestanas, panel de identidad solo de la ultima
 * pagina cargada, sin tema conmutable en la UI (T070), sugerencias en
 * dialogo (no desplegable) y el pintado respeta SOLO lo que el motor
 * CSS/layout soporta hoy (paginas reales simples, honesto).
 */
class BrowserActivity : Activity() {

    private val clock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    private val tabs by lazy { TabsManager(clock, HttpTransport(), File(cacheDir, "perfil-navegador")) }
    private val marcadores = BookmarkManager()

    private val colores by lazy { NiaUi.colores(this, NiaTokens.Modo.AUTO) }

    private lateinit var omnibox: EditText
    private lateinit var indicador: TextView
    private lateinit var estado: TextView
    private lateinit var progreso: NiaProgressBar
    private lateinit var pagina: PaintView
    private lateinit var scroll: ScrollView
    private lateinit var filaPestanas: LinearLayout
    private var panelIdentidad: String = ""

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
        omnibox.hint = "Busca o escribe una URL (R0176)"
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
        estado.text = "listo (R0176)"
        estado.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        estado.setTextColor(NiaUi.color(colores.fg))
        estado.setPadding(espacio, 0, espacio, 0)
        raiz.addView(estado)

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
        pintarStrip()
    }

    /** Strip de pestanas: una tarjeta por pestana viva y el boton + al final. */
    private fun pintarStrip() {
        filaPestanas.removeAllViews()
        for (tarjeta in tabs.tarjetas()) {
            filaPestanas.addView(chipDe(tarjeta))
        }
        filaPestanas.addView(botonMas())
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
                    5 -> startActivity(Intent(this, MainActivity::class.java))
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
        val cierre = tabs.cerrar(id) ?: return
        val mensaje = if (cierre.perfilBorrado) {
            "ultima incognito cerrada: perfil borrado de memoria"
        } else {
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

    /** Texto seguro para el HTML de la pagina de error (sin <, > ni &). */
    private fun textoHtml(s: String): String =
        s.replace('<', ' ').replace('>', ' ').replace('&', ' ')

    private fun paginaNueva(): String =
        "<html><body><h1>Navegador NIA (R0176)</h1>" +
            "<p>Motor propio, sin WebView. Busca o escribe una URL arriba.</p>" +
            "<p>Pestanas arriba: toca una para cambiar, x para cerrar, + para abrir. Incognito y reabrir en el Menu.</p>" +
            "<p>Las paginas reales se pintan con el layout del motor; hoy se ven simples (exclusion honesta del shell v2).</p>" +
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
