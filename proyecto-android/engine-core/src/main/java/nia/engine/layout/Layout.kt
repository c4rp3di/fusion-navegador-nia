package nia.engine.layout

import nia.engine.css.CssComputedStyle
import nia.engine.html.DomNode

/**
 * Layout v3 (R0084): caja rectangular en coordenadas del documento.
 * Limitaciones v4: sin width/height fijados, sin float (tablas en v3,
 * flex/grid en v4), medicion monoespaciada (avance = fontSize/2), overflow
 * permitido (las palabras no se parten).
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
    private val noneElements: Set<DomNode>,
    private val visualWords: Map<DomNode, List<String>> = emptyMap()
) {
    fun boxOf(node: DomNode): LayoutBox? {
        if (node is DomNode.Element) return blockBoxes[node] ?: inlineBounding(node)
        if (node is DomNode.Text) return fragments[node]?.let { bounding(it) }
        return null
    }

    fun fragmentsOf(node: DomNode): List<LayoutBox> = fragments[node] ?: emptyList()

    /** T111 v6 / TK-111-11 (R0443, RES-0174): forma VISUAL (bidi v1) de las
     * palabras de un texto; null si el layout no la calculo. */
    fun visualWordsOf(node: DomNode): List<String>? = visualWords[node]

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
 *
 * v4 (R0090, RES-0050) - flexbox, grid y responsive (T032 v2):
 * display:flex coloca los items (medidos como contenido inline, ancho
 * natural de una linea) en la direccion principal con flex-direction
 * row/row-reverse/column/column-reverse, flex-wrap wrap solo horizontal
 * por ancho preferido mas gap, justify-content flex-start/center/flex-end/
 * space-between/space-around/space-evenly, align-items stretch/flex-start/
 * center/flex-end, gap como column-gap/row-gap en px y flex-grow reparte
 * el espacio libre. display:grid coloca los items en las columnas de
 * grid-template-columns (px/em/rem/% del contenedor, fr del espacio libre,
 * auto al ancho preferido) con filas implicitas del alto del contenido y
 * gap. @media min-width/max-width se evalua en CssCascade.compute contra
 * el viewport (default 360). Exclusiones v2: sin flex-shrink/order/basis
 * ni align-self, sin grid-template-rows ni areas, el contenido de bloque
 * de un item no se estructura (se mide como texto inline).
 *
 * v6 (T111 v6 / TK-111-11, R0443, RES-0174) - bidi/RTL v1: direccion
 * base del texto por CSS direction o atributo dir (rtl/ltr, auto por
 * primer caracter fuerte; heredada subiendo por los ancestros),
 * lineas rtl con las palabras colocadas de derecha a izquierda (la
 * envoltura greedy es logica, igual que en CSS), text-align ausente
 * = start (right con rtl) y la forma VISUAL de cada palabra (runs
 * fuertes RTL invertidos; orden de runs invertido con base rtl;
 * digitos y latinas legibles) expuesta en visualWordsOf para la
 * pintura. Exclusiones v1: bidi simplificado sin UBA completo
 * (neutrales, mirroring de pares, marcas combinantes), sin
 * unicode-bidi/isolate, direccion uniforme por run inline (un
 * elemento con dir propio dentro del inline NO rompe la linea) y sin
 * flex/grid/tablas espejados (solo el flujo de texto).
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
        private val visualWords = LinkedHashMap<DomNode, MutableList<String>>()

        private val lenRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))(px|em|rem|%)$")
        private val numRegex = Regex("^([+-]?(\\d+(\\.\\d+)?|\\.\\d+))$")
        private val frRegex = Regex("^(\\d+(\\.\\d+)?|\\.\\d+)fr$")

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
            return LayoutResult(LinkedHashMap(blockBoxes), fragments.mapValues { it.value.toList() }, noneElements, visualWords.mapValues { it.value.toList() })
        }

        private fun childrenOf(node: DomNode): List<DomNode> = when (node) {
            is DomNode.Document -> node.children
            is DomNode.Element -> node.children
            else -> emptyList()
        }

        private fun buildParents(node: DomNode) {
            if (node is DomNode.Element) {
                val d = styleOf(node, "display")
                val v = if (d == "none" || d == "block" || d == "inline" || d == "table" || d == "table-row" || d == "table-row-group" || d == "table-cell" || d == "flex" || d == "grid") d else defaultDisplay(node.name)
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
            if (v == "center" || v == "right" || v == "left") return v
            return if (dirOf(n) == "rtl") "right" else "left"
        }

        private fun textWidth(s: String, fs: Double): Double = s.length * fs / 2

        private fun wordsOf(s: String): List<String> =
            s.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }


        /**
         * T111 v6 / TK-111-11 (R0443, RES-0174): direccion base del texto:
         * propiedad CSS direction sobre el atributo dir (rtl/ltr),
         * heredada subiendo por los ancestros; dir=auto resuelve con el
         * primer caracter fuerte del texto (hebreo/arabe -> rtl, latina
         * -> ltr); default ltr.
         */
        private fun dirOf(n: DomNode): String {
            var cur: DomNode? = n
            while (cur != null) {
                val el = cur as? DomNode.Element
                if (el != null) {
                    val css = styleOf(el, "direction")?.trim()?.lowercase()
                    if (css == "rtl" || css == "ltr") return css
                    val attr = el.attributes["dir"]?.trim()?.lowercase()
                    if (attr == "rtl" || attr == "ltr") return attr
                    if (attr == "auto") {
                        if (n is DomNode.Text) {
                            for (c in n.data) {
                                if (esRtlFuerte(c)) return "rtl"
                                if (c in 'A'..'Z' || c in 'a'..'z') return "ltr"
                            }
                        }
                        return "ltr"
                    }
                }
                cur = parents[cur]
            }
            return "ltr"
        }

        /** T111 v6 / TK-111-11 (R0443, RES-0174): caracter fuerte RTL (hebreo/arabe). */
        private fun esRtlFuerte(c: Char): Boolean =
            c in '\u0590'..'\u05FF' || c in '\uFB1D'..'\uFB4F' ||
                c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' ||
                c in '\u08A0'..'\u08FF' || c in '\uFB50'..'\uFDFF' ||
                c in '\uFE70'..'\uFEFF'

        /**
         * T111 v6 / TK-111-11 (R0443, RES-0174): forma VISUAL de una palabra
         * para un renderer que dibuja de izquierda a derecha (bidi
         * simplificado v1): con base rtl se invierte el orden de los runs
         * y los runs fuertes RTL se invierten por dentro; con base ltr
         * SOLO se invierten los runs RTL (digitos y latinas quedan
         * legibles). Sin neutrales del UBA, sin mirroring de pares ni
         * marcas combinantes (exclusiones v1).
         */
        private fun visualOf(s: String, dir: String): String {
            val runs = mutableListOf<Pair<Boolean, StringBuilder>>()
            var rtl = false
            var cur = StringBuilder()
            for (c in s) {
                val r = esRtlFuerte(c)
                if (cur.isEmpty() || r != rtl) {
                    if (cur.isNotEmpty()) runs.add(rtl to cur)
                    cur = StringBuilder()
                    rtl = r
                }
                cur.append(c)
            }
            if (cur.isNotEmpty()) runs.add(rtl to cur)
            val out = StringBuilder()
            if (dir == "rtl") {
                for (i in runs.indices.reversed()) {
                    val run = runs[i]
                    if (run.first) out.append(run.second.reverse().toString()) else out.append(run.second.toString())
                }
            } else {
                for (run in runs) {
                    if (run.first) out.append(run.second.reverse().toString()) else out.append(run.second.toString())
                }
            }
            return out.toString()
        }
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
            val dir = if (run.isEmpty()) "ltr" else dirOf(run.first())
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
                    val vx = if (dir == "rtl") L.x - it.startX - textWidth(it.text, it.fs) else it.startX
                    fragments.getOrPut(it.node) { mutableListOf() }
                        .add(LayoutBox(x + offset + vx, lineY, textWidth(it.text, it.fs), lh))
                    visualWords.getOrPut(it.node) { mutableListOf() }
                        .add(visualOf(it.text, dir))
                }
                lineY += lh
            }
            return lineY - y
        }

        /** Alto de una secuencia inline sin dibujar (espejo de layoutInlineRun). */
        private fun measureInlineHeight(run: List<DomNode>, width: Double): Double {
            val words = mutableListOf<W>()
            for (fg in run) collectText(fg, words)
            var x = 0.0
            var curLh = 0.0
            var has = false
            var h = 0.0
            for (w in words) {
                val ww = textWidth(w.text, w.fs)
                val sp = w.fs / 2
                if (has && x + sp + ww > width) {
                    h += curLh
                    x = 0.0
                    curLh = 0.0
                    has = false
                }
                if (has) x += sp
                x += ww
                if (w.lh > curLh) curLh = w.lh
                has = true
            }
            if (has) h += curLh
            return h
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
        private fun naturalWidthOf(cell: DomNode.Element): Double = naturalWidthOfRun(cell.children.toList())

        /** Ancho natural de una linea de una secuencia inline (items de flex/grid). */
        private fun naturalWidthOfRun(run: List<DomNode>): Double {
            val words = mutableListOf<W>()
            for (fg in run) collectText(fg, words)
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

        /** Item de flex/grid: nodo, run inline, cromo y ancho preferido (outer). */
        private class FGItem(
            val node: DomNode,
            val run: List<DomNode>,
            val el: DomNode.Element?,
            val ml: Double, val mr: Double, val mt: Double, val mb: Double,
            val pl: Double, val pr: Double, val pt: Double, val pb: Double,
            val bl: Double, val br: Double, val bt: Double, val bb: Double,
            val prefW: Double
        )

        /** Items del contenedor flex/grid: Text no-ws -> item anonimo sin cromo; Element non-none -> item con cromo. */
        private fun itemsOf(container: DomNode): List<FGItem> {
            val items = mutableListOf<FGItem>()
            for (child in childrenOf(container)) {
                if (child is DomNode.Text) {
                    if (isWsOnly(child.data)) continue
                    items.add(FGItem(child, listOf(child), null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, naturalWidthOfRun(listOf(child))))
                    continue
                }
                if (child !is DomNode.Element) continue
                if (displayOf(child) == "none") continue
                val fs = fontSizeOf(child)
                val ml = boxLen(child, "margin-left", 0.0, fs)
                val mr = boxLen(child, "margin-right", 0.0, fs)
                val mt = boxLen(child, "margin-top", 0.0, fs)
                val mb = boxLen(child, "margin-bottom", 0.0, fs)
                val pl = boxLen(child, "padding-left", 0.0, fs)
                val pr = boxLen(child, "padding-right", 0.0, fs)
                val pt = boxLen(child, "padding-top", 0.0, fs)
                val pb = boxLen(child, "padding-bottom", 0.0, fs)
                val bl = boxLen(child, "border-left-width", 0.0, fs)
                val br = boxLen(child, "border-right-width", 0.0, fs)
                val bt = boxLen(child, "border-top-width", 0.0, fs)
                val bb = boxLen(child, "border-bottom-width", 0.0, fs)
                val style = parseLen(styleOf(child, "width"), fs, 0.0)
                val pref = (style ?: naturalWidthOfRun(child.children.toList())) + ml + mr + pl + pr + bl + br
                items.add(FGItem(child, child.children.toList(), child, ml, mr, mt, mb, pl, pr, pt, pb, bl, br, bt, bb, pref))
            }
            return items
        }

        /** Layout flex: filas horizontales por prefW+gap (wrap), justify/align/grow; devuelve el alto. */
        private fun layoutFlex(container: DomNode.Element, x: Double, y: Double, contentW: Double): Double {
            val fs = fontSizeOf(container)
            val dir = (styleOf(container, "flex-direction") ?: "row").trim()
            if (dir == "column" || dir == "column-reverse") return flexColumn(container, x, y, contentW, dir)
            val items = itemsOf(container)
            if (items.isEmpty()) return 0.0
            val wrap = styleOf(container, "flex-wrap") == "wrap"
            val justify = styleOf(container, "justify-content") ?: "flex-start"
            val align = styleOf(container, "align-items") ?: "stretch"
            val gap = parseLen(styleOf(container, "column-gap") ?: styleOf(container, "gap"), fs, contentW) ?: 0.0
            val rowGap = parseLen(styleOf(container, "row-gap") ?: styleOf(container, "gap"), fs, contentW) ?: 0.0
            val lines = mutableListOf<MutableList<FGItem>>()
            var line = mutableListOf<FGItem>()
            var cursor = 0.0
            for (fg in items) {
                if (line.isNotEmpty() && wrap && cursor + gap + fg.prefW > contentW) {
                    lines.add(line)
                    line = mutableListOf()
                    cursor = 0.0
                }
                if (line.isNotEmpty()) cursor += gap
                cursor += fg.prefW
                line.add(fg)
            }
            if (line.isNotEmpty()) lines.add(line)
            val reverse = dir == "row-reverse"
            var yy = y
            for (ln in lines) {
                val cross = flexPlaceRow(ln, yy, contentW, justify, align, gap, reverse)
                yy += cross + rowGap
            }
            return yy - y - rowGap
        }

        /** Coloca una linea flex horizontal y devuelve su cross (outer maximo con margenes). */
        private fun flexPlaceRow(
            line: List<FGItem>, y: Double, contentW: Double,
            justify: String, align: String, gap: Double, reverse: Boolean
        ): Double {
            val n = line.size
            val mains = DoubleArray(n)
            val grows = DoubleArray(n)
            var base = 0.0
            var gSum = 0.0
            for (k in line.indices) {
                mains[k] = line[k].prefW
                base += line[k].prefW
                if (k > 0) base += gap
                val e = line[k].el
                val g = if (e != null) (styleOf(e, "flex-grow")?.toDoubleOrNull() ?: 0.0) else 0.0
                if (g > 0.0) {
                    grows[k] = g
                    gSum += g
                }
            }
            val free = contentW - base
            if (free > 0.0 && gSum > 0.0) {
                for (k in line.indices) mains[k] += free * grows[k] / gSum
                base = contentW
            }
            var free2 = contentW - base
            if (free2 < 0.0) free2 = 0.0
            val stepBetween = if (n > 1) free2 / (n - 1) else 0.0
            val around = free2 / n
            val evenly = free2 / (n + 1)
            var px = 0.0
            when (justify) {
                "center" -> px = free2 / 2
                "flex-end" -> px = free2
                "space-around" -> px = around / 2
                "space-evenly" -> px = evenly
            }
            val heights = DoubleArray(n)
            val innerWs = DoubleArray(n)
            var lineCross = 0.0
            for (k in line.indices) {
                val fg = line[k]
                var innerW = mains[k] - fg.ml - fg.mr - fg.pl - fg.pr - fg.bl - fg.br
                if (innerW < 0.0) innerW = 0.0
                innerWs[k] = innerW
                heights[k] = measureInlineHeight(fg.run, innerW)
                val outer = heights[k] + fg.pt + fg.pb + fg.bt + fg.bb + fg.mt + fg.mb
                if (outer > lineCross) lineCross = outer
            }
            for (k in line.indices) {
                val fg = line[k]
                var ix = px
                if (reverse) ix = contentW - (ix + mains[k])
                val own = heights[k] + fg.pt + fg.pb + fg.bt + fg.bb
                val boxCross = if (align == "stretch") lineCross - fg.mt - fg.mb else own
                val offY = when (align) {
                    "center" -> (lineCross - own - fg.mt - fg.mb) / 2
                    "flex-end" -> lineCross - own - fg.mt - fg.mb
                    else -> 0.0
                }
                layoutInlineRun(fg.run, ix + fg.ml + fg.bl + fg.pl, y + offY + fg.mt + fg.bt + fg.pt, innerWs[k], alignOf(fg.node))
                val e = fg.el
                if (e != null) blockBoxes[e] = LayoutBox(ix + fg.ml, y + offY + fg.mt, mains[k] - fg.ml - fg.mr, boxCross)
                px += mains[k] + gap
                if (justify == "space-between" && n > 1) px += stepBetween
                if (justify == "space-around") px += around
                if (justify == "space-evenly") px += evenly
            }
            return lineCross
        }

        /** Columna flex: items apilados en el eje vertical (stretch al ancho); column-reverse espeja. */
        private fun flexColumn(container: DomNode.Element, x: Double, y: Double, contentW: Double, dir: String): Double {
            val items = itemsOf(container)
            if (items.isEmpty()) return 0.0
            val fs = fontSizeOf(container)
            val align = styleOf(container, "align-items") ?: "stretch"
            val stretch = align == "stretch"
            val gap = parseLen(styleOf(container, "row-gap") ?: styleOf(container, "gap"), fs, contentW) ?: 0.0
            val n = items.size
            val ys = DoubleArray(n)
            val mains = DoubleArray(n)
            val innerWs = DoubleArray(n)
            val boxWs = DoubleArray(n)
            val hs = DoubleArray(n)
            var yy = 0.0
            for (k in 0 until n) {
                val fg = items[k]
                val itemW = if (stretch) contentW else fg.prefW
                var innerW = itemW - fg.ml - fg.mr - fg.pl - fg.pr - fg.bl - fg.br
                if (innerW < 0.0) innerW = 0.0
                val h = measureInlineHeight(fg.run, innerW)
                val main = h + fg.pt + fg.pb + fg.bt + fg.bb + fg.mt + fg.mb
                ys[k] = yy
                mains[k] = main
                innerWs[k] = innerW
                boxWs[k] = itemW - fg.ml - fg.mr
                hs[k] = h
                yy += main + gap
            }
            val total = yy - gap
            for (k in 0 until n) {
                val fg = items[k]
                var iy = ys[k]
                if (dir == "column-reverse") iy = total - (iy + mains[k])
                layoutInlineRun(fg.run, x + fg.ml + fg.bl + fg.pl, y + iy + fg.mt + fg.bt + fg.pt, innerWs[k], alignOf(fg.node))
                val e = fg.el
                if (e != null) blockBoxes[e] = LayoutBox(x + fg.ml, y + iy + fg.mt, boxWs[k], hs[k] + fg.pt + fg.pb + fg.bt + fg.bb)
            }
            return total
        }

        /** Layout grid: columnas de grid-template-columns (px/em/rem/%/fr/auto), filas implicitas; devuelve el alto. */
        private fun layoutGrid(container: DomNode.Element, x: Double, y: Double, contentW: Double): Double {
            val items = itemsOf(container)
            if (items.isEmpty()) return 0.0
            val fs = fontSizeOf(container)
            val rawTracks = wordsOf(styleOf(container, "grid-template-columns") ?: "")
            val nCols = if (rawTracks.size < 1) 1 else rawTracks.size
            val colGap = parseLen(styleOf(container, "column-gap") ?: styleOf(container, "gap"), fs, contentW) ?: 0.0
            val rowGap = parseLen(styleOf(container, "row-gap") ?: styleOf(container, "gap"), fs, contentW) ?: 0.0
            val colW = DoubleArray(nCols)
            val isFr = BooleanArray(nCols)
            val isAuto = BooleanArray(nCols)
            val frVals = DoubleArray(nCols)
            var sumFr = 0.0
            for (c in 0 until nCols) {
                val t = if (c < rawTracks.size) rawTracks[c] else "auto"
                val fr = frRegex.find(t)
                if (fr != null) {
                    isFr[c] = true
                    frVals[c] = fr.groupValues[1].toDouble()
                    sumFr += frVals[c]
                } else {
                    val fixed = parseLen(t, fs, contentW)
                    if (t == "auto" || fixed == null) {
                        isAuto[c] = true
                    } else {
                        colW[c] = fixed
                    }
                }
            }
            for (k in items.indices) {
                val c = k % nCols
                if (isAuto[c] && items[k].prefW > colW[c]) colW[c] = items[k].prefW
            }
            var base = colGap * (nCols - 1)
            for (c in 0 until nCols) if (!isFr[c]) base += colW[c]
            val free = contentW - base
            if (free > 0.0 && sumFr > 0.0) {
                for (c in 0 until nCols) if (isFr[c]) colW[c] = free * frVals[c] / sumFr
            }
            val colX = DoubleArray(nCols)
            var acc = x
            for (c in 0 until nCols) {
                colX[c] = acc
                acc += colW[c] + colGap
            }
            val nRows = (items.size + nCols - 1) / nCols
            val rowH = DoubleArray(nRows)
            val innerWs = DoubleArray(items.size)
            for (k in items.indices) {
                val fg = items[k]
                val c = k % nCols
                var innerW = colW[c] - fg.ml - fg.mr - fg.pl - fg.pr - fg.bl - fg.br
                if (innerW < 0.0) innerW = 0.0
                innerWs[k] = innerW
                val h = measureInlineHeight(fg.run, innerW)
                val outer = h + fg.pt + fg.pb + fg.bt + fg.bb + fg.mt + fg.mb
                if (outer > rowH[k / nCols]) rowH[k / nCols] = outer
            }
            val rowY = DoubleArray(nRows)
            var accY = y
            for (r in 0 until nRows) {
                rowY[r] = accY
                accY += rowH[r] + rowGap
            }
            val totalH = accY - y - rowGap
            for (k in items.indices) {
                val fg = items[k]
                val c = k % nCols
                val r = k / nCols
                layoutInlineRun(fg.run, colX[c] + fg.ml + fg.bl + fg.pl, rowY[r] + fg.mt + fg.bt + fg.pt, innerWs[k], alignOf(fg.node))
                val e = fg.el
                if (e != null) blockBoxes[e] = LayoutBox(colX[c] + fg.ml, rowY[r] + fg.mt, colW[c] - fg.ml - fg.mr, rowH[r] - fg.mt - fg.mb)
            }
            return totalH
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
                    if (d == "flex" || d == "grid") {
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
                        val ch = if (d == "flex") layoutFlex(child, x + ml + bl + pl, cur + mt + bt + pt, cw) else layoutGrid(child, x + ml + bl + pl, cur + mt + bt + pt, cw)
                        val bh = ch + pt + pb + bt + bb
                        blockBoxes[child] = LayoutBox(x + ml, cur + mt, width - ml - mr, bh)
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
