package nia.engine.seleccion

import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.dom.DomDocument
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la seleccion de texto (T079 v1, RES-0095): espejo del gemelo
 * TS validado antes del push. Los fragmentos son una caja por palabra
 * en orden documental, el anclaje coge el mas cercano en radio con
 * offset redondeado y JAMAS parte surrogates, la palabra bajo el punto
 * se selecciona entera, el texto entre nodos colapsa con un espacio y
 * recorta parcial en los extremos, las cajas recortan por offset y el
 * fragmento :~:text= codifica en UTF-8 y cae a URL sola si el texto no
 * es anclable. El ultimo test recorre el pipeline REAL del motor.
 */
class SeleccionTest {

    private fun nodo(): DomNode {
        val doc = DomDocument.parse("<p>base</p>")
        return doc.nodeOf(doc.rootHandle())
    }

    /** Fragmento de fs=20 (anchoChar=10) con el texto medido por el motor. */
    private fun frag(nodo: DomNode, texto: String, x: Double, y: Double): FragmentoTexto {
        val fs = 20.0
        val op = PaintOp.DrawText(x, y, texto.length * fs / 2.0, fs, texto, "#000000", fs, nodo)
        return Fragmentos.de(PaintScene(listOf(op)))[0]
    }

    private fun fragsBase(): List<FragmentoTexto> {
        // UNA sola escena para las tres palabras: los indices
        // documentales del extractor solo existen dentro de una
        // escena (una escena por palabra daba indice 0 a todas).
        val n = nodo()
        val fs = 20.0
        val ops = listOf(
            PaintOp.DrawText(0.0, 0.0, "Hola".length * fs / 2.0, fs, "Hola", "#000000", fs, n),
            PaintOp.DrawText(50.0, 0.0, "mundo".length * fs / 2.0, fs, "mundo", "#000000", fs, n),
            PaintOp.DrawText(110.0, 0.0, "cruel".length * fs / 2.0, fs, "cruel", "#000000", fs, n)
        )
        return Fragmentos.de(PaintScene(ops))
    }

    // ---- Fragmentos: orden documental, solo DrawText ----

    @Test
    fun fragmentosOrdenDocumentalYFiltraRects() {
        val n = nodo()
        val rect = PaintOp.DrawRect(0.0, 0.0, 100.0, 20.0, "#ffffff", n)
        val t1 = PaintOp.DrawText(0.0, 0.0, 40.0, 20.0, "Hola", "#000000", 20.0, n)
        val t2 = PaintOp.DrawText(50.0, 0.0, 50.0, 20.0, "mundo", "#000000", 20.0, n)
        val frags = Fragmentos.de(PaintScene(listOf(rect, t1, t2)))
        assertEquals(2, frags.size)
        assertEquals(0, frags[0].indice)
        assertEquals(1, frags[1].indice)
        assertEquals("Hola", frags[0].texto)
        assertEquals(10.0, frags[0].anchoChar, 0.0001)
    }

    // ---- Anclaje: cercania, radio, offset y snap ----

    @Test
    fun anclarDentroDeRadioDaElMasCercano() {
        val frags = fragsBase()
        val pos = Seleccion.anclarMasCercano(frags, 55.0, 5.0, 64.0)
        assertEquals(1, pos!!.fragmento)
        assertEquals(1, pos.offset)
    }

    @Test
    fun anclarFueraDeRadioEsNull() {
        val frags = fragsBase()
        assertNull(Seleccion.anclarMasCercano(frags, 55.0, 300.0, 64.0))
    }

    @Test
    fun anclarRadioEsInclusivo() {
        val frags = fragsBase()
        // frag 2: x 110..160; distancia exacta 20 desde abajo
        val pos = Seleccion.anclarMasCercano(frags, 135.0, 40.0, 20.0)
        assertEquals(2, pos!!.fragmento)
    }

    @Test
    fun anclarOffsetRedondeaYClampea() {
        val frags = fragsBase()
        assertEquals(2, Seleccion.anclarMasCercano(frags, 24.0, 5.0, 8.0)!!.offset)
        assertEquals(0, Seleccion.anclarMasCercano(frags, -3.0, 5.0, 8.0)!!.offset)
        assertEquals(4, Seleccion.anclarMasCercano(frags, 45.0, 5.0, 8.0)!!.offset)
    }

    @Test
    fun anclarNuncaParteSurrogates() {
        val n = nodo()
        val f = frag(n, "a\uD83D\uDE00b", 0.0, 0.0)
        val pos = Seleccion.anclarMasCercano(listOf(f), 24.0, 5.0, 64.0)
        assertTrue(pos!!.offset == 2 || pos.offset == 3)
        assertEquals(3, Seleccion.snap(f, 2))
    }

    // ---- Palabra bajo el punto ----

    @Test
    fun palabraBajoDaLaPalabraCompleta() {
        val frags = fragsBase()
        val sel = Seleccion.palabraBajo(frags, 55.0, 10.0)
        assertEquals(1, sel!!.first.fragmento)
        assertEquals(0, sel.first.offset)
        assertEquals(5, sel.second.offset)
    }

    @Test
    fun palabraBajoFueraDelRectEsNull() {
        val frags = fragsBase()
        assertNull(Seleccion.palabraBajo(frags, 45.0, 10.0))
    }

    // ---- Normalizacion y texto entre nodos ----

    @Test
    fun normalizarOrdenaPorDocumento() {
        val a = PosSeleccion(2, 1)
        val b = PosSeleccion(0, 4)
        val par = Seleccion.normalizar(a, b)
        assertEquals(0, par.first.fragmento)
        assertEquals(2, par.second.fragmento)
    }

    @Test
    fun textoEntreNodosColapsaConUnEspacio() {
        val frags = fragsBase()
        val t = Seleccion.texto(frags, PosSeleccion(0, 0), PosSeleccion(2, 5))
        assertEquals("Hola mundo cruel", t)
        assertEquals(t, Seleccion.texto(frags, PosSeleccion(2, 5), PosSeleccion(0, 0)))
    }

    @Test
    fun textoParcialEnLosExtremos() {
        val frags = fragsBase()
        val t = Seleccion.texto(frags, PosSeleccion(0, 2), PosSeleccion(1, 3))
        assertEquals("la mun", t)
    }

    @Test
    fun textoVacioSiElRangoNoExiste() {
        val frags = fragsBase()
        assertEquals("", Seleccion.texto(frags, PosSeleccion(7, 0), PosSeleccion(9, 0)))
        assertEquals("", Seleccion.texto(frags, PosSeleccion(0, 2), PosSeleccion(0, 2)))
    }

    // ---- Cajas y handles ----

    @Test
    fun cajasRecortanPorOffsetEnLosExtremos() {
        val frags = fragsBase()
        val cajas = Seleccion.cajas(frags, PosSeleccion(0, 2), PosSeleccion(1, 3))
        assertEquals(2, cajas.size)
        assertEquals(20.0, cajas[0].x, 0.0001)
        assertEquals(20.0, cajas[0].width, 0.0001)
        assertEquals(50.0, cajas[1].x, 0.0001)
        assertEquals(30.0, cajas[1].width, 0.0001)
    }

    @Test
    fun puntoHandleEnElOffsetDelAncla() {
        val frags = fragsBase()
        val p = Seleccion.punto(frags, PosSeleccion(1, 3))
        assertEquals(80.0, p.x, 0.0001)
        assertEquals(20.0, p.y, 0.0001)
    }

    // ---- Fragmento de URL ----

    @Test
    fun fragmentoUrlAnclaElTexto() {
        assertEquals(
            "https://x.test/p#:~:text=Hola%20mundo",
            FragmentoUrl.crear("https://x.test/p", "Hola mundo")
        )
    }

    @Test
    fun fragmentoUrlCodificaUtf8() {
        // trim de extremos; enye multibyte; unreserved pasan tal cual
        assertEquals(
            "u#:~:text=a%C3%B1o-~ok",
            FragmentoUrl.crear("u", " a\u00f1o-~ok ")
        )
        // surrogate: el emoji va entero (4 bytes UTF-8)
        assertEquals(
            "u#:~:text=%F0%9F%98%80",
            FragmentoUrl.crear("u", "\uD83D\uDE00")
        )
    }

    @Test
    fun fragmentoUrlCaeAUrlSolaSiNoEsAnclable() {
        val u = "https://x.test/p"
        assertEquals(u, FragmentoUrl.crear(u, "   "))
        assertEquals(u, FragmentoUrl.crear(u, "linea\nrota"))
        assertEquals(u, FragmentoUrl.crear(u, "x".repeat(301)))
        assertEquals(u, FragmentoUrl.crear(u, ""))
    }

    // ---- Pipeline real: seleccion sobre el motor de verdad ----

    @Test
    fun pipelineRealProduceFragmentosSeleccionables() {
        val doc = DomDocument.parse("<p>Hola mundo cruel</p>")
        val root = doc.nodeOf(doc.rootHandle()) as DomNode.Document
        val sheet = CssStylesheet.parse("p { font-size: 20px; }")
        val computed = CssCascade.compute(root, sheet)
        val layout = LayoutEngine.layout(root, computed)
        val scene = PaintSceneBuilder.build(root, computed, layout)
        val frags = Fragmentos.de(scene)
        assertEquals(3, frags.size)
        assertEquals(
            "Hola mundo cruel",
            Seleccion.texto(frags, PosSeleccion(0, 0), PosSeleccion(2, frags[2].largo))
        )
        val palabra = Seleccion.palabraBajo(frags, frags[1].x + 1.0, frags[1].y + 1.0)
        assertEquals("mundo", Seleccion.texto(frags, palabra!!.first, palabra.second))
    }
}
