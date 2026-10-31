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
import nia.engine.html.HtmlTreeBuilder
import nia.engine.layout.LayoutEngine
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder

/**
 * Adaptador Android del motor (T016, R0021): ejecuta el pipeline completo
 * (HTML -> CSS -> layout -> display list) en el init y dibuja la escena
 * escalando el viewport logico de 360px al ancho de pantalla. El toque se
 * convierte a coordenadas de documento y se resuelve con hitTest del motor;
 * el op topmost queda resaltado. Sin WebView y sin layout XML.
 */
class PaintView(context: Context) : View(context) {

    private val viewport = 360.0

    private val demoHtml = "<html><body>" +
        "<h1>Navegador NIA</h1>" +
        "<p>Motor propio: tokenizer, arbol HTML, CSS, layout y pintura. Sin WebView.</p>" +
        "<div id=\"card\"><p>Esta pantalla la genera el pipeline completo del motor. Toca cualquier zona para resaltar el op topmost que resuelve hitTest.</p></div>" +
        "</body></html>"

    private val demoCss = "body{background-color:#141428;font-size:18px;color:#f0eef8}" +
        "h1{font-size:28px;color:#7fe0bc}" +
        "#card{background-color:#23233f}"

    private val scene: PaintScene

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

    private var lastHit: PaintOp? = null

    init {
        val parsed = HtmlTreeBuilder.parse(demoHtml)
        val computed = CssCascade.compute(parsed.document, CssStylesheet.parse(demoCss))
        val layout = LayoutEngine.layout(parsed.document, computed, viewport)
        scene = PaintSceneBuilder.build(parsed.document, computed, layout)
    }

    override fun onDraw(canvas: Canvas) {
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
        canvas.drawText("dibujo del motor propio · sin WebView", 24f, height - 48f, footnote)
        canvas.drawText("contracts v$ContractsVersion", 24f, height - 16f, footnote)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val scale = width / viewport.toFloat()
            val docX = (event.x / scale).toDouble()
            val docY = (event.y / scale).toDouble()
            lastHit = scene.hitTest(docX, docY)
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
