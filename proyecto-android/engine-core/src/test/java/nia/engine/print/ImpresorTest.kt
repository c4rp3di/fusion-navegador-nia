package nia.engine.print

import nia.engine.html.DomNode
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del impresor (T098 v1, RES-0108): espejo validado contra el
 * gemelo TS (paginacion por franjas, bordes exactos, margenes, tope,
 * documento largo con orologio de cruces independiente). Los tests
 * construyen ops directas porque el impresor solo consume la
 * PaintScene; el nodo de cada op es un DomNode.Text testigo, el
 * impresor no lo mira.
 */
class ImpresorTest {

    private val altoUtil = 842.0 - 2 * 36.0

    private fun rect(y: Double, h: Double): PaintOp.DrawRect =
        PaintOp.DrawRect(0.0, y, 100.0, h, "#112233", DomNode.Text("r"))

    private fun texto(y: Double, t: String = "x"): PaintOp.DrawText =
        PaintOp.DrawText(0.0, y, 10.0, 12.0, t, "#000000", 16.0, DomNode.Text(t))

    private fun escena(ops: List<PaintOp>): PaintScene = PaintScene(ops)

    @Test
    fun cortoUnaPagina() {
        val d = Impresor.paginar(escena(listOf(texto(0.0))), Orientacion.VERTICAL)
        assertEquals(1, d.numeroPaginas, "una pagina")
        assertEquals(595.0, d.anchoPt, 0.01, "ancho A4")
        assertEquals(842.0, d.altoPt, 0.01, "alto A4")
        assertEquals(523.0, d.anchoUtilPt, 0.01, "ancho util")
        val op = d.paginas[0].ops[0] as PaintOp.DrawText
        assertEquals(36.0, op.x, 0.01, "margen x")
        assertEquals(36.0, op.y, 0.01, "margen y")
        assertEquals("x", op.text, "el texto viaja")
    }

    @Test
    fun largoPaginasExactas() {
        val ops = listOf(texto(0.0), texto(altoUtil), texto(2 * altoUtil))
        val d = Impresor.paginar(escena(ops), Orientacion.VERTICAL)
        assertEquals(3, d.numeroPaginas, "tres paginas")
        for (p in d.paginas) assertEquals(1, p.ops.size, "una op por pagina " + p.numero)
        assertEquals(36.0, (d.paginas[1].ops[0] as PaintOp.DrawText).y, 0.01, "pagina 2 en margen")
        assertEquals(36.0, (d.paginas[2].ops[0] as PaintOp.DrawText).y, 0.01, "pagina 3 en margen")
    }

    @Test
    fun margenesRespetados() {
        val ops = (0 until 200).map { texto(it * 40.0, "w" + it) }
        val d = Impresor.paginar(escena(ops), Orientacion.VERTICAL)
        for (p in d.paginas) {
            for (op in p.ops) {
                assertTrue(op.x >= 36.0 - 0.01, "x con margen")
                assertTrue(op.y < 36.0 + altoUtil + 0.01, "empieza antes del pie")
                assertTrue(op.y + op.height > 36.0 - 0.01, "interseca la franja")
            }
        }
    }

    @Test
    fun orientacionHorizontal() {
        val d = Impresor.paginar(escena(listOf(texto(0.0))), Orientacion.HORIZONTAL)
        assertEquals(842.0, d.anchoPt, 0.01, "ancho apaisado")
        assertEquals(595.0, d.altoPt, 0.01, "alto apaisado")
        assertEquals(595.0 - 2 * 36.0, d.altoUtilPt, 0.01, "alto util apaisado")
    }

    @Test
    fun fondoAtraviesaPaginas() {
        val d = Impresor.paginar(escena(listOf(rect(0.0, altoUtil * 2.5))), Orientacion.VERTICAL)
        assertEquals(3, d.numeroPaginas, "tres paginas")
        for (p in d.paginas) {
            assertEquals(1, p.ops.size, "fondo en la pagina " + p.numero)
            assertTrue(p.ops[0] is PaintOp.DrawRect, "es rect")
        }
    }

    @Test
    fun vacioUnaPagina() {
        val d = Impresor.paginar(escena(emptyList()), Orientacion.VERTICAL)
        assertEquals(1, d.numeroPaginas, "una pagina")
        assertEquals(0, d.paginas[0].ops.size, "vacia")
    }

    @Test
    fun topeDePaginas() {
        val ops = (0 until 200).map { texto(it * 40.0) }
        val d = Impresor.paginar(escena(ops), Orientacion.VERTICAL, maxPaginas = 3)
        assertEquals(3, d.numeroPaginas, "tope respetado")
    }

    @Test
    fun bordeExactoDeFranja() {
        // op que EMPIEZA justo en el corte: solo en la pagina siguiente
        val d1 = Impresor.paginar(escena(listOf(texto(altoUtil))), Orientacion.VERTICAL)
        assertEquals(2, d1.numeroPaginas, "dos paginas")
        assertEquals(0, d1.paginas[0].ops.size, "nada en la primera")
        assertEquals(1, d1.paginas[1].ops.size, "la op en la segunda")
        // op que TERMINA justo en el corte: NO cae en la siguiente
        val d2 = Impresor.paginar(escena(listOf(rect(0.0, altoUtil))), Orientacion.VERTICAL)
        assertEquals(1, d2.numeroPaginas, "una pagina")
        assertEquals(1, d2.paginas[0].ops.size, "termina dentro")
    }

    @Test
    fun documentoLargoEstructural() {
        // 5000 ops densas (y=3i, alto 12): cada corte k*altoUtil lo
        // cruzan las ops que EMPIEZAN en la ventana (corte-12, corte):
        // 3 o 4 multiplos de 3 caben ahi (regla independiente).
        val ops = (0 until 5000).map { texto(it * 3.0) }
        val d = Impresor.paginar(escena(ops), Orientacion.VERTICAL)
        assertEquals(20, d.numeroPaginas, "veinte paginas")
        var extra = 0
        for (k in 1 until 20) extra += if ((k * altoUtil) % 3.0 == 0.0) 3 else 4
        assertEquals(70, extra, "regla independiente: 3 o 4 ops por corte")
        val suma = d.paginas.sumOf { it.ops.size }
        assertEquals(5000 + extra, suma, "toda op presente, los cruces duplican")
    }

    @Test
    fun opsOriginalesNoMutados() {
        val originales = listOf(rect(0.0, 10.0), texto(0.0), texto(altoUtil - 2.0))
        val copia = originales.toMutableList()
        Impresor.paginar(escena(originales), Orientacion.VERTICAL)
        assertEquals(originales, copia, "la lista de ops no cambia")
        assertEquals(0.0, (originales[1] as PaintOp.DrawText).y, 0.01, "y original intacta")
        assertEquals(altoUtil - 2.0, (originales[2] as PaintOp.DrawText).y, 0.01, "y original intacta 2")
    }

    @Test
    fun margenAbsurdoSeAcota() {
        val d = Impresor.paginar(escena(listOf(texto(0.0))), Orientacion.VERTICAL, margenPt = 10000.0)
        assertEquals(595.0 / 2 - 1, d.margenPt, 0.01, "margen acotado a media hoja")
        assertTrue(d.numeroPaginas >= 1, "siempre imprimible")
    }
}
