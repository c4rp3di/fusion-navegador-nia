package nia.engine.js

import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle

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
 * Exclusiones v1: sin querySelector (necesita CssCascade), sin textContent
 * de Element, sin accessors sobre nodos (JsProp.Accessor exige JsFun del
 * AST), sin captura/burbujeo (T024) y document.body es una referencia
 * fija al body del parseo.
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
            w.props["addEventListener"] = JsProp.Value(JsBuiltins.fn("addEventListener") { args, thiz ->
                val target = thiz as? JsObj
                if (target != null) {
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val type = strArg(JsBuiltins.a0(args))
                        val cb = JsBuiltins.a1(args)
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
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val type = strArg(JsBuiltins.a0(args))
                        val cb = JsBuiltins.a1(args)
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
                }
                JsUndefined
            })
            w.props["dispatchEvent"] = JsProp.Value(JsBuiltins.fn("dispatchEvent") { args, thiz ->
                val target = thiz as? JsObj
                val ev = JsBuiltins.a0(args)
                if (target == null || ev !is JsObj) return@fn JsBool(false)
                ev.props["target"] = JsProp.Value(target)
                ev.props["currentTarget"] = JsProp.Value(target)
                val lEntry = target.props["__listeners"]
                val map = (lEntry as? JsProp.Value)?.v as? JsObj
                if (map != null) {
                    val typeEntry = ev.props["type"]
                    val typeV = (typeEntry as? JsProp.Value)?.v
                    if (typeV != null) {
                        val listEntry = map.props[strArg(typeV)]
                        val list = (listEntry as? JsProp.Value)?.v as? JsObj
                        if (list != null) {
                            val snapshot = ArrayList<JsValue>(list.elems)
                            for (cb in snapshot) {
                                JsInterpreter.callFunction(cb, target, listOf(ev))
                            }
                        }
                    }
                }
                val cEntry = ev.props["cancelable"]
                val cVal = (cEntry as? JsProp.Value)?.v
                val can = cVal is JsBool && cVal.value
                if (!can) return@fn JsBool(true)
                val dEntry = ev.props["defaultPrevented"]
                val dVal = (dEntry as? JsProp.Value)?.v
                JsBool(!(dVal is JsBool && dVal.value))
            })
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
        document.props["createElement"] = JsProp.Value(JsBuiltins.fn("createElement") { args, _ ->
            val name = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.wrap(domOp { doc.createElement(name) })
        })
        document.props["createTextNode"] = JsProp.Value(JsBuiltins.fn("createTextNode") { args, _ ->
            val data = JsRuntime.toStringJs(JsBuiltins.a0(args))
            s.wrap(domOp { doc.createTextNode(data) })
        })
        val bodyH = domOp { doc.findFirst("body") }
        document.props["body"] = JsProp.Value(s.wrapOrNull(bodyH))
        genv.declare("document", document)
    }
}
