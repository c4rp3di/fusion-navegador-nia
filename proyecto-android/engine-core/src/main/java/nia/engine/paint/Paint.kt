package nia.engine.paint

import nia.engine.css.CssComputedStyle
import nia.engine.html.DomNode
import nia.engine.layout.LayoutResult

/**
 * Pintura v2 (R0023): display list de operaciones de dibujo sobre el layout.
 * DrawRect pinta el fondo de un elemento (background-color no transparente)
 * y las tiras de sus bordes; DrawText pinta una palabra con la caja del
 * fragmento de layout y el color y tamano del contexto heredado. El orden es
 * documental (pre-orden). Limitaciones v2: sin z-index/stacking contexts,
 * sin imagenes, sin transformaciones ni clip.
 */
sealed class PaintOp {
    abstract val x: Double
    abstract val y: Double
    abstract val width: Double
    abstract val height: Double
    abstract val color: String
    abstract val node: DomNode

    data class DrawRect(
        override val x: Double,
        override val y: Double,
        override val width: Double,
        override val height: Double,
        override val color: String,
        override val node: DomNode
    ) : PaintOp()

    data class DrawText(
        override val x: Double,
        override val y: Double,
        override val width: Double,
        override val height: Double,
        val text: String,
        override val color: String,
        val fontSize: Double,
        override val node: DomNode
    ) : PaintOp()
}

/**
 * Escena pintada: lista de ops en orden documental. hitTest devuelve el op
 * topmost que contiene el punto (bordes exclusivos: [x, x+w) x [y, y+h)).
 */
class PaintScene(val ops: List<PaintOp>) {

    fun hitTest(x: Double, y: Double): PaintOp? {
        var res: PaintOp? = null
        for (op in ops) {
            if (x >= op.x && x < op.x + op.width && y >= op.y && y < op.y + op.height) {
                res = op
            }
        }
        return res
    }
}

/**
 * Constructor de la escena v2: recorre el arbol en pre-orden sobre el layout
 * ya calculado. Por cada elemento con caja: primero el rectangulo de fondo
 * (background-color no transparente, la caja border-box del layout) y
 * despues hasta 4 tiras de borde (border-color o color heredado): arriba y
 * abajo a lo ancho de la caja, izquierda y derecha entre las tiras
 * horizontales. Contrato del layout: fragmentsOf(texto) devuelve una caja
 * por palabra de wordsOf(texto.data), en el mismo orden. T111 v6
 * (TK-111-11, R0443): visualWordsOf(texto) da la forma VISUAL (bidi v1)
 * de cada palabra y sustituye a la cruda al pintar (fallback a wordsOf
 * si el layout no la calculo).
 */
object PaintSceneBuilder {

    fun build(root: DomNode.Document, computed: CssComputedStyle, layout: LayoutResult): PaintScene {
        val ops = mutableListOf<PaintOp>()

        fun parsePx(v: String?): Double? {
            if (v == null || v.length < 3) return null
            if (v.substring(v.length - 2) != "px") return null
            return v.substring(0, v.length - 2).toDoubleOrNull()
        }

        fun wordsOf(s: String): List<String> =
            s.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }

        fun walk(node: DomNode, fs: Double, color: String) {
            when (node) {
                is DomNode.Document -> for (c in node.children) walk(c, fs, color)
                is DomNode.Element -> {
                    val elFs = parsePx(computed.propertyOf(node, "font-size")) ?: fs
                    val elColor = computed.propertyOf(node, "color") ?: color
                    val bg = computed.propertyOf(node, "background-color") ?: "transparent"
                    val box = layout.boxOf(node)
                    if (box != null) {
                        if (bg != "transparent") {
                            ops.add(PaintOp.DrawRect(box.x, box.y, box.width, box.height, bg, node))
                        }
                        val bcol = computed.propertyOf(node, "border-color") ?: elColor
                        val bt = parsePx(computed.propertyOf(node, "border-top-width")) ?: 0.0
                        val bb = parsePx(computed.propertyOf(node, "border-bottom-width")) ?: 0.0
                        val bl = parsePx(computed.propertyOf(node, "border-left-width")) ?: 0.0
                        val br = parsePx(computed.propertyOf(node, "border-right-width")) ?: 0.0
                        if (bt > 0) {
                            ops.add(PaintOp.DrawRect(box.x, box.y, box.width, bt, bcol, node))
                        }
                        if (bb > 0) {
                            ops.add(PaintOp.DrawRect(box.x, box.y + box.height - bb, box.width, bb, bcol, node))
                        }
                        val ih = box.height - bt - bb
                        if (bl > 0 && ih > 0) {
                            ops.add(PaintOp.DrawRect(box.x, box.y + bt, bl, ih, bcol, node))
                        }
                        if (br > 0 && ih > 0) {
                            ops.add(PaintOp.DrawRect(box.x + box.width - br, box.y + bt, br, ih, bcol, node))
                        }
                    }
                    for (c in node.children) walk(c, elFs, elColor)
                }
                is DomNode.Text -> {
                    val words = layout.visualWordsOf(node) ?: wordsOf(node.data)
                    val frags = layout.fragmentsOf(node)
                    var i = 0
                    while (i < words.size && i < frags.size) {
                        val f = frags[i]
                        ops.add(PaintOp.DrawText(f.x, f.y, f.width, f.height, words[i], color, fs, node))
                        i++
                    }
                }
                is DomNode.Doctype -> {
                }
                is DomNode.Comment -> {
                }
            }
        }

        walk(root, 16.0, "#000000")
        return PaintScene(ops.toList())
    }
}
