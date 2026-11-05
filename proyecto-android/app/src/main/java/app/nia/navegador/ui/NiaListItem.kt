package app.nia.navegador.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): item de lista con titulo (rol
 * cuerpo) y nota (rol nota); altura minima 48dp para el target tactil
 * AA y color fg pleno (sin alpha que rompa el contraste).
 */
class NiaListItem(
    context: Context,
    colores: NiaTokens.Colores,
    titulo: String,
    nota: String
) : LinearLayout(context) {

    init {
        orientation = LinearLayout.VERTICAL
        minimumHeight = NiaUi.dp(context, 48)
        val espacio = NiaUi.dp(context, NiaTokens.ESPACIADO[3])
        setPadding(espacio, espacio, espacio, espacio)
        val tituloView = TextView(context)
        tituloView.text = titulo
        tituloView.textSize = NiaTokens.TIPOGRAFIA[3].sp.toFloat()
        tituloView.setTextColor(NiaUi.color(colores.fg))
        val notaView = TextView(context)
        notaView.text = nota
        notaView.textSize = NiaTokens.TIPOGRAFIA[4].sp.toFloat()
        notaView.setTextColor(NiaUi.color(colores.fg))
        addView(tituloView)
        addView(notaView)
        isClickable = true
    }
}
