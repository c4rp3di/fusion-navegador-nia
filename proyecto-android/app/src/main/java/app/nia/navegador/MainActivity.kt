package app.nia.navegador

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import nia.contracts.ContractsVersion

/**
 * Baseline de la app (inicio de T003): primer dibujo PROPIO sobre Canvas.
 * Sin WebView y sin layout XML: la superficie donde vivirá el renderizado
 * del motor nace aquí.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(BootstrapView(this))
    }
}

private class BootstrapView(context: Context) : View(context) {

    private val accent = Paint().apply { color = Color.rgb(108, 92, 231) }

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 238, 248)
        textSize = 44f
        textAlign = Paint.Align.CENTER
    }

    private val footnote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(127, 224, 188)
        textSize = 26f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(20, 20, 34))
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawRect(cx - 150f, cy - 150f, cx + 150f, cy + 150f, accent)
        canvas.drawText("Navegador NIA", cx, cy - 20f, title)
        canvas.drawText("baseline 0.2.0 · dibujo propio · sin WebView", 24f, height - 48f, footnote)
        canvas.drawText("contracts v$ContractsVersion", 24f, height - 16f, footnote)
    }
}
