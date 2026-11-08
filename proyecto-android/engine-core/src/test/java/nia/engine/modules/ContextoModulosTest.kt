package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

import nia.engine.inspector.EventContext

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 14.14.25e8790c): ContextoModulos acuna la
 * identidad de los fires reales del shell (C10 v2, R0267, RES-0118):
 * sesion del navegador, pestana, frame main (el motor v1 no tiene
 * frame tree) y documento con generacion monotona por navegacion.
 */
class ContextoModulosTest {

    @Test
    fun sesionEnBlancoSeRechaza() {
        try {
            ContextoModulos(" ")
            fail("sessionId en blanco debe rechazarse")
        } catch (e: IllegalArgumentException) {
            assertEquals("sessionId obligatorio", e.message)
        }
    }

    @Test
    fun nuevaNavegacionAcunaElContextoCompleto() {
        val c = ContextoModulos("s-demo")
        val ctx = c.nuevaNavegacion(5L)
        assertEquals("s-demo", ctx.sessionId)
        assertEquals("t-5", ctx.tabId)
        assertEquals("el motor v1 no tiene frame tree: frame main", "main", ctx.frameId)
        assertEquals("d-5-1", ctx.documentId)
        assertEquals(1L, ctx.generation)
    }

    @Test
    fun laGeneracionCreceYElDocumentoAvanza() {
        val c = ContextoModulos("s-demo")
        c.nuevaNavegacion(5L)
        val segunda = c.nuevaNavegacion(5L)
        assertEquals(2L, segunda.generation)
        assertEquals("d-5-2", segunda.documentId)
    }

    @Test
    fun lasPestanasSonIndependientes() {
        val c = ContextoModulos("s-demo")
        c.nuevaNavegacion(5L)
        c.nuevaNavegacion(5L)
        val deLaOtra = c.nuevaNavegacion(6L)
        assertEquals("la generacion es por pestana", 1L, deLaOtra.generation)
        assertEquals("d-6-1", deLaOtra.documentId)
    }

    @Test
    fun actualEsNullAntesDeNavegar() {
        val c = ContextoModulos("s-demo")
        assertNull(c.actual(9L))
    }

    @Test
    fun actualDevuelveElUltimoAcunado() {
        val c = ContextoModulos("s-demo")
        val primera = c.nuevaNavegacion(7L)
        assertEquals(primera, c.actual(7L))
        val segunda = c.nuevaNavegacion(7L)
        assertEquals(segunda, c.actual(7L))
        assertEquals("d-7-2", c.actual(7L)!!.documentId)
    }
}
