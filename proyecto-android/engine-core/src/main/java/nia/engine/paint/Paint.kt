package nia.engine.paint

import nia.engine.css.CssComputedStyle
import nia.engine.html.DomNode
import nia.engine.layout.LayoutResult

/**
 * Pintura v1 (T016): display list de operaciones de dibujo sobre el layout.
 * DrawRect pinta el fondo de un elemento (background-color no transparente);
 * DrawText pinta una palabra con la caja del fragmento de layout y el
 * color y tamano del contexto heredado. El orden es documental (pre-orden).
 * Limitaciones v1: sin z-index/stacking contexts, sin imagenes, sin
 * transformaciones ni clip.
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
 * Constructor de la escena: recorre el arbol en pre-orden sobre el layout ya
 * calculado. Contrato del layout v1: fragmentsOf(texto) devuelve una caja por
 * palabra de wordsOf(texto.data), en el mismo orden.
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
                    if (box != null && bg != "transparent") {
                        ops.add(PaintOp.DrawRect(box.x, box.y, box.width, box.height, bg, node))
                    }
                    for (c in node.children) walk(c, elFs, elColor)
                }
                is DomNode.Text -> {
                    val words = wordsOf(node.data)
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
