package app.nia.navegador

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import nia.contracts.ErrorCode
import nia.contracts.WallClock
import nia.engine.bookmarks.BookmarkManager
import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.errors.ErrorPages
import nia.engine.html.DomNode
import nia.engine.nav.NavController
import nia.engine.nav.NavOutcome
import nia.engine.net.HttpTransport
import nia.engine.omnibox.OmniboxInput
import nia.engine.omnibox.OmniboxSugerencias
import nia.engine.omnibox.Sugerencia
import nia.engine.profile.BrowserProfile

/**
 * BrowserActivity (T072 v1, RES-0075, R0155, ADR-022): shell de navegacion
 * con omnibox sobre el NavController verificado (T071). La resolucion del
 * input sigue las cinco reglas del ADR (OmniboxInput), la navegacion REAL
 * corre fuera del hilo de UI (HttpTransport contra el perfil normal en
 * cacheDir), el estado y el origen quedan visibles, stop cancela por
 * generaciones, recargar re-navega la pagina actual, back/forward usa el
 * historial del tab y las sugerencias son SIEMPRE locales (marcadores en
 * memoria + historial del tab, sin red ni telemetria).
 *
 * Fallback DNS (ADR-022): si la navegacion implicita de un dominio falla de
 * DNS (ErrorCode.NETWORK con mensaje de DNS, contrato del FetchManager), se
 * OFRECE la busqueda del texto original tras confirmacion, nunca en
 * silencio; cualquier otro fallo muestra su error honesto.
 *
 * Paginas de error (T083 v1, RES-0076): todo fallo que no sea una cancelacion o
 * un handle caducado muestra su pagina de error (ErrorPages del motor) con el
 * detalle REDACTADO y la URL de la pagina comprometida sin secretos; TLS nunca
 * ofrece recargar como recuperacion.
 *
 * Exclusiones v1 (documentadas en la capability engine.omnibox): sin pintar
 * la pagina (el PaintView del shell llega con el smoke T067-A; aqui se lee
 * el texto del documento), sin gestor de historial global ni persistencia de
 * marcadores (llegan con las sesiones), sin incognito del shell (las
 * sugerencias lo respetan por el motor), motor DuckDuckGo HTML por defecto
 * elegible con los ajustes (T054/T094) y sin event loop del shell
 * (T034/T025).
 */
class BrowserActivity : Activity() {

    private val clock = object : WallClock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    private val profile by lazy { BrowserProfile.normal(clock, File(cacheDir, "perfil-navegador")) }
    private val nav by lazy { NavController(HttpTransport(), clock, profile) }
    private val marcadores = BookmarkManager()

    private lateinit var omnibox: EditText
    private lateinit var estado: TextView
    private lateinit var pagina: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val fila1 = LinearLayout(this)
        omnibox = EditText(this)
        omnibox.hint = "Busca o escribe una URL"
        fila1.addView(omnibox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila1.addView(boton("Ir") { resolverYnavegar() })
        fila1.addView(boton("Stop") {
            nav.invalidateGeneration()
            estado.text = "navegacion cancelada por generacion"
        })
        fila1.addView(boton("Recargar") { recargar() })
        raiz.addView(fila1)

        val fila2 = LinearLayout(this)
        fila2.addView(boton("Atras") {
            if (!nav.canGoBack()) {
                estado.text = "sin historial para atras"
            } else {
                navegarOutcome { nav.back() }
            }
        })
        fila2.addView(boton("Adelante") {
            if (!nav.canGoForward()) {
                estado.text = "sin historial para adelante"
            } else {
                navegarOutcome { nav.forward() }
            }
        })
        fila2.addView(boton("Sugerencias") { mostrarSugerencias() })
        raiz.addView(fila2)

        estado = TextView(this)
        estado.text = "listo"
        raiz.addView(estado)

        pagina = TextView(this)
        pagina.text = ""
        val scroll = ScrollView(this)
        scroll.addView(
            pagina,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        raiz.addView(
            scroll,
            LinearLayout.LayoutParams(0, 0, 1f)
        )

        setContentView(raiz)
    }

    private fun boton(etiqueta: String, alPulsar: () -> Unit): Button {
        val b = Button(this)
        b.text = etiqueta
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
        val actual = nav.currentPage()?.url
        if (actual == null) {
            estado.text = "sin pagina actual"
        } else {
            navegar(actual, false, null)
        }
    }

    private fun navegar(url: String, dominioImplicito: Boolean, textoOriginal: String?) {
        nav.invalidateGeneration()
        Thread {
            val o = nav.navigate(url)
            runOnUiThread {
                when (o) {
                    is NavOutcome.Loaded -> pintaPagina(o)
                    is NavOutcome.Failed -> manejaFallo(o, dominioImplicito, textoOriginal)
                }
            }
        }.start()
    }

    private fun navegarOutcome(accion: () -> NavOutcome?) {
        nav.invalidateGeneration()
        Thread {
            val o = accion()
            runOnUiThread {
                if (o == null) {
                    estado.text = "sin resultado de navegacion"
                } else {
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
        }
    }

    private fun mostrarSugerencias() {
        val texto = omnibox.text.toString()
        val historial = nav.historyUrls().asReversed()
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

    private fun pintaPagina(o: NavOutcome.Loaded) {
        val page = o.page
        estado.text = "LOADED " + page.status + " - " + page.origin + " - " + page.url
        pagina.text = textoDe(page.document).take(20000)
    }

    private fun textoDe(doc: DomDocument): String {
        val sb = StringBuilder()
        fun recorrer(h: DomHandle) {
            when (val n = doc.nodeOf(h)) {
                is DomNode.Text -> {
                    sb.append(n.data)
                    sb.append('\n')
                }
                is DomNode.Element -> for (k in hijos(doc, h)) recorrer(k)
                is DomNode.Document -> for (k in hijos(doc, h)) recorrer(k)
                else -> {}
            }
        }
        recorrer(doc.rootHandle())
        return sb.toString()
    }

    private fun hijos(doc: DomDocument, h: DomHandle): List<DomHandle> =
        try {
            doc.childrenOf(h)
        } catch (e: DomException) {
            emptyList()
        }
}
