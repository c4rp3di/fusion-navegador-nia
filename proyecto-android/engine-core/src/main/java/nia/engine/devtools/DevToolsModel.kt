package nia.engine.devtools

import nia.engine.css.CssCascade
import nia.engine.css.CssComplexSelector
import nia.engine.css.CssCompoundSelector
import nia.engine.css.CssComputedStyle
import nia.engine.css.CssNth
import nia.engine.css.CssSimpleSelector
import nia.engine.css.CssSpecificity
import nia.engine.css.CssStylesheet
import nia.engine.dom.DomDocument
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorEvent
import nia.engine.inspector.InspectorSession

/**
 * T017 v1 (R0038, RES-0025): panel DevTools sin UI Android. Modelo de
 * inspeccion sobre el DOM real (DomDocument, T013) y la cascada real
 * (CssCascade, T014): arbol con nodeIds, estilos computados por nodo y,
 * para cada propiedad, la CAUSA visible (que regla gano y con que
 * especificidad/orden, valor heredado del padre o valor por defecto).
 *
 * Las causas se derivan con el MISMO algoritmo que CssCascade.compute
 * (mejor especificidad por regla, orden de aparicion, expansion de
 * shorthands): el valor mostrado siempre es el de compute y la causa es
 * la ultima escritura ganadora. La seleccion se graba como evento del
 * inspector (T008) con redaccion en la ingesta.
 *
 * T017 v3 (R0072, RES-0041): handleOf (nodo vivo -> handle por identidad)
 * y handleWithNodeId (nodeId -> handle en pre-orden), las dos consultas
 * de la seleccion interactiva del panel (el hit de pintura se resuelve a
 * handle; el panel mapea nodeId -> handle).
 *
 * Limitaciones v3: re-analisis por consulta (sin cache por revision),
 * sin origenes multiples ni !important (limitaciones heredadas de la
 * cascada v2), sin edicion de estilos ni live-highlight.
 */

sealed class StyleCause {
    data class Rule(
        val selector: String,
        val specificity: CssSpecificity,
        val ruleOrder: Int,
        val declaredValue: String
    ) : StyleCause()

    data class Inherited(val fromNodeId: Int, val value: String) : StyleCause()

    data class Default(val value: String) : StyleCause()
}

data class SelectorMatch(
    val selector: String,
    val specificity: CssSpecificity,
    val ruleOrder: Int
)

class DevToolsModel(private val document: DomDocument, private val stylesheet: CssStylesheet) {

    constructor(document: DomDocument, cssText: String) : this(document, CssStylesheet.parse(cssText))

    private data class Writer(
        val selector: String,
        val specificity: CssSpecificity,
        val ruleOrder: Int,
        val declaredValue: String
    )

    private data class Hit(
        val spec: CssSpecificity,
        val order: Int,
        val ruleIdx: Int,
        val selector: String
    )

    private class Analysis(
        val computed: CssComputedStyle,
        val writers: Map<DomNode, Map<String, Writer>>
    )

    private fun analyze(): Analysis {
        val root = document.nodeOf(document.rootHandle())
        val parents = CssCascade.parentMap(root)
        val computed = CssCascade.compute(root, stylesheet)
        val writers = HashMap<DomNode, Map<String, Writer>>()
        fun walk(node: DomNode) {
            if (node is DomNode.Element) {
                val w = HashMap<String, Writer>()
                val hits = mutableListOf<Hit>()
                for (ri in stylesheet.rules.indices) {
                    val r = stylesheet.rules[ri]
                    var best: CssSpecificity? = null
                    var bestSel = ""
                    for (cx in r.selectors) {
                        if (CssCascade.matchAt(cx, cx.compounds.size - 1, node, parents)) {
                            val sp = CssCascade.specificityOf(cx)
                            if (best == null || sp > best) {
                                best = sp
                                bestSel = describeComplex(cx)
                            }
                        }
                    }
                    val b = best
                    if (b != null) hits.add(Hit(b, r.order, ri, bestSel))
                }
                hits.sortWith(compareBy({ it.spec }, { it.order }))
                for (h in hits) {
                    val rule = stylesheet.rules[h.ruleIdx]
                    for ((k, v) in rule.declarations) {
                        for (pair in expandShorthand(k, v)) {
                            w[pair.first] = Writer(h.selector, h.spec, h.order, pair.second)
                        }
                    }
                }
                writers[node] = w
            }
            for (c in kidsOf(node)) walk(c)
        }
        walk(root)
        return Analysis(computed, writers)
    }

    /** Arbol del documento con nodeIds en pre-orden e indentacion de 2 espacios. */
    fun tree(): String {
        val sb = StringBuilder()
        fun walk(h: DomHandle, depth: Int) {
            val node = document.nodeOf(h)
            for (i in 0 until depth) sb.append("  ")
            sb.append(h.nodeId.toString()).append(' ').append(describeNode(node)).append('\n')
            if (node is DomNode.Document || node is DomNode.Element) {
                for (k in document.childrenOf(h)) walk(k, depth + 1)
            }
        }
        walk(document.rootHandle(), 0)
        return sb.toString()
    }

    /** Estilo computado de un elemento (valores resueltos); vacio en otro caso. */
    fun computedOf(handle: DomHandle): Map<String, String> {
        val node = document.nodeOf(handle)
        if (node !is DomNode.Element) return emptyMap()
        val a = analyze()
        val out = LinkedHashMap<String, String>()
        for (k in CssCascade.defaultValues.keys) {
            val v = a.computed.propertyOf(node, k)
            if (v != null) out[k] = v
        }
        val extra = a.writers[node]?.keys ?: emptySet()
        for (k in extra) {
            if (!out.containsKey(k)) {
                val v = a.computed.propertyOf(node, k)
                if (v != null) out[k] = v
            }
        }
        return out
    }

    /** Causa visible del valor computado de una propiedad, o null si no hay. */
    fun causeOf(handle: DomHandle, property: String): StyleCause? {
        val node = document.nodeOf(handle)
        if (node !is DomNode.Element) return null
        val a = analyze()
        val w = a.writers[node]?.get(property)
        if (w != null) return StyleCause.Rule(w.selector, w.specificity, w.ruleOrder, w.declaredValue)
        if (CssCascade.inheritedProperties.contains(property)) {
            val ph = document.parentOf(handle)
            if (ph != null) {
                val pn = document.nodeOf(ph)
                if (pn is DomNode.Element) {
                    val pv = a.computed.propertyOf(pn, property)
                    if (pv != null) return StyleCause.Inherited(ph.nodeId, pv)
                }
            }
        }
        val d = CssCascade.defaultValues[property]
        if (d != null) return StyleCause.Default(d)
        return null
    }

    /** Todos los selectores que matchean el elemento, en orden de hoja. */
    fun matchingSelectors(handle: DomHandle): List<SelectorMatch> {
        val node = document.nodeOf(handle)
        if (node !is DomNode.Element) return emptyList()
        val parents = CssCascade.parentMap(document.nodeOf(document.rootHandle()))
        val out = mutableListOf<SelectorMatch>()
        for (r in stylesheet.rules) {
            for (cx in r.selectors) {
                if (CssCascade.matchAt(cx, cx.compounds.size - 1, node, parents)) {
                    out.add(SelectorMatch(describeComplex(cx), CssCascade.specificityOf(cx), r.order))
                }
            }
        }
        return out
    }

    /** Handle del nodo vivo dado por identidad (T017 v3): recorre el
     * documento en pre-orden desde la raiz; null si el nodo no pertenece
     * al documento registrado (p. ej. de otro parse o desregistrado). */
    fun handleOf(node: DomNode): DomHandle? {
        fun walk(h: DomHandle): DomHandle? {
            val n = document.nodeOf(h)
            if (n === node) return h
            if (n is DomNode.Document || n is DomNode.Element) {
                for (c in document.childrenOf(h)) {
                    val r = walk(c)
                    if (r != null) return r
                }
            }
            return null
        }
        return walk(document.rootHandle())
    }

    /** Handle del nodo con ese nodeId de pre-orden (T017 v3), o null. */
    fun handleWithNodeId(nodeId: Int): DomHandle? {
        fun walk(h: DomHandle): DomHandle? {
            if (h.nodeId == nodeId) return h
            val n = document.nodeOf(h)
            if (n is DomNode.Document || n is DomNode.Element) {
                for (c in document.childrenOf(h)) {
                    val r = walk(c)
                    if (r != null) return r
                }
            }
            return null
        }
        return walk(document.rootHandle())
    }

    /** Graba la seleccion como evento del inspector (payload redactado en la ingesta). */
    fun recordSelection(session: InspectorSession, context: EventContext, handle: DomHandle): InspectorEvent {
        val node = document.nodeOf(handle)
        val payload = LinkedHashMap<String, String>()
        payload["nodeId"] = handle.nodeId.toString()
        when (node) {
            is DomNode.Document -> payload["name"] = "document"
            is DomNode.Doctype -> payload["name"] = "doctype"
            is DomNode.Comment -> payload["name"] = "comment"
            is DomNode.Text -> {
                payload["name"] = "text"
                payload["text"] = node.data
            }
            is DomNode.Element -> {
                payload["name"] = node.name
                payload["attributes"] = node.attributes.size.toString()
            }
        }
        return session.custom(context, "devtools", EventLevel.INFO, payload)
    }

    private fun kidsOf(node: DomNode): List<DomNode> = when (node) {
        is DomNode.Document -> node.children
        is DomNode.Element -> node.children
        else -> emptyList()
    }

    private fun describeNode(node: DomNode): String = when (node) {
        is DomNode.Document -> "document"
        is DomNode.Doctype -> {
            val nm = node.name
            if (nm == null) "doctype" else "doctype " + nm
        }
        is DomNode.Comment -> "comment \"" + node.data + "\""
        is DomNode.Text -> "text \"" + node.data + "\""
        is DomNode.Element -> {
            val sb = StringBuilder(node.name)
            for ((k, v) in node.attributes) {
                sb.append(' ').append(k).append("=\"").append(v).append('"')
            }
            sb.toString()
        }
    }

    /** Expansion de shorthands espejo de CssCascade (margin/padding/border-width TRBL). */
    private fun expandShorthand(k: String, v: String): List<Pair<String, String>> {
        if (k != "margin" && k != "padding" && k != "border-width") return listOf(k to v)
        val parts = v.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return emptyList()
        for (p in parts) if (!lenLike(p)) return emptyList()
        val q = when (parts.size) {
            1 -> listOf(parts[0], parts[0], parts[0], parts[0])
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            else -> parts
        }
        val names = when (k) {
            "margin" -> listOf("margin-top", "margin-right", "margin-bottom", "margin-left")
            "padding" -> listOf("padding-top", "padding-right", "padding-bottom", "padding-left")
            else -> listOf("border-top-width", "border-right-width", "border-bottom-width", "border-left-width")
        }
        return listOf(names[0] to q[0], names[1] to q[1], names[2] to q[2], names[3] to q[3])
    }

    /** Longitud valida para un shorthand: numero con unidad px/em/rem/% o numero puro. */
    private fun lenLike(s: String): Boolean {
        var i = 0
        val n = s.length
        if (n == 0) return false
        if (s[i] == '+' || s[i] == '-') i++
        var digits = 0
        while (i < n && s[i] in '0'..'9') {
            i++
            digits++
        }
        if (i < n && s[i] == '.') {
            i++
            var frac = 0
            while (i < n && s[i] in '0'..'9') {
                i++
                frac++
            }
            if (digits == 0 && frac == 0) return false
        } else if (digits == 0) return false
        if (i == n) return true
        val unit = s.substring(i)
        return unit == "px" || unit == "em" || unit == "rem" || unit == "%"
    }

    private fun describeComplex(cx: CssComplexSelector): String {
        if (cx.compounds.isEmpty()) return ""
        val sb = StringBuilder(describeCompound(cx.compounds[0]))
        for (i in cx.combinators.indices) {
            sb.append(combText(cx.combinators[i]))
            sb.append(describeCompound(cx.compounds[i + 1]))
        }
        return sb.toString()
    }

    private fun describeCompound(compound: CssCompoundSelector): String {
        val sb = StringBuilder()
        for (s in compound.simples) sb.append(describeSimple(s))
        return sb.toString()
    }

    private fun describeSimple(s: CssSimpleSelector): String = when (s) {
        is CssSimpleSelector.Universal -> "*"
        is CssSimpleSelector.Type -> s.name
        is CssSimpleSelector.Id -> "#" + s.value
        is CssSimpleSelector.Cls -> "." + s.value
        is CssSimpleSelector.Attr -> when (s.op) {
            "" -> "[" + s.name + "]"
            "=" -> "[" + s.name + "=" + (s.value ?: "") + "]"
            else -> "[" + s.name + "~=" + (s.value ?: "") + "]"
        }
        is CssSimpleSelector.Pseudo -> {
            val nth = s.nth
            if (nth == null) ":" + s.name else ":" + s.name + "(" + describeNth(nth) + ")"
        }
    }

    private fun describeNth(n: CssNth): String {
        if (n.a == 0) return n.b.toString()
        val head = when {
            n.a == 1 -> "n"
            n.a == -1 -> "-n"
            else -> n.a.toString() + "n"
        }
        val tail = when {
            n.b == 0 -> ""
            n.b > 0 -> "+" + n.b
            else -> "-" + (-n.b)
        }
        return head + tail
    }

    private fun combText(c: String): String = when (c) {
        "child" -> " > "
        "adj" -> " + "
        "gen" -> " ~ "
        else -> " "
    }
}
