package nia.engine.gestures

import kotlin.math.abs

/**
 * T077 v1 (R0195, RES-0093): GESTOS BASE del navegador, logica PURA del
 * motor SIN ninguna API de Android. El shell (PaintView +
 * BrowserActivity) alimenta estos rastreadores con eventos de toque
 * reales y EJECUTA lo que deciden; toda decision es medible aqui con
 * GestosTest en CI (no hay estado oculto ni magia de framework).
 *
 * Decisiones v1 (aceptacion de T077):
 * - Swipe atras/adelante SOLO si el toque nace dentro del borde
 *   (ConfigGestos.bordePx); se CANCELA en cuanto el movimiento
 *   vertical domina: el scroll del sistema manda y NUNCA se roba un
 *   gesto que empieza vertical. Solo dispara UNA vez por toque y solo
 *   hacia dentro de la pantalla.
 * - Pull-to-refresh OFF por defecto (PoliticaPullRefresh): T078 lo
 *   configura (gestos.pull-refresh, OFF por defecto).
 * - Zoom por doble tap (toggle 1x/2x) y por pinza (factor acumulado
 *   con limites): el zoom es REAL del motor — el viewport logico baja
 *   de 360 a 360/zoom y el documento se RE-MAQUETA (reflow), no se
 *   escala un bitmap.
 * - La barra de URL se oculta al bajar y reaparece al subir o al
 *   llegar al borde superior (OcultadorBarra, con acumulacion para no
 *   parpadear con micro-scrolls).
 */
data class ConfigGestos(
    /** Banda del borde donde nace un swipe back/forward. */
    val bordePx: Double = 24.0,
    /** Distancia horizontal que convierte un arrastre de borde en accion. */
    val umbralSwipePx: Double = 72.0,
    /** |dy| > |dx| * dominancia cancela (el vertical manda). */
    val dominancia: Double = 1.5,
    /** Zoom minimo aplicable. */
    val zoomMin: Double = 0.75,
    /** Zoom maximo aplicable. */
    val zoomMax: Double = 3.0,
    /** Zoom objetivo del doble tap. */
    val zoomDobleTap: Double = 2.0,
    /** Ventana temporal del doble tap en milisegundos. */
    val dobleTapMs: Long = 320L,
    /** Distancia maxima entre los dos taps del doble tap. */
    val dobleTapPx: Double = 48.0,
    /** Scroll descendente acumulado que oculta la barra de URL. */
    val ocultarDpx: Double = 24.0,
    /** Scroll ascendente acumulado que la vuelve a mostrar. */
    val mostrarDpx: Double = 12.0,
    /** Pull-to-refresh desactivado por defecto (T078 lo configura). */
    val pullToRefresh: Boolean = false,
    /** T078: toggle del swipe de borde atras/adelante. */
    val swipe: Boolean = true,
    /** T078: toggle del zoom por doble tap. */
    val dobleTap: Boolean = true,
    /** T078: toggle del zoom por pinza. */
    val pinza: Boolean = true,
    /** T078: toggle del auto-ocultado de la barra de URL. */
    val barra: Boolean = true,
    /** T078: sobrescroll acumulado que dispara el pull-to-refresh. */
    val pullUmbralPx: Double = 64.0,
    /** T078: el tiron debe completarse en menos de este tiempo (ms). */
    val pullMaxMs: Long = 800L
)

/** Accion de borde decidida por el rastreador. */
enum class AccionBorde { NINGUNA, ATRAS, ADELANTE }

/**
 * Rastreador del swipe de borde: ATRAS desde el borde izquierdo hacia
 * dentro, ADELANTE desde el derecho hacia dentro. Nace armado SOLO si
 * el down cae en la banda del borde; el resto del mapa de la pagina no
 * se toca nunca.
 */
class RastreadorBorde(
    private val anchoPantalla: Double,
    private val cfg: ConfigGestos = ConfigGestos()
) {
    private var activo = false
    private var consumido = false
    private var x0 = 0.0
    private var y0 = 0.0
    private var desdeIzquierda = false

    /** El gesto nace (o no) en el borde; fuera de banda NUNCA dispara. */
    fun down(x: Double, y: Double): Boolean {
        desdeIzquierda = x <= cfg.bordePx
        val desdeDerecha = x >= anchoPantalla - cfg.bordePx
        activo = desdeIzquierda || desdeDerecha
        consumido = false
        x0 = x
        y0 = y
        return activo
    }

    /**
     * Decide con cada movimiento: mientras el vertical no domine y el
     * umbral no se cruce no pasa nada (el scroll sigue suyo); al cruzar
     * el umbral hacia DENTRO dispara UNA sola vez; hacia fuera del
     * borde se cancela (el sistema manda en el borde fisico).
     */
    fun move(x: Double, y: Double): AccionBorde {
        if (!activo || consumido) return AccionBorde.NINGUNA
        val dx = x - x0
        val dy = y - y0
        if (abs(dy) > abs(dx) * cfg.dominancia) {
            activo = false
            return AccionBorde.NINGUNA
        }
        if (abs(dx) < cfg.umbralSwipePx) return AccionBorde.NINGUNA
        val haciaDentro = if (desdeIzquierda) dx > 0 else dx < 0
        if (!haciaDentro) {
            activo = false
            return AccionBorde.NINGUNA
        }
        consumido = true
        return if (desdeIzquierda) AccionBorde.ATRAS else AccionBorde.ADELANTE
    }

    /** Fin del toque: el rastreador queda listo para el siguiente. */
    fun up() {
        activo = false
        consumido = false
    }
}

/**
 * Zoom por doble tap: dos toques dentro de la ventana de tiempo y de
 * distancia alternan entre el zoom objetivo y 1x. El reloj se inyecta
 * para que los tests midan el tiempo sin dormir.
 */
class ZoomToque(
    private val cfg: ConfigGestos = ConfigGestos(),
    private val reloj: () -> Long
) {
    private var tPrevio: Long = Long.MIN_VALUE
    private var xPrevio = 0.0
    private var yPrevio = 0.0

    /**
     * Toque simple: devuelve el zoom del DOBLE TAP o null si es un tap
     * aislado (fuera de tiempo o de distancia). Con zoom > 1.05 vuelve
     * a 1x; si no, va al zoom objetivo.
     */
    fun toque(x: Double, y: Double, zoomActual: Double): Double? {
        val t = reloj()
        if (tPrevio != Long.MIN_VALUE &&
            t - tPrevio in 0..cfg.dobleTapMs &&
            abs(x - xPrevio) <= cfg.dobleTapPx &&
            abs(y - yPrevio) <= cfg.dobleTapPx
        ) {
            tPrevio = Long.MIN_VALUE
            return limitar(if (zoomActual > 1.05) 1.0 else cfg.zoomDobleTap, cfg)
        }
        tPrevio = t
        xPrevio = x
        yPrevio = y
        return null
    }

    companion object {
        /** Limita un zoom a [zoomMin, zoomMax]. */
        fun limitar(z: Double, cfg: ConfigGestos = ConfigGestos()): Double =
            minOf(maxOf(z, cfg.zoomMin), cfg.zoomMax)

        /**
         * Zoom REAL del motor: viewport logico para RE-MAQUETEAR el
         * documento (360/zoom), nunca un reescalado de bitmap.
         */
        fun viewportPara(zoom: Double, base: Double = 360.0, cfg: ConfigGestos = ConfigGestos()): Double =
            base / limitar(zoom, cfg)

        /** Factor acumulado de pinza con limites (se aplica al soltar). */
        fun pinza(zoomActual: Double, factor: Double, cfg: ConfigGestos = ConfigGestos()): Double =
            limitar(zoomActual * factor, cfg)
    }
}

/**
 * Ocultador de la barra de URL: acumula el scroll descendente hasta
 * ocultarla y el ascendente hasta mostrarla; en el borde superior se
 * muestra SIEMPRE. La acumulacion evita el parpadeo con micro-scrolls.
 */
class OcultadorBarra(private val cfg: ConfigGestos = ConfigGestos()) {
    private var acumulado = 0.0
    private var visible = true

    /**
     * true = mostrar la barra. deltaY positivo = contenido que baja
     * (el dedo sube); bordeSuperior la muestra y resetea.
     */
    fun scroll(deltaY: Double, bordeSuperior: Boolean): Boolean {
        if (bordeSuperior) {
            acumulado = 0.0
            visible = true
            return true
        }
        acumulado += deltaY
        if (visible) {
            if (acumulado >= cfg.ocultarDpx) {
                visible = false
                acumulado = 0.0
            }
        } else {
            if (acumulado <= -cfg.mostrarDpx) {
                visible = true
                acumulado = 0.0
            }
        }
        return visible
    }

    /** Visibilidad actual de la barra. */
    fun estaVisible(): Boolean = visible

    /** Reset (nueva pagina, nuevo documento). */
    fun reset() {
        acumulado = 0.0
        visible = true
    }
}

/**
 * Politica de pull-to-refresh: OFF por defecto (T077); solo con
 * pullToRefresh habilitado (T078) puede refrescar, y solo desde el
 * borde superior. v1 la usa el shell para NO disparar nada.
 */
class PoliticaPullRefresh(private val cfg: ConfigGestos = ConfigGestos()) {
    /** Refresco solo si esta habilitado Y hay sobre-scroll real. */
    fun debeRefrescar(haySobreScroll: Boolean): Boolean = cfg.pullToRefresh && haySobreScroll

    /** Estado de la politica (OFF por defecto en T077). */
    fun habilitado(): Boolean = cfg.pullToRefresh
}

/**
 * T078 v1 (R0199, RES-0094): tiron de pull-to-refresh decidido en el
 * MOTOR. El shell alimenta con el sobrescroll REAL del borde
 * superior (deltaY > 0 = tirar hacia abajo) y el reloj se inyecta
 * como en ZoomToque; refresca SOLO si el gesto esta habilitado
 * (ConfigGestos.pullToRefresh), el acumulado cruza pullUmbralPx y el
 * tiron entero dura menos de pullMaxMs (los tirones lentos se
 * descartan). Soltar o cancelar dejan el rastreador listo para el
 * siguiente tiron: el acumulado NUNCA se arrastra.
 */
class TironRefresh(private val cfg: ConfigGestos = ConfigGestos()) {
    private var activo = false
    private var acumulado = 0.0
    private var t0 = 0L
    private var dentroTiempo = true

    /** Acumula un trozo de sobrescroll descendente; el tiron arranca
     * solo con el primero (el padre puede interceptar el gesto y
     * este ver solo los move). deltaY <= 0 no ensucia el acumulado
     * (el scroll normal vive fuera del tiron). */
    fun mover(deltaY: Double, t: Long) {
        if (!cfg.pullToRefresh) {
            return
        }
        if (!activo) {
            activo = true
            acumulado = 0.0
            t0 = t
            dentroTiempo = true
        }
        if (deltaY <= 0.0) {
            return
        }
        acumulado += deltaY
        if (t - t0 > cfg.pullMaxMs) {
            dentroTiempo = false
        }
    }

    /** Suelta del toque: decide si refresca y queda listo para el
     * siguiente tiron. */
    fun soltar(t: Long): Boolean {
        if (activo && t - t0 > cfg.pullMaxMs) {
            dentroTiempo = false
        }
        val refrescar = activo && dentroTiempo && acumulado >= cfg.pullUmbralPx
        activo = false
        acumulado = 0.0
        return refrescar
    }

    /** Cancela el tiron en curso (el scroll robo el gesto). */
    fun cancelar() {
        activo = false
        acumulado = 0.0
    }

    /** Sobrescroll acumulado del tiron en curso (feedback). */
    fun distancia(): Double = acumulado
}
