package app.nia.navegador.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): interruptor propio: pista y bola
 * dibujadas con los tokens (accent al activar, surface apagado), target
 * tactil >= 48dp y contentDescription pour TalkBack; sin animacion de
 * movimiento en v1 (movimiento reducido respetado).
 */
class NiaToggle(
    context: Context,
    private val colores: NiaTokens.Colores
) : View(context) {

    var encendido = false
        private set

    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    private val altoPista = NiaUi.dp(context, NiaTokens.ESPACIADO[5])

    init {
        minimumWidth = NiaUi.dp(context, 48)
        minimumHeight = NiaUi.dp(context, 48)
        contentDescription = "interruptor desactivado"
        setOnClickListener { alternar() }
    }

    /** Alterna el estado y lo expone a TalkBack. */
    fun alternar() {
        encendido = !encendido
        contentDescription = if (encendido) "interruptor activado" else "interruptor desactivado"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ancho = width.toFloat()
        val alto = minOf(altoPista, height).toFloat()
        val radio = alto / 2f
        pintura.color = NiaUi.color(if (encendido) colores.accent else colores.surface)
        canvas.drawRoundRect(0f, 0f, ancho, alto, radio, radio, pintura)
        pintura.color = NiaUi.color(colores.fg)
        val bola = alto * 0.35f
        val centro = if (encendido) ancho - radio - bola else radio + bola
        canvas.drawCircle(centro, alto / 2f, bola, pintura)
    }
}
