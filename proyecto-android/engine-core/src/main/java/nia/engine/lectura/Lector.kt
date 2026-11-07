package nia.engine.lectura

import nia.engine.dom.DomDocument
import nia.engine.html.DomNode

/**
 * T082 v1 (R0191, RES-0092): modo lectura con heuristica DOM propia.
 *
 * El Lector es puro JVM y trabaja sobre el DOM REAL del motor
 * (DomDocument.parse de T012/T013), nunca sobre texto suelto: elige el
 * contenedor de contenido por prioridad (article, main, body) y junta
 * los parrafos <p> con texto suficiente, colapsando espacios. Los
 * limites son honestos y documentados: 500 parrafos, 4000 caracteres
 * por parrafo y 200 para el titulo del <title>.
 *
 * Sin contenido principal la lectura es vacia (el shell avisa, no
 * inventa). Los scripts extraidos NUNCA se ejecutan: el Lector solo
 * junta texto de los nodos Text del subarbol de cada <p>.
 */
object Lector {

    /** Lectura extraida: titulo del documento y parrafos del contenido. */
    data class Lectura(val titulo: String, val parrafos: List<String>)

    private const val MIN_PARAFO = 40
    private const val MAX_PARRAFOS = 500
    private const val MAX_LARGO_PARRAFO = 4000
    private const val MAX_TITULO = 200
    private val CONTENEDORES = listOf("article", "main", "body")

    /** Extrae la lectura del HTML con el DOM real del motor. */
    fun leer(html: String): Lectura {
        val doc = DomDocument.parse(html)
        val tituloHandle = doc.findFirst("title")
        val titulo = if (tituloHandle == null) {
            ""
        } else {
            recortar(normalizar(textoDe(doc.nodeOf(tituloHandle))), MAX_TITULO)
        }
        val parrafos = ArrayList<String>()
        recolectar(contenedorDe(doc), parrafos)
        return Lectura(titulo, parrafos)
    }

    /** Primer elemento contenedor por prioridad; si no hay, la raiz. */
    private fun contenedorDe(doc: DomDocument): DomNode {
        for (nombre in CONTENEDORES) {
            val h = doc.findFirst(nombre)
            if (h != null) {
                return doc.nodeOf(h)
            }
        }
        return doc.nodeOf(doc.rootHandle())
    }

    /** Recorre en pre-orden y junta los <p> con texto suficiente. */
    private fun recolectar(node: DomNode, salida: ArrayList<String>) {
        if (salida.size >= MAX_PARRAFOS) {
            return
        }
        if (node is DomNode.Element && node.name == "p") {
            val texto = normalizar(textoDe(node))
            if (texto.length >= MIN_PARAFO) {
                salida.add(recortar(texto, MAX_LARGO_PARRAFO))
            }
        }
        when (node) {
            is DomNode.Document -> for (c in node.children) recolectar(c, salida)
            is DomNode.Element -> for (c in node.children) recolectar(c, salida)
            else -> {
            }
        }
    }

    /** Texto concatenado de todos los Text del subarbol. */
    private fun textoDe(node: DomNode): String {
        val sb = StringBuilder()
        acumular(node, sb)
        return sb.toString()
    }

    private fun acumular(node: DomNode, sb: StringBuilder) {
        when (node) {
            is DomNode.Text -> sb.append(node.data)
            is DomNode.Document -> for (c in node.children) acumular(c, sb)
            is DomNode.Element -> for (c in node.children) acumular(c, sb)
            else -> {
            }
        }
    }

    /** Colapsa espacios y recorta los extremos. */
    private fun normalizar(s: String): String =
        s.trim().replace(Regex("\\s+"), " ")

    private fun recortar(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max)
}
