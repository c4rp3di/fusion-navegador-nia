package nia.engine.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas antes del push): los 12 tests cubren el roundtrip
 * Netscape HTML, fixtures estilo Firefox y Chrome, URLs
 * permitidas y titulos, los limites de tamano/entradas/
 * profundidad, el fallo que no altera la coleccion y la mezcla
 * con dedupe por url y carpetas reutilizadas por nombre.
 * T099 v1 (RES-0102).
 */
@Suppress("UNCHECKED_CAST")
class MarcadoresHtmlTest {

    private fun okDe(r: Outcome<ImportHtml>): ImportHtml? =
        (r as? Outcome.Success)?.value as? ImportHtml

    private fun hijosDe(m: BookmarkManager, carpeta: String): String =
        ((m.hijos(carpeta) as? Outcome.Success)?.value as? List<*>)?.joinToString(",") ?: ""

    private fun managerConArbol(): BookmarkManager {
        val m = BookmarkManager()
        m.addCarpeta("Trabajo")
        m.addCarpeta("Docs", "cf.1")
        m.add("Diario", "https://ejemplo.com/diario?a=1&b=2")
        m.add("Novedades", "https://news.example.com/n", "cf.1")
        m.add("Guia", "https://docs.example.com/g", "cf.2")
        return m
    }

    @Test
    fun roundtripExportImportHtml() {
        val m = managerConArbol()
        val html = MarcadoresHtml.escribir(m)
        val imp = okDe(MarcadoresHtml.parsear(html))
        assertTrue(imp != null)
        assertEquals(0, imp!!.rechazados)
        assertEquals(3, imp.entradas.size)
        assertEquals("Guia", imp.entradas[0].titulo)
        assertEquals("https://docs.example.com/g", imp.entradas[0].url)
        assertEquals("Trabajo,Docs", imp.entradas[0].ruta.joinToString(","))
        assertEquals("Novedades", imp.entradas[1].titulo)
        assertEquals("Trabajo", imp.entradas[1].ruta.joinToString(","))
        assertEquals("Diario", imp.entradas[2].titulo)
        assertEquals("https://ejemplo.com/diario?a=1&b=2", imp.entradas[2].url)
        assertEquals(0, imp.entradas[2].ruta.size)
    }

    @Test
    fun fixtureFirefoxMayusculasYAtributos() {
        val html = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n" +
            "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n" +
            "<TITLE>Bookmarks</TITLE>\n" +
            "<H1>Bookmarks</H1>\n" +
            "<DL><p>\n" +
            "    <DT><H3 ADD_DATE=\"123\">Lecturas</H3>\n" +
            "    <DL><p>\n" +
            "        <DT><A HREF=\"https://sitio.com/a\" ADD_DATE=\"456\" LAST_VISITED=\"78\">Articulo</A>\n" +
            "    </DL><p>\n" +
            "    <DT><A HREF=\"https://otro.com/b\">Otro</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        assertEquals(2, imp.entradas.size)
        assertEquals(0, imp.rechazados)
        assertEquals("Articulo", imp.entradas[0].titulo)
        assertEquals("https://sitio.com/a", imp.entradas[0].url)
        assertEquals("Lecturas", imp.entradas[0].ruta[0])
        assertEquals("Otro", imp.entradas[1].titulo)
        assertEquals(0, imp.entradas[1].ruta.size)
    }

    @Test
    fun fixtureChromeEntidadesYComillas() {
        val html = "<DL><p>\n" +
            "<DT><A HREF='https://a.com/?x=1&amp;y=2'>A &amp; B</A>\n" +
            "<DT><H3>Car&quot;peta</H3>\n" +
            "<DL><p>\n" +
            "<DT><A HREF=\"https://b.com/\">&lt;Titulo&gt;</A>\n" +
            "</DL><p>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        assertEquals(2, imp.entradas.size)
        assertEquals("https://a.com/?x=1&y=2", imp.entradas[0].url)
        assertEquals("A & B", imp.entradas[0].titulo)
        assertEquals("Car\"peta", imp.entradas[1].ruta[0])
        assertEquals("<Titulo>", imp.entradas[1].titulo)
    }

    @Test
    fun esquemasNoPermitidosSeRechazan() {
        val html = "<DL><p>\n" +
            "<DT><A HREF=\"javascript:alert(1)\">Uno</A>\n" +
            "<DT><A HREF=\"ftp://dos.com/\">Dos</A>\n" +
            "<DT><A HREF=\"about:blank\">Tres</A>\n" +
            "<DT><A HREF=\"https://cuatro.com/\">Cuatro</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        assertEquals(1, imp.entradas.size)
        assertEquals(3, imp.rechazados)
        assertEquals("https://cuatro.com/", imp.entradas[0].url)
    }

    @Test
    fun titulosInvalidosSeRechazan() {
        val largo = "T".repeat(65)
        val html = "<DL><p>\n" +
            "<DT><A HREF=\"https://a.com/\"></A>\n" +
            "<DT><A HREF=\"https://b.com/\">" + largo + "</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        assertEquals(0, imp.entradas.size)
        assertEquals(2, imp.rechazados)
    }

    @Test
    fun limiteDeEntradasEsFalloSinTocarColeccion() {
        val m = managerConArbol()
        val sb = StringBuilder("<DL><p>\n")
        for (i in 1..501) {
            sb.append("<DT><A HREF=\"https://m" + i + ".com/\">M" + i + "</A>\n")
        }
        sb.append("</DL><p>\n")
        val r = MarcadoresHtml.parsear(sb.toString())
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.QUOTA, (r as Outcome.Failure).error.code)
        assertEquals(3, m.total)
        assertEquals("cf.1,bm.1", hijosDe(m, "root"))
    }

    @Test
    fun limiteDeProfundidadEsFallo() {
        val sb = StringBuilder("<DL><p>\n")
        for (i in 1..9) {
            sb.append("<DT><H3>N" + i + "</H3>\n<DL><p>\n")
        }
        sb.append("<DT><A HREF=\"https://profundo.com/\">P</A>\n")
        sb.append("</DL><p>\n")
        val r = MarcadoresHtml.parsear(sb.toString())
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.QUOTA, (r as Outcome.Failure).error.code)
    }

    @Test
    fun limiteDeTamanoEsFallo() {
        val html = "<DL><p>\n" + "x".repeat(262145)
        val r = MarcadoresHtml.parsear(html)
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.QUOTA, (r as Outcome.Failure).error.code)
    }

    @Test
    fun htmlMalformadoNoAlteraLaColeccion() {
        val m = managerConArbol()
        val antes = m.exportar()
        val r = MarcadoresHtml.parsear("esto no es un archivo de marcadores")
        assertTrue(r is Outcome.Failure)
        assertEquals(ErrorCode.PARSE, (r as Outcome.Failure).error.code)
        assertEquals(antes, m.exportar())
        assertEquals(3, m.total)
    }

    @Test
    fun mezclaDeduplicaPorUrl() {
        val m = BookmarkManager()
        m.add("Vivo", "https://v.com/v")
        val html = "<DL><p>\n" +
            "<DT><A HREF=\"https://v.com/v\">Vivo copia</A>\n" +
            "<DT><A HREF=\"https://v.com/v\">Vivo otra</A>\n" +
            "<DT><A HREF=\"https://n.com/n\">Nuevo</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        val res = MarcadoresHtml.mezclar(m, imp)
        assertEquals(1, res.agregados)
        assertEquals(2, res.duplicados)
        assertEquals(0, res.fallidos)
        assertEquals(2, m.total)
        assertEquals("https://n.com/n", m.marcadorDe("bm.2")?.url)
    }

    @Test
    fun mezclaCreaYReutilizaCarpetas() {
        val m = BookmarkManager()
        val html = "<DL><p>\n" +
            "<DT><H3>Leer</H3>\n" +
            "<DL><p>\n" +
            "<DT><H3>Tarde</H3>\n" +
            "<DL><p>\n" +
            "<DT><A HREF=\"https://a.com/\">A</A>\n" +
            "</DL><p>\n" +
            "<DT><A HREF=\"https://b.com/\">B</A>\n" +
            "</DL><p>\n" +
            "<DT><A HREF=\"https://c.com/\">C</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        val r1 = MarcadoresHtml.mezclar(m, imp)
        assertEquals(3, r1.agregados)
        assertEquals(2, r1.carpetas)
        assertEquals(0, r1.duplicados)
        assertEquals(0, r1.fallidos)
        val r2 = MarcadoresHtml.mezclar(m, imp)
        assertEquals(0, r2.agregados)
        assertEquals(3, r2.duplicados)
        assertEquals(0, r2.carpetas)
        assertEquals("cf.1,bm.3", hijosDe(m, "root"))
        assertEquals("cf.2,bm.2", hijosDe(m, "cf.1"))
        assertEquals("bm.1", hijosDe(m, "cf.2"))
    }

    @Test
    fun ordenDeEntradasPreservado() {
        val m = BookmarkManager()
        val html = "<DL><p>\n" +
            "<DT><A HREF=\"https://uno.com/\">Uno</A>\n" +
            "<DT><A HREF=\"https://dos.com/\">Dos</A>\n" +
            "<DT><A HREF=\"https://tres.com/\">Tres</A>\n" +
            "</DL><p>\n"
        val imp = okDe(MarcadoresHtml.parsear(html))!!
        val res = MarcadoresHtml.mezclar(m, imp)
        assertEquals(3, res.agregados)
        assertEquals("bm.1,bm.2,bm.3", hijosDe(m, "root"))
        assertEquals("Uno", m.marcadorDe("bm.1")?.titulo)
    }
}
