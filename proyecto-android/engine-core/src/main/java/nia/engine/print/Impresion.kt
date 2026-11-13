package nia.engine.print

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene

/**
 * Impresion (T098 v1, RES-0108): paginacion por franjas horizontales
 * de la escena del motor. El documento impreso sale del MISMO render
 * (ops de pintura re-maquetadas al ancho util de la hoja), jamas de
 * una captura de la vista. Sin bitmaps: solo coordenadas y ops, la
 * memoria crece con el numero de paginas (tope maxPaginas). Los
 * puntos se tratan como pixeles logicos del motor (v1).
 */

/** Orientacion de la hoja. */
enum class Orientacion {
    VERTICAL,
    HORIZONTAL
}

/** Una pagina fisica: numero 0-based y las ops que la intersecan. */
class PaginaImpresa internal constructor(val numero: Int, val ops: List<PaintOp>)

/** Documento paginado listo para pintar pagina a pagina. */
class DocumentoPaginado(
    val anchoPt: Double,
    val altoPt: Double,
    val margenPt: Double,
    val paginas: List<PaginaImpresa>
) {
    val numeroPaginas: Int get() = paginas.size
    val anchoUtilPt: Double get() = anchoPt - 2 * margenPt
    val altoUtilPt: Double get() = altoPt - 2 * margenPt
}

object Impresor {

    const val A4_ANCHO_PT = 595.0
    const val A4_ALTO_PT = 842.0
    const val MARGEN_DEFECTO_PT = 36.0
    const val MAX_PAGINAS_DEFECTO = 2000

    /**
     * Reparte las ops de la escena en franjas de altoUtil. Una op que
     * cruza un corte aparece en las paginas que interseca (cada copia
     * desplazada a su franja): el fondo de la pagina viaja asi de
     * pagina en pagina y el corte roza el margen superior de la
     * siguiente. El tope maxPaginas acota la memoria: lo que queda
     * mas alla se trunca (exclusion honesta).
     */
    fun paginar(
        escena: PaintScene,
        orientacion: Orientacion,
        margenPt: Double = MARGEN_DEFECTO_PT,
        maxPaginas: Int = MAX_PAGINAS_DEFECTO
    ): DocumentoPaginado {
        val ancho = if (orientacion == Orientacion.VERTICAL) A4_ANCHO_PT else A4_ALTO_PT
        val alto = if (orientacion == Orientacion.VERTICAL) A4_ALTO_PT else A4_ANCHO_PT
        val margen = max(0.0, minOf(margenPt, ancho / 2 - 1, alto / 2 - 1))
        val altoUtil = alto - 2 * margen
        var maxY = 0.0
        for (op in escena.ops) {
            val fin = op.y + op.height
            if (fin > maxY) maxY = fin
        }
        val nDeseado = if (maxY <= 0.0) 1 else ceil(maxY / altoUtil).toInt()
        val n = max(1, minOf(nDeseado, maxPaginas))
        val paginas = ArrayList<ArrayList<PaintOp>>(n)
        for (i in 0 until n) paginas.add(ArrayList())
        for (op in escena.ops) {
            val primera = floor(max(0.0, op.y) / altoUtil).toInt()
            val fin = op.y + op.height
            var ultima =
                if (fin % altoUtil == 0.0) (fin / altoUtil).toInt() - 1
                else floor(fin / altoUtil).toInt()
            if (ultima < primera) ultima = primera
            var p = primera
            while (p <= ultima && p < n) {
                paginas[p].add(desplazar(op, p * altoUtil, margen))
                p++
            }
        }
        val fuera = ArrayList<PaginaImpresa>(n)
        for (i in 0 until n) fuera.add(PaginaImpresa(i, paginas[i]))
        return DocumentoPaginado(ancho, alto, margen, fuera)
    }

    /** Copia desplazada: la escena es del render vivo, JAMAS se muta. */
    private fun desplazar(op: PaintOp, inicioAlto: Double, margen: Double): PaintOp =
        when (op) {
            is PaintOp.DrawRect -> PaintOp.DrawRect(
                op.x + margen,
                op.y - inicioAlto + margen,
                op.width,
                op.height,
                op.color,
                op.node
            )
            is PaintOp.DrawText -> PaintOp.DrawText(
                op.x + margen,
                op.y - inicioAlto + margen,
                op.width,
                op.height,
                op.text,
                op.color,
                op.fontSize,
                op.node
            )
            // T111 v8 (TK-111-5, R0448, RES-0176): la imagen viaja intacta.
            is PaintOp.DrawImage -> PaintOp.DrawImage(
                op.x + margen,
                op.y - inicioAlto + margen,
                op.width,
                op.height,
                op.data,
                op.node
            )
        }
}

