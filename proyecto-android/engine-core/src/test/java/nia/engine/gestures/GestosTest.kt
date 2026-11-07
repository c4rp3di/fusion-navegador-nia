package nia.engine.gestures

import nia.engine.settings.SettingsManager
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

    // ---- T078 v1 (R0199, RES-0094): tiron de pull-to-refresh ----

    @Test
    fun tironApagadoNuncaRefresca() {
        val t = TironRefresh()
        t.mover(100.0, 0L)
        t.mover(100.0, 100L)
        assertFalse(t.soltar(200L))
    }

    @Test
    fun tironBajoElUmbralNoRefresca() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(30.0, 0L)
        t.mover(30.0, 100L)
        assertFalse(t.soltar(200L))
    }

    @Test
    fun tironSobreElUmbralEnTiempoRefresca() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(30.0, 0L)
        t.mover(40.0, 100L)
        assertTrue(t.soltar(200L))
    }

    @Test
    fun tironLentoSeDescarta() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(60.0, 0L)
        t.mover(40.0, 400L)
        t.mover(40.0, 900L)
        assertFalse(t.soltar(950L))
    }

    @Test
    fun tironCanceladoNoRefresca() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(40.0, 0L)
        t.mover(40.0, 100L)
        t.cancelar()
        assertFalse(t.soltar(200L))
    }

    @Test
    fun tironSueltaYArrancaDeNuevoLimpio() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(70.0, 0L)
        assertTrue(t.soltar(100L))
        t.mover(30.0, 200L)
        assertFalse(t.soltar(300L))
    }

    @Test
    fun tironIgnoraElMovimientoHaciaArriba() {
        val t = TironRefresh(ConfigGestos(pullToRefresh = true))
        t.mover(70.0, 0L)
        t.mover(-50.0, 100L)
        assertEquals(70.0, t.distancia(), 1e-9)
        assertTrue(t.soltar(200L))
    }

    // ---- T078 v1: AjustesGestos (seccion Gestos del armazon) ----

    @Test
    fun ajustesGestosRegistranNueveOpciones() {
        val s = SettingsManager()
        assertTrue(AjustesGestos.registrar(s))
        assertEquals(9, s.contar())
        assertTrue(s.secciones().contains("Gestos"))
    }

    @Test
    fun ajustesGestosNoRegistranDosVeces() {
        val s = SettingsManager()
        assertTrue(AjustesGestos.registrar(s))
        assertFalse(AjustesGestos.registrar(s))
    }

    @Test
    fun configDeGestosPorDefectoEsLaDelMotor() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        val c = AjustesGestos.config(s)
        val d = ConfigGestos()
        assertEquals(d.bordePx, c.bordePx, 1e-9)
        assertEquals(d.umbralSwipePx, c.umbralSwipePx, 1e-9)
        assertEquals(d.swipe, c.swipe)
        assertEquals(d.dobleTap, c.dobleTap)
        assertEquals(d.pinza, c.pinza)
        assertEquals(d.barra, c.barra)
        assertEquals(d.pullToRefresh, c.pullToRefresh)
        assertEquals(d.pullUmbralPx, c.pullUmbralPx, 1e-9)
        assertEquals(d.pullMaxMs, c.pullMaxMs)
    }

    @Test
    fun configAplicaLosCambiosEnCaliente() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        assertTrue(s.asignar("gestos.swipe", "false"))
        assertTrue(s.asignar("gestos.borde", "8"))
        assertTrue(s.asignar("gestos.umbral-swipe", "200"))
        assertTrue(s.asignar("gestos.pull-refresh", "true"))
        assertTrue(s.asignar("gestos.pull-umbral", "240"))
        assertTrue(s.asignar("gestos.pull-tiempo", "2000"))
        val c = AjustesGestos.config(s)
        assertFalse(c.swipe)
        assertEquals(8.0, c.bordePx, 1e-9)
        assertEquals(200.0, c.umbralSwipePx, 1e-9)
        assertTrue(c.pullToRefresh)
        assertEquals(240.0, c.pullUmbralPx, 1e-9)
        assertEquals(2000L, c.pullMaxMs)
    }

    @Test
    fun configRecuperaADefectosSinRegistro() {
        val s = SettingsManager()
        val c = AjustesGestos.config(s)
        val d = ConfigGestos()
        assertEquals(d.bordePx, c.bordePx, 1e-9)
        assertEquals(d.umbralSwipePx, c.umbralSwipePx, 1e-9)
        assertEquals(d.swipe, c.swipe)
        assertFalse(c.pullToRefresh)
    }

    @Test
    fun elArmazonRechazaValoresFueraDeRango() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        assertFalse(s.asignar("gestos.borde", "999"))
        assertFalse(s.asignar("gestos.borde", "4"))
        assertFalse(s.asignar("gestos.umbral-swipe", "201"))
        assertFalse(s.asignar("gestos.pull-tiempo", "100"))
        assertEquals("24", s.valor("gestos.borde"))
        assertEquals("72", s.valor("gestos.umbral-swipe"))
        assertEquals("800", s.valor("gestos.pull-tiempo"))
    }

    @Test
    fun restablecerGestosVuelveADefectos() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        s.asignar("gestos.swipe", "false")
        s.asignar("gestos.pull-refresh", "true")
        s.asignar("gestos.borde", "8")
        assertTrue(s.restablecer("gestos.swipe"))
        assertTrue(s.restablecer("gestos.pull-refresh"))
        assertTrue(s.restablecer("gestos.borde"))
        val c = AjustesGestos.config(s)
        assertTrue(c.swipe)
        assertFalse(c.pullToRefresh)
        assertEquals(24.0, c.bordePx, 1e-9)
    }

    @Test
    fun restablecerTodoCubreLosGestos() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        s.asignar("gestos.swipe", "false")
        s.asignar("gestos.pull-umbral", "32")
        assertEquals(2, s.restablecerTodo())
        val c = AjustesGestos.config(s)
        assertTrue(c.swipe)
        assertEquals(64.0, c.pullUmbralPx, 1e-9)
    }

    @Test
    fun gestosViajanEnElExportSinSecretos() {
        val s = SettingsManager()
        AjustesGestos.registrar(s)
        s.asignar("gestos.pull-refresh", "true")
        val e = s.exportar()
        assertTrue(e.contains("S	gestos.swipe	true"))
        assertTrue(e.contains("S	gestos.pull-refresh	true"))
    }
}
