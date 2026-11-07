package nia.engine.busqueda

import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.dom.DomDocument
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder
import nia.engine.seleccion.FragmentoTexto
import nia.engine.seleccion.Fragmentos
import nia.engine.seleccion.PosSeleccion
import nia.engine.seleccion.Seleccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de buscar en pagina (T081 v1, RES-0096): espejo del gemelo TS
 * (27 checks en verde antes del push). Los fragmentos se construyen en
 * UNA sola escena (leccion R0202: los indices documentales solo
 * existen dentro de una escena), el texto virtual une las palabras con
 * un espacio como el layout y las coincidencias pueden cruzar nodos;
 * greedy no solapado, case-insensitive simple por unidad UTF-16,
 * limites MAX_CONSULTA/MAX_COINCIDENCIAS, navegacion circular y el
 * pipeline REAL del motor al final.
 */
class BusquedaTest {

    private fun nodo(): DomNode {
        val doc = DomDocument.parse("<p>base</p>")
        return doc.nodeOf(doc.rootHandle())
    }

    /** UNA escena con las palabras dadas, fs=20 (anchoChar=10) y
     *  hueco de 10px entre palabras (como el layout del gemelo). */
    private fun frags(vararg palabras: String): List<FragmentoTexto> {
        val n = nodo()
        val fs = 20.0
        val ops = ArrayList<PaintOp.DrawText>()
        var x = 0.0
        for (p in palabras) {
            ops.add(PaintOp.DrawText(x, 0.0, p.length * fs / 2.0, fs, p, "#000000", fs, n))
            x += p.length * fs / 2.0 + 10.0
        }
        return Fragmentos.de(PaintScene(ops))
    }

    @Test
    fun consultaVaciaOBlancaDevuelveVacio() {
        assertTrue(Busqueda.buscar(frags("Hola", "mundo"), "").isEmpty())
        assertTrue(Busqueda.buscar(frags("Hola", "mundo"), "   ").isEmpty())
    }

    @Test
    fun consultaLargaDevuelveVacio() {
        assertTrue(Busqueda.buscar(frags("Hola"), "x".repeat(201)).isEmpty())
    }

    @Test
    fun matchDentroDePalabra() {
        val m = Busqueda.buscar(frags("Hola", "mundo"), "ola")
        assertEquals(1, m.size)
        assertEquals(PosSeleccion(0, 1), m[0].inicio)
        assertEquals(PosSeleccion(0, 4), m[0].fin)
    }

    @Test
    fun matchEntreNodosCruzaElEspacio() {
        val f = frags("Hola", "mundo", "cruel")
        val m = Busqueda.buscar(f, "la mun")
        assertEquals(1, m.size)
        assertEquals(PosSeleccion(0, 2), m[0].inicio)
        assertEquals(PosSeleccion(1, 3), m[0].fin)
        assertEquals("la mun", Seleccion.texto(f, m[0].inicio, m[0].fin))
        val cajas = Seleccion.cajas(f, m[0].inicio, m[0].fin)
        assertEquals(2, cajas.size)
        assertEquals(20.0, cajas[0].x, 0.0001)
        assertEquals(20.0, cajas[0].width, 0.0001)
        assertEquals(50.0, cajas[1].x, 0.0001)
        assertEquals(30.0, cajas[1].width, 0.0001)
    }

    @Test
    fun caseInsensitiveSimple() {
        val f = frags("Hola", "mundo")
        val m = Busqueda.buscar(f, "HOLA MUN")
        assertEquals(1, m.size)
        assertEquals("Hola mun", Seleccion.texto(f, m[0].inicio, m[0].fin))
    }

    @Test
    fun matchesNoSolapadasGreedy() {
        assertEquals(2, Busqueda.buscar(frags("aaaa"), "aa").size)
        assertEquals(1, Busqueda.buscar(frags("aaa"), "aaa").size)
    }

    @Test
    fun limiteDeCoincidencias() {
        assertEquals(500, Busqueda.buscar(frags("a".repeat(1000)), "a").size)
    }

    @Test
    fun enyeYAcentos() {
        assertEquals(1, Busqueda.buscar(frags("a\u00f1o"), "A\u00d1O").size)
    }

    @Test
    fun emojiParCompleto() {
        val m = Busqueda.buscar(frags("a\uD83D\uDE00b"), "\uD83D\uDE00")
        assertEquals(1, m.size)
        assertEquals(PosSeleccion(0, 1), m[0].inicio)
        assertEquals(PosSeleccion(0, 3), m[0].fin)
    }

    @Test
    fun todoElTexto() {
        val m = Busqueda.buscar(frags("Hola", "mundo", "cruel"), "Hola mundo cruel")
        assertEquals(1, m.size)
        assertEquals(PosSeleccion(0, 0), m[0].inicio)
        assertEquals(PosSeleccion(2, 5), m[0].fin)
    }

    @Test
    fun espaciosInternosExactos() {
        assertEquals(1, Busqueda.buscar(frags("Hola", "mundo"), "hola mundo").size)
        // el texto virtual colapsa con UN espacio: dos nunca encajan
        assertEquals(0, Busqueda.buscar(frags("Hola", "mundo"), "hola  mundo").size)
    }

    @Test
    fun trimDeLaConsulta() {
        assertEquals(1, Busqueda.buscar(frags("Hola", "mundo", "cruel"), "  mundo  ").size)
    }

    @Test
    fun navegarCircular() {
        assertEquals(-1, Busqueda.navegar(0, -1, 1))
        assertEquals(0, Busqueda.navegar(3, -1, 1))
        assertEquals(0, Busqueda.navegar(3, 2, 1))
        assertEquals(2, Busqueda.navegar(3, 0, -1))
        assertEquals(0, Busqueda.navegar(1, 0, -1))
    }

    @Test
    fun sinFragmentosDevuelveVacio() {
        assertTrue(Busqueda.buscar(emptyList(), "x").isEmpty())
    }

    @Test
    fun matchesEnFragmentosDistintos() {
        val f = frags("gato", "persigue", "gato", "raton")
        val m = Busqueda.buscar(f, "gato")
        assertEquals(2, m.size)
        assertEquals(0, m[0].inicio.fragmento)
        assertEquals(2, m[1].inicio.fragmento)
        val u = Busqueda.buscar(f, "persigue gato")
        assertEquals(1, u.size)
        assertEquals("persigue gato", Seleccion.texto(f, u[0].inicio, u[0].fin))
    }

    @Test
    fun pipelineRealBuscaEntreNodos() {
        val doc = DomDocument.parse("<p>Hola mundo cruel</p>")
        val root = doc.nodeOf(doc.rootHandle()) as DomNode.Document
        val sheet = CssStylesheet.parse("p { font-size: 20px; }")
        val computed = CssCascade.compute(root, sheet)
        val layout = LayoutEngine.layout(root, computed)
        val scene = PaintSceneBuilder.build(root, computed, layout)
        val frags = Fragmentos.de(scene)
        val m = Busqueda.buscar(frags, "MUNDO CRUEL")
        assertEquals(1, m.size)
        assertEquals(1, m[0].inicio.fragmento)
        assertEquals(2, m[0].fin.fragmento)
        assertEquals("mundo cruel", Seleccion.texto(frags, m[0].inicio, m[0].fin))
    }
}
