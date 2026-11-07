package nia.engine.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la logica PURA de los gestos base (T077 v1, R0195,
 * RES-0093): el rastreador de borde solo dispara naciendo en el borde
 * y hacia dentro, cede ante el vertical y no repite por toque; el
 * doble tap alterna con ventana de tiempo/distancia y el reloj
 * inyectado mide sin dormir; el zoom respeta limites y re-maquetea
 * con viewport 360/zoom; la barra de URL se oculta/muestra con
 * histeresis y el borde superior manda; pull-to-refresh se niega OFF
 * por defecto y solo podria con T078.
 */
class GestosTest {

    // ---- RastreadorBorde: swipe back/forward en bordes ----

    @Test
    fun fueraDelBordeNuncaArmaNiDispara() {
        val r = RastreadorBorde(360.0)
        assertFalse(r.down(180.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, r.move(320.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, r.move(20.0, 100.0))
    }

    @Test
    fun bordeIzquierdoHaciaDentroEsAtras() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(10.0, 100.0))
        assertEquals(AccionBorde.ATRAS, r.move(90.0, 110.0))
    }

    @Test
    fun bordeDerechoHaciaDentroEsAdelante() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(350.0, 100.0))
        assertEquals(AccionBorde.ADELANTE, r.move(270.0, 110.0))
    }

    @Test
    fun verticalDominanteCancelaParaSiempre() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(10.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, r.move(30.0, 300.0))
        assertEquals(AccionBorde.NINGUNA, r.move(200.0, 320.0))
    }

    @Test
    fun swipeHaciaFueraDelBordeNoDispara() {
        val izq = RastreadorBorde(360.0)
        assertTrue(izq.down(10.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, izq.move(-40.0, 100.0))
        val der = RastreadorBorde(360.0)
        assertTrue(der.down(350.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, der.move(400.0, 100.0))
    }

    @Test
    fun debajoDelUmbralSigueDecidiendoNada() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(10.0, 100.0))
        assertEquals(AccionBorde.NINGUNA, r.move(60.0, 108.0))
        assertEquals(AccionBorde.ATRAS, r.move(90.0, 115.0))
    }

    @Test
    fun unSoloDisparoPorToque() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(10.0, 100.0))
        assertEquals(AccionBorde.ATRAS, r.move(90.0, 110.0))
        assertEquals(AccionBorde.NINGUNA, r.move(250.0, 120.0))
    }

    @Test
    fun trasUpVuelveAEstarListo() {
        val r = RastreadorBorde(360.0)
        assertTrue(r.down(10.0, 100.0))
        assertEquals(AccionBorde.ATRAS, r.move(90.0, 110.0))
        r.up()
        assertTrue(r.down(350.0, 100.0))
        assertEquals(AccionBorde.ADELANTE, r.move(270.0, 110.0))
    }

    // ---- ZoomToque: doble tap, limites y viewport de re-maqueteo ----

    @Test
    fun dobleTapDuplicaElZoom() {
        var t = 0L
        val z = ZoomToque { t }
        assertNull(z.toque(100.0, 100.0, 1.0))
        t = 150
        assertEquals(2.0, z.toque(102.0, 101.0, 1.0)!!, 1e-9)
    }

    @Test
    fun dobleTapConZoomVuelveA1x() {
        var t = 0L
        val z = ZoomToque { t }
        assertNull(z.toque(100.0, 100.0, 1.5))
        t = 150
        assertEquals(1.0, z.toque(102.0, 101.0, 1.5)!!, 1e-9)
    }

    @Test
    fun tapsLejanosNoSonDobleTap() {
        var t = 0L
        val z = ZoomToque { t }
        assertNull(z.toque(100.0, 100.0, 1.0))
        t = 150
        assertNull(z.toque(200.0, 100.0, 1.0))
    }

    @Test
    fun tapsTardiosNoSonDobleTap() {
        var t = 0L
        val z = ZoomToque { t }
        assertNull(z.toque(100.0, 100.0, 1.0))
        t = 1000
        assertNull(z.toque(101.0, 101.0, 1.0))
    }

    @Test
    fun limitarRespetaRangos() {
        val cfg = ConfigGestos()
        assertEquals(0.75, ZoomToque.limitar(0.1, cfg), 1e-9)
        assertEquals(3.0, ZoomToque.limitar(9.0, cfg), 1e-9)
        assertEquals(1.5, ZoomToque.limitar(1.5, cfg), 1e-9)
    }

    @Test
    fun viewportParaReMaquetaCon360EntreZoom() {
        val cfg = ConfigGestos()
        assertEquals(360.0, ZoomToque.viewportPara(1.0, 360.0, cfg), 1e-9)
        assertEquals(180.0, ZoomToque.viewportPara(2.0, 360.0, cfg), 1e-9)
        assertEquals(120.0, ZoomToque.viewportPara(4.0, 360.0, cfg), 1e-9)
    }

    @Test
    fun pinzaAcumulaConLimites() {
        val cfg = ConfigGestos()
        assertEquals(2.5, ZoomToque.pinza(1.0, 2.5, cfg), 1e-9)
        assertEquals(3.0, ZoomToque.pinza(2.9, 2.0, cfg), 1e-9)
        assertEquals(0.75, ZoomToque.pinza(1.0, 0.1, cfg), 1e-9)
    }

    // ---- OcultadorBarra: ocultar al bajar, mostrar al subir/borde ----

    @Test
    fun ocultaAlBajarLoSuficiente() {
        val b = OcultadorBarra()
        assertTrue(b.scroll(10.0, false))
        assertFalse(b.scroll(15.0, false))
        assertFalse(b.estaVisible())
    }

    @Test
    fun muestraAlSubirDesdeOculta() {
        val b = OcultadorBarra()
        assertFalse(b.scroll(30.0, false))
        assertTrue(b.scroll(-15.0, false))
        assertTrue(b.estaVisible())
    }

    @Test
    fun bordeSuperiorMuestraSiempre() {
        val b = OcultadorBarra()
        assertFalse(b.scroll(30.0, false))
        assertTrue(b.scroll(0.0, true))
        assertTrue(b.estaVisible())
    }

    @Test
    fun microScrollsAcumulanSinParpadear() {
        val b = OcultadorBarra()
        assertTrue(b.scroll(10.0, false))
        assertTrue(b.scroll(10.0, false))
        assertFalse(b.scroll(10.0, false))
        assertFalse(b.scroll(5.0, false))
        assertFalse(b.scroll(5.0, false))
    }

    @Test
    fun resetVuelveAVisible() {
        val b = OcultadorBarra()
        assertFalse(b.scroll(30.0, false))
        b.reset()
        assertTrue(b.estaVisible())
    }

    // ---- PoliticaPullRefresh: OFF por defecto ----

    @Test
    fun pullRefreshOffPorDefectoSeNiegaSiempre() {
        val p = PoliticaPullRefresh()
        assertFalse(p.habilitado())
        assertFalse(p.debeRefrescar(true))
    }

    @Test
    fun pullRefreshSoloConConfiguracionDeT078() {
        val p = PoliticaPullRefresh(ConfigGestos(pullToRefresh = true))
        assertTrue(p.habilitado())
        assertTrue(p.debeRefrescar(true))
        assertFalse(p.debeRefrescar(false))
    }
}
