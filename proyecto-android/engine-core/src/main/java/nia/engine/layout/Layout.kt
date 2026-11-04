package nia.engine.layout

import nia.engine.css.CssComputedStyle
import nia.engine.html.DomNode

/**
 * Layout v3 (R0084): caja rectangular en coordenadas del documento.
 * Limitaciones v3: sin width/height fijados, sin float/flex (tablas en v3),
 * medicion monoespaciada (avance = fontSize/2), overflow permitido
 * (las palabras no se parten).
 */
class LayoutBox(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Resultado del layout. boxOf: bloque -> su caja (border box); inline/texto ->
 * envolvente de sus fragmentos (null si no dibuja nada). fragmentsOf: una caja
 * por palabra de un nodo de texto.
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
 * Motor de layout v2 (R0023): apila bloques verticalmente y fluye el
 * contenido inline con envoltura greedy de palabras. Novedades v2: cada
 * bloque hijo se coloca con margin (sin margin collapsing), padding y
 * border de sus estilos computados; el modelo es border-box para el
 * rectangulo devuelto (los margins quedan fuera), la caja de contenido
 * del hijo es x + margin-left + border-left + padding-left y ancho del
 * contenedor menos margins, paddings y borders horizontales. margin,
 * padding y border-width aceptan px, em/rem (contra la fuente del propio
 * elemento; rem contra la raiz 16px) y N% resuelto contra el ancho del
 * contenedor. Alturas de linea: line-height en px o normal = 1.2 x
 * fontSize (por linea se usa el maximo de sus palabras). Alineacion
 * heredada left/center/right. Los textos solo con espacios se ignoran;
 * el whitespace colapsa a un espacio.
 *
 * v3 (R0084, RES-0047) - tablas HTML (T032 v1): table es un display propio
 * (por tag o por CSS, con table-row/table-row-group/table-cell);
 * thead/tbody/tfoot se aplanan y cada tr aporta una fila de celdas td/th.
 * Ancho de columna = maximo del preferido de sus celdas (width px/em/rem/%
 * de la tabla, o ancho natural de una linea del contenido inline, mas el
 * padding y border horizontales de la celda); la tabla estira a su width
 * declarado repartiendo el resto a partes iguales y sin width queda en su
 * ancho natural. Cada celda fluye su contenido inline con la misma
 * envoltura greedy y se estira al alto de su fila. Exclusiones v1: sin
 * colspan/rowspan, sin border-collapse/border-spacing, caption/col/
 * colgroup a display none, el contenido de bloque de una celda no se
 * estructura (una tabla anidada se mide como texto inline) y filas/celdas
 * fuera de tabla se tratan como inline.
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
        private val root: DomNode.Document,
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

        private val lenRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))(px|em|rem|%)$")
        private val numRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))$")

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
                val v = if (d == "none" || d == "block" || d == "inline" || d == "table" || d == "table-row" || d == "table-row-group" || d == "table-cell") d else defaultDisplay(node.name)
                displays[node] = v
                if (v == "none") noneElements.add(node)
            }
            for (c in childrenOf(node)) {
                parents[c] = node
                buildParents(c)
            }
        }

        private fun defaultDisplay(name: String): String {
            val n = name.lowercase()
            if (n == "table") return "table"
            if (n == "tr") return "table-row"
            if (n == "thead" || n == "tbody" || n == "tfoot") return "table-row-group"
            if (n == "td" || n == "th") return "table-cell"
            if (n == "caption" || n == "col" || n == "colgroup") return "none"
            return if (n in blockTags) "block" else "inline"
        }

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

        /** Longitud con unidad o numero puro; em/rem contra fs (rem usa raiz 16px), % contra containerW. */
        private fun parseLen(v: String?, fs: Double, containerW: Double): Double? {
            if (v == null) return null
            val s = v.trim()
            if (s.isEmpty()) return null
            val m = lenRegex.find(s)
            if (m != null) {
                val num = m.groupValues[1].toDouble()
                return when (m.groupValues[4]) {
                    "px" -> num
                    "%" -> (num * containerW) / 100
                    "em" -> num * fs
                    "rem" -> num * 16.0
                    else -> null
                }
            }
            val mn = numRegex.find(s)
            if (mn != null) {
                val num = mn.groupValues[1].toDouble()
                return if (num == 0.0) 0.0 else null
            }
            return null
        }

        private fun boxLen(el: DomNode.Element, name: String, containerW: Double, fs: Double): Double =
            parseLen(styleOf(el, name), fs, containerW) ?: 0.0

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

        private class TableGrid(val width: Double, val height: Double)

        /** Filas de la tabla: pares (tr, celdas) aplanando thead/tbody/tfoot en orden del documento. */
        private fun rowsOf(table: DomNode.Element): List<Pair<DomNode.Element, List<DomNode.Element>>> {
            val rows = mutableListOf<Pair<DomNode.Element, List<DomNode.Element>>>()
            fun walk(n: DomNode) {
                for (c in childrenOf(n)) {
                    if (c is DomNode.Element) {
                        val dc = displayOf(c)
                        if (dc == "table") continue
                        if (dc == "table-row") {
                            val cells = mutableListOf<DomNode.Element>()
                            for (k in c.children) {
                                if (k is DomNode.Element && displayOf(k) == "table-cell") cells.add(k)
                            }
                            if (cells.isNotEmpty()) rows.add(c to cells)
                        } else {
                            walk(c)
                        }
                    }
                }
            }
            walk(table)
            return rows
        }

        /** Ancho natural de una linea del contenido inline de la celda (medicion monoespaciada). */
        private fun naturalWidthOf(cell: DomNode.Element): Double {
            val words = mutableListOf<W>()
            collectText(cell, words)
            var w = 0.0
            for (i in words.indices) {
                if (i > 0) w += words[i].fs / 2
                w += textWidth(words[i].text, words[i].fs)
            }
            return w
        }

        /** Preferido de la celda: width declarado (px/em/rem/% de la tabla) o natural, mas el cromo horizontal. */
        private fun preferredWidthOf(cell: DomNode.Element, tableW: Double): Double {
            val fs = fontSizeOf(cell)
            val chrome = boxLen(cell, "padding-left", tableW, fs) +
                boxLen(cell, "padding-right", tableW, fs) +
                boxLen(cell, "border-left-width", tableW, fs) +
                boxLen(cell, "border-right-width", tableW, fs)
            val style = parseLen(styleOf(cell, "width"), fs, tableW)
            return (style ?: naturalWidthOf(cell)) + chrome
        }

        /** Layout de la tabla: columnas por maximo preferido, filas apiladas, celdas estiradas al alto de fila. */
        private fun layoutTable(table: DomNode.Element, x: Double, y: Double, contentW: Double): TableGrid {
            val rows = rowsOf(table)
            if (rows.isEmpty()) return TableGrid(0.0, 0.0)
            var nCols = 0
            for (r in rows) if (r.second.size > nCols) nCols = r.second.size
            if (nCols == 0) return TableGrid(0.0, 0.0)
            val cols = DoubleArray(nCols)
            for (r in rows) {
                val cells = r.second
                for (ci in cells.indices) {
                    val pref = preferredWidthOf(cells[ci], contentW)
                    if (pref > cols[ci]) cols[ci] = pref
                }
            }
            var natural = 0.0
            for (c in cols) natural += c
            val fs = fontSizeOf(table)
            val styleW = parseLen(styleOf(table, "width"), fs, contentW)
            val tableW = if (styleW != null && styleW > natural) styleW else natural
            val extra = if (tableW > natural) (tableW - natural) / nCols else 0.0
            val colX = DoubleArray(nCols)
            var acc = x
            for (ci in 0 until nCols) {
                cols[ci] += extra
                colX[ci] = acc
                acc += cols[ci]
            }
            var curY = y
            for (r in rows) {
                val tr = r.first
                val cells = r.second
                var rowH = 0.0
                val cellH = DoubleArray(cells.size)
                for (ci in cells.indices) {
                    val cell = cells[ci]
                    val cfs = fontSizeOf(cell)
                    val pt = boxLen(cell, "padding-top", cols[ci], cfs)
                    val pb = boxLen(cell, "padding-bottom", cols[ci], cfs)
                    val pl = boxLen(cell, "padding-left", cols[ci], cfs)
                    val pr = boxLen(cell, "padding-right", cols[ci], cfs)
                    val bt = boxLen(cell, "border-top-width", cols[ci], cfs)
                    val bb = boxLen(cell, "border-bottom-width", cols[ci], cfs)
                    val bl = boxLen(cell, "border-left-width", cols[ci], cfs)
                    val br = boxLen(cell, "border-right-width", cols[ci], cfs)
                    var innerW = cols[ci] - pl - pr - bl - br
                    if (innerW < 0.0) innerW = 0.0
                    val h = layoutInlineRun(cell.children.toList(), colX[ci] + bl + pl, curY + bt + pt, innerW, alignOf(cell))
                    cellH[ci] = h + pt + pb + bt + bb
                    if (cellH[ci] > rowH) rowH = cellH[ci]
                }
                for (ci in cells.indices) {
                    blockBoxes[cells[ci]] = LayoutBox(colX[ci], curY, cols[ci], rowH)
                }
                blockBoxes[tr] = LayoutBox(x, curY, acc - x, rowH)
                curY += rowH
            }
            return TableGrid(acc - x, curY - y)
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
                    if (d == "inline" || d == "table-cell" || d == "table-row" || d == "table-row-group") {
                        run.add(child)
                        continue
                    }
                    if (d == "table") {
                        if (run.isNotEmpty()) {
                            cur += layoutInlineRun(run.toList(), x, cur, width, align)
                            run.clear()
                        }
                        val fs = fontSizeOf(child)
                        val mt = boxLen(child, "margin-top", width, fs)
                        val mr = boxLen(child, "margin-right", width, fs)
                        val mb = boxLen(child, "margin-bottom", width, fs)
                        val ml = boxLen(child, "margin-left", width, fs)
                        val pt = boxLen(child, "padding-top", width, fs)
                        val pr = boxLen(child, "padding-right", width, fs)
                        val pb = boxLen(child, "padding-bottom", width, fs)
                        val pl = boxLen(child, "padding-left", width, fs)
                        val bt = boxLen(child, "border-top-width", width, fs)
                        val br = boxLen(child, "border-right-width", width, fs)
                        val bb = boxLen(child, "border-bottom-width", width, fs)
                        val bl = boxLen(child, "border-left-width", width, fs)
                        var cw = width - ml - mr - bl - br - pl - pr
                        if (cw < 0.0) cw = 0.0
                        val grid = layoutTable(child, x + ml + bl + pl, cur + mt + bt + pt, cw)
                        val bh = grid.height + pt + pb + bt + bb
                        blockBoxes[child] = LayoutBox(x + ml, cur + mt, grid.width + bl + br + pl + pr, bh)
                        cur += mt + bh + mb
                        continue
                    }
                    if (run.isNotEmpty()) {
                        cur += layoutInlineRun(run.toList(), x, cur, width, align)
                        run.clear()
                    }
                    val fs = fontSizeOf(child)
                    val mt = boxLen(child, "margin-top", width, fs)
                    val mr = boxLen(child, "margin-right", width, fs)
                    val mb = boxLen(child, "margin-bottom", width, fs)
                    val ml = boxLen(child, "margin-left", width, fs)
                    val pt = boxLen(child, "padding-top", width, fs)
                    val pr = boxLen(child, "padding-right", width, fs)
                    val pb = boxLen(child, "padding-bottom", width, fs)
                    val pl = boxLen(child, "padding-left", width, fs)
                    val bt = boxLen(child, "border-top-width", width, fs)
                    val br = boxLen(child, "border-right-width", width, fs)
                    val bb = boxLen(child, "border-bottom-width", width, fs)
                    val bl = boxLen(child, "border-left-width", width, fs)
                    val cx = x + ml + bl + pl
                    val cy = cur + mt + bt + pt
                    val cw = width - ml - mr - bl - br - pl - pr
                    val h = layoutContainer(child, cx, cy, cw)
                    val bh = h + pt + pb + bt + bb
                    blockBoxes[child] = LayoutBox(x + ml, cur + mt, width - ml - mr, bh)
                    cur += mt + bh + mb
                    continue
                }
            }
            if (run.isNotEmpty()) cur += layoutInlineRun(run.toList(), x, cur, width, align)
            return cur - y
        }
    }
}
