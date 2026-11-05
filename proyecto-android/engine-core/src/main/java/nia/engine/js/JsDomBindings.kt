package nia.engine.js

import nia.engine.css.CssCascade
import nia.engine.css.CssComplexSelector
import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode

/**
 * Bindings DOM + consola (T022 v1): puente entre el interprete ECMAScript
 * y el DomDocument del motor. Los wrappers JsObj de los nodos comparten
 * identidad por nodeId (mismo nodo -> mismo objeto en JS) y exponen
 * mutaciones, atributos, hijos y eventos con el patron __listeners de
 * EventTarget (this del listener = wrapper, ev.target/currentTarget).
 * console.log/info/warn/error/debug devuelven la linea formateada
 * (primitivos con ToString, objetos/arrays con el dump canonico) para que
 * la consola sea observable y testable sin UI. Los DomException se
 * reportan a JS como TypeError con el codigo DOM como message.
 * T024 v1: captura/burbujeo por el arbol (addEventListener acepta un
 * tercer argumento useCapture; dispatchEvent recorre los ancestros en
 * captura de raiz a padre y burbujea de padre a raiz solo si ev.bubbles;
 * stopPropagation corta el recorrido) y querySelector/querySelectorAll en
 * document y nodos con los selectores de CssCascade. T024 v2: textContent
 * de Element/Text como accessor real (JsProp.Accessor con JsFun generados
 * por el propio parser: get concatena los textos descendientes, set
 * reemplaza los hijos por un unico Text y en Text fija su data),
 * stopImmediatePropagation (corta la lista actual y las fases restantes)
 * y listeners de captura del propio objetivo invocados en fase objetivo
 * antes que los de burbuja. T022 v2 (R0138, RES-0071): reflejo de nodos en los wrappers (nodeType y nodeName constantes del nodo, nodeValue como accessor con el wrong-node-type tipificado de Comment, tagName del Element, id/className reflejando atributos y navegacion de solo lectura parentNode/firstChild/lastChild/nextSibling/previousSibling) y document.createComment/nodeType/nodeName en el global document. Exclusiones: pseudo-clases dinamicas y
 * document.body fijo al body del parseo.
 */
object JsDomBindings {

    /** Ejecuta una operacion DOM convirtiendo sus DomException en TypeError. */
    private fun <R> domOp(op: () -> R): R {
        try {
            return op()
        } catch (e: DomException) {
            throw JsRuntime.throwNative("TypeError", e.code)
        }
    }

    private fun consoleLine(args: List<JsValue>): String {
        val sb = StringBuilder()
        var first = true
        for (a in args) {
            if (!first) sb.append(" ")
            first = false
            if (a is JsObj) sb.append(JsRuntime.dumpValue(a))
            else sb.append(JsRuntime.toStringJs(a))
        }
        return sb.toString()
    }

    /** Instala console (log/info/warn/error/debug) en el entorno global. */
    fun installConsole(genv: Env) {
        val console = JsRuntime.newObj()
        val methods = listOf("log", "info", "warn", "error", "debug")
        for (m in methods) {
            console.props[m] = JsProp.Value(JsBuiltins.fn(m) { args, _ ->
                JsStr(consoleLine(args))
            })
        }
        genv.declare("console", console)
    }

    private class Session(val doc: DomDocument) {

        val byNode = HashMap<Int, JsObj>()
        val handles = HashMap<JsObj, DomHandle>()
        val captureListeners = HashMap<Int, HashMap<String, MutableList<JsValue>>>()

        fun wrap(h: DomHandle): JsObj {
            val known = byNode[h.nodeId]
            if (known != null) return known
            val w = JsRuntime.newObj()
            byNode[h.nodeId] = w
            handles[w] = h
            installNodeApi(w)
            return w
        }

        fun wrapOrNull(h: DomHandle?): JsValue {
            if (h == null) return JsNull
            return wrap(h)
        }

        fun handleOf(w: JsValue?): DomHandle? {
            if (w !is JsObj) return null
            return handles[w]
        }

        private fun strArg(v: JsValue): String = JsRuntime.toStringJs(v)

        private fun idOf(h: DomHandle): String? {
            return try {
                doc.attributeOf(h, "id")
            } catch (e: DomException) {
                null
            }
        }

        private fun childrenOrEmpty(h: DomHandle): List<DomHandle> {
            return try {
                doc.childrenOf(h)
            } catch (e: DomException) {
                emptyList()
            }
        }

        fun findById(h: DomHandle, id: String): DomHandle? {
            if (idOf(h) == id) return h
            for (k in childrenOrEmpty(h)) {
                val f = findById(k, id)
                if (f != null) return f
            }
            return null
        }

        fun childrenArray(h: DomHandle): JsObj {
            val arr = JsRuntime.newArr()
            for (k in childrenOrEmpty(h)) arr.elems.add(wrap(k))
            return arr
        }

        /** Ancestros de h en orden padre -> raiz (excluye al propio nodo). */
        fun ancestorsOf(h: DomHandle): List<DomHandle> {
            val out = ArrayList<DomHandle>()
            var cur: DomHandle? = domOp { doc.parentOf(h) }
            while (true) {
                val c: DomHandle = cur ?: break
                out.add(c)
                cur = domOp { doc.parentOf(c) }
            }
            return out
        }

        private fun selectorListOrThrow(sel: String): List<CssComplexSelector> {
            val sels = CssCascade.parseSelectorList(sel)
            if (sels == null) {
                throw JsRuntime.throwNative("TypeError", "invalid-selector")
            }
            return sels
        }

        fun queryHandles(from: DomHandle, sel: String): List<DomHandle> {
            val sels = selectorListOrThrow(sel)
            val parents = CssCascade.parentMap(domOp { doc.nodeOf(doc.rootHandle()) })
            val out = ArrayList<DomHandle>()
            fun go(h: DomHandle) {
                val node = domOp { doc.nodeOf(h) }
                if (node is DomNode.Element && CssCascade.matchesAny(node, sels, parents)) {
                    out.add(h)
                }
                for (k in childrenOrEmpty(h)) go(k)
            }
            go(from)
            return out
        }

        fun queryArray(from: DomHandle, sel: String): JsObj {
            val arr = JsRuntime.newArr()
            for (h in queryHandles(from, sel)) arr.elems.add(wrap(h))
            return arr
        }

        /** Accessors de textContent: JsFun parseados una vez por sesion. */
        private val tcGet = parseAccessorFun("(function () { return this.__textContentGet(); })")
        private val tcSet = parseAccessorFun("(function (v) { this.__textContentSet(v); })")

        /** Accessors de value de input y de form.elements (T027): parseados una vez por sesion. */
        private val ivGet = parseAccessorFun("(function () { return this.__valueGet(); })")
        private val ivSet = parseAccessorFun("(function (v) { this.__valueSet(v); })")
        private val elGet = parseAccessorFun("(function () { return this.__elementsGet(); })")
        // T022 v2 (RES-0071): accessores del reflejo de nodos, parseados una vez por sesion.
        private val nvGet = parseAccessorFun("(function () { return this.__nodeValueGet(); })")
        private val nvSet = parseAccessorFun("(function (v) { this.__nodeValueSet(v); })")
        private val pnGet = parseAccessorFun("(function () { return this.__parentNodeGet(); })")
        private val fcGet = parseAccessorFun("(function () { return this.__firstChildGet(); })")
        private val lcGet = parseAccessorFun("(function () { return this.__lastChildGet(); })")
        private val nsGet = parseAccessorFun("(function () { return this.__nextSiblingGet(); })")
        private val psGet = parseAccessorFun("(function () { return this.__previousSiblingGet(); })")
        private val idGet = parseAccessorFun("(function () { return this.__idGet(); })")
        private val idSet = parseAccessorFun("(function (v) { this.__idSet(v); })")
        private val clGet = parseAccessorFun("(function () { return this.__classNameGet(); })")
        private val clSet = parseAccessorFun("(function (v) { this.__classNameSet(v); })")


        private fun parseAccessorFun(src: String): JsFun {
            val r = JsInterpreter.evaluate(src)
            val v = r.value
            if (v is JsFun) return v
            throw JsRuntime.throwNative("TypeError", "accessor-no-compilable")
        }

        private fun deepTextOf(node: DomNode): String {
            val sb = StringBuilder()
            fun go(n: DomNode) {
                if (n is DomNode.Text) sb.append(n.data)
                when (n) {
                    is DomNode.Document -> for (c in n.children) go(c)
                    is DomNode.Element -> for (c in n.children) go(c)
                    else -> {}
                }
            }
            go(node)
            return sb.toString()
        }

        private fun installNodeApi(w: JsObj) {
            w.props["__listeners"] = JsProp.Value(JsRuntime.newObj())
            w.props["appendChild"] = JsProp.Value(JsBuiltins.fn("appendChild") { args, thiz ->
                val parent = handleOf(thiz)
                val childV = JsBuiltins.a0(args)
                val child = handleOf(childV)
                if (parent == null || child == null) {
                    throw JsRuntime.throwNative("TypeError", "appendChild exige nodos del documento")
                }
                domOp { doc.appendChild(parent, child) }
                childV
            })
            w.props["removeChild"] = JsProp.Value(JsBuiltins.fn("removeChild") { args, thiz ->
                val parent = handleOf(thiz)
                val childV = JsBuiltins.a0(args)
                val child = handleOf(childV)
                if (parent == null || child == null) {
                    throw JsRuntime.throwNative("TypeError", "removeChild exige nodos del documento")
                }
                domOp { doc.removeChild(parent, child) }
                childV
            })
            w.props["setAttribute"] = JsProp.Value(JsBuiltins.fn("setAttribute") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "setAttribute exige un nodo del documento")
                }
                val name = strArg(JsBuiltins.a0(args))
                val value = strArg(JsBuiltins.a1(args))
                domOp { doc.setAttribute(h, name, value) }
                JsUndefined
            })
            w.props["getAttribute"] = JsProp.Value(JsBuiltins.fn("getAttribute") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "getAttribute exige un nodo del documento")
                }
                val name = strArg(JsBuiltins.a0(args))
                val v = domOp { doc.attributeOf(h, name) }
                if (v == null) return@fn JsNull
                JsStr(v)
            })
            w.props["text"] = JsProp.Value(JsBuiltins.fn("text") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "text exige un nodo del documento")
                }
                JsStr(domOp { doc.textOf(h) })
            })
            w.props["setText"] = JsProp.Value(JsBuiltins.fn("setText") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "setText exige un nodo del documento")
                }
                val data = strArg(JsBuiltins.a0(args))
                domOp { doc.setText(h, data) }
                thiz
            })
            w.props["children"] = JsProp.Value(JsBuiltins.fn("children") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "children exige un nodo del documento")
                }
                childrenArray(h)
            })
            w.props["querySelector"] = JsProp.Value(JsBuiltins.fn("querySelector") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "querySelector exige un nodo del documento")
                }
                val sel = strArg(JsBuiltins.a0(args))
                val found = queryHandles(h, sel)
                if (found.isEmpty()) return@fn JsNull
                wrap(found[0])
            })
            w.props["querySelectorAll"] = JsProp.Value(JsBuiltins.fn("querySelectorAll") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "querySelectorAll exige un nodo del documento")
                }
                val sel = strArg(JsBuiltins.a0(args))
                queryArray(h, sel)
            })
            w.props["__textContentGet"] = JsProp.Value(JsBuiltins.fn("textContent-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "textContent exige un nodo del documento")
                }
                val node = domOp { doc.nodeOf(h) }
                JsStr(deepTextOf(node))
            })
            w.props["__textContentSet"] = JsProp.Value(JsBuiltins.fn("textContent-set") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "textContent exige un nodo del documento")
                }
                val v = JsRuntime.toStringJs(JsBuiltins.a0(args))
                domOp {
                    val node = doc.nodeOf(h)
                    if (node is DomNode.Text) {
                        doc.setText(h, v)
                    } else {
                        for (c in doc.childrenOf(h)) doc.removeChild(h, c)
                        if (v.isNotEmpty()) doc.appendChild(h, doc.createTextNode(v))
                    }
                }
                JsUndefined
            })
            w.props["textContent"] = JsProp.Accessor(tcGet, tcSet)
            // T022 v2 (RES-0071): nodeValue y navegacion de solo lectura.
            w.props["__nodeValueGet"] = JsProp.Value(JsBuiltins.fn("nodeValue-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "nodeValue exige un nodo del documento")
                }
                val node = domOp { doc.nodeOf(h) }
                when (node) {
                    is DomNode.Text -> JsStr(node.data)
                    is DomNode.Comment -> JsStr(node.data)
                    else -> JsNull
                }
            })
            w.props["__nodeValueSet"] = JsProp.Value(JsBuiltins.fn("nodeValue-set") { args, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "nodeValue exige un nodo del documento")
                }
                val v = JsRuntime.toStringJs(JsBuiltins.a0(args))
                domOp {
                    val node = doc.nodeOf(h)
                    if (node is DomNode.Text || node is DomNode.Comment) {
                        doc.setText(h, v)
                    }
                }
                JsUndefined
            })
            w.props["__parentNodeGet"] = JsProp.Value(JsBuiltins.fn("parentNode-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "parentNode exige un nodo del documento")
                }
                wrapOrNull(domOp { doc.parentOf(h) })
            })
            w.props["__firstChildGet"] = JsProp.Value(JsBuiltins.fn("firstChild-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "firstChild exige un nodo del documento")
                }
                wrapOrNull(childrenOrEmpty(h).firstOrNull())
            })
            w.props["__lastChildGet"] = JsProp.Value(JsBuiltins.fn("lastChild-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "lastChild exige un nodo del documento")
                }
                wrapOrNull(childrenOrEmpty(h).lastOrNull())
            })
            w.props["__nextSiblingGet"] = JsProp.Value(JsBuiltins.fn("nextSibling-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "nextSibling exige un nodo del documento")
                }
                val p = domOp { doc.parentOf(h) }
                if (p == null) return@fn JsNull
                val kids = domOp { doc.childrenOf(p) }
                val idx = kids.indexOfFirst { it.nodeId == h.nodeId }
                if (idx < 0) return@fn JsNull
                wrapOrNull(kids.getOrNull(idx + 1))
            })
            w.props["__previousSiblingGet"] = JsProp.Value(JsBuiltins.fn("previousSibling-get") { _, thiz ->
                val h = handleOf(thiz)
                if (h == null) {
                    throw JsRuntime.throwNative("TypeError", "previousSibling exige un nodo del documento")
                }
                val p = domOp { doc.parentOf(h) }
                if (p == null) return@fn JsNull
                val kids = domOp { doc.childrenOf(p) }
                val idx = kids.indexOfFirst { it.nodeId == h.nodeId }
                if (idx < 0) return@fn JsNull
                wrapOrNull(kids.getOrNull(idx - 1))
            })
            w.props["nodeValue"] = JsProp.Accessor(nvGet, nvSet)
            w.props["parentNode"] = JsProp.Accessor(pnGet, null)
            w.props["firstChild"] = JsProp.Accessor(fcGet, null)
            w.props["lastChild"] = JsProp.Accessor(lcGet, null)
            w.props["nextSibling"] = JsProp.Accessor(nsGet, null)
            w.props["previousSibling"] = JsProp.Accessor(psGet, null)


            w.props["addEventListener"] = JsProp.Value(JsBuiltins.fn("addEventListener") { args, thiz ->
                val target = thiz as? JsObj
                if (target != null) {
                    val type = strArg(JsBuiltins.a0(args))
                    val cb = JsBuiltins.a1(args)
                    val capV = if (args.size > 2) args[2] else JsUndefined
                    val useCap = capV is JsBool && capV.value
                    val h = handleOf(target)
                    if (useCap) {
                        if (h != null) {
                            val byType = captureListeners[h.nodeId] ?: HashMap<String, MutableList<JsValue>>()
                            captureListeners[h.nodeId] = byType
                            val cl = byType[type] ?: mutableListOf()
                            byType[type] = cl
                            for (e in cl) {
                                if (JsBuiltins.same(e, cb)) return@fn JsUndefined
                            }
                            cl.add(cb)
                        }
                        return@fn JsUndefined
                    }
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val listEntry = map.props[type]
                        var list = (listEntry as? JsProp.Value)?.v as? JsObj
                        if (list == null) {
                            list = JsRuntime.newArr()
                            map.props[type] = JsProp.Value(list)
                        }
                        for (e in list.elems) {
                            if (JsBuiltins.same(e, cb)) return@fn JsUndefined
                        }
                        list.elems.add(cb)
                    }
                }
                JsUndefined
            })

            w.props["removeEventListener"] = JsProp.Value(JsBuiltins.fn("removeEventListener") { args, thiz ->
                val target = thiz as? JsObj
                if (target != null) {
                    val type = strArg(JsBuiltins.a0(args))
                    val cb = JsBuiltins.a1(args)
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val listEntry = map.props[type]
                        val list = (listEntry as? JsProp.Value)?.v as? JsObj
                        if (list != null) {
                            var j = 0
                            while (j < list.elems.size) {
                                if (JsBuiltins.same(list.elems[j], cb)) {
                                    list.elems.removeAt(j)
                                    break
                                }
                                j++
                            }
                        }
                    }
                    val h = handleOf(target)
                    if (h != null) {
                        val byType = captureListeners[h.nodeId]
                        if (byType != null) {
                            val cl = byType[type]
                            if (cl != null) {
                                var j2 = 0
                                while (j2 < cl.size) {
                                    if (JsBuiltins.same(cl[j2], cb)) {
                                        cl.removeAt(j2)
                                        break
                                    }
                                    j2++
                                }
                            }
                        }
                    }
                }
                JsUndefined
            })

            w.props["dispatchEvent"] = JsProp.Value(JsBuiltins.fn("dispatchEvent") { args, thiz ->
                val target = thiz as? JsObj
                val evV = JsBuiltins.a0(args)
                if (target == null || evV !is JsObj) return@fn JsBool(false)
                val ev = evV
                ev.props["target"] = JsProp.Value(target)
                val typeEntry = ev.props["type"]
                val typeV = (typeEntry as? JsProp.Value)?.v
                if (typeV == null) return@fn JsBool(false)
                val type = strArg(typeV)
                val targetH = handleOf(target)
                if (targetH == null) return@fn JsBool(false)
                val bEntry = ev.props["bubbles"]
                val bVal = (bEntry as? JsProp.Value)?.v
                val bubbles = bVal is JsBool && bVal.value
                ev.props["stopPropagation"] = JsProp.Value(JsBuiltins.fn("stopPropagation") { _, _ ->
                    ev.props["__stopped"] = JsProp.Value(JsBool(true))
                    JsUndefined
                })
                ev.props["stopImmediatePropagation"] = JsProp.Value(JsBuiltins.fn("stopImmediatePropagation") { _, _ ->
                    ev.props["__stopped"] = JsProp.Value(JsBool(true))
                    ev.props["__immediate"] = JsProp.Value(JsBool(true))
                    JsUndefined
                })
                fun stopped(): Boolean {
                    val sEntry = ev.props["__stopped"]
                    val sVal = (sEntry as? JsProp.Value)?.v
                    return sVal is JsBool && sVal.value
                }
                fun immediate(): Boolean {
                    val iEntry = ev.props["__immediate"]
                    val iVal = (iEntry as? JsProp.Value)?.v
                    return iVal is JsBool && iVal.value
                }
                fun fire(list: List<JsValue>, current: JsObj) {
                    for (cb in list) {
                        if (immediate()) break
                        ev.props["currentTarget"] = JsProp.Value(current)
                        JsInterpreter.callFunction(cb, current, listOf(ev))
                    }
                }
                val chain = ancestorsOf(targetH)
                for (i in chain.indices.reversed()) {
                    if (stopped()) break
                    val ancH = chain[i]
                    val anc = wrap(ancH)
                    val cl = captureListeners[ancH.nodeId]?.get(type)
                    if (cl != null) fire(ArrayList(cl), anc)
                }
                if (!stopped()) {
                    val capList = captureListeners[targetH.nodeId]?.get(type)
                    if (capList != null) {
                        fire(ArrayList(capList), target)
                    }
                    if (!immediate()) {
                        val lEntry = target.props["__listeners"]
                        val map = (lEntry as? JsProp.Value)?.v as? JsObj
                        if (map != null) {
                            val listEntry = map.props[type]
                            val list = (listEntry as? JsProp.Value)?.v as? JsObj
                            if (list != null) {
                                fire(ArrayList(list.elems), target)
                            }
                        }
                    }
                }
                if (bubbles && !stopped()) {
                    for (ancH in chain) {
                        if (stopped()) break
                        val anc = wrap(ancH)
                        val lEntry = anc.props["__listeners"]
                        val map = (lEntry as? JsProp.Value)?.v as? JsObj
                        if (map != null) {
                            val listEntry = map.props[type]
                            val list = (listEntry as? JsProp.Value)?.v as? JsObj
                            if (list != null) {
                                fire(ArrayList(list.elems), anc)
                            }
                        }
                    }
                }
                ev.props["currentTarget"] = JsProp.Value(target)
                val cEntry = ev.props["cancelable"]
                val cVal = (cEntry as? JsProp.Value)?.v
                val can = cVal is JsBool && cVal.value
                if (!can) return@fn JsBool(true)
                val dEntry = ev.props["defaultPrevented"]
                val dVal = (dEntry as? JsProp.Value)?.v

                JsBool(!(dVal is JsBool && dVal.value))
            })

            // T027 v1 (RES-0033): formularios - value de input/textarea,
            // form.elements y form.submit().
            val selfH = handleOf(w)
            val selfNode = if (selfH != null) domOp { doc.nodeOf(selfH) } else null
            val tag = (selfNode as? DomNode.Element)?.name?.lowercase()
            if (tag == "input") {
                w.props["__valueGet"] = JsProp.Value(JsBuiltins.fn("__valueGet") { _, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "value exige un nodo del documento")
                    }
                    val v = try {
                        doc.attributeOf(h, "value")
                    } catch (e: DomException) {
                        null
                    }
                    JsStr(v ?: "")
                })
                w.props["__valueSet"] = JsProp.Value(JsBuiltins.fn("__valueSet") { args, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "value exige un nodo del documento")
                    }
                    val v = JsRuntime.toStringJs(JsBuiltins.a0(args))
                    domOp { doc.setAttribute(h, "value", v) }
                    JsUndefined
                })
                w.props["value"] = JsProp.Accessor(ivGet, ivSet)
            }
            if (tag == "textarea") {
                w.props["value"] = JsProp.Accessor(tcGet, tcSet)
            }
            if (tag == "form") {
                w.props["__elementsGet"] = JsProp.Value(JsBuiltins.fn("__elementsGet") { _, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "elements exige un nodo del documento")
                    }
                    val arr = JsRuntime.newArr()
                    fun go(cur: DomHandle) {
                        for (k in childrenOrEmpty(cur)) {
                            val named = try {
                                doc.attributeOf(k, "name")
                            } catch (e: DomException) {
                                null
                            }
                            if (named != null) arr.elems.add(wrap(k))
                            go(k)
                        }
                    }
                    go(h)
                    arr
                })
                w.props["elements"] = JsProp.Accessor(elGet, null)
                w.props["submit"] = JsProp.Value(JsBuiltins.fn("submit") { _, thiz ->
                    val target = thiz as? JsObj
                    if (target == null) return@fn JsBool(false)
                    val ev = JsRuntime.newObj()
                    ev.props["type"] = JsProp.Value(JsStr("submit"))
                    ev.props["bubbles"] = JsProp.Value(JsBool(true))
                    ev.props["cancelable"] = JsProp.Value(JsBool(true))
                    ev.props["defaultPrevented"] = JsProp.Value(JsBool(false))
                    ev.props["preventDefault"] = JsProp.Value(JsBuiltins.fn("preventDefault") { _, _ ->
                        ev.props["defaultPrevented"] = JsProp.Value(JsBool(true))
                        JsUndefined
                    })
                    val dEntry = target.props["dispatchEvent"]
                    val dVal = (dEntry as? JsProp.Value)?.v
                    if (dVal == null) return@fn JsBool(false)
                    JsInterpreter.callFunction(dVal, target, listOf(ev))
                })
            }
            // T022 v2 (RES-0071): reflejo de nodos. nodeType y nodeName son
            // constantes del nodo; tagName, id y className solo en Elements.
            if (selfNode != null) {
                val nt = when (selfNode) {
                    is DomNode.Document -> 9.0
                    is DomNode.Element -> 1.0
                    is DomNode.Text -> 3.0
                    is DomNode.Comment -> 8.0
                    is DomNode.Doctype -> 10.0
                }
                w.props["nodeType"] = JsProp.Value(JsNum(nt))
                val nn = when (selfNode) {
                    is DomNode.Document -> "#document"
                    is DomNode.Element -> selfNode.name.uppercase()
                    is DomNode.Text -> "#text"
                    is DomNode.Comment -> "#comment"
                    is DomNode.Doctype -> selfNode.name ?: ""
                }
                w.props["nodeName"] = JsProp.Value(JsStr(nn))
            }
            if (selfNode is DomNode.Element) {
                w.props["tagName"] = JsProp.Value(JsStr(selfNode.name.uppercase()))
                w.props["__idGet"] = JsProp.Value(JsBuiltins.fn("id-get") { _, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "id exige un nodo del documento")
                    }
                    JsStr(domOp { doc.attributeOf(h, "id") } ?: "")
                })
                w.props["__idSet"] = JsProp.Value(JsBuiltins.fn("id-set") { args, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "id exige un nodo del documento")
                    }
                    val v = JsRuntime.toStringJs(JsBuiltins.a0(args))
                    domOp { doc.setAttribute(h, "id", v) }
                    JsUndefined
                })
                w.props["__classNameGet"] = JsProp.Value(JsBuiltins.fn("class-get") { _, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "class exige un nodo del documento")
                    }
                    JsStr(domOp { doc.attributeOf(h, "class") } ?: "")
                })
                w.props["__classNameSet"] = JsProp.Value(JsBuiltins.fn("class-set") { args, thiz ->
                    val h = handleOf(thiz)
                    if (h == null) {
                        throw JsRuntime.throwNative("TypeError", "class exige un nodo del documento")
                    }
                    val v = JsRuntime.toStringJs(JsBuiltins.a0(args))
                    domOp { doc.setAttribute(h, "class", v) }
                    JsUndefined
                })
                w.props["id"] = JsProp.Accessor(idGet, idSet)
                w.props["className"] = JsProp.Accessor(clGet, clSet)
            }
        }
    }

    /** Instala el global document con los bindings sobre doc (T022). */
    fun install(genv: Env, doc: DomDocument) {
        val s = Session(doc)
        val document = JsRuntime.newObj()
        document.props["getElementById"] = JsProp.Value(JsBuiltins.fn("getElementById") { args, _ ->
            val id = JsRuntime.toStringJs(JsBuiltins.a0(args))
            val h = s.findById(doc.rootHandle(), id)
            s.wrapOrNull(h)
        })
        document.props["querySelector"] = JsProp.Value(JsBuiltins.fn("querySelector") { args, _ ->
            val sel = JsRuntime.toStringJs(JsBuiltins.a0(args))
            val found = s.queryHandles(doc.rootHandle(), sel)
            if (found.isEmpty()) JsNull else s.wrap(found[0])
        })
        document.props["querySelectorAll"] = JsProp.Value(JsBuiltins.fn("querySelectorAll") { args, _ ->
            val sel = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.queryArray(doc.rootHandle(), sel)
        })
        document.props["createElement"] = JsProp.Value(JsBuiltins.fn("createElement") { args, _ ->
            val name = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.wrap(domOp { doc.createElement(name) })
        })
        document.props["createTextNode"] = JsProp.Value(JsBuiltins.fn("createTextNode") { args, _ ->
            val data = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.wrap(domOp { doc.createTextNode(data) })
        })
        document.props["createComment"] = JsProp.Value(JsBuiltins.fn("createComment") { args, _ ->
            val data = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.wrap(domOp { doc.createComment(data) })
        })
        val bodyH = domOp { doc.findFirst("body") }
        document.props["body"] = JsProp.Value(s.wrapOrNull(bodyH))
        document.props["nodeType"] = JsProp.Value(JsNum(9.0))
        document.props["nodeName"] = JsProp.Value(JsStr("#document"))
        genv.declare("document", document)
    }
}

