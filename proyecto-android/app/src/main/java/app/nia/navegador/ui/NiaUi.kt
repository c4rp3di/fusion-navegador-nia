package app.nia.navegador.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): adaptador Android de los tokens
 * del shell. Convierte los hex del schema a ColorInt, aplica dp/sp con
 * las metricas del sistema (fontScale de accesibilidad incluido) y
 * resuelve el modo AUTO con el uiMode del sistema. Los componentes de
 * app/ui consumen este adaptador y NiaTokens, sin logica del motor.
 */
object NiaUi {

    /** ColorInt de un hex #rrggbb del schema. */
    fun color(hex: String): Int = Color.parseColor(hex)

    /** dp fisicos segun la densidad del dispositivo. */
    fun dp(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** sp segun el fontScale de accesibilidad del sistema. */
    fun sp(context: Context, sp: Int): Int =
        (sp * context.resources.displayMetrics.scaledDensity).toInt()

    /** Colores del modo resuelto: AUTO sigue al uiMode del sistema. */
    fun colores(context: Context, modo: NiaTokens.Modo): NiaTokens.Colores {
        val noche = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return NiaTokens.builtin(modo, noche)
    }
}
