package nia.engine.permisos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del broker de permisos por sitio (T089 v1, RES-0109):
 * espejo del gemelo TS (25 checks en verde antes del push). Sin
 * backend la API JAMAS se anuncia habilitada, el background no
 * pregunta, la revocacion corta el acceso de inmediato, las
 * decisiones de sesion mueren al fin de sesion, las permanentes
 * persisten en permisos.tsv TODO-o-NADA (un corrupto se ignora) y
 * la generacion invalida las respuestas de dialogos en vuelo.
 */
class PermisosTest {

    @Test
    fun noDisponibleSeDeniegaSinAnunciarse() {
        val g = GestorPermisos()
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        assertFalse(r.concedido)
        assertFalse(r.prompt)
        assertEquals(FalloPermiso.NO_DISPONIBLE, r.fallo)
        assertEquals(FalloPermiso.NO_DISPONIBLE, g.solicitar("https://a:443", TipoPermiso.CAMARA, false).fallo)
    }

    @Test
    fun disponiblePidePromptSoloEnPrimerPlano() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        assertTrue(r.prompt)
        assertFalse(r.concedido)
        assertNull(r.fallo)
        val rb = g.solicitar("https://a:443", TipoPermiso.CAMARA, false)
        assertEquals(FalloPermiso.BACKGROUND, rb.fallo)
        assertFalse(rb.prompt)
    }

    @Test
    fun origenInvalidoRechazado() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.GEOLOCALIZACION, true)
        assertEquals(FalloPermiso.ORIGEN_INVALIDO, g.solicitar("", TipoPermiso.GEOLOCALIZACION, true).fallo)
        assertEquals(FalloPermiso.ORIGEN_INVALIDO, g.solicitar("a\tb", TipoPermiso.GEOLOCALIZACION, true).fallo)
        assertEquals(FalloPermiso.ORIGEN_INVALIDO, g.solicitar("x".repeat(201), TipoPermiso.GEOLOCALIZACION, true).fallo)
        assertEquals(FalloPermiso.ORIGEN_INVALIDO, g.solicitar("a\nb", TipoPermiso.GEOLOCALIZACION, true).fallo)
    }

    @Test
    fun registrarConcedeEnSesion() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        assertTrue(g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion))
        val r2 = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        assertTrue(r2.concedido)
        assertFalse(r2.prompt)
        assertEquals(AmbitoPermiso.SESION, r2.ambito)
        assertEquals(1, g.total())
    }

    @Test
    fun denegacionRecordadaNoVuelveAPreguntar() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.NOTIFICACIONES, true)
        val r = g.solicitar("https://a:443", TipoPermiso.NOTIFICACIONES, true)
        g.registrar("https://a:443", TipoPermiso.NOTIFICACIONES, false, AmbitoPermiso.SESION, r.generacion)
        val r2 = g.solicitar("https://a:443", TipoPermiso.NOTIFICACIONES, true)
        assertFalse(r2.concedido)
        assertFalse(r2.prompt)
        assertNull(r2.fallo)
    }

    @Test
    fun finDeSesionExpiraLasDeSesion() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion)
        val generacionVieja = r.generacion
        assertEquals(1, g.finDeSesion())
        assertTrue(g.solicitar("https://a:443", TipoPermiso.CAMARA, true).prompt)
        assertFalse(g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, generacionVieja))
        assertEquals(0, g.total())
    }

    @Test
    fun permanenteSobreviveAFinDeSesion() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.PORTAPAPELES, true)
        val r = g.solicitar("https://a:443", TipoPermiso.PORTAPAPELES, true)
        g.registrar("https://a:443", TipoPermiso.PORTAPAPELES, true, AmbitoPermiso.PERMANENTE, r.generacion)
        assertEquals(0, g.finDeSesion())
        assertTrue(g.solicitar("https://a:443", TipoPermiso.PORTAPAPELES, true).concedido)
    }

    @Test
    fun revocarCortaElAccesoDeInmediato() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.PERMANENTE, r.generacion)
        assertTrue(g.revocar("https://a:443", TipoPermiso.CAMARA))
        assertTrue(g.solicitar("https://a:443", TipoPermiso.CAMARA, true).prompt)
        assertFalse(g.revocar("https://a:443", TipoPermiso.CAMARA))
    }

    @Test
    fun revocarInvalidaRespuestasEnVuelo() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion)
        g.revocar("https://a:443", TipoPermiso.CAMARA)
        assertFalse(g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion))
        assertEquals(0, g.total())
    }

    @Test
    fun decisionesPorSitioAisladas() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val ra = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, ra.generacion)
        val rb = g.solicitar("https://b:443", TipoPermiso.CAMARA, true)
        assertTrue(rb.prompt)
        assertFalse(rb.concedido)
    }

    @Test
    fun roundtripExportImportSoloPermanentes() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        g.marcarDisponible(TipoPermiso.MICROFONO, true)
        var r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.PERMANENTE, r.generacion)
        r = g.solicitar("https://b:80", TipoPermiso.MICROFONO, true)
        g.registrar("https://b:80", TipoPermiso.MICROFONO, false, AmbitoPermiso.PERMANENTE, r.generacion)
        r = g.solicitar("https://c:443", TipoPermiso.CAMARA, true)
        g.registrar("https://c:443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion)
        val lineas = g.exportar().split('\n')
        assertEquals("nia-permisos\t1", lineas[0])
        assertEquals(3, lineas.size)
        assertEquals("P\thttps://a:443\tCAMARA\tPERMITIDO", lineas[1])
        assertEquals("P\thttps://b:80\tMICROFONO\tDENEGADO", lineas[2])
        val g2 = GestorPermisos()
        assertTrue(g2.importar(g.exportar()))
        assertEquals(2, g2.total())
        assertEquals(FalloPermiso.NO_DISPONIBLE, g2.solicitar("https://a:443", TipoPermiso.CAMARA, true).fallo)
        g2.marcarDisponible(TipoPermiso.CAMARA, true)
        g2.marcarDisponible(TipoPermiso.MICROFONO, true)
        assertTrue(g2.solicitar("https://a:443", TipoPermiso.CAMARA, true).concedido)
        assertFalse(g2.solicitar("https://b:80", TipoPermiso.MICROFONO, true).concedido)
        assertTrue(g2.solicitar("https://c:443", TipoPermiso.CAMARA, true).prompt)
    }

    @Test
    fun importInvalidoNoTocaElVivo() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("vivo", TipoPermiso.CAMARA, true)
        g.registrar("vivo", TipoPermiso.CAMARA, true, AmbitoPermiso.PERMANENTE, r.generacion)
        assertFalse(g.importar("nia-permisos\t2\nP\tv\tCAMARA\tPERMITIDO"))
        assertFalse(g.importar("nia-permisos\t1\nX\thttps://v:1\tCAMARA\tPERMITIDO"))
        assertFalse(g.importar("nia-permisos\t1\nP\thttps://v:1\tTELEPATIA\tPERMITIDO"))
        assertFalse(g.importar("nia-permisos\t1\nP\thttps://v:1\tCAMARA\tQUIZA"))
        assertFalse(g.importar("nia-permisos\t1\nP\ta\tb\tCAMARA\tPERMITIDO"))
        assertFalse(g.importar("nia-permisos\t1\nP\thttps://v:1\tCAMARA\tPERMITIDO\nP\thttps://v:1\tCAMARA\tDENEGADO"))
        assertEquals(1, g.total())
        assertTrue(g.solicitar("vivo", TipoPermiso.CAMARA, true).concedido)
    }

    @Test
    fun persistenciaRealConArchivo() {
        val f = File.createTempFile("permisos", ".tsv")
        f.deleteOnExit()
        val m = GestorPermisos(f)
        m.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = m.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        m.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.PERMANENTE, r.generacion)
        val m2 = GestorPermisos(f)
        assertTrue(m2.recargar())
        m2.marcarDisponible(TipoPermiso.CAMARA, true)
        assertTrue(m2.solicitar("https://a:443", TipoPermiso.CAMARA, true).concedido)
        f.writeText("chatarra")
        val m3 = GestorPermisos(f)
        assertFalse(m3.recargar())
        assertEquals(0, m3.total())
    }

    @Test
    fun topeExpulsaALaMasVieja() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        for (i in 0..GestorPermisos.MAX_DECISIONES) {
            val r = g.solicitar("https://s" + i + ":443", TipoPermiso.CAMARA, true)
            g.registrar("https://s" + i + ":443", TipoPermiso.CAMARA, true, AmbitoPermiso.SESION, r.generacion)
        }
        assertEquals(GestorPermisos.MAX_DECISIONES, g.total())
        val filas = g.listar()
        assertEquals("https://s1:443", filas[0].clave)
        assertEquals("https://s200:443", filas[filas.size - 1].clave)
    }

    @Test
    fun quitarElBackendCortaLaApi() {
        val g = GestorPermisos()
        g.marcarDisponible(TipoPermiso.CAMARA, true)
        val r = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        g.registrar("https://a:443", TipoPermiso.CAMARA, true, AmbitoPermiso.PERMANENTE, r.generacion)
        g.marcarDisponible(TipoPermiso.CAMARA, false)
        val r2 = g.solicitar("https://a:443", TipoPermiso.CAMARA, true)
        assertEquals(FalloPermiso.NO_DISPONIBLE, r2.fallo)
        assertFalse(r2.concedido)
    }
}
