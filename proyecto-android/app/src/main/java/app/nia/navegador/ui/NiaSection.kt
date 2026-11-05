package app.nia.navegador.ui

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): seccion del catalogo: titulo del
 * rol "seccion" con el espaciado del token y contenedor vertical.
 */
class NiaSection(
    context: Context,
    colores: NiaTokens.Colores,
    titulo: String
) : LinearLayout(context) {

    /** Contenedor vertical del cuerpo de la seccion. */
    val contenedor: LinearLayout = LinearLayout(context)

    init {
        orientation = LinearLayout.VERTICAL
        val label = TextView(context)
        label.text = titulo
        val rol = NiaTokens.TIPOGRAFIA[2]
        label.textSize = rol.sp.toFloat()
        label.typeface = Typeface.create(
            Typeface.DEFAULT,
            if (rol.negrita) Typeface.BOLD else Typeface.NORMAL
        )
        label.setTextColor(NiaUi.color(colores.fg))
        val espacio = NiaUi.dp(context, NiaTokens.ESPACIADO[3])
        label.setPadding(espacio, espacio, espacio, 0)
        addView(label)
        contenedor.orientation = LinearLayout.VERTICAL
        addView(
            contenedor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
}
