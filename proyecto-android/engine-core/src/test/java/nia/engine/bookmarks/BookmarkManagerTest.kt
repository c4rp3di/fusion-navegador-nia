package nia.engine.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 12a2ec81.15bdf3db.627): los 20 tests cubren
 * el CRUD de marcadores y carpetas, el orden por carpeta con el
 * ciclo rechazado, la busqueda, los pins y el roundtrip de
 * exportar/importar con el estado vivo intacto ante archivos
 * corruptos. T060 v1 (R0135, RES-0070).
 */
@Suppress("UNCHECKED_CAST")
class BookmarkManagerTest {

    private fun bmDe(r: Outcome<Bookmark>): Bookmark? =
        (r as? Outcome.Success)?.value as? Bookmark

    private fun cfDe(r: Outcome<Carpeta>): Carpeta? =
        (r as? Outcome.Success)?.value as? Carpeta

    private fun idsDe(r: Outcome<List<String>>): String =
        ((r as? Outcome.Success)?.value as? List<*>)?.joinToString(",") ?: ""

    private fun mensajeDe(r: Outcome<*>): String =
        if (r is Outcome.Failure) r.error.safeMessage else ""

    private fun textoExport(): String {
        val m = BookmarkManager()
        m.addCarpeta("Trabajo")
        m.addCarpeta("Casa")
        m.add("Diario", "https://ejemplo.com/diario", "cf.1")
        m.add("Correo", "https://mail.example.com", "cf.2")
        m.add("Docs", "https://docs.example.com")
        m.move("cf.2", "root", 0)
        m.move("bm.2", "cf.1", 0)
        m.pin("bm.1")
        m.pin("bm.2")
        return m.exportar()
    }

    @Test
    fun raizPorDefecto() {
        val m = BookmarkManager()
        val r = m.hijos("root")
        assertTrue(r is Outcome.Success)
        assertEquals("", idsDe(r))
        assertEquals(0, m.accesosRapidos().size)
        assertEquals(0, m.buscar("x").size)
    }

    @Test
    fun addCreaMarcadorEnRaiz() {
        val m = BookmarkManager()
        val r = m.add("Diario", "https://ejemplo.com/diario")
        assertNotNull(bmDe(r))
        assertEquals("bm.1", bmDe(r)?.id)
        assertEquals("root", bmDe(r)?.carpeta)
        assertEquals("bm.1", idsDe(m.hijos("root")))
    }

    @Test
    fun addRechazaUrlInvalida() {
        val m = BookmarkManager()
        val r1 = m.add("Malo", "ftp://x")
        val r2 = m.add("Malo2", "https://a b")
        assertTrue(r1 is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (r1 as Outcome.Failure).error.code)
        assertEquals("url no valida: ftp://x", mensajeDe(r1))
        assertTrue(r2 is Outcome.Failure)
        assertEquals("url no valida: https://a b", mensajeDe(r2))
        assertEquals(0, m.total)
    }

    @Test
    fun addRechazaCarpetaYTitulo() {
        val m = BookmarkManager()
        m.add("Diario", "https://ejemplo.com/diario")
        val rCarpeta = m.add("X", "https://ejemplo.com/x", "no.hay")
        val rTitulo = m.add("", "https://ejemplo.com/y")
        assertEquals("carpeta no existe: no.hay", mensajeDe(rCarpeta))
        assertEquals("titulo no valido: ", mensajeDe(rTitulo))
        assertEquals(1, m.buscar("").size)
    }

    @Test
    fun addCarpetaCreaYCuelga() {
        val m = BookmarkManager()
        m.add("Diario", "https://ejemplo.com/diario")
        val rc1 = m.addCarpeta("Noticias")
        val rc2 = m.addCarpeta("Deportes", "cf.1")
        val ra1 = m.add("N", "https://ejemplo.com/n", "cf.1")
        assertTrue(rc1 is Outcome.Success)
        assertEquals("cf.1", cfDe(rc1)?.id)
        assertTrue(rc2 is Outcome.Success)
        assertEquals("cf.2", cfDe(rc2)?.id)
        assertTrue(ra1 is Outcome.Success)
        assertEquals("bm.2", bmDe(ra1)?.id)
        assertEquals("cf.2,bm.2", idsDe(m.hijos("cf.1")))
    }

    @Test
    fun addCarpetaRechazaPadreYNombre() {
        val m = BookmarkManager()
        val rPadre = m.addCarpeta("X", "no.hay")
        val rNombre = m.addCarpeta("")
        assertEquals("carpeta padre no existe: no.hay", mensajeDe(rPadre))
        assertEquals("nombre no valido: ", mensajeDe(rNombre))
        assertEquals("root", idsDe(m.hijos("root")))
    }

    @Test
    fun renameYRenombrarCarpeta() {
        val m = BookmarkManager()
        m.add("Diario", "https://ejemplo.com/diario")
        m.addCarpeta("Noticias")
        val rr1 = m.rename("bm.1", "Diario 2")
        val rr2 = m.rename("x.1", "Y")
        val rr3 = m.renombrarCarpeta("cf.1", "News")
        val rr4 = m.renombrarCarpeta("root", "R")
        assertEquals("Diario 2", bmDe(rr1)?.titulo)
        assertEquals("marcador no existe: x.1", mensajeDe(rr2))
        assertEquals("News", cfDe(rr3)?.nombre)
        assertEquals("carpeta reservada: root", mensajeDe(rr4))
    }

    @Test
    fun moveReordenaEnLaMismaCarpeta() {
        val m = BookmarkManager()
        m.add("A", "https://a.com/a")
        m.add("B", "https://b.com/b")
        m.add("C", "https://c.com/c")
        val r = m.move("bm.2", "root", 0)
        assertTrue(r is Outcome.Success)
        assertEquals("bm.2,bm.1,bm.3", idsDe(m.hijos("root")))
    }

    @Test
    fun moveEntreCarpetas() {
        val m = BookmarkManager()
        m.add("A", "https://a.com/a")
        m.add("B", "https://b.com/b")
        m.add("C", "https://c.com/c")
        m.addCarpeta("Caja")
        val r = m.move("bm.1", "cf.1", 0)
        assertTrue(r is Outcome.Success)
        assertEquals("bm.1", idsDe(m.hijos("cf.1")))
        assertEquals("bm.2,bm.3,cf.1", idsDe(m.hijos("root")))
        assertEquals("cf.1", m.marcadorDe("bm.1")?.carpeta)
    }

    @Test
    fun moveCicloYReservadas() {
        val m = BookmarkManager()
        m.addCarpeta("Uno")
        m.addCarpeta("Dos", "cf.1")
        assertEquals("ciclo de carpetas: cf.1", mensajeDe(m.move("cf.1", "cf.1", 0)))
        assertEquals("ciclo de carpetas: cf.1", mensajeDe(m.move("cf.1", "cf.2", 0)))
        assertEquals("carpeta reservada: root", mensajeDe(m.move("root", "cf.1", 0)))
        assertEquals("elemento no existe: x.9", mensajeDe(m.move("x.9", "root", 0)))
    }

    @Test
    fun deleteQuitaMarcadorYDesfija() {
        val m = BookmarkManager()
        m.add("A", "https://a.com/a")
        m.pin("bm.1")
        val r = m.delete("bm.1")
        assertTrue(r is Outcome.Success)
        assertEquals(0, m.accesosRapidos().size)
        assertEquals("marcador no existe: bm.1", mensajeDe(m.rename("bm.1", "Z")))
    }

    @Test
    fun deleteCarpetaVaciaVaciaSolo() {
        val m = BookmarkManager()
        m.addCarpeta("Vacia")
        m.addCarpeta("Llena")
        m.add("A", "https://a.com/a", "cf.2")
        assertTrue(m.delete("cf.1") is Outcome.Success)
        assertEquals("carpeta no vacia: cf.2", mensajeDe(m.delete("cf.2")))
        assertEquals("carpeta reservada: root", mensajeDe(m.delete("root")))
        assertEquals("cf.2", idsDe(m.hijos("root")))
    }

    @Test
    fun buscarPorTituloYUrlCaseInsensitive() {
        val m = BookmarkManager()
        m.add("Diario", "https://EJEMPLO.com/diario")
        m.add("Deportes", "https://ejemplo.com/deportes")
        val porTitulo = m.buscar("diario")
        val porUrl = m.buscar("ejemplo.com/diario")
        assertEquals(1, porTitulo.size)
        assertEquals("bm.1", porTitulo[0].id)
        assertEquals(1, porUrl.size)
        assertEquals("https://EJEMPLO.com/diario", porUrl[0].url)
        assertEquals(0, m.buscar("zzz").size)
    }

    @Test
    fun pinUnpinAccesosRapidos() {
        val m = BookmarkManager()
        m.add("A", "https://a.com/a")
        m.add("B", "https://b.com/b")
        assertTrue(m.pin("bm.2") is Outcome.Success)
        assertTrue(m.pin("bm.1") is Outcome.Success)
        assertEquals("marcador ya fijado: bm.1", mensajeDe(m.pin("bm.1")))
        assertEquals("bm.2,bm.1", m.accesosRapidos().map { it.id }.joinToString(","))
        assertTrue(m.unpin("bm.2") is Outcome.Success)
        assertEquals("marcador no fijado: bm.2", mensajeDe(m.unpin("bm.2")))
        assertEquals("marcador no existe: x.9", mensajeDe(m.unpin("x.9")))
        assertEquals("bm.1", m.accesosRapidos().map { it.id }.joinToString(","))
    }

    @Test
    fun pinRechazaCarpetaYNoInstalado() {
        val m = BookmarkManager()
        m.addCarpeta("Caja")
        assertEquals("solo se fijan marcadores: cf.1", mensajeDe(m.pin("cf.1")))
        assertEquals("marcador no existe: x.9", mensajeDe(m.pin("x.9")))
    }

    @Test
    fun exportImportRoundtrip() {
        val clon = BookmarkManager()
        val imp = clon.importar(textoExport())
        assertTrue(imp is Outcome.Success)
        assertEquals("cf.2,cf.1,bm.3", idsDe(clon.hijos("root")))
        assertEquals("bm.2,bm.1", idsDe(clon.hijos("cf.1")))
        assertEquals("", idsDe(clon.hijos("cf.2")))
        assertEquals("bm.1,bm.2", clon.accesosRapidos().map { it.id }.joinToString(","))
        assertEquals("Trabajo", clon.carpetaDe("cf.1")?.nombre)
        assertEquals("cf.1", clon.marcadorDe("bm.2")?.carpeta)
        val nuevo = clon.add("Nuevo", "https://n.example.com")
        assertEquals("bm.4", bmDe(nuevo)?.id)
        val nuevaCf = clon.addCarpeta("Nueva")
        assertEquals("cf.3", cfDe(nuevaCf)?.id)
    }

    @Test
    fun importRechazaSinTocarEstado() {
        val m = BookmarkManager()
        m.add("Vivo", "https://v.com/v")
        val lineas = textoExport().split("\n").toMutableList()
        val idx = lineas.indexOfFirst { it.startsWith("M\tbm.3") }
        lineas.add(idx + 1, lineas[idx])
        val r = m.importar(lineas.joinToString("\n"))
        assertTrue(r is Outcome.Failure)
        assertEquals("id repetida: bm.3", mensajeDe(r))
        assertEquals("bm.1", idsDe(m.hijos("root")))
        assertEquals(1, m.buscar("vivo").size)
        assertEquals("bm.2", bmDe(m.add("Otro", "https://o.com/o"))?.id)
        val rPadre = m.importar("nia-bookmarks\t1\nC\tcf.9\tno.hay\tX\n")
        assertEquals("carpeta padre no existe: no.hay", mensajeDe(rPadre))
    }

    @Test
    fun importRechazaPadreEncabezadoYLinea() {
        val m = BookmarkManager()
        val rCiclo = m.importar("nia-bookmarks\t1\nC\tcf.1\tcf.2\tA\nC\tcf.2\tcf.1\tB\n")
        assertEquals("carpeta padre no existe: cf.2", mensajeDe(rCiclo))
        val rEnc = m.importar("nia-storage\t1\nC\tcf.1\troot\tA\n")
        assertEquals("encabezado no valido", mensajeDe(rEnc))
        val rLinea = m.importar("nia-bookmarks\t1\nX\tcf.1\troot\tA\n")
        assertEquals("linea desconocida: X", mensajeDe(rLinea))
        val rVacio = m.importar("")
        assertEquals("encabezado no valido", mensajeDe(rVacio))
    }

    @Test
    fun importRechazaHijoAjenoPinYOrden() {
        val m = BookmarkManager()
        val ajeno = "nia-bookmarks\t1\n" +
            "C\tcf.1\troot\tA\n" +
            "M\tbm.1\troot\tDiario\thttps://ejemplo.com/d\n" +
            "O\tcf.1\tbm.1\n"
        assertEquals("hijo ajeno a la carpeta: bm.1", mensajeDe(m.importar(ajeno)))
        val pinMalo = "nia-bookmarks\t1\n" +
            "C\tcf.1\troot\tA\n" +
            "M\tbm.1\troot\tDiario\thttps://ejemplo.com/d\n" +
            "O\troot\tcf.1\tbm.1\n" +
            "P\tcf.1\n"
        assertEquals("pin no valido: cf.1", mensajeDe(m.importar(pinMalo)))
        val incompleto = "nia-bookmarks\t1\n" +
            "M\tbm.1\troot\tDiario\thttps://ejemplo.com/d\n" +
            "M\tbm.2\troot\tOtro\thttps://ejemplo.com/o\n" +
            "O\troot\tbm.1\n"
        assertEquals("orden incompleto: root", mensajeDe(m.importar(incompleto)))
    }

    @Test
    fun importEscapaCampos() {
        val m = BookmarkManager()
        val escapes = "nia-bookmarks\t1\n" +
            "C\tcf.1\troot\tTa\\tb\\ny\\\\z\n" +
            "M\tbm.1\tcf.1\tTi\\ttulo\thttps://ejemplo.com/a\n" +
            "O\troot\tcf.1\n" +
            "O\tcf.1\tbm.1\n"
        val r = m.importar(escapes)
        assertTrue(r is Outcome.Success)
        assertEquals("Ta\tb\ny\\z", m.carpetaDe("cf.1")?.nombre)
        assertEquals("Ti\ttulo", m.marcadorDe("bm.1")?.titulo)
        val rMal = m.importar(escapes.replace("Ta\\tb\\ny\\\\z", "Ta\\qb"))
        assertEquals("escape invalido en linea 2", mensajeDe(rMal))
    }
}
