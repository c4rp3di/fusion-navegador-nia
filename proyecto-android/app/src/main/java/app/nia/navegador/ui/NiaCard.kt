package app.nia.navegador.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): tarjeta del catalogo: fondo
 * surface con radio y elevacion de tokens (widgets de plataforma,
 * sin Material).
 */
class NiaCard(
    context: Context,
    colores: NiaTokens.Colores
) : FrameLayout(context) {

    init {
        val fondo = GradientDrawable()
        fondo.setColor(NiaUi.color(colores.surface))
        fondo.cornerRadius = NiaUi.dp(context, NiaTokens.RADIOS[3]).toFloat()
        background = fondo
        elevation = NiaUi.dp(context, NiaTokens.ELEVACION[2]).toFloat()
    }
}
