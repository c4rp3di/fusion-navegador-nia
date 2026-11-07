package nia.engine.gestures

import nia.engine.settings.OpcionAjuste
import nia.engine.settings.SettingsManager
import nia.engine.settings.TipoOpcion

/**
 * T078 v1 (R0199, RES-0094): puente entre el armazon de Ajustes (T093,
 * SettingsManager) y los gestos del motor (T077). Es la UNICA fuente
 * de verdad de los ids, los rangos y los defectos de la seccion
 * Gestos: el shell registra las opciones con registrar() y reconstruye
 * la ConfigGestos EN CALIENTE con config().
 *
 * Validacion y recovery por las dos capas: el armazon YA valida al
 * asignar (BOOL exacto, ENTERO en [minimo, maximo]) y config() ADEMAS
 * hace recovery: un id ausente o un valor no numerico cae al DEFECTO
 * medido de T077 y un valor fuera de rango se CLAMPA, nunca a un
 * estado roto. Los defectos de la seccion Gestos SON los de
 * ConfigGestos(): los ajustes solo exponen lo que el motor ya hace.
 */
object AjustesGestos {

    const val ID_SWIPE = "gestos.swipe"
    const val ID_DOBLE_TAP = "gestos.doble-tap"
    const val ID_PINZA = "gestos.pinza"
    const val ID_BARRA = "gestos.barra"
    const val ID_PULL_REFRESH = "gestos.pull-refresh"
    const val ID_BORDE = "gestos.borde"
    const val ID_UMBRAL_SWIPE = "gestos.umbral-swipe"
    const val ID_PULL_UMBRAL = "gestos.pull-umbral"
    const val ID_PULL_TIEMPO = "gestos.pull-tiempo"

    /** Registra la seccion Gestos en el armazon (9 opciones);
     * false si alguna colisiona con un id ya registrado. */
    fun registrar(s: SettingsManager): Boolean {
        val ok = mutableListOf<Boolean>()
        ok.add(s.registrar(OpcionAjuste(
            ID_SWIPE, "Gestos", "Swipe de borde",
            "Atras/adelante deslizando desde el borde de la pantalla",
            TipoOpcion.BOOL, "true"
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_DOBLE_TAP, "Gestos", "Zoom por doble toque",
            "Doble toque alterna 1x/2x (el menu Zoom sigue siempre)",
            TipoOpcion.BOOL, "true"
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_PINZA, "Gestos", "Zoom por pinza",
            "Pellizcar acerca o aleja con re-maqueteo real del documento",
            TipoOpcion.BOOL, "true"
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_BARRA, "Gestos", "Ocultar barra al bajar",
            "La barra de URL se esconde al bajar y vuelve al subir",
            TipoOpcion.BOOL, "true"
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_PULL_REFRESH, "Gestos", "Tirar para recargar",
            "Tiron desde arriba del todo recarga (OFF por defecto)",
            TipoOpcion.BOOL, "false"
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_BORDE, "Gestos", "Banda del borde",
            "Ancho en px de la banda donde nace el swipe (8 a 64)",
            TipoOpcion.ENTERO, "24", 8L, 64L
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_UMBRAL_SWIPE, "Gestos", "Umbral del swipe",
            "Distancia en px que dispara atras/adelante (24 a 200)",
            TipoOpcion.ENTERO, "72", 24L, 200L
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_PULL_UMBRAL, "Gestos", "Umbral del tiron",
            "Sobrescroll en px que dispara la recarga (32 a 240)",
            TipoOpcion.ENTERO, "64", 32L, 240L
        )))
        ok.add(s.registrar(OpcionAjuste(
            ID_PULL_TIEMPO, "Gestos", "Tiempo del tiron",
            "Tiempo maximo del tiron en ms (200 a 2000)",
            TipoOpcion.ENTERO, "800", 200L, 2000L
        )))
        return ok.all { it }
    }

    /** Reconstruye la ConfigGestos desde el armazon con recovery:
     * lo ausente o no numerico cae al defecto y lo fuera de rango se
     * clampa (el armazon ya rechaza asignar fuera de rango; esto
     * cubre imports antiguos o valores a mano). */
    fun config(s: SettingsManager): ConfigGestos {
        val d = ConfigGestos()
        return ConfigGestos(
            bordePx = entero(s, ID_BORDE, d.bordePx, 8.0, 64.0),
            umbralSwipePx = entero(s, ID_UMBRAL_SWIPE, d.umbralSwipePx, 24.0, 200.0),
            swipe = boolean(s, ID_SWIPE, d.swipe),
            dobleTap = boolean(s, ID_DOBLE_TAP, d.dobleTap),
            pinza = boolean(s, ID_PINZA, d.pinza),
            barra = boolean(s, ID_BARRA, d.barra),
            pullToRefresh = boolean(s, ID_PULL_REFRESH, d.pullToRefresh),
            pullUmbralPx = entero(s, ID_PULL_UMBRAL, d.pullUmbralPx, 32.0, 240.0),
            pullMaxMs = entero(s, ID_PULL_TIEMPO, d.pullMaxMs.toDouble(), 200.0, 2000.0).toLong()
        )
    }

    private fun boolean(s: SettingsManager, id: String, defecto: Boolean): Boolean =
        when (s.valor(id)) {
            "true" -> true
            "false" -> false
            else -> defecto
        }

    private fun entero(s: SettingsManager, id: String, defecto: Double, min: Double, max: Double): Double {
        val v = s.valor(id)?.toDoubleOrNull() ?: return defecto
        return minOf(maxOf(v, min), max)
    }
}
