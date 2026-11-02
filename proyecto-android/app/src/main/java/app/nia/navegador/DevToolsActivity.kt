package app.nia.navegador

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

import nia.engine.devtools.DevToolsModel
import nia.engine.devtools.StyleCause
import nia.engine.dom.DomDocument
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode

/**
 * T017 v2 (R0041, RES-0027): panel DevTools propio sobre el modelo del
 * motor (T017 v1, verificado en R0039): arbol con nodeIds, estilos
 * computados reales de la cascada y la CAUSA de cada propiedad. Sin
 * WebView: un ScrollView con texto monoespaciado construido con el
 * DevToolsModel del engine-core sobre una demo propia.
 */
class DevToolsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val text = TextView(this)
        text.typeface = Typeface.MONOSPACE
        text.textSize = 12f
        text.setPadding(24, 32, 24, 64)
        text.text = report()
        scroll.addView(text)
        setContentView(scroll)
    }

    private fun report(): String {
        val doc = DomDocument.parse(DEMO_HTML)
        val model = DevToolsModel(doc, DEMO_CSS)
        val sb = StringBuilder()
        sb.append("== Arbol (nodeIds en pre-orden) ==").append('\n')
        sb.append(model.tree())
        val body = doc.findFirst("body")
        if (body != null) {
            for (h in doc.childrenOf(body)) {
                appendNodeReport(sb, doc, model, h)
            }
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

    private fun causeText(c: StyleCause?): String {
        if (c == null) return "-"
        return when (c) {
            is StyleCause.Rule -> "regla " + c.selector + " (orden " + c.ruleOrder + ", declarado " + c.declaredValue + ")"
            is StyleCause.Inherited -> "heredado de #" + c.fromNodeId + " (" + c.value + ")"
            is StyleCause.Default -> "por defecto (" + c.value + ")"
        }
    }

    companion object {
        private const val DEMO_HTML = "<html><head><title>demo</title></head><body><h1 id=\"titulo\">NIA DevTools</h1><p class=\"tarjeta\">Panel v1</p><p>Seleccion y causas visibles</p></body></html>"

        private const val DEMO_CSS = "body { color: #222222; font-size: 20px; }" + "\n" +
            "h1 { color: #445566; }" + "\n" +
            "#titulo { color: #778899; }" + "\n" +
            "p { margin: 1em; }" + "\n" +
            ".tarjeta { margin: 2em; }"
    }
}
