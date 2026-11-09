package app.nia.navegador

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import nia.engine.css.CssStylesheet
import nia.engine.devtools.DevToolsModel
import nia.engine.devtools.StyleCause
import nia.engine.dom.DomDocument
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode
import nia.engine.devtools.NetworkPanel
import nia.engine.net.FetchManager
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult
import nia.contracts.CancellationToken
import nia.engine.js.DebugSession
import nia.engine.js.JsInterpreter

/**
 * T017 v2 (R0041, RES-0027): panel DevTools propio sobre el modelo del
 * motor (T017 v1, verificado en R0039): arbol con nodeIds, estilos
 * computados reales de la cascada y la CAUSA de cada propiedad. Sin
 * WebView: un ScrollView con texto monoespaciado construido con el
 * DevToolsModel del engine-core sobre una demo propia.
 *
 * T017 v3 (R0072, RES-0041): la demo se unifica en DemoPage (la misma
 * pagina que pinta PaintView) y el panel abre centrado en la SELECCION
 * del usuario (DevToolsState): estilos computados con causas y matches
 * del nodo elegido con el toque, ademas del arbol y los reportes.
 *
 * T017 v4 (R0074, RES-0042): barra de edicion al pie del reporte: el
 * usuario escribe una regla CSS, Aplicar la anade a
 * DevToolsState.userCss y el reporte re-renderiza sobre la demo mas las
 * reglas del usuario (los errores de parseo se cuentan, no rompen nada);
 * Restaurar limpia la edicion. La pagina pintada re-renderiza al volver.
 * T017 v5 (R0082, RES-0046): seccion RED del reporte: el panel de red
 * del motor (NetworkPanel sobre FetchManager, redaccion de secretos en
 * la ingesta) alimentado por una demo determinista sin red en el
 * dispositivo.
 * T028 v4 (R0086, RES-0048): seccion DEBUGGER del reporte: la sesion de
 * debug del motor (DebugSession con reanudacion real) sobre una demo
 * determinista sin red, con el call stack real de la pausa.
 * T094 v5 (R0296, RES-0124): seccion MODULOS del reporte: el estado
 * DECLARATIVO de los modulos instalados viaja por el intent extra
 * "modulos" (construido en vivo por el navegador); sin extra, nota
 * honesta de abrirlo desde el Menu del navegador.
 */
class DevToolsActivity : Activity() {

    private lateinit var text: TextView

    /** T094 v5 (R0296, RES-0124): reporte declarativo de modulos que
     *  viaja por el intent (null cuando se abre desde la demo). */
    private var modulosDeclarados: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modulosDeclarados = intent.getStringExtra("modulos")
        val scroll = ScrollView(this)
        text = TextView(this)
        text.typeface = Typeface.MONOSPACE
        text.textSize = 12f
        text.setPadding(24, 32, 24, 64)
        text.text = report()
        scroll.addView(text)
        val input = EditText(this)
        input.hint = "regla CSS, p. ej. h1 { color: #ff0000; }"
        val apply = Button(this)
        apply.text = "Aplicar"
        apply.setOnClickListener {
            DevToolsState.addUserRule(input.text.toString())
            text.text = report()
        }
        val reset = Button(this)
        reset.text = "Restaurar"
        reset.setOnClickListener {
            DevToolsState.clearUserRules()
            text.text = report()
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(apply)
        bar.addView(reset)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun report(): String {
        val userCss = DevToolsState.userCss
        val css = if (userCss.isEmpty()) DemoPage.CSS else DemoPage.CSS + "\n" + userCss
        val doc = DomDocument.parse(DemoPage.HTML)
        val model = DevToolsModel(doc, css)
        val sb = StringBuilder()
        sb.append("== Arbol (nodeIds en pre-orden) ==").append('\n')
        sb.append(model.tree())
        val userSheet = CssStylesheet.parse(userCss)
        sb.append('\n')
        sb.append("== Reglas del usuario (").append(userSheet.rules.size).append(") ==").append('\n')
        if (userCss.isEmpty()) {
            sb.append("(sin reglas; escribe una regla CSS en la barra y pulsa Aplicar)").append('\n')
        } else {
            sb.append(userCss).append('\n')
            if (userSheet.errors.isNotEmpty()) {
                sb.append("(parse: ").append(userSheet.errors.size).append(" error(es) ignorados)").append('\n')
            }
        }
        val net = demoNetwork()
        sb.append('\n')
        sb.append("== RED (").append(net.count()).append(" entradas, secretos redactados) ==").append('\n')
        sb.append(net.summary())
        sb.append('\n')
        sb.append("== DEBUGGER (demo determinista, reanudacion real) ==").append('\n')
        sb.append(demoDebugger())
        sb.append('\n')
        sb.append("== MODULOS (paneles declarados) ==").append('\n')
        sb.append(modulosDeclarados ?: "(sin datos: abrelo desde el Menu del navegador)").append('\n')
        val sel = DevToolsState.selectedNodeId
        if (sel != null) {
            val h = model.handleWithNodeId(sel)
            if (h != null) appendSelection(sb, doc, model, h)
        }
        val body = doc.findFirst("body")
        if (body != null) {
            for (h in doc.childrenOf(body)) {
                appendNodeReport(sb, doc, model, h)
            }
        }
        return sb.toString()
    }

    /** Panel de red de la demo (v5): fake determinista, sin red en el device. */
    private fun demoNetwork(): NetworkPanel {
        val transport = object : FetchTransport {
            override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
                return when (request.url) {
                    "http://demo.local/datos" -> TransportResult.Success(
                        FetchResponse(200, mapOf("content-type" to "text/plain"), "datos de la demo".toByteArray())
                    )
                    "http://demo.local/falta" -> TransportResult.Success(
                        FetchResponse(404, emptyMap(), ByteArray(0))
                    )
                    else -> TransportResult.Failure(TransportFailure.NETWORK)
                }
            }
        }
        val panel = NetworkPanel(FetchManager(transport))
        panel.fetch(FetchRequest(1, "GET", "http://demo.local/datos", mapOf("Authorization" to "Bearer demo"), null))
        panel.fetch(FetchRequest(2, "GET", "http://demo.local/falta?token=demo", emptyMap(), null))
        panel.fetch(FetchRequest(3, "GET", "http://demo.local/roto", emptyMap(), null))
        return panel
    }

    /** Demo del debugger (T028 v4): DebugSession con reanudacion real
     * sobre un programa fijo, sin red en el dispositivo. */
    private fun demoDebugger(): String {
        val src = "function f() {\n  var x = 1\n}\nfunction g() {\n  f()\n}\ng()"
        val s = DebugSession(src).breakOn(2).start()
        val sb = StringBuilder()
        try {
            val p = s.awaitPause()
            if (p != null) {
                sb.append("pausa en linea ").append(p.line).append(" (").append(p.reason).append(")\n")
                sb.append("stack (interno primero):\n")
                for (fr in p.stack) sb.append("  ").append(fr).append('\n')
                s.resume()
            }
            if (s.awaitDone()) {
                val r = s.result
                if (r != null) sb.append("resultado: ").append(JsInterpreter.dumpResult(r)).append('\n')
            }
        } finally {
            s.stop()
        }
        return sb.toString()
    }

    private fun appendNodeReport(sb: StringBuilder, doc: DomDocument, model: DevToolsModel, handle: DomHandle) {
        val node = doc.nodeOf(handle)
        if (node is DomNode.Element) {
            sb.append('\n')
            sb.append("== #").append(handle.nodeId).append(' ').append(node.name).append(" ==").append('\n')
            val computed = model.computedOf(handle)
            for ((k, v) in computed) {
                sb.append(k).append(": ").append(v)
                sb.append("  // ").append(causeText(model.causeOf(handle, k))).append('\n')
            }
            for (m in model.matchingSelectors(handle)) {
                sb.append("match ").append(m.selector)
                sb.append(" (orden ").append(m.ruleOrder).append(')').append('\n')
            }
            for (c in doc.childrenOf(handle)) {
                val cn = doc.nodeOf(c)
                if (cn is DomNode.Element) appendNodeReport(sb, doc, model, c)
            }
        }
    }

    private fun appendSelection(sb: StringBuilder, doc: DomDocument, model: DevToolsModel, handle: DomHandle) {
        val node = doc.nodeOf(handle)
        sb.append('\n')
        sb.append("== SELECCION #").append(handle.nodeId).append(' ').append(describeName(node)).append(" ==").append('\n')
        val computed = model.computedOf(handle)
        if (computed.isEmpty()) {
            sb.append("(sin estilo computado)").append('\n')
        }
        for ((k, v) in computed) {
            sb.append(k).append(": ").append(v)
            sb.append("  // ").append(causeText(model.causeOf(handle, k))).append('\n')
        }
        for (m in model.matchingSelectors(handle)) {
            sb.append("match ").append(m.selector)
            sb.append(" (orden ").append(m.ruleOrder).append(')').append('\n')
        }
    }

    private fun describeName(node: DomNode): String = when (node) {
        is DomNode.Document -> "document"
        is DomNode.Text -> "text \"" + node.data + "\""
        is DomNode.Comment -> "comment"
        is DomNode.Doctype -> "doctype"
        is DomNode.Element -> node.name
    }

    private fun causeText(c: StyleCause?): String {
        if (c == null) return "-"
        return when (c) {
            is StyleCause.Rule -> "regla " + c.selector + " (orden " + c.ruleOrder + ", declarado " + c.declaredValue + ")"
            is StyleCause.Inherited -> "heredado de #" + c.fromNodeId + " (" + c.value + ")"
            is StyleCause.Default -> "por defecto (" + c.value + ")"
        }
    }

}
