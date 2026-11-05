package app.nia.navegador.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): estado vacio del catalogo:
 * simbolo monolineal de texto (rol display), titulo (rol seccion) y
 * nota (rol nota) centrados con el espaciado del token.
 */
class NiaEmptyState(
    context: Context,
    colores: NiaTokens.Colores,
    simbolo: String,
    titulo: String,
    nota: String
) : LinearLayout(context) {

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val espacio = NiaUi.dp(context, NiaTokens.ESPACIADO[4])
        setPadding(espacio, espacio, espacio, espacio)
        val icono = TextView(context)
        icono.text = simbolo
        icono.textSize = NiaTokens.TIPOGRAFIA[0].sp.toFloat()
        icono.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        icono.setTextColor(NiaUi.color(colores.fg))
        icono.gravity = Gravity.CENTER
        val tituloView = TextView(context)
        tituloView.text = titulo
        tituloView.textSize = NiaTokens.TIPOGRAFIA[2].sp.toFloat()
        tituloView.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tituloView.setTextColor(NiaUi.color(colores.fg))
        tituloView.gravity = Gravity.CENTER
        val notaView = TextView(context)
        notaView.text = nota
        notaView.textSize = NiaTokens.TIPOGRAFIA[4].sp.toFloat()
        notaView.setTextColor(NiaUi.color(colores.fg))
        notaView.gravity = Gravity.CENTER
        addView(icono)
        addView(tituloView)
        addView(notaView)
    }
}
