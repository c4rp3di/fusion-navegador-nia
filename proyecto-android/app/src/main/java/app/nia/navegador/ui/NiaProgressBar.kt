package app.nia.navegador.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): barra de progreso propia: pista
 * surface y avance accent con el radio del token; fraccion acotada 0..1
 * y sin animacion de movimiento en v1 (movimiento reducido).
 */
class NiaProgressBar(
    context: Context,
    private val colores: NiaTokens.Colores
) : View(context) {

    private var fraccion = 0f
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        minimumHeight = NiaUi.dp(context, NiaTokens.ESPACIADO[1])
    }

    /** Fija el avance (acotado 0..1) y repinta. */
    fun setFraccion(valor: Float) {
        fraccion = valor.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alto = height.toFloat()
        val radio = alto / 2f
        pintura.color = NiaUi.color(colores.surface)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), alto, radio, radio, pintura)
        if (fraccion > 0f) {
            pintura.color = NiaUi.color(colores.accent)
            canvas.drawRoundRect(0f, 0f, width * fraccion, alto, radio, radio, pintura)
        }
    }
}
