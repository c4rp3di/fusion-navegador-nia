package nia.engine.layout

import nia.engine.css.CssComputedStyle
import nia.engine.html.DomNode

/**
 * Layout v1 (T015): caja rectangular en coordenadas del documento.
 * Limitaciones v1: sin margin/padding/border, sin width/height fijados,
 * sin float/flex/tablas, medicion monoespaciada (avance = fontSize/2),
 * overflow permitido (las palabras no se parten).
 */
class LayoutBox(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Resultado del layout. boxOf: bloque -> su caja; inline/texto -> envolvente
 * de sus fragmentos (null si no dibuja nada). fragmentsOf: una caja por
 * palabra de un nodo de texto.
 */
class LayoutResult internal constructor(
    private val blockBoxes: Map<DomNode, LayoutBox>,
    private val fragments: Map<DomNode, List<LayoutBox>>,
    private val noneElements: Set<DomNode>
) {
    fun boxOf(node: DomNode): LayoutBox? {
        if (node is DomNode.Element) return blockBoxes[node] ?: inlineBounding(node)
        if (node is DomNode.Text) return fragments[node]?.let { bounding(it) }
        return null
    }

    fun fragmentsOf(node: DomNode): List<LayoutBox> = fragments[node] ?: emptyList()

    private fun bounding(list: List<LayoutBox>): LayoutBox? {
        if (list.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (b in list) {
            if (b.x < minX) minX = b.x
            if (b.y < minY) minY = b.y
            if (b.x + b.width > maxX) maxX = b.x + b.width
            if (b.y + b.height > maxY) maxY = b.y + b.height
        }
        return LayoutBox(minX, minY, maxX - minX, maxY - minY)
    }

    private fun inlineBounding(el: DomNode.Element): LayoutBox? {
        val list = mutableListOf<LayoutBox>()
        fun collect(n: DomNode.Element) {
            for (c in n.children) {
                if (c is DomNode.Text) {
                    val f = fragments[c]
                    if (f != null) list.addAll(f)
                } else if (c is DomNode.Element) {
                    if (c !in noneElements) collect(c)
                }
            }
        }
        collect(el)
        return bounding(list)
    }
}

/**
 * Motor de layout v1 (T015): apila bloques verticalmente (ancho completo del
 * contenedor) y fluye el contenido inline con envoltura greedy de palabras;
 * las tiras de inline entre bloques generan bloques anonimos. Alturas de
 * linea: line-height en px o normal = 1.2 x fontSize (por linea se usa el
 * maximo de sus palabras). Alineacion heredada left/center/right. El hueco
 * entre palabras usa la fuente de la palabra siguiente. Los textos solo con
 * espacios se ignoran; el whitespace colapsa a un espacio.
 */
object LayoutEngine {

    const val DEFAULT_VIEWPORT = 360.0

    fun layout(
        root: DomNode.Document,
        computed: CssComputedStyle,
        viewportWidth: Double = DEFAULT_VIEWPORT
    ): LayoutResult = layout(root, { node, name -> computed.propertyOf(node, name) }, viewportWidth)

    fun layout(
        root: DomNode.Document,
        styleOf: (DomNode, String) -> String?,
        viewportWidth: Double = DEFAULT_VIEWPORT
    ): LayoutResult = Machine(root, styleOf, viewportWidth).run()

    private class Machine(
        root: DomNode.Document,
        private val styleOf: (DomNode, String) -> String?,
        private val viewport: Double
    ) {
        private val blockTags = setOf(
            "html", "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section"
        )
        private val parents = HashMap<DomNode, DomNode>()
        private val displays = HashMap<DomNode.Element, String>()
        private val noneElements = HashSet<DomNode>()
        private val blockBoxes = LinkedHashMap<DomNode, LayoutBox>()
        private val fragments = LinkedHashMap<DomNode, MutableList<LayoutBox>>()

        private class W(val node: DomNode.Text, val text: String, val fs: Double, val lh: Double, var startX: Double)

        private class LineBox {
            val items = mutableListOf<W>()
            var x = 0.0
        }

        init {
            buildParents(root)
        }

        fun run(): LayoutResult {
            layoutContainer(root, 0.0, 0.0, viewport)
            return LayoutResult(LinkedHashMap(blockBoxes), fragments.mapValues { it.value.toList() }, noneElements)
        }

        private fun childrenOf(node: DomNode): List<DomNode> = when (node) {
            is DomNode.Document -> node.children
            is DomNode.Element -> node.children
            else -> emptyList()
        }

        private fun buildParents(node: DomNode) {
            if (node is DomNode.Element) {
                val d = styleOf(node, "display")
                val v = if (d == "none" || d == "block" || d == "inline") d else defaultDisplay(node.name)
                displays[node] = v
                if (v == "none") noneElements.add(node)
            }
            for (c in childrenOf(node)) {
                parents[c] = node
                buildParents(c)
            }
        }

        private fun defaultDisplay(name: String): String =
            if (name.lowercase() in blockTags) "block" else "inline"

        private fun displayOf(el: DomNode.Element): String = displays[el] ?: defaultDisplay(el.name)

        private fun nearestEl(n: DomNode): DomNode.Element? {
            var cur: DomNode? = n
            while (cur != null && cur !is DomNode.Element) cur = parents[cur]
            return cur as? DomNode.Element
        }

        private fun parsePx(v: String?): Double? {
            if (v == null || v.length < 3) return null
            if (v.substring(v.length - 2) != "px") return null
            return v.substring(0, v.length - 2).toDoubleOrNull()
        }

        private fun fontSizeOf(n: DomNode): Double {
            val el = nearestEl(n)
            val v = if (el != null) parsePx(styleOf(el, "font-size")) else null
            return v ?: 16.0
        }

        private fun lineHeightOfEl(el: DomNode.Element): Double =
            parsePx(styleOf(el, "line-height")) ?: fontSizeOf(el) * 1.2

        private fun alignOf(n: DomNode): String {
            val el = nearestEl(n)
            val v = if (el != null) styleOf(el, "text-align") else null
            return if (v == "center" || v == "right") v else "left"
        }

        private fun textWidth(s: String, fs: Double): Double = s.length * fs / 2

        private fun wordsOf(s: String): List<String> =
            s.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }

        private fun isWsOnly(s: String): Boolean = wordsOf(s).isEmpty()

        private fun collectText(item: DomNode, out: MutableList<W>) {
            if (item is DomNode.Text) {
                if (isWsOnly(item.data)) return
                val el = nearestEl(item)
                val fs = fontSizeOf(item)
                val lh = if (el != null) lineHeightOfEl(el) else fs * 1.2
                for (w in wordsOf(item.data)) out.add(W(item, w, fs, lh, 0.0))
            } else if (item is DomNode.Element) {
                if (displayOf(item) == "none") return
                for (c in item.children) collectText(c, out)
            }
        }

        private fun layoutInlineRun(run: List<DomNode>, x: Double, y: Double, width: Double, align: String): Double {
            val words = mutableListOf<W>()
            for (it in run) collectText(it, words)
            val lines = mutableListOf<LineBox>()
            var line = LineBox()
            for (w in words) {
                val ww = textWidth(w.text, w.fs)
                val sp = w.fs / 2
                if (line.items.isNotEmpty() && line.x + sp + ww > width) {
                    lines.add(line)
                    line = LineBox()
                }
                if (line.items.isNotEmpty()) {
                    line.x += sp
                    w.startX = line.x
                    line.x += ww
                } else {
                    w.startX = 0.0
                    line.x = ww
                }
                line.items.add(w)
            }
            if (line.items.isNotEmpty()) lines.add(line)
            var lineY = y
            for (L in lines) {
                var lh = 0.0
                for (it in L.items) if (it.lh > lh) lh = it.lh
                val offset = when (align) {
                    "center" -> (width - L.x) / 2
                    "right" -> width - L.x
                    else -> 0.0
                }
                for (it in L.items) {
                    fragments.getOrPut(it.node) { mutableListOf() }
                        .add(LayoutBox(x + offset + it.startX, lineY, textWidth(it.text, it.fs), lh))
                }
                lineY += lh
            }
            return lineY - y
        }

        private fun layoutContainer(node: DomNode, x: Double, y: Double, width: Double): Double {
            val align = alignOf(node)
            var cur = y
            val run = mutableListOf<DomNode>()
            for (child in childrenOf(node)) {
                if (child is DomNode.Text) {
                    if (isWsOnly(child.data)) continue
                    run.add(child)
                    continue
                }
                if (child is DomNode.Element) {
                    val d = displayOf(child)
                    if (d == "none") continue
                    if (d == "inline") {
                        run.add(child)
                        continue
                    }
                    if (run.isNotEmpty()) {
                        cur += layoutInlineRun(run.toList(), x, cur, width, align)
                        run.clear()
                    }
                    val h = layoutContainer(child, x, cur, width)
                    blockBoxes[child] = LayoutBox(x, cur, width, h)
                    cur += h
                    continue
                }
            }
            if (run.isNotEmpty()) cur += layoutInlineRun(run.toList(), x, cur, width, align)
            return cur - y
        }
    }
}
