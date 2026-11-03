package nia.engine.dom

import nia.engine.html.DomNode
import nia.engine.html.DomPretty
import nia.engine.html.HtmlTreeBuilder

/**
 * Excepcion tipificada del DOM (T013 v1). El codigo identifica el fallo:
 * stale-handle, node-not-found, node-already-attached, invalid-parent,
 * not-a-child, wrong-node-type, invalid-attribute-name, attribute-not-found.
 */
class DomException(val code: String, message: String) : Exception(message)

/** Handle estable de un nodo: documento + generacion + id de nodo (pre-orden). */
data class DomHandle(val documentId: String, val generation: Long, val nodeId: Int)

/** Snapshot inmutable del documento: generacion, revision y arbol serializado. */
data class DomSnapshot(
    val documentId: String,
    val generation: Long,
    val revision: Long,
    val tree: String
)

/**
 * Documento DOM (T013 v1): envuelve el arbol que produce HtmlTreeBuilder y
 * expone creacion de nodos y mutaciones con handles estables. Los ids se
 * asignan en pre-orden al parsear; cada handle lleva la generacion del
 * documento, de modo que tras invalidate() (p. ej. al navegar) los handles
 * anteriores caducan con "stale-handle". La revision solo la incrementan las
 * mutaciones exitosas: los snapshots (revision + arbol con DomPretty) son
 * inmutables y comparables entre si.
 *
 * removeChild desregistra el subarbol completo (los handles de los nodos
 * internos dejan de resolver con "node-not-found"). Los nodos creados con
 * createElement/createTextNode/createComment nacen detached y no incrementan
 * la revision hasta que se attachan.
 *
 * Fuera de alcance v1: eventos/bindings (T024), snapshots incrementales
 * (T017) y setData de comentarios.
 */
class DomDocument private constructor(val documentId: String, private val root: DomNode.Document) {

    companion object {
        private var nextDocumentNumber = 0L

        /** Parsea HTML via HtmlTreeBuilder y registra el arbol en pre-orden. */
        fun parse(input: String): DomDocument {
            val result = HtmlTreeBuilder.parse(input)
            val doc = DomDocument("dom-" + (++nextDocumentNumber), result.document)
            doc.registerPreOrder(result.document, -1)
            return doc
        }
    }

    private var generation = 1L
    private var revision = 0L
    private var nextNodeId = 0

    private val registry = HashMap<Int, DomNode>()
    private val ids = HashMap<DomNode, Int>()
    private val parentIds = HashMap<Int, Int>()

    /** Invalida todos los handles actuales (bump de generacion). */
    fun invalidate() {
        generation++
    }

    /** Snapshot inmutable del estado actual (generacion + revision + arbol). */
    fun snapshot(): DomSnapshot =
        DomSnapshot(documentId, generation, revision, DomPretty.stringify(root))

    /** Handle fresco del nodo raiz (document). */
    fun rootHandle(): DomHandle =
        DomHandle(documentId, generation, requireNotNull(ids[root]))

    /** Crea un elemento detached (no incrementa la revision). */
    fun createElement(name: String): DomHandle =
        registerDetached(DomNode.Element(name, emptyMap()))

    /** Crea un nodo de texto detached (no incrementa la revision). */
    fun createTextNode(data: String): DomHandle =
        registerDetached(DomNode.Text(data))

    /** Crea un comentario detached (no incrementa la revision). */
    fun createComment(data: String): DomHandle =
        registerDetached(DomNode.Comment(data))

    /**
     * Attacha child al final de parent. child debe estar detached
     * ("node-already-attached") y parent debe ser Element o Document
     * ("invalid-parent"). El nodo raiz no puede ser hijo ("invalid-parent").
     */
    fun appendChild(parent: DomHandle, child: DomHandle) {
        val parentNode = fresh(parent)
        val childNode = fresh(child)
        if (childNode is DomNode.Document) {
            throw DomException("invalid-parent", "el nodo raiz no puede ser hijo")
        }
        if (parentIds.containsKey(child.nodeId)) {
            throw DomException("node-already-attached", "el nodo ya tiene padre")
        }
        val pc = childrenListOf(parentNode)
            ?: throw DomException("invalid-parent", "el padre no admite hijos")
        pc.add(childNode)
        parentIds[child.nodeId] = parent.nodeId
        revision++
    }

    /**
     * Inserta child antes de ref dentro de parent. ref debe ser hijo de
     * parent ("not-a-child"); el resto de reglas como appendChild.
     */
    fun insertBefore(parent: DomHandle, child: DomHandle, ref: DomHandle) {
        val parentNode = fresh(parent)
        val childNode = fresh(child)
        val refNode = fresh(ref)
        if (childNode is DomNode.Document) {
            throw DomException("invalid-parent", "el nodo raiz no puede ser hijo")
        }
        if (parentIds.containsKey(child.nodeId)) {
            throw DomException("node-already-attached", "el nodo ya tiene padre")
        }
        val pc = childrenListOf(parentNode)
            ?: throw DomException("invalid-parent", "el padre no admite hijos")
        val idx = pc.indexOf(refNode)
        if (idx < 0) {
            throw DomException("not-a-child", "ref no es hijo de parent")
        }
        pc.add(idx, childNode)
        parentIds[child.nodeId] = parent.nodeId
        revision++
    }

    /**
     * Desattacha child de parent y desregistra su subarbol completo: los
     * handles de los nodos internos dejan de resolver ("node-not-found").
     */
    fun removeChild(parent: DomHandle, child: DomHandle) {
        val parentNode = fresh(parent)
        val childNode = fresh(child)
        val pc = childrenListOf(parentNode)
            ?: throw DomException("invalid-parent", "el padre no admite hijos")
        val idx = pc.indexOf(childNode)
        if (idx < 0) {
            throw DomException("not-a-child", "child no es hijo de parent")
        }
        pc.removeAt(idx)
        deregister(child.nodeId, childNode)
        revision++
    }

    /** Reemplaza el texto de un nodo Text ("wrong-node-type" en otro caso). */
    fun setText(handle: DomHandle, data: String) {
        val node = fresh(handle)
        if (node !is DomNode.Text) {
            throw DomException("wrong-node-type", "setText exige un nodo Text")
        }
        node.data = data
        revision++
    }

    /**
     * Fija un atributo de un Element. El nombre no puede estar vacio ni
     * contener espacio ASCII, '/', '>', '=', '<', '\"' o '\'' (codigo
     * "invalid-attribute-name"). Sobrescribir conserva la posicion original
     * del atributo (orden de llegada preservado).
     */
    fun setAttribute(handle: DomHandle, name: String, value: String) {
        val node = fresh(handle)
        if (node !is DomNode.Element) {
            throw DomException("wrong-node-type", "setAttribute exige un Element")
        }
        if (!isValidAttrName(name)) {
            throw DomException("invalid-attribute-name", "nombre de atributo invalido")
        }
        node.attributes[name] = value
        revision++
    }

    /** Elimina un atributo existente ("attribute-not-found" si no existe). */
    fun removeAttribute(handle: DomHandle, name: String) {
        val node = fresh(handle)
        if (node !is DomNode.Element) {
            throw DomException("wrong-node-type", "removeAttribute exige un Element")
        }
        if (!node.attributes.containsKey(name)) {
            throw DomException("attribute-not-found", "el atributo no existe")
        }
        node.attributes.remove(name)
        revision++
    }

    /** Handles de los hijos de un Element o Document ("wrong-node-type" en otro caso). */
    fun childrenOf(handle: DomHandle): List<DomHandle> {
        val node = fresh(handle)
        val kids = childrenListOf(node)
            ?: throw DomException("wrong-node-type", "childrenOf exige Element o Document")
        return kids.map { handleOf(it) }
    }

    /** Primer elemento con ese nombre en pre-orden, o null. */
    fun findFirst(elementName: String): DomHandle? {
        val found = findByName(root, elementName) ?: return null
        return handleOf(found)
    }

    /** Texto de un nodo Text ("wrong-node-type" en otro caso). */
    fun textOf(handle: DomHandle): String {
        val node = fresh(handle)
        if (node !is DomNode.Text) {
            throw DomException("wrong-node-type", "textOf exige un nodo Text")
        }
        return node.data
    }

    /** Valor de un atributo de un Element, o null si no existe. */
    fun attributeOf(handle: DomHandle, name: String): String? {
        val node = fresh(handle)
        if (node !is DomNode.Element) {
            throw DomException("wrong-node-type", "attributeOf exige un Element")
        }
        return node.attributes[name]
    }

    /** Handle del padre del nodo, o null si es la raiz o esta detached. */
    fun parentOf(handle: DomHandle): DomHandle? {
        fresh(handle)
        val pid = parentIds[handle.nodeId] ?: return null
        return DomHandle(documentId, generation, pid)
    }

    /** DomNode vivo detras de un handle, para consultas de solo lectura. */
    fun nodeOf(handle: DomHandle): DomNode = fresh(handle)

    private fun isValidAttrName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r') return false
            if (c == '/' || c == '>' || c == '=' || c == '<' || c == '\"' || c == '\'') return false
        }
        return true
    }

    private fun childrenListOf(node: DomNode): ArrayList<DomNode>? = when (node) {
        is DomNode.Document -> node.children
        is DomNode.Element -> node.children
        else -> null
    }

    private fun fresh(h: DomHandle): DomNode {
        if (h.documentId != documentId || h.generation != generation) {
            throw DomException("stale-handle", "handle de otra generacion o documento")
        }
        return registry[h.nodeId] ?: throw DomException("node-not-found", "el nodo no esta registrado")
    }

    private fun handleOf(node: DomNode): DomHandle =
        DomHandle(documentId, generation, requireNotNull(ids[node]))

    private fun registerPreOrder(node: DomNode, parent: Int): Int {
        val id = ++nextNodeId
        registry[id] = node
        ids[node] = id
        if (parent >= 0) parentIds[id] = parent
        val kids = childrenListOf(node)
        if (kids != null) {
            for (c in kids) registerPreOrder(c, id)
        }
        return id
    }

    private fun registerDetached(node: DomNode): DomHandle {
        val id = ++nextNodeId
        registry[id] = node
        ids[node] = id
        return DomHandle(documentId, generation, id)
    }

    private fun deregister(id: Int, node: DomNode) {
        registry.remove(id)
        ids.remove(node)
        parentIds.remove(id)
        val kids = childrenListOf(node)
        if (kids != null) {
            for (c in kids) {
                val cid = ids[c]
                if (cid != null) deregister(cid, c)
            }
        }
    }

    private fun findByName(node: DomNode, name: String): DomNode? {
        if (node is DomNode.Element && node.name == name) return node
        val kids = childrenListOf(node)
        if (kids != null) {
            for (c in kids) {
                val f = findByName(c, name)
                if (f != null) return f
            }
        }
        return null
    }
}
