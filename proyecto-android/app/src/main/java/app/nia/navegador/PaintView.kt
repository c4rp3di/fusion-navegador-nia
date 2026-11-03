package app.nia.navegador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import nia.contracts.ContractsVersion
import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.devtools.DevToolsModel
import nia.engine.dom.DomDocument
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder

/**
 * Adaptador Android del motor (v2, R0023): ejecuta el pipeline completo
 * (HTML -> CSS -> layout -> display list) en el init y dibuja la escena
 * escalando el viewport logico de 360px al ancho de pantalla. La demo usa
 * las novedades v2: padding de body, bordes de h1 y de la tarjeta con
 * border-color, pseudo-clase :first-child y selector de hermano p+p. El
 * toque se convierte a coordenadas de documento y se resuelve con hitTest
 * del motor; el op topmost queda resaltado. Sin WebView y sin layout XML.
 *
 * T017 v3 (R0072): el toque tambien SELECCIONA el nodo del op (resuelto a
 * handle con DevToolsModel.handleOf sobre el documento registrado) y el
 * nodeId queda en DevToolsState para el panel DevTools.
 *
 * T017 v4 (R0074, RES-0042): la pagina re-renderiza con las reglas CSS del
 * usuario escritas en el panel (rebuild por revision de DevToolsState) y
 * el nodo seleccionado queda con resaltado persistente en cian, distinto
 * del resaltado amarillo transitorio del toque.
 */
class PaintView(context: Context) : View(context) {

    private val viewport = 360.0

    private val document: DomDocument
    private val devtoolsModel: DevToolsModel
    private val root: DomNode
    private val rootDoc: DomNode.Document
    private lateinit var scene: PaintScene

    private var builtRevision = -1

    private val fillPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val footnote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(127, 224, 188)
        textSize = 26f
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var lastHit: PaintOp? = null

    init {
        document = DomDocument.parse(DemoPage.HTML)
        devtoolsModel = DevToolsModel(document, DemoPage.CSS)
        root = document.nodeOf(document.rootHandle())
        rootDoc = root as DomNode.Document
        rebuild()
    }

    /** Recalcula cascada+layout+escena con las reglas del usuario (v4). */
    private fun rebuild() {
        val css = if (DevToolsState.userCss.isEmpty()) DemoPage.CSS else DemoPage.CSS + "\n" + DevToolsState.userCss
        val computed = CssCascade.compute(root, CssStylesheet.parse(css))
        val layout = LayoutEngine.layout(rootDoc, computed, viewport)
        scene = PaintSceneBuilder.build(rootDoc, computed, layout)
        builtRevision = DevToolsState.revision
    }

    override fun onDraw(canvas: Canvas) {
        if (DevToolsState.revision != builtRevision) rebuild()
        canvas.drawColor(Color.rgb(20, 20, 34))
        val scale = width / viewport.toFloat()
        for (op in scene.ops) {
            when (op) {
                is PaintOp.DrawRect -> {
                    fillPaint.color = parseColor(op.color)
                    canvas.drawRect(
                        (op.x * scale).toFloat(),
                        (op.y * scale).toFloat(),
                        ((op.x + op.width) * scale).toFloat(),
                        ((op.y + op.height) * scale).toFloat(),
                        fillPaint
                    )
                }
                is PaintOp.DrawText -> {
                    textPaint.color = parseColor(op.color)
                    textPaint.textSize = (op.fontSize * scale).toFloat()
                    canvas.drawText(
                        op.text,
                        (op.x * scale).toFloat(),
                        ((op.y + op.fontSize) * scale).toFloat(),
                        textPaint
                    )
                }
            }
        }
        val hit = lastHit
        if (hit != null) {
            canvas.drawRect(
                (hit.x * scale).toFloat(),
                (hit.y * scale).toFloat(),
                ((hit.x + hit.width) * scale).toFloat(),
                ((hit.y + hit.height) * scale).toFloat(),
                highlight
            )
        }
        val sel = DevToolsState.selectedNodeId
        if (sel != null) {
            val sh = devtoolsModel.handleWithNodeId(sel)
            if (sh != null) {
                val selNode = document.nodeOf(sh)
                for (op in scene.ops) {
                    if (op.node === selNode) {
                        canvas.drawRect(
                            (op.x * scale).toFloat(),
                            (op.y * scale).toFloat(),
                            ((op.x + op.width) * scale).toFloat(),
                            ((op.y + op.height) * scale).toFloat(),
                            selected
                        )
                    }
                }
            }
            canvas.drawText("seleccion #" + sel, 24f, height - 80f, footnote)
        }
        canvas.drawText("dibujo del motor propio · sin WebView", 24f, height - 48f, footnote)
        canvas.drawText("contracts v$ContractsVersion", 24f, height - 16f, footnote)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val scale = width / viewport.toFloat()
            val docX = (event.x / scale).toDouble()
            val docY = (event.y / scale).toDouble()
            val hit = scene.hitTest(docX, docY)
            lastHit = hit
            if (hit != null) {
                val h = devtoolsModel.handleOf(hit.node)
                if (h != null) DevToolsState.selectedNodeId = h.nodeId
            }
            performClick()
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun parseColor(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.BLACK
        }
}
