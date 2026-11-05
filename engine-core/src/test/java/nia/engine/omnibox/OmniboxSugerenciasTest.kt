package nia.engine.omnibox

import nia.engine.bookmarks.BookmarkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OmniboxSugerenciasTest (T072 v1, RES-0075, R0155): espejo de los
 * escenarios de sugerencias del gemelo TS validado offline (huella
 * 9822.26f4.982, 34 escenarios): marcadores primero, historial silenciado
 * en incognito (ADR-019), orden exacta/prefijo/resto, dedup por URL con el
 * marcador ganando y tope recortando el orden ya clasificado.
 */
class OmniboxSugerenciasTest {

    private fun managerConMarcadores(): BookmarkManager {
        val m = BookmarkManager()
        m.add("Diario", "https://ejemplo.com/diario")
        m.add("Cafe", "https://cafe.com")
        m.add("ejemplo", "https://ejemplo.com")
        return m
    }

    private val historial = listOf(
        "https://ejemplo.com/inicio",
        "https://cafe.com/menu",
        "https://otros.net/x"
    )

    @Test
    fun sugerenciaExactaPorTituloYOrden() {
        val s = OmniboxSugerencias.sugerencias("ejemplo", managerConMarcadores(), historial, false)
        assertEquals(3, s.size)
        assertEquals("ejemplo", s[0].titulo)
        assertEquals(Origen.MARCADOR, s[0].origen)
        assertEquals("Diario", s[1].titulo)
        assertEquals(Origen.MARCADOR, s[1].origen)
        assertEquals("https://ejemplo.com/inicio", s[2].url)
        assertEquals(Origen.HISTORIAL, s[2].origen)
    }

    @Test
    fun sugerenciaPrefijoDeUrl() {
        val s = OmniboxSugerencias.sugerencias("https://cafe.com", managerConMarcadores(), historial, false)
        assertEquals(2, s.size)
        assertEquals("https://cafe.com", s[0].url)
        assertEquals(Origen.MARCADOR, s[0].origen)
        assertEquals("https://cafe.com/menu", s[1].url)
        assertEquals(Origen.HISTORIAL, s[1].origen)
    }

    @Test
    fun textoVacioSinSugerencias() {
        val s = OmniboxSugerencias.sugerencias("", managerConMarcadores(), historial, false)
        assertTrue(s.isEmpty())
        assertFalse(s.isNotEmpty())
    }

    @Test
    fun sinCoincidencias() {
        val s = OmniboxSugerencias.sugerencias("zzz", managerConMarcadores(), historial, false)
        assertTrue(s.isEmpty())
    }

    @Test
    fun incognitoSilenciaHistorial() {
        val s = OmniboxSugerencias.sugerencias("cafe", managerConMarcadores(), historial, true)
        assertEquals(1, s.size)
        assertEquals("https://cafe.com", s[0].url)
        assertEquals(Origen.MARCADOR, s[0].origen)
    }

    @Test
    fun historialVisibleSinIncognito() {
        val s = OmniboxSugerencias.sugerencias("cafe", managerConMarcadores(), historial, false)
        assertEquals(2, s.size)
        assertEquals(Origen.HISTORIAL, s[1].origen)
    }

    @Test
    fun dedupPorUrlGanaElMarcador() {
        val s = OmniboxSugerencias.sugerencias(
            "diario", managerConMarcadores(), listOf("https://ejemplo.com/diario"), false
        )
        assertEquals(1, s.size)
        assertEquals(Origen.MARCADOR, s[0].origen)
        assertEquals("Diario", s[0].titulo)
    }

    @Test
    fun topeRecortaOrdenando() {
        val s = OmniboxSugerencias.sugerencias("e", managerConMarcadores(), historial, false, 2)
        assertEquals(2, s.size)
        assertEquals("ejemplo", s[0].titulo)
    }

    @Test
    fun sinMarcadoresNiHistorialDevuelveVacio() {
        val s = OmniboxSugerencias.sugerencias("x", null, emptyList(), false)
        assertTrue(s.isEmpty())
        assertNull(s.firstOrNull())
    }
}
