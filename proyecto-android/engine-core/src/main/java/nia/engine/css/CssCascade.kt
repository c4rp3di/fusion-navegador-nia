package nia.engine.css

import nia.engine.html.DomNode

/**
 * Especificidad (a, b, c): ids, clases/atributos, tipos. Orden lexicografico.
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
 * Cascada CSS v1 (T014): matchea selectores contra el arbol DomNode, ordena
 * las declaraciones por (especificidad, orden de aparicion) y computa estilos:
 * defaults + herencia (color y tipografia bajan del padre; background-color
 * no) + declaraciones ganadoras. Limitaciones v1: sin origenes multiples,
 * !important, inline styles ni unidades relativas.
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
        "background-color" to "transparent"
    )

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

    private fun splitWs(s: String): List<String> = s.split(' ', '\t', '\n', '\r', '\u000C')

    fun matchCompound(compound: CssCompoundSelector, node: DomNode): Boolean {
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
            }
        }
        return true
    }

    fun compute(root: DomNode, stylesheet: CssStylesheet): CssComputedStyle {
        val parents = HashMap<DomNode, DomNode>()
        fun buildParents(node: DomNode) {
            for (c in childrenOf(node)) {
                parents[c] = node
                buildParents(c)
            }
        }
        buildParents(root)

        fun matchAt(selector: CssComplexSelector, idx: Int, node: DomNode): Boolean {
            if (!matchCompound(selector.compounds[idx], node)) return false
            if (idx == 0) return true
            val comb = selector.combinators[idx - 1]
            if (comb == "child") {
                val p = parents[node] ?: return false
                return matchAt(selector, idx - 1, p)
            }
            var p = parents[node]
            while (p != null) {
                if (matchAt(selector, idx - 1, p)) return true
                p = parents[p]
            }
            return false
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
                        if (matchAt(cx, cx.compounds.size - 1, node)) {
                            val sp = specificityOf(cx)
                            if (best == null || sp > best) best = sp
                        }
                    }
                    if (best != null) entries.add(Triple(best, r.order, r))
                }
                entries.sortWith(compareBy({ it.first }, { it.second }))
                for (e in entries) {
                    for ((k, v) in e.third.declarations) styles[k] = v
                }
            }
            computed[node] = styles
            val passDown = if (node is DomNode.Element) styles else parentStyles
            for (c in childrenOf(node)) walk(c, passDown)
        }
        walk(root, null)
        return CssComputedStyle(computed)
    }
}
