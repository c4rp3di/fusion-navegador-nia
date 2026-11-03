package nia.engine.css

import nia.engine.html.DomNode

/**
 * Especificidad (a, b, c): ids, clases/atributos/pseudo-clases, tipos. Orden lexicografico.
 */
data class CssSpecificity(val ids: Int, val classes: Int, val types: Int) : Comparable<CssSpecificity> {
    override fun compareTo(other: CssSpecificity): Int {
        if (ids != other.ids) return ids - other.ids
        if (classes != other.classes) return classes - other.classes
        return types - other.types
    }
}

/**
 * Estilos computados de un arbol: mapa por nodo (identidad). propertyOf
 * devuelve null para nodos no presentes o propiedades sin valor.
 */
class CssComputedStyle internal constructor(private val computed: Map<DomNode, Map<String, String>>) {
    fun propertyOf(element: DomNode, name: String): String? = computed[element]?.get(name)
}

/**
 * Cascada CSS v2 (R0023): matchea selectores contra el arbol DomNode
 * (incluidos hermanos adyacente/general y pseudo-clases estaticas con
 * posicion 1-based entre elementos hijos), ordena las declaraciones por
 * (especificidad, orden de aparicion) y computa estilos: defaults +
 * herencia (color y tipografia bajan del padre; background-color, margin,
 * padding y border no) + declaraciones ganadoras con expansion de
 * shorthands (margin/padding/border-width con 1-4 valores TRBL) +
 * resolucion de unidades relativas a px (em/% de font-size contra el
 * padre; rem contra la raiz; em/rem de line-height, letter-spacing,
 * margin, padding y border contra la propia fuente o la raiz; el cero
 * sin unidades vale 0px). margin/padding/border-width en % se quedan como
 * "N%" y los resuelve el layout contra el ancho del contenedor.
 * Limitaciones v2: sin origenes multiples, !important ni inline styles.
 */
object CssCascade {

    val inheritedProperties = listOf(
        "color", "font-size", "font-family", "font-weight", "font-style",
        "text-align", "line-height", "letter-spacing"
    )

    val defaultValues = linkedMapOf(
        "color" to "#000000",
        "font-size" to "16px",
        "font-family" to "serif",
        "font-weight" to "normal",
        "font-style" to "normal",
        "text-align" to "left",
        "line-height" to "normal",
        "letter-spacing" to "normal",
        "background-color" to "transparent",
        "margin-top" to "0px",
        "margin-right" to "0px",
        "margin-bottom" to "0px",
        "margin-left" to "0px",
        "padding-top" to "0px",
        "padding-right" to "0px",
        "padding-bottom" to "0px",
        "padding-left" to "0px",
        "border-top-width" to "0px",
        "border-right-width" to "0px",
        "border-bottom-width" to "0px",
        "border-left-width" to "0px"
    )

    private val boxLonghands = listOf(
        "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border-top-width", "border-right-width", "border-bottom-width", "border-left-width"
    )

    private data class LenToken(val num: Double, val unit: String)

    private val lenRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))(px|em|rem|%)$")
    private val numRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))$")

    /** Longitud con unidad (px/em/rem/%) o numero puro (unit: "num"; el 0 puro vale "px"). */
    private fun lenToken(v: String?): LenToken? {
        if (v == null) return null
        val s = v.trim()
        if (s.isEmpty()) return null
        val m = lenRegex.find(s)
        if (m != null) return LenToken(m.groupValues[1].toDouble(), m.groupValues[4])
        val mn = numRegex.find(s)
        if (mn != null) {
            val num = mn.groupValues[1].toDouble()
            return if (num == 0.0) LenToken(0.0, "px") else LenToken(num, "num")
        }
        return null
    }

    private fun parsePx(v: String?): Double? {
        if (v == null || v.length < 3) return null
        if (v.substring(v.length - 2) != "px") return null
        return v.substring(0, v.length - 2).toDoubleOrNull()
    }

    private fun fmt(x: Double): String = if (x == 0.0) "0" else x.toString()

    fun specificityOf(selector: CssComplexSelector): CssSpecificity {
        var ids = 0
        var classes = 0
        var types = 0
        for (compound in selector.compounds) {
            for (s in compound.simples) {
                when (s) {
                    is CssSimpleSelector.Id -> ids++
                    is CssSimpleSelector.Cls -> classes++
                    is CssSimpleSelector.Attr -> classes++
                    is CssSimpleSelector.Pseudo -> classes++
                    is CssSimpleSelector.Type -> types++
                    is CssSimpleSelector.Universal -> {
                    }
                }
            }
        }
        return CssSpecificity(ids, classes, types)
    }

    private fun childrenOf(node: DomNode): List<DomNode> = when (node) {
        is DomNode.Document -> node.children
        is DomNode.Element -> node.children
        else -> emptyList()
    }

    /** Mapa hijo -> padre de todo el arbol, para combinadores en consultas. */
    fun parentMap(root: DomNode): Map<DomNode, DomNode> = buildParents(root)

    private fun buildParents(root: DomNode): HashMap<DomNode, DomNode> {
        val parents = HashMap<DomNode, DomNode>()
        fun go(node: DomNode) {
            for (c in childrenOf(node)) {
                parents[c] = node
                go(c)
            }
        }
        go(root)
        return parents
    }

    internal fun elementChildrenOf(node: DomNode): List<DomNode.Element> {
        val out = mutableListOf<DomNode.Element>()
        for (c in childrenOf(node)) if (c is DomNode.Element) out.add(c)
        return out
    }

    private fun matchNth(nth: CssNth, idx: Int): Boolean {
        val pos = idx + 1
        if (nth.a == 0) return pos == nth.b
        val d = pos - nth.b
        return d % nth.a == 0 && d / nth.a >= 0
    }

    internal fun matchCompound(compound: CssCompoundSelector, node: DomNode, parents: Map<DomNode, DomNode>): Boolean {
        if (node !is DomNode.Element) return false
        for (s in compound.simples) {
            when (s) {
                is CssSimpleSelector.Universal -> {
                }
                is CssSimpleSelector.Type -> if (node.name.lowercase() != s.name.lowercase()) return false
                is CssSimpleSelector.Id -> if (node.attributes["id"] != s.value) return false
                is CssSimpleSelector.Cls -> {
                    val cls = node.attributes["class"] ?: return false
                    if (!splitWs(cls).contains(s.value)) return false
                }
                is CssSimpleSelector.Attr -> {
                    val actual = node.attributes[s.name]
                    when (s.op) {
                        "" -> if (actual == null) return false
                        "=" -> if (actual != s.value) return false
                        "~=" -> {
                            if (actual == null) return false
                            if (!splitWs(actual).contains(s.value)) return false
                        }
                    }
                }
                is CssSimpleSelector.Pseudo -> {
                    val parent = parents[node] ?: return false
                    val els = elementChildrenOf(parent)
                    val idx = els.indexOf(node)
                    if (idx < 0) return false
                    when (s.name) {
                        "first-child" -> if (idx != 0) return false
                        "last-child" -> if (idx != els.size - 1) return false
                        "only-child" -> if (els.size != 1) return false
                        "nth-child" -> {
                            val nth = s.nth ?: return false
                            if (!matchNth(nth, idx)) return false
                        }
                    }
                }
            }
        }
        return true
    }

    internal fun matchAt(selector: CssComplexSelector, idx: Int, node: DomNode, parents: Map<DomNode, DomNode>): Boolean {
        if (!matchCompound(selector.compounds[idx], node, parents)) return false
        if (idx == 0) return true
        val comb = selector.combinators[idx - 1]
        if (comb == "child") {
            val p = parents[node] ?: return false
            return matchAt(selector, idx - 1, p, parents)
        }
        if (comb == "adj") {
            val parent = parents[node] ?: return false
            val els = elementChildrenOf(parent)
            val k = els.indexOf(node)
            if (k <= 0) return false
            return matchAt(selector, idx - 1, els[k - 1], parents)
        }
        if (comb == "gen") {
            val parent = parents[node] ?: return false
            val els = elementChildrenOf(parent)
            val k = els.indexOf(node)
            if (k <= 0) return false
            for (j in 0 until k) {
                if (matchAt(selector, idx - 1, els[j], parents)) return true
            }
            return false
        }
        var p = parents[node]
        while (p != null) {
            if (matchAt(selector, idx - 1, p, parents)) return true
            p = parents[p]
        }
        return false
    }

    /**
     * Parsea una lista de selectores separada por comas (T024). Devuelve
     * null si el texto es invalido o vacio.
     */
    fun parseSelectorList(text: String): List<CssComplexSelector>? {
        val sheet = CssStylesheet.parse(text.trim() + " { }")
        if (sheet.errors.isNotEmpty()) return null
        return sheet.rules.firstOrNull()?.selectors ?: emptyList()
    }

    /** True si node cumple cualquiera de los selectores (parents del arbol). */
    fun matchesAny(node: DomNode, selectors: List<CssComplexSelector>, parents: Map<DomNode, DomNode>): Boolean {
        for (cx in selectors) {
            if (matchAt(cx, cx.compounds.size - 1, node, parents)) return true
        }
        return false
    }

    private fun expandDecl(k: String, v: String): List<Pair<String, String>> {
        if (k == "margin" || k == "padding" || k == "border-width") {
            val parts = v.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.size > 4) return emptyList()
            for (p in parts) if (lenToken(p) == null) return emptyList()
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
        return listOf(k to v)
    }

    fun compute(root: DomNode, stylesheet: CssStylesheet): CssComputedStyle {
        val parents = buildParents(root)

        var rootFs: Double? = null

        fun resolveUnits(styles: LinkedHashMap<String, String>, parentStyles: Map<String, String>?) {
            val pfs0 = if (parentStyles != null) parsePx(parentStyles["font-size"]) else null
            val parentFs = pfs0 ?: 16.0
            val rootF = rootFs ?: 16.0
            val fsRaw = styles["font-size"]
            if (fsRaw != null && parsePx(fsRaw) == null) {
                val t = lenToken(fsRaw)
                var v: Double? = null
                if (t != null) {
                    v = when (t.unit) {
                        "em" -> t.num * parentFs
                        "%" -> (t.num * parentFs) / 100
                        "rem" -> t.num * rootF
                        "px" -> t.num
                        else -> null
                    }
                }
                if (v != null) styles["font-size"] = fmt(v) + "px"
            }
            val ownFs = parsePx(styles["font-size"]) ?: 16.0
            val lh = styles["line-height"]
            if (lh != null && lh != "normal" && parsePx(lh) == null) {
                val t = lenToken(lh)
                var v: Double? = null
                if (t != null) {
                    v = when (t.unit) {
                        "em" -> t.num * ownFs
                        "%" -> (t.num * ownFs) / 100
                        "rem" -> t.num * rootF
                        "px" -> t.num
                        "num" -> t.num * ownFs
                        else -> null
                    }
                }
                if (v != null) styles["line-height"] = fmt(v) + "px"
            }
            val ls = styles["letter-spacing"]
            if (ls != null && ls != "normal" && parsePx(ls) == null) {
                val t = lenToken(ls)
                var v: Double? = null
                if (t != null) {
                    v = when (t.unit) {
                        "em" -> t.num * ownFs
                        "%" -> (t.num * ownFs) / 100
                        "rem" -> t.num * rootF
                        "px" -> t.num
                        else -> null
                    }
                }
                if (v != null) styles["letter-spacing"] = fmt(v) + "px"
            }
            for (nm in boxLonghands) {
                val v0 = styles[nm] ?: continue
                if (parsePx(v0) != null) continue
                val t = lenToken(v0) ?: continue
                when (t.unit) {
                    "em" -> styles[nm] = fmt(t.num * ownFs) + "px"
                    "rem" -> styles[nm] = fmt(t.num * rootF) + "px"
                    "px" -> styles[nm] = fmt(t.num) + "px"
                }
            }
        }

        val computed = HashMap<DomNode, Map<String, String>>()
        fun walk(node: DomNode, parentStyles: Map<String, String>?) {
            val styles = LinkedHashMap<String, String>()
            if (node is DomNode.Element) {
                for ((k, v) in defaultValues) styles[k] = v
                if (parentStyles != null) {
                    for (k in inheritedProperties) {
                        val v = parentStyles[k]
                        if (v != null) styles[k] = v
                    }
                }
                val entries = mutableListOf<Triple<CssSpecificity, Int, CssRule>>()
                for (r in stylesheet.rules) {
                    var best: CssSpecificity? = null
                    for (cx in r.selectors) {
                        if (matchAt(cx, cx.compounds.size - 1, node, parents)) {
                            val sp = specificityOf(cx)
                            if (best == null || sp > best) best = sp
                        }
                    }
                    if (best != null) entries.add(Triple(best, r.order, r))
                }
                entries.sortWith(compareBy({ it.first }, { it.second }))
                for (e in entries) {
                    for ((k, v) in e.third.declarations) {
                        for (pair in expandDecl(k, v)) styles[pair.first] = pair.second
                    }
                }
                val firstEl = rootFs == null
                resolveUnits(styles, parentStyles)
                if (firstEl) {
                    rootFs = parsePx(styles["font-size"]) ?: 16.0
                }
            }
            computed[node] = styles
            val passDown = if (node is DomNode.Element) styles else parentStyles
            for (c in childrenOf(node)) walk(c, passDown)
        }
        walk(root, null)
        return CssComputedStyle(computed)
    }

    private fun splitWs(s: String): List<String> = s.split(' ', '\t', '\n', '\r', '\u000C')
}
