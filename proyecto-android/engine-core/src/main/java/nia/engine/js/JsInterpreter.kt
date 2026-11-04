package nia.engine.js

import nia.engine.dom.DomDocument
import java.util.concurrent.LinkedBlockingQueue

/**
 * Interprete ECMAScript v1 (T019): evalua el AST del parser v2.
 * Espejo validado del gemelo TS/JS del diseno (interp.js @ gemelo R0027).
 * Entornos en cadena (var con hoisting a la funcion, let/const de bloque,
 * hoisting de function declarations, closures, this por llamada), completions
 * normal/break/continue/return con labels, try/catch/finally (el finally
 * reemplaza), switch con === y fallthrough, for-in propias/for-of
 * array-string, clases completas del AST v2 (constructor por defecto que
 * delega en el super con los argumentos, metodos, get/set, computed, static,
 * extends), new/instanceof/in/delete, optional chaining con cortocircuito,
 * coerciones completas y errores tipificados como objetos Error.
 * Exclusiones: builtins salvo Error, Object.prototype, super, TDZ, strict,
 * arguments, modulos. T018 v4 anade destructuring completo
 * (declaraciones, asignacion y parametros con patrones anidados, defaults
 * sobre undefined y rest; sin patrones en for-in/of). T023 v1 anade la cola
 * de microtasks de Promise (drenada al final de cada evaluacion, con
 * completion value REPL) y los miembros de RegExp sobre su JsRegExp.
 * T018 v5 anade async/await y generators sobre runners reanudables: hilos
 * con handoff estricto (en cada momento solo un hilo ejecuta JS; suspender
 * es bloquearse en su inbox; quien lanza o reanuda espera su senal de
 * parada) y el drenaje reanuda los awaits asentados y al final aborta los
 * runners colgados. T021 anade las cuotas de ejecucion: toda evaluacion
 * corre con una sesion JsQuotas (safepoint por sentencia/expresion contra el
 * tope de pasos, newObj/newArr contra el tope de heap), el abort NO es
 * JsThrown (invisible al try/catch de JS, siempre llega al punto de entrada),
 * interrupt() pide el corte desde otro hilo (flag volatile) y el drenaje de
 * microtasks corre dentro de la sesion.
 */
/**
 * Resultado de una evaluacion: completion value del REPL, throw sin capturar
 * y, desde T021, el motivo de abort cuando la evaluacion fue interrumpida
 * por cuotas o por interrupcion externa (interrupted).
 */
class JsEvalResult(
    val value: JsValue,
    val thrown: Boolean,
    val thrownValue: JsValue?,
    val abortReason: JsAbortReason? = null
) {
    /** Cierto si la evaluacion aborto por cuotas o interrupcion (T021). */
    val interrupted: Boolean get() = abortReason != null
}

object JsInterpreter {

    private enum class CType { NORMAL, BREAK, CONTINUE, RETURN }

    private class Completion(val type: CType, val value: JsValue?, val label: String?)

    private fun rootEnv(e: Env): Env {
        var x: Env? = e
        while (x != null) {
            val p = x.parent
            if (p == null) return x
            x = p
        }
        return e
    }

    private fun normal(): Completion = Completion(CType.NORMAL, null, null)

    private fun makeFun(
        name: String?, params: List<Param>, body: JsStmt.Block?, bodyExpr: JsExpr?,
        env: Env, isArrow: Boolean, async: Boolean = false, gen: Boolean = false
    ): JsFun = JsFun(name, params, body, bodyExpr, env, isArrow, LinkedHashMap(), async, gen)

    private fun getOrCreatePrototype(f: JsFun): JsObj {
        var entry = f.props["prototype"]
        if (entry == null) {
            entry = JsProp.Value(JsRuntime.newObj())
            f.props["prototype"] = entry
        }
        if (entry is JsProp.Value) {
            val v = entry.v
            if (v is JsObj) return v
        }
        return JsRuntime.newObj()
    }

    private fun indexKey(key: String): Int {
        if (!key.matches(Regex("^(0|[1-9][0-9]*)$"))) return -1
        val n = key.toLongOrNull() ?: return -1
        if (n > 2147483647L) return -1
        return n.toInt()
    }

    private fun propsOf(o: JsValue): LinkedHashMap<String, JsProp>? {
        return when (o) {
            is JsObj -> o.props
            is JsFun -> o.props
            is JsClass -> o.props
            is JsNative -> o.props
            else -> null
        }
    }

    private fun protoOf(o: JsValue): JsObj? {
        if (o is JsObj) return o.proto
        return null
    }

    private fun memberKeyOf(m: JsExpr.Member, env: Env): String {
        if (m.name != null) return m.name
        return JsRuntime.toStringJs(evalExpr(m.computed!!, env))
    }

    private fun keyString(k: Key, env: Env): String {
        return when (k) {
            is Key.IdentKey -> k.name
            is Key.StringKey -> k.value
            is Key.NumberKey -> JsRuntime.jsNumToString(JsRuntime.strToNumber(k.raw))
            is Key.ComputedKey -> JsRuntime.toStringJs(evalExpr(k.expr, env))
        }
    }

    fun getMember(o: JsValue, key: String): JsValue {
        if (JsRuntime.isNullish(o)) {
            throw JsRuntime.throwNative("TypeError", "Cannot read properties of " + JsRuntime.toStringJs(o))
        }
        if (o is JsRegExp) {
            if (key == "source") return JsStr(o.body)
            if (key == "flags") return JsStr(o.flags)
            if (key == "global") return JsBool(o.flags.contains('g'))
            if (key == "ignoreCase") return JsBool(o.flags.contains('i'))
            if (key == "multiline") return JsBool(o.flags.contains('m'))
            if (key == "lastIndex") return JsNum(o.lastIndex)
        }
        if (o is JsStr) {
            if (key == "length") return JsNum(o.value.length.toDouble())
            val six = indexKey(key)
            if (six != -1) {
                if (six >= 0 && six < o.value.length) return JsStr(o.value[six].toString())
                return JsUndefined
            }
        }
        if (o is JsFun && key == "prototype") return getOrCreatePrototype(o)
        if (o is JsObj && o.isArr) {
            if (key == "length") return JsNum(o.elems.size.toDouble())
            val ix = indexKey(key)
            if (ix != -1) {
                if (ix >= 0 && ix < o.elems.size) return o.elems[ix]
                return JsUndefined
            }
        }
        var cur: JsValue = o
        while (true) {
            val props = propsOf(cur)
            if (props != null) {
                val entry = props[key]
                if (entry != null) {
                    if (entry is JsProp.Accessor) {
                        if (entry.get == null) {
                            throw JsRuntime.throwNative("TypeError", "No getter for property " + key)
                        }
                        return callFunction(entry.get, o, ArrayList())
                    }
                    if (entry is JsProp.Value) return entry.v
                }
            }
            val p = protoFor(cur)
            if (p == null) break
            cur = p
        }
        return JsUndefined
    }

    /** Prototipo para la busqueda de miembros, tambien sobre primitivos (v2). */
    private fun protoFor(o: JsValue): JsObj? {
        return when (o) {
            is JsObj -> o.proto
            is JsStr -> JsBuiltins.protoStr
            is JsNum -> JsBuiltins.protoNum
            is JsBool -> JsBuiltins.protoBool
            is JsFun -> JsBuiltins.protoFun
            is JsNative -> JsBuiltins.protoFun
            is JsRegExp -> JsBuiltins.protoReg
            else -> null
        }
    }

    fun setMember(o: JsValue, key: String, v: JsValue) {
        if (JsRuntime.isNullish(o)) {
            throw JsRuntime.throwNative("TypeError", "Cannot set properties of " + JsRuntime.toStringJs(o))
        }
        if (o is JsRegExp && key == "lastIndex") {
            o.lastIndex = JsRuntime.toNumber(v)
            return
        }
        if (o is JsObj && o.isArr) {
            if (key == "length") {
                val n = JsRuntime.toNumber(v)
                if (n.isNaN() || n < 0.0 || n > 4294967295.0) {
                    throw JsRuntime.throwNative("RangeError", "Invalid array length")
                }
                val t = Math.floor(n)
                while (o.elems.size > t) o.elems.removeAt(o.elems.size - 1)
                while (o.elems.size < t) o.elems.add(JsUndefined)
                return
            }
            val ix = indexKey(key)
            if (ix != -1) {
                while (o.elems.size <= ix) o.elems.add(JsUndefined)
                o.elems[ix] = v
                return
            }
        }
        val props = propsOf(o)
        if (props != null) {
            var cur: JsValue = o
            while (true) {
                val pr = propsOf(cur) ?: break
                val entry = pr[key]
                if (entry != null) {
                    if (entry is JsProp.Accessor) {
                        if (entry.set == null) {
                            throw JsRuntime.throwNative("TypeError", "No setter for property " + key)
                        }
                        callFunction(entry.set, o, listOf(v))
                        return
                    }
                    break
                }
                val p = protoOf(cur)
                if (p == null) break
                cur = p
            }
            props[key] = JsProp.Value(v)
        }
    }

    private fun deleteMember(o: JsValue, key: String): Boolean {
        if (JsRuntime.isNullish(o)) {
            throw JsRuntime.throwNative("TypeError", "Cannot delete properties of " + JsRuntime.toStringJs(o))
        }
        if (o is JsObj) {
            if (o.isArr) {
                val ix = indexKey(key)
                if (ix != -1) {
                    if (ix >= 0 && ix < o.elems.size) o.elems[ix] = JsUndefined
                    return true
                }
                if (key == "length") return false
            }
            o.props.remove(key)
            return true
        }
        return true
    }

    private fun hasProp(o: JsValue, key: String): Boolean {
        if (o is JsObj && o.isArr) {
            if (key == "length") return true
            val ix = indexKey(key)
            if (ix != -1) return ix >= 0 && ix < o.elems.size
        }
        var cur: JsValue = o
        while (true) {
            val props = propsOf(cur) ?: break
            if (props.containsKey(key)) return true
            val p = protoOf(cur)
            if (p == null) break
            cur = p
        }
        return false
    }

    private fun strictEq(a: JsValue, b: JsValue): Boolean {
        if (a is JsUndefined && b is JsUndefined) return true
        if (a is JsNull && b is JsNull) return true
        if (a is JsBool && b is JsBool) return a.value == b.value
        if (a is JsNum && b is JsNum) return a.value == b.value
        if (a is JsStr && b is JsStr) return a.value == b.value
        if (a is JsRegExp && b is JsRegExp) return a === b
        if (a is JsObj && b is JsObj) return a === b
        if (a is JsFun && b is JsFun) return a === b
        if (a is JsClass && b is JsClass) return a === b
        if (a is JsNative && b is JsNative) return a === b
        return false
    }

    private fun looseEq(a: JsValue, b: JsValue): Boolean {
        if (a is JsUndefined && b is JsUndefined) return true
        if (a is JsNull && b is JsNull) return true
        if (a is JsUndefined && b is JsNull) return true
        if (a is JsNull && b is JsUndefined) return true
        if (a is JsBool) return looseEq(JsNum(if (a.value) 1.0 else 0.0), b)
        if (b is JsBool) return looseEq(a, JsNum(if (b.value) 1.0 else 0.0))
        if (a is JsNum && b is JsStr) return a.value == JsRuntime.toNumber(b)
        if (a is JsStr && b is JsNum) return JsRuntime.toNumber(a) == b.value
        if (a is JsObj && (b is JsNum || b is JsStr)) return looseEq(JsRuntime.toPrimitiveJs(a), b)
        if (b is JsObj && (a is JsNum || a is JsStr)) return looseEq(a, JsRuntime.toPrimitiveJs(b))
        if (a is JsStr && b is JsStr) return a.value == b.value
        if (a is JsNum && b is JsNum) return a.value == b.value
        if (a is JsRegExp && b is JsRegExp) return a === b
        if (a is JsObj && b is JsObj) return a === b
        if (a is JsFun && b is JsFun) return a === b
        if (a is JsClass && b is JsClass) return a === b
        if (a is JsNative && b is JsNative) return a === b
        return false
    }

    private fun addValues(l: JsValue, r: JsValue): JsValue {
        val pl = JsRuntime.toPrimitiveJs(l)
        val pr = JsRuntime.toPrimitiveJs(r)
        if (pl is JsStr || pr is JsStr) return JsStr(JsRuntime.toStringJs(pl) + JsRuntime.toStringJs(pr))
        return JsNum(JsRuntime.toNumber(pl) + JsRuntime.toNumber(pr))
    }

    private fun typeofValue(v: JsValue): String {
        return when (v) {
            is JsUndefined -> "undefined"
            is JsNull -> "object"
            is JsBool -> "boolean"
            is JsNum -> "number"
            is JsStr -> "string"
            is JsRegExp -> "object"
            is JsObj -> "object"
            else -> "function"
        }
    }

    // ---- Clases ----

    private fun makeClassValue(name: String?, superClassExpr: JsExpr?, members: List<ClassMember>, env: Env): JsClass {
        val cls = JsClass(name, null, JsRuntime.newObj(), null, env, LinkedHashMap())
        if (superClassExpr != null) {
            val sv = evalExpr(superClassExpr, env)
            if (sv !is JsClass) {
                throw JsRuntime.throwNative("TypeError", "Class extends value is not a class")
            }
            cls.superClass = sv
            cls.proto.proto = sv.proto
        }
        for (m in members) {
            val key = keyString(m.key, env)
            val targetProps = if (m.isStatic) cls.props else cls.proto.props
            if (m.kind == "method") {
                if (!m.isStatic && key == "constructor") {
                    cls.ctor = m
                } else {
                    targetProps[key] = JsProp.Value(makeFun(key, m.params, m.body, null, env, false))
                }
            } else if (m.kind == "get") {
                targetProps[key] = JsProp.Accessor(makeFun(key, m.params, m.body, null, env, false), null)
            } else {
                targetProps[key] = JsProp.Accessor(null, makeFun(key, m.params, m.body, null, env, false))
            }
        }
        return cls
    }

    private fun callMemberFunction(m: ClassMember, cls: JsClass, thisArg: JsValue, args: List<JsValue>): JsValue {
        val f = makeFun(null, m.params, m.body, null, cls.env, false)
        return callFunction(f, thisArg, args)
    }

    private fun callCtorOfClass(cls: JsClass, thisArg: JsValue, args: List<JsValue>): JsValue {
        if (cls.ctor == null) {
            val sc = cls.superClass
            if (sc != null) callCtorOfClass(sc, thisArg, emptyList())
            return thisArg
        }
        val r = callMemberFunction(cls.ctor!!, cls, thisArg, args)
        if (r is JsObj) return r
        return thisArg
    }

    private fun instantiate(cls: JsClass, args: List<JsValue>): JsValue {
        val obj = JsRuntime.newObj()
        obj.proto = cls.proto
        if (cls.ctor == null) {
            val sc = cls.superClass
            if (sc != null) callCtorOfClass(sc, obj, args)
            return obj
        }
        val r = callMemberFunction(cls.ctor!!, cls, obj, args)
        if (r is JsObj) return r
        return obj
    }

    private fun instanceOfValue(v: JsValue, c: JsValue): Boolean {
        if (c is JsClass) {
            if (v !is JsObj) return false
            var p: JsObj? = v.proto
            while (p != null) {
                if (p === c.proto) return true
                p = p.proto
            }
            return false
        }
        if (c is JsFun) {
            if (v !is JsObj) return false
            val target = getOrCreatePrototype(c)
            var p: JsObj? = v.proto
            while (p != null) {
                if (p === target) return true
                p = p.proto
            }
            return false
        }
        throw JsRuntime.throwNative("TypeError", "Right-hand side of instanceof is not callable")
    }

    // ---- Llamadas ----

    internal fun callFunction(f: JsValue, thisArg: JsValue, args: List<JsValue>): JsValue {
        if (f is JsNative) {
            val c = f.call
            if (c != null) return c(args, thisArg)
            val msg = if (args.isNotEmpty()) JsRuntime.toStringJs(args[0]) else ""
            return JsRuntime.makeErrorObj("Error", msg)
        }
        if (f is JsClass) {
            throw JsRuntime.throwNative("TypeError", "Class constructor cannot be invoked without new")
        }
        if (f !is JsFun) {
            throw JsRuntime.throwNative("TypeError", JsRuntime.toStringJs(f) + " is not a function")
        }
        if (f.gen) return makeGenerator(f, thisArg, args)
        if (f.async) return startAsync(f, thisArg, args)
        return runFunBody(f, thisArg, args)
    }

    private fun runFunBody(f: JsFun, thisArg: JsValue, args: List<JsValue>): JsValue {
        callFrames.addLast(JsCallFrame(f.name, curStmtStart))
        try {
            val env = Env(f.env, true)
            if (!f.isArrow) {
                env.declare("this", thisArg)
                if (f.name != null) env.declare(f.name, f)
            }
            for (j in f.params.indices) {
                val p = f.params[j]
                var v: JsValue = if (j < args.size) args[j] else JsUndefined
                if (v is JsUndefined && p.def != null) v = evalExpr(p.def, env)
                if (p.pat != null) bindDecl(p.pat, v, "let", env) else env.declare(p.name, v)
            }
            if (f.isArrow && f.bodyExpr != null) return evalExpr(f.bodyExpr, env)
            val b = f.body
            if (b == null) return JsUndefined
            hoist(b.statements, env)
            val c = runBlockStatements(b.statements, env, emptyList())
            if (c.type == CType.RETURN) return c.value ?: JsUndefined
            return JsUndefined
        } finally {
            callFrames.removeLast()
        }
    }

    private fun evalArgs(argExprs: List<JsExpr>, env: Env): List<JsValue> {
        val args = ArrayList<JsValue>()
        for (a in argExprs) {
            if (a is JsExpr.Spread) {
                val v = evalExpr(a.expr, env)
                if (v is JsObj && v.isArr) {
                    for (el in v.elems) args.add(el)
                } else if (v is JsStr) {
                    for (c in v.value) args.add(JsStr(c.toString()))
                } else {
                    throw JsRuntime.throwNative("TypeError", "Spread argument is not iterable")
                }
            } else {
                args.add(evalExpr(a, env))
            }
        }
        return args
    }

    // ---- Hoisting ----

    private fun hoist(stmts: List<JsStmt>, env: Env) {
        for (s in stmts) hoistStmt(s, env)
    }

    private fun hoistStmt(s: JsStmt, env: Env) {
        when (s) {
            is JsStmt.Var -> {
                if (s.kind == "var") {
                    for (d in s.declarators) {
                        if (d.pat != null) hoistPat(d.pat, env) else env.declareFnVar(d.name, JsUndefined)
                    }
                }
            }
            is JsStmt.Function -> env.declareFnVar(s.name, makeFun(s.name, s.params, s.body, null, env, false, s.async, s.gen))
            is JsStmt.Block -> hoist(s.statements, env)
            is JsStmt.If -> {
                hoistStmt(s.thenBranch, env)
                if (s.elseBranch != null) hoistStmt(s.elseBranch, env)
            }
            is JsStmt.While -> hoistStmt(s.body, env)
            is JsStmt.DoWhile -> hoistStmt(s.body, env)
            is JsStmt.For -> {
                if (s.init != null) hoistStmt(s.init, env)
                hoistStmt(s.body, env)
            }
            is JsStmt.ForInOf -> {
                if (s.leftKind == "var") env.declareFnVar(s.leftName!!, JsUndefined)
                hoistStmt(s.body, env)
            }
            is JsStmt.Try -> {
                hoist(s.block.statements, env)
                if (s.catchBlock != null) hoist(s.catchBlock.statements, env)
                if (s.finallyBlock != null) hoist(s.finallyBlock.statements, env)
            }
            is JsStmt.Switch -> {
                for (c in s.cases) hoist(c.statements, env)
            }
            is JsStmt.Labeled -> hoistStmt(s.body, env)
            else -> {}
        }
    }

    private fun hoistPat(p: JsPat, env: Env) {
        when (p) {
            is JsPat.PIdent -> env.declareFnVar(p.name, JsUndefined)
            is JsPat.PMember -> {}
            is JsPat.PArray -> {
                for (el in p.elems) if (el != null) hoistPat(el, env)
                val r = p.rest
                if (r != null) hoistPat(r, env)
            }
            is JsPat.PObject -> {
                for (en in p.entries) hoistPat(en.pat, env)
                val r = p.rest
                if (r != null) hoistPat(r, env)
            }
        }
    }

    // ---- Asignacion ----

    private fun assignToIdent(name: String, v: JsValue, env: Env) {
        if (!env.setExisting(name, v)) rootEnv(env).declare(name, v)
    }

    private fun assignToTarget(target: JsExpr, env: Env, v: JsValue) {
        if (target is JsExpr.Ident) {
            assignToIdent(target.name, v, env)
            return
        }
        if (target is JsExpr.Member) {
            val obj = evalExpr(target.obj, env)
            if (target.optional && JsRuntime.isNullish(obj)) return
            val key = memberKeyOf(target, env)
            setMember(obj, key, v)
            return
        }
        if (target is JsExpr.Paren) {
            assignToTarget(target.expr, env, v)
            return
        }
        throw JsRuntime.throwNative("TypeError", "Invalid assignment target")
    }

    // ---- Destructuring (T018 v4) ----

    private fun patNullishGuard(v: JsValue) {
        if (JsRuntime.isNullish(v)) {
            throw JsRuntime.throwNative("TypeError", "Cannot destructure " + JsRuntime.toStringJs(v))
        }
    }

    private fun patLen(v: JsValue): Int {
        if (v is JsObj && v.isArr) return v.elems.size
        if (v is JsStr) return v.value.length
        val l = getMember(v, "length")
        val n = JsRuntime.toNumber(l)
        if (n.isNaN() || n < 0.0) return 0
        return n.toInt()
    }

    private fun patGet(v: JsValue, i: Int): JsValue {
        if (v is JsStr) {
            if (i >= 0 && i < v.value.length) return JsStr(v.value[i].toString())
            return JsUndefined
        }
        return getMember(v, i.toString())
    }

    private fun patFromExpr(e: JsExpr): JsPat? {
        when (e) {
            is JsExpr.Ident -> return JsPat.PIdent(e.start, e.end, e.name, null)
            is JsExpr.Member -> return JsPat.PMember(e.start, e.end, e, null)
            is JsExpr.Paren -> return patFromExpr(e.expr)
            is JsExpr.Assign -> {
                if (e.op != "=") return null
                val t = patFromExpr(e.target)
                if (t !is JsPat.PIdent) return null
                return JsPat.PIdent(t.start, e.end, t.name, e.value)
            }
            is JsExpr.ArrayLit -> {
                val elems = mutableListOf<JsPat?>()
                var rest: JsPat? = null
                for (it in e.items) {
                    if (it is JsExpr.Spread) {
                        rest = patFromExpr(it.expr)
                        if (rest == null) return null
                    } else {
                        val p = patFromExpr(it)
                        if (p == null) return null
                        elems.add(p)
                    }
                }
                return JsPat.PArray(e.start, e.end, elems, rest)
            }
            is JsExpr.ObjectLit -> {
                val entries = mutableListOf<PEntry>()
                var rest: JsPat? = null
                for (pr in e.props) {
                    when (pr) {
                        is Prop.Shorthand -> entries.add(PEntry(pr.start, pr.end, Key.IdentKey(pr.start, pr.end, pr.name), JsPat.PIdent(pr.start, pr.end, pr.name, null)))
                        is Prop.KeyValue -> {
                            val tp = patFromExpr(pr.value)
                            if (tp == null) return null
                            entries.add(PEntry(pr.start, pr.end, pr.key, tp))
                        }
                        is Prop.SpreadProp -> {
                            rest = patFromExpr(pr.expr)
                            if (rest == null) return null
                        }
                        else -> return null
                    }
                }
                return JsPat.PObject(e.start, e.end, entries, rest)
            }
            else -> return null
        }
    }

    private fun bindDecl(p: JsPat, v: JsValue, kind: String, env: Env) {
        when (p) {
            is JsPat.PIdent -> {
                var pv = v
                if (pv is JsUndefined && p.def != null) pv = evalExpr(p.def, env)
                if (kind == "var") {
                    if (!env.setExisting(p.name, pv)) env.declareFnVar(p.name, pv)
                } else {
                    env.declare(p.name, pv)
                }
            }
            is JsPat.PMember -> {
                var pv = v
                if (pv is JsUndefined && p.def != null) pv = evalExpr(p.def, env)
                assignToTarget(p.target, env, pv)
            }
            is JsPat.PArray -> {
                patNullishGuard(v)
                var idx = 0
                for (el in p.elems) {
                    if (el != null) bindDecl(el, patGet(v, idx), kind, env)
                    idx++
                }
                val r = p.rest
                if (r != null) {
                    val n = patLen(v)
                    val arr = JsRuntime.newArr()
                    while (idx < n) {
                        arr.elems.add(patGet(v, idx))
                        idx++
                    }
                    bindDecl(r, arr, kind, env)
                }
            }
            is JsPat.PObject -> {
                patNullishGuard(v)
                val taken = HashSet<String>()
                for (en in p.entries) {
                    val k = keyString(en.key, env)
                    taken.add(k)
                    bindDecl(en.pat, getMember(v, k), kind, env)
                }
                val r = p.rest
                if (r != null) {
                    val obj = JsRuntime.newObj()
                    val props = propsOf(v)
                    if (props != null) {
                        for (kk in props.keys) {
                            if (!taken.contains(kk)) obj.props[kk] = JsProp.Value(getMember(v, kk))
                        }
                    }
                    bindDecl(r, obj, kind, env)
                }
            }
        }
    }

    private fun assignPattern(p: JsPat, env: Env, v: JsValue) {
        when (p) {
            is JsPat.PIdent -> {
                var pv = v
                if (pv is JsUndefined && p.def != null) pv = evalExpr(p.def, env)
                assignToIdent(p.name, pv, env)
            }
            is JsPat.PMember -> {
                var pv = v
                if (pv is JsUndefined && p.def != null) pv = evalExpr(p.def, env)
                assignToTarget(p.target, env, pv)
            }
            is JsPat.PArray -> {
                patNullishGuard(v)
                var idx = 0
                for (el in p.elems) {
                    if (el != null) assignPattern(el, env, patGet(v, idx))
                    idx++
                }
                val r = p.rest
                if (r != null) {
                    val n = patLen(v)
                    val arr = JsRuntime.newArr()
                    while (idx < n) {
                        arr.elems.add(patGet(v, idx))
                        idx++
                    }
                    assignPattern(r, env, arr)
                }
            }
            is JsPat.PObject -> {
                patNullishGuard(v)
                val taken = HashSet<String>()
                for (en in p.entries) {
                    val k = keyString(en.key, env)
                    taken.add(k)
                    assignPattern(en.pat, env, getMember(v, k))
                }
                val r = p.rest
                if (r != null) {
                    val obj = JsRuntime.newObj()
                    val props = propsOf(v)
                    if (props != null) {
                        for (kk in props.keys) {
                            if (!taken.contains(kk)) obj.props[kk] = JsProp.Value(getMember(v, kk))
                        }
                    }
                    assignPattern(r, env, obj)
                }
            }
        }
    }

    // ---- Sentencias ----

    private fun runBlockStatements(stmts: List<JsStmt>, env: Env, labels: List<String>): Completion {
        var last: JsValue? = null
        for (s in stmts) {
            val c = runStatement(s, env, labels)
            if (c.type != CType.NORMAL) return c
            if (c.value != null) last = c.value
        }
        return Completion(CType.NORMAL, last, null)
    }

    private fun runStatement(s: JsStmt, env: Env, labels: List<String>): Completion {
        JsQuotas.tick()
        curStmtStart = s.start
        val dbgHook = debugHook
        if (dbgHook != null) dbgHook(s, env)
        when (s) {
            is JsStmt.Empty -> return normal()
            is JsStmt.Block -> {
                val benv = Env(env, false)
                hoist(s.statements, benv)
                return runBlockStatements(s.statements, benv, labels)
            }
            is JsStmt.Var -> {
                for (d in s.declarators) {
                    var v: JsValue = JsUndefined
                    if (d.init != null) v = evalExpr(d.init, env)
                    if (d.pat != null) {
                        bindDecl(d.pat, v, s.kind, env)
                    } else if (s.kind == "var") {
                        if (!env.setExisting(d.name, v)) env.declareFnVar(d.name, v)
                    } else {
                        env.declare(d.name, v)
                    }
                }
                return normal()
            }
            is JsStmt.Function -> {
                env.declareFnVar(s.name, makeFun(s.name, s.params, s.body, null, env, false, s.async, s.gen))
                return normal()
            }
            is JsStmt.Return -> {
                var v: JsValue = JsUndefined
                if (s.value != null) v = evalExpr(s.value, env)
                return Completion(CType.RETURN, v, null)
            }
            is JsStmt.If -> {
                if (JsRuntime.toBoolean(evalExpr(s.test, env))) return runStatement(s.thenBranch, env, labels)
                if (s.elseBranch != null) return runStatement(s.elseBranch, env, labels)
                return normal()
            }
            is JsStmt.While -> {
                while (JsRuntime.toBoolean(evalExpr(s.test, env))) {
                    val c = runStatement(s.body, env, labels)
                    if (c.type == CType.BREAK) {
                        if (c.label == null || labels.contains(c.label)) break
                        return c
                    }
                    if (c.type == CType.CONTINUE) {
                        if (c.label == null || labels.contains(c.label)) continue
                        return c
                    }
                    if (c.type != CType.NORMAL) return c
                }
                return normal()
            }
            is JsStmt.DoWhile -> {
                while (true) {
                    val c = runStatement(s.body, env, labels)
                    if (c.type == CType.BREAK) {
                        if (c.label == null || labels.contains(c.label)) break
                        return c
                    }
                    if (c.type == CType.CONTINUE) {
                        if (c.label == null || labels.contains(c.label)) {
                            if (!JsRuntime.toBoolean(evalExpr(s.test, env))) break
                            continue
                        }
                        return c
                    }
                    if (c.type != CType.NORMAL) return c
                    if (!JsRuntime.toBoolean(evalExpr(s.test, env))) break
                }
                return normal()
            }
            is JsStmt.For -> {
                val fenv = Env(env, false)
                if (s.init != null) {
                    if (s.init is JsStmt.Var) {
                        runStatement(s.init, fenv, labels)
                    } else if (s.init is JsStmt.ExprStmt) {
                        evalExpr(s.init.expr, fenv)
                    }
                }
                while (s.test == null || JsRuntime.toBoolean(evalExpr(s.test, fenv))) {
                    val c = runStatement(s.body, fenv, labels)
                    if (c.type == CType.BREAK) {
                        if (c.label == null || labels.contains(c.label)) break
                        return c
                    }
                    if (c.type == CType.CONTINUE) {
                        if (c.label == null || labels.contains(c.label)) {
                            if (s.update != null) evalExpr(s.update, fenv)
                            continue
                        }
                        return c
                    }
                    if (c.type != CType.NORMAL) return c
                    if (s.update != null) evalExpr(s.update, fenv)
                }
                return normal()
            }
            is JsStmt.ForInOf -> {
                val right = evalExpr(s.right, env)
                val items = ArrayList<JsValue>()
                if (s.isOf) {
                    if (right is JsObj && right.isArr) {
                        for (el in right.elems) items.add(el)
                    } else if (right is JsStr) {
                        for (c in right.value) items.add(JsStr(c.toString()))
                    } else if (right is JsObj && genStates.containsKey(right)) {
                        while (true) {
                            val nx = genNext(right)
                            val dn = nx.props["done"]
                            if (dn is JsProp.Value && dn.v is JsBool && dn.v.value) break
                            val vv = nx.props["value"]
                            items.add(if (vv is JsProp.Value) vv.v else JsUndefined)
                        }
                    } else {
                        throw JsRuntime.throwNative("TypeError", "Value is not iterable")
                    }
                } else {
                    if (right is JsObj && right.isArr) {
                        for (j in right.elems.indices) items.add(JsStr(j.toString()))
                    } else if (right is JsObj) {
                        for (k in right.props.keys) items.add(JsStr(k))
                    } else if (right is JsStr) {
                        for (j in right.value.indices) items.add(JsStr(j.toString()))
                    }
                }
                for (item in items) {
                    val fenv = Env(env, false)
                    if (s.leftKind != null) {
                        if (s.leftKind == "var") env.declareFnVar(s.leftName!!, item)
                        else fenv.declare(s.leftName!!, item)
                    } else {
                        assignToTarget(s.leftExpr!!, fenv, item)
                    }
                    val c = runStatement(s.body, fenv, labels)
                    if (c.type == CType.BREAK) {
                        if (c.label == null || labels.contains(c.label)) break
                        return c
                    }
                    if (c.type == CType.CONTINUE) {
                        if (c.label == null || labels.contains(c.label)) continue
                        return c
                    }
                    if (c.type != CType.NORMAL) return c
                }
                return normal()
            }
            is JsStmt.Break -> return Completion(CType.BREAK, JsUndefined, s.label)
            is JsStmt.Continue -> return Completion(CType.CONTINUE, JsUndefined, s.label)
            is JsStmt.Throw -> {
                throw JsThrown(evalExpr(s.value, env))
            }
            is JsStmt.Try -> {
                var result = normal()
                var pending: JsThrown? = null
                try {
                    result = runStatement(s.block, env, labels)
                } catch (ex: Exception) {
                    if (ex is JsThrown) pending = ex else throw ex
                }
                if (pending != null && s.catchBlock != null) {
                    val cenv = Env(env, false)
                    cenv.declare(s.catchParam!!, pending.value)
                    result = runBlockStatements(s.catchBlock.statements, cenv, labels)
                    pending = null
                }
                if (s.finallyBlock != null) {
                    val fc = runStatement(s.finallyBlock, env, labels)
                    if (fc.type != CType.NORMAL) result = fc
                }
                if (pending != null) throw pending
                return result
            }
            is JsStmt.Switch -> {
                val disc = evalExpr(s.disc, env)
                val senv = Env(env, false)
                var start = -1
                var defaultIx = -1
                for (j in s.cases.indices) {
                    val c = s.cases[j]
                    if (c.test == null) {
                        if (defaultIx == -1) defaultIx = j
                    } else if (start == -1 && strictEq(disc, evalExpr(c.test, senv))) {
                        start = j
                    }
                }
                if (start == -1) start = defaultIx
                if (start == -1) return normal()
                var lastV: JsValue? = null
                for (j in start until s.cases.size) {
                    for (st in s.cases[j].statements) {
                        val c = runStatement(st, senv, labels)
                        if (c.type == CType.BREAK) {
                            if (c.label == null) return Completion(CType.NORMAL, lastV, null)
                            return c
                        }
                        if (c.type != CType.NORMAL) return c
                        if (c.value != null) lastV = c.value
                    }
                }
                return Completion(CType.NORMAL, lastV, null)
            }
            is JsStmt.ExprStmt -> return Completion(CType.NORMAL, evalExpr(s.expr, env), null)
            is JsStmt.Labeled -> {
                val nl = ArrayList(labels)
                nl.add(s.label)
                val c = runStatement(s.body, env, nl)
                if (c.type == CType.BREAK && c.label == s.label) return normal()
                return c
            }
            is JsStmt.ClassStmt -> {
                val cls = makeClassValue(s.name, s.superClass, s.members, env)
                if (s.name != null) env.declare(s.name, cls)
                return normal()
            }
        }
    }

    // ---- Expresiones ----

    private fun compoundResult(op: String, cur: JsValue, rv: JsValue): JsValue {
        if (op == "+=") return addValues(cur, rv)
        if (op == "-=") return JsNum(JsRuntime.toNumber(cur) - JsRuntime.toNumber(rv))
        if (op == "*=") return JsNum(JsRuntime.toNumber(cur) * JsRuntime.toNumber(rv))
        if (op == "/=") return JsNum(JsRuntime.toNumber(cur) / JsRuntime.toNumber(rv))
        if (op == "%=") return JsNum(JsRuntime.toNumber(cur) % JsRuntime.toNumber(rv))
        if (op == "**=") return JsNum(Math.pow(JsRuntime.toNumber(cur), JsRuntime.toNumber(rv)))
        if (op == "<<=") {
            return JsNum((JsRuntime.toInt32(cur) shl (JsRuntime.toUint32(rv) and 31L).toInt()).toDouble())
        }
        if (op == ">>=") {
            return JsNum((JsRuntime.toInt32(cur) shr (JsRuntime.toUint32(rv) and 31L).toInt()).toDouble())
        }
        if (op == ">>>=") {
            return JsNum((JsRuntime.toUint32(cur) ushr (JsRuntime.toUint32(rv) and 31L).toInt()).toDouble())
        }
        if (op == "&=") return JsNum((JsRuntime.toInt32(cur) and JsRuntime.toInt32(rv)).toDouble())
        if (op == "|=") return JsNum((JsRuntime.toInt32(cur) or JsRuntime.toInt32(rv)).toDouble())
        return JsNum((JsRuntime.toInt32(cur) xor JsRuntime.toInt32(rv)).toDouble())
    }

    private fun evalExpr(e: JsExpr, env: Env): JsValue {
        JsQuotas.tick()
        when (e) {
            is JsExpr.Ident -> return env.get(e.name)
            is JsExpr.Number -> return JsNum(e.value)
            is JsExpr.BigInt -> throw JsRuntime.throwNative("TypeError", "BigInt literals are not supported")
            is JsExpr.Str -> return JsStr(e.value)
            is JsExpr.Template -> {
                var r = e.quasis[0]
                for (j in e.exprs.indices) {
                    r += JsRuntime.toStringJs(evalExpr(e.exprs[j], env))
                    r += e.quasis[j + 1]
                }
                return JsStr(r)
            }
            is JsExpr.RegExp -> return JsRegExp(e.body, e.flags)
            is JsExpr.Bool -> return JsBool(e.value)
            is JsExpr.Null -> return JsNull
            is JsExpr.This -> return env.get("this")
            is JsExpr.ArrayLit -> {
                val arr = JsRuntime.newArr()
                for (item in e.items) {
                    if (item is JsExpr.Spread) {
                        val v = evalExpr(item.expr, env)
                        if (v is JsObj && v.isArr) {
                            for (el in v.elems) arr.elems.add(el)
                        } else if (v is JsStr) {
                            for (c in v.value) arr.elems.add(JsStr(c.toString()))
                        } else {
                            throw JsRuntime.throwNative("TypeError", "Spread element is not iterable")
                        }
                    } else {
                        arr.elems.add(evalExpr(item, env))
                    }
                }
                return arr
            }
            is JsExpr.ObjectLit -> {
                val obj = JsRuntime.newObj()
                for (p in e.props) {
                    if (p is Prop.KeyValue) {
                        obj.props[keyString(p.key, env)] = JsProp.Value(evalExpr(p.value, env))
                    } else if (p is Prop.Method) {
                        val key = keyString(p.key, env)
                        obj.props[key] = JsProp.Value(makeFun(key, p.params, p.body, null, env, false))
                    } else if (p is Prop.Shorthand) {
                        obj.props[p.name] = JsProp.Value(env.get(p.name))
                    } else if (p is Prop.SpreadProp) {
                        val v = evalExpr(p.expr, env)
                        if (v is JsObj) {
                            for ((k, entry) in v.props) {
                                if (entry is JsProp.Accessor) {
                                    val g = entry.get
                                    if (g != null) {
                                        obj.props[k] = JsProp.Value(callFunction(g, v, emptyList()))
                                    }
                                } else if (entry is JsProp.Value) {
                                    obj.props[k] = JsProp.Value(entry.v)
                                }
                            }
                        } else if (!JsRuntime.isNullish(v)) {
                            throw JsRuntime.throwNative("TypeError", "Spread expression is not an object")
                        }
                    } else if (p is Prop.Accessor) {
                        val key = keyString(p.key, env)
                        if (p.isGetter) {
                            obj.props[key] = JsProp.Accessor(makeFun(key, p.params, p.body, null, env, false), null)
                        } else {
                            obj.props[key] = JsProp.Accessor(null, makeFun(key, p.params, p.body, null, env, false))
                        }
                    }
                }
                return obj
            }
            is JsExpr.Paren -> return evalExpr(e.expr, env)
            is JsExpr.Unary -> {
                if (e.op == "typeof") {
                    val opnd = e.operand
                    if (opnd is JsExpr.Ident && env.lookupEnv(opnd.name) == null) return JsStr("undefined")
                    return JsStr(typeofValue(evalExpr(opnd, env)))
                }
                if (e.op == "delete") {
                    if (e.operand is JsExpr.Ident) return JsBool(true)
                    if (e.operand is JsExpr.Member) {
                        val m = e.operand
                        val obj = evalExpr(m.obj, env)
                        if (m.optional && JsRuntime.isNullish(obj)) return JsBool(true)
                        val key = memberKeyOf(m, env)
                        return JsBool(deleteMember(obj, key))
                    }
                    evalExpr(e.operand, env)
                    return JsBool(true)
                }
                if (e.op == "void") {
                    evalExpr(e.operand, env)
                    return JsUndefined
                }
                val v = evalExpr(e.operand, env)
                if (e.op == "!") return JsBool(!JsRuntime.toBoolean(v))
                if (e.op == "~") return JsNum(JsRuntime.toInt32(v).inv().toDouble())
                if (e.op == "+") return JsNum(JsRuntime.toNumber(v))
                return JsNum(-JsRuntime.toNumber(v))
            }
            is JsExpr.Update -> {
                if (e.operand is JsExpr.Ident) {
                    val name = (e.operand as JsExpr.Ident).name
                    val oldNum = JsRuntime.toNumber(env.get(name))
                    val nv = if (e.op == "++") oldNum + 1.0 else oldNum - 1.0
                    assignToIdent(name, JsNum(nv), env)
                    if (e.prefix) return JsNum(nv)
                    return JsNum(oldNum)
                }
                if (e.operand is JsExpr.Member) {
                    val m = e.operand
                    val obj = evalExpr(m.obj, env)
                    val key = memberKeyOf(m, env)
                    val oldNum = JsRuntime.toNumber(getMember(obj, key))
                    val nv = if (e.op == "++") oldNum + 1.0 else oldNum - 1.0
                    setMember(obj, key, JsNum(nv))
                    if (e.prefix) return JsNum(nv)
                    return JsNum(oldNum)
                }
                throw JsRuntime.throwNative("TypeError", "Invalid update target")
            }
            is JsExpr.Spread -> throw JsRuntime.throwNative("TypeError", "Unexpected spread expression")
            is JsExpr.Binary -> {
                val l = evalExpr(e.left, env)
                val r = evalExpr(e.right, env)
                if (e.op == "+") return addValues(l, r)
                if (e.op == "-") return JsNum(JsRuntime.toNumber(l) - JsRuntime.toNumber(r))
                if (e.op == "*") return JsNum(JsRuntime.toNumber(l) * JsRuntime.toNumber(r))
                if (e.op == "/") return JsNum(JsRuntime.toNumber(l) / JsRuntime.toNumber(r))
                if (e.op == "%") return JsNum(JsRuntime.toNumber(l) % JsRuntime.toNumber(r))
                if (e.op == "**") return JsNum(Math.pow(JsRuntime.toNumber(l), JsRuntime.toNumber(r)))
                if (e.op == "===") return JsBool(strictEq(l, r))
                if (e.op == "!==") return JsBool(!strictEq(l, r))
                if (e.op == "==") return JsBool(looseEq(l, r))
                if (e.op == "!=") return JsBool(!looseEq(l, r))
                if (e.op == "instanceof") return JsBool(instanceOfValue(l, r))
                if (e.op == "in") {
                    if (r !is JsObj) {
                        throw JsRuntime.throwNative("TypeError", "Cannot use in operator on non-object")
                    }
                    return JsBool(hasProp(r, JsRuntime.toStringJs(l)))
                }
                if (e.op == "<" || e.op == ">" || e.op == "<=" || e.op == ">=") {
                    if (l is JsStr && r is JsStr) {
                        if (e.op == "<") return JsBool(l.value < r.value)
                        if (e.op == ">") return JsBool(l.value > r.value)
                        if (e.op == "<=") return JsBool(l.value <= r.value)
                        return JsBool(l.value >= r.value)
                    }
                    val ln = JsRuntime.toNumber(l)
                    val rn = JsRuntime.toNumber(r)
                    if (ln.isNaN() || rn.isNaN()) return JsBool(false)
                    if (e.op == "<") return JsBool(ln < rn)
                    if (e.op == ">") return JsBool(ln > rn)
                    if (e.op == "<=") return JsBool(ln <= rn)
                    return JsBool(ln >= rn)
                }
                if (e.op == "<<") {
                    return JsNum((JsRuntime.toInt32(l) shl (JsRuntime.toUint32(r) and 31L).toInt()).toDouble())
                }
                if (e.op == ">>") {
                    return JsNum((JsRuntime.toInt32(l) shr (JsRuntime.toUint32(r) and 31L).toInt()).toDouble())
                }
                if (e.op == ">>>") {
                    return JsNum((JsRuntime.toUint32(l) ushr (JsRuntime.toUint32(r) and 31L).toInt()).toDouble())
                }
                if (e.op == "&") return JsNum((JsRuntime.toInt32(l) and JsRuntime.toInt32(r)).toDouble())
                if (e.op == "|") return JsNum((JsRuntime.toInt32(l) or JsRuntime.toInt32(r)).toDouble())
                return JsNum((JsRuntime.toInt32(l) xor JsRuntime.toInt32(r)).toDouble())
            }
            is JsExpr.Logical -> {
                if (e.op == "&&") {
                    val l = evalExpr(e.left, env)
                    if (!JsRuntime.toBoolean(l)) return l
                    return evalExpr(e.right, env)
                }
                if (e.op == "||") {
                    val l = evalExpr(e.left, env)
                    if (JsRuntime.toBoolean(l)) return l
                    return evalExpr(e.right, env)
                }
                val l = evalExpr(e.left, env)
                if (JsRuntime.isNullish(l)) return evalExpr(e.right, env)
                return l
            }
            is JsExpr.Assign -> {
                if (e.op == "=") {
                    val tl = e.target
                    val inner = if (tl is JsExpr.Paren) tl.expr else tl
                    if (inner is JsExpr.ArrayLit || inner is JsExpr.ObjectLit) {
                        val dv = evalExpr(e.value, env)
                        val pat = patFromExpr(inner)
                        if (pat == null) {
                            throw JsRuntime.throwNative("TypeError", "Invalid destructuring target")
                        }
                        assignPattern(pat, env, dv)
                        return dv
                    }
                    val v = evalExpr(e.value, env)
                    assignToTarget(e.target, env, v)
                    return v
                }
                if (e.op == "&&=" || e.op == "||=" || e.op == "??=") {
                    if (e.target is JsExpr.Member) {
                        val m = e.target
                        val obj = evalExpr(m.obj, env)
                        val key = memberKeyOf(m, env)
                        val cur = getMember(obj, key)
                        if (e.op == "&&=") {
                            if (!JsRuntime.toBoolean(cur)) return cur
                        } else if (e.op == "||=") {
                            if (JsRuntime.toBoolean(cur)) return cur
                        } else {
                            if (!JsRuntime.isNullish(cur)) return cur
                        }
                        val v = evalExpr(e.value, env)
                        setMember(obj, key, v)
                        return v
                    }
                    if (e.target is JsExpr.Ident) {
                        val name = (e.target as JsExpr.Ident).name
                        val cur = env.get(name)
                        if (e.op == "&&=") {
                            if (!JsRuntime.toBoolean(cur)) return cur
                        } else if (e.op == "||=") {
                            if (JsRuntime.toBoolean(cur)) return cur
                        } else {
                            if (!JsRuntime.isNullish(cur)) return cur
                        }
                        val v = evalExpr(e.value, env)
                        assignToIdent(name, v, env)
                        return v
                    }
                    throw JsRuntime.throwNative("TypeError", "Invalid assignment target")
                }
                if (e.target is JsExpr.Ident) {
                    val name = (e.target as JsExpr.Ident).name
                    val cur = env.get(name)
                    val rv = evalExpr(e.value, env)
                    val v = compoundResult(e.op, cur, rv)
                    assignToIdent(name, v, env)
                    return v
                }
                if (e.target is JsExpr.Member) {
                    val m = e.target
                    val obj = evalExpr(m.obj, env)
                    val key = memberKeyOf(m, env)
                    val cur = getMember(obj, key)
                    val rv = evalExpr(e.value, env)
                    val v = compoundResult(e.op, cur, rv)
                    setMember(obj, key, v)
                    return v
                }
                throw JsRuntime.throwNative("TypeError", "Invalid assignment target")
            }
            is JsExpr.Cond -> {
                if (JsRuntime.toBoolean(evalExpr(e.test, env))) return evalExpr(e.cons, env)
                return evalExpr(e.alt, env)
            }
            is JsExpr.Call -> {
                var f: JsValue
                var thisArg: JsValue = JsUndefined
                if (e.callee is JsExpr.Member) {
                    val m = e.callee
                    val obj = evalExpr(m.obj, env)
                    if (m.optional && JsRuntime.isNullish(obj)) return JsUndefined
                    val key = memberKeyOf(m, env)
                    f = getMember(obj, key)
                    thisArg = obj
                } else {
                    f = evalExpr(e.callee, env)
                }
                if (e.optional && JsRuntime.isNullish(f)) return JsUndefined
                val args = evalArgs(e.args, env)
                return callFunction(f, thisArg, args)
            }
            is JsExpr.New -> {
                val c = evalExpr(e.callee, env)
                val args = evalArgs(e.args, env)
                if (c is JsClass) return instantiate(c, args)
                if (c is JsNative) {
                    val ct = c.construct
                    if (ct != null) return ct(args)
                    val msg = if (args.isNotEmpty()) JsRuntime.toStringJs(args[0]) else ""
                    return JsRuntime.makeErrorObj("Error", msg)
                }
                if (c is JsFun) {
                    val obj = JsRuntime.newObj()
                    obj.proto = getOrCreatePrototype(c)
                    val r = callFunction(c, obj, args)
                    if (r is JsObj) return r
                    return obj
                }
                throw JsRuntime.throwNative("TypeError", "Value is not a constructor")
            }
            is JsExpr.Member -> {
                val obj = evalExpr(e.obj, env)
                if (e.optional && JsRuntime.isNullish(obj)) return JsUndefined
                return getMember(obj, memberKeyOf(e, env))
            }
            is JsExpr.Seq -> {
                var last: JsValue = JsUndefined
                for (x in e.exprs) last = evalExpr(x, env)
                return last
            }
            is JsExpr.FunctionExpr -> return makeFun(e.name, e.params, e.body, null, env, false, e.async, e.gen)
            is JsExpr.Arrow -> return makeFun(null, e.params, e.bodyBlock, e.bodyExpr, env, true, e.async)
            is JsExpr.Await -> return doAwait(evalExpr(e.arg, env))
            is JsExpr.Yield -> return doYield(e, env)
            is JsExpr.ClassExpr -> return makeClassValue(e.name, e.superClass, e.members, env)
        }
    }


    // ---- Builtins con callbacks (v2): Array iterables, Function.call/apply/bind, EventTarget ----

    fun installCallbackBuiltins(genv: Env) {
        JsBuiltins.protoArr.props["sort"] = JsProp.Value(JsBuiltins.fn("sort") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cmp = JsBuiltins.a0(args)
            val hasCmp = cmp is JsFun || cmp is JsNative
            var i = 1
            while (i < o.elems.size) {
                val x = o.elems[i]
                var j = i - 1
                while (j >= 0) {
                    val y = o.elems[j]
                    val yAfter = if (hasCmp) {
                        JsRuntime.toNumber(callFunction(cmp, JsUndefined, listOf(y, x))) > 0.0
                    } else {
                        JsRuntime.toStringJs(y) > JsRuntime.toStringJs(x)
                    }
                    if (!yAfter) break
                    o.elems[j + 1] = y
                    j--
                }
                o.elems[j + 1] = x
                i++
            }
            o
        })
        JsBuiltins.protoArr.props["forEach"] = JsProp.Value(JsBuiltins.fn("forEach") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            var j = 0
            while (j < o.elems.size) {
                callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o))
                j++
            }
            JsUndefined
        })
        JsBuiltins.protoArr.props["map"] = JsProp.Value(JsBuiltins.fn("map") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            val r = JsRuntime.newArr()
            var j = 0
            while (j < o.elems.size) {
                r.elems.add(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))
                j++
            }
            r
        })
        JsBuiltins.protoArr.props["filter"] = JsProp.Value(JsBuiltins.fn("filter") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            val r = JsRuntime.newArr()
            var j = 0
            while (j < o.elems.size) {
                if (JsRuntime.toBoolean(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))) {
                    r.elems.add(o.elems[j])
                }
                j++
            }
            r
        })
        JsBuiltins.protoArr.props["find"] = JsProp.Value(JsBuiltins.fn("find") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            var j = 0
            while (j < o.elems.size) {
                if (JsRuntime.toBoolean(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))) {
                    return@fn o.elems[j]
                }
                j++
            }
            JsUndefined
        })
        JsBuiltins.protoArr.props["findIndex"] = JsProp.Value(JsBuiltins.fn("findIndex") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            var j = 0
            while (j < o.elems.size) {
                if (JsRuntime.toBoolean(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))) {
                    return@fn JsNum(j.toDouble())
                }
                j++
            }
            JsNum(-1.0)
        })
        JsBuiltins.protoArr.props["some"] = JsProp.Value(JsBuiltins.fn("some") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            var j = 0
            while (j < o.elems.size) {
                if (JsRuntime.toBoolean(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))) {
                    return@fn JsBool(true)
                }
                j++
            }
            JsBool(false)
        })
        JsBuiltins.protoArr.props["every"] = JsProp.Value(JsBuiltins.fn("every") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            val tArg = if (args.size > 1) args[1] else JsUndefined
            var j = 0
            while (j < o.elems.size) {
                if (!JsRuntime.toBoolean(callFunction(cb, tArg, listOf(o.elems[j], JsNum(j.toDouble()), o)))) {
                    return@fn JsBool(false)
                }
                j++
            }
            JsBool(true)
        })
        JsBuiltins.protoArr.props["reduce"] = JsProp.Value(JsBuiltins.fn("reduce") { args, thiz ->
            val o = JsBuiltins.arrThis(thiz)
            val cb = JsBuiltins.a0(args)
            var acc: JsValue
            var start = 0
            if (args.size > 1) {
                acc = args[1]
            } else {
                if (o.elems.isEmpty()) {
                    throw JsRuntime.throwNative("TypeError", "Reduce of empty array with no initial value")
                }
                acc = o.elems[0]
                start = 1
            }
            var j = start
            while (j < o.elems.size) {
                acc = callFunction(cb, JsUndefined, listOf(acc, o.elems[j], JsNum(j.toDouble()), o))
                j++
            }
            acc
        })
        JsBuiltins.protoFun.props["call"] = JsProp.Value(JsBuiltins.fn("call") { args, thiz ->
            val t = if (args.isNotEmpty()) args[0] else JsUndefined
            callFunction(thiz, t, args.drop(1))
        })
        JsBuiltins.protoFun.props["apply"] = JsProp.Value(JsBuiltins.fn("apply") { args, thiz ->
            val t = if (args.isNotEmpty()) args[0] else JsUndefined
            val arrV = JsBuiltins.a1(args)
            val cargs = ArrayList<JsValue>()
            if (arrV is JsObj && arrV.isArr) cargs.addAll(arrV.elems)
            callFunction(thiz, t, cargs)
        })
        JsBuiltins.protoFun.props["bind"] = JsProp.Value(JsBuiltins.fn("bind") { args, thiz ->
            val t = if (args.isNotEmpty()) args[0] else JsUndefined
            val pre = args.drop(1)
            JsNative("bound", { later, _ ->
                val all = ArrayList<JsValue>()
                all.addAll(pre)
                all.addAll(later)
                callFunction(thiz, t, all)
            })
        })
        genv.declare("EventTarget", JsNative("EventTarget", construct = { _ ->
            val t = JsRuntime.newObj()
            t.props["__listeners"] = JsProp.Value(JsRuntime.newObj())
            t.props["addEventListener"] = JsProp.Value(JsBuiltins.fn("addEventListener") { args, thiz ->
                val target = thiz as? JsObj
                if (target != null) {
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val type = JsRuntime.toStringJs(JsBuiltins.a0(args))
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
            t.props["removeEventListener"] = JsProp.Value(JsBuiltins.fn("removeEventListener") { args, thiz ->
                val target = thiz as? JsObj
                if (target != null) {
                    val lEntry = target.props["__listeners"]
                    val map = (lEntry as? JsProp.Value)?.v as? JsObj
                    if (map != null) {
                        val type = JsRuntime.toStringJs(JsBuiltins.a0(args))
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
            t.props["dispatchEvent"] = JsProp.Value(JsBuiltins.fn("dispatchEvent") { args, thiz ->
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
                        val listEntry = map.props[JsRuntime.toStringJs(typeV)]
                        val list = (listEntry as? JsProp.Value)?.v as? JsObj
                        if (list != null) {
                            val snapshot = ArrayList<JsValue>(list.elems)
                            for (cb in snapshot) {
                                callFunction(cb, target, listOf(ev))
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
            t
        }))
        installPromise(genv)
    }

    // ---- Promise (T023 v1): constructor, then/catch y cola de microtasks ----

    private class PReaction(val onF: JsValue?, val onR: JsValue?, val child: JsObj?)

    private val promiseReactions = LinkedHashMap<JsObj, MutableList<PReaction>>()

    private fun isPromise(v: JsValue): Boolean {
        if (v !is JsObj) return false
        val e = v.props["__promise"]
        val pv = (e as? JsProp.Value)?.v
        return pv is JsBool && pv.value
    }

    private fun makePromise(): JsObj {
        val p = JsRuntime.newObj()
        p.proto = JsBuiltins.protoPromise
        p.props["__promise"] = JsProp.Value(JsBool(true))
        p.props["__state"] = JsProp.Value(JsStr("pending"))
        p.props["__value"] = JsProp.Value(JsUndefined)
        return p
    }

    private fun promiseState(p: JsObj): String? {
        val e = p.props["__state"]
        val v = (e as? JsProp.Value)?.v
        if (v is JsStr) return v.value
        return null
    }

    private fun promiseValue(p: JsObj): JsValue {
        val e = p.props["__value"]
        val v = (e as? JsProp.Value)?.v
        if (v == null) return JsUndefined
        return v
    }

    private fun settlePromise(p: JsObj, ok: Boolean, v: JsValue) {
        val st = promiseState(p)
        if (st != null && st != "pending") return
        p.props["__state"] = JsProp.Value(JsStr(if (ok) "fulfilled" else "rejected"))
        p.props["__value"] = JsProp.Value(v)
        flushReactions(p)
        val aws = awaiters.remove(p)
        if (aws != null) {
            for (r in aws) {
                val msg = if (ok) InMsg.Resume(v) else InMsg.Rej(v)
                JsRuntime.microtasks.addLast {
                    r.inbox.put(msg)
                    val m2 = r.out.take()
                    val ap = r.asyncPromise
                    if (ap != null) {
                        if (m2 is OutMsg.Done) settlePromise(ap, true, m2.v)
                        if (m2 is OutMsg.ThrowErr) settlePromise(ap, false, m2.v)
                    }
                    null
                }
            }
        }
    }

    private fun addReaction(p: JsObj, r: PReaction) {
        val rs = promiseReactions[p]
        if (rs == null) promiseReactions[p] = mutableListOf(r) else rs.add(r)
        val st = promiseState(p)
        if (st != null && st != "pending") flushReactions(p)
    }

    private fun resolvePromiseValue(p: JsObj, v: JsValue) {
        if (isPromise(v)) {
            val inner = v as JsObj
            addReaction(inner, PReaction(null, null, p))
        } else {
            settlePromise(p, true, v)
        }
    }

    private fun flushReactions(p: JsObj) {
        val rs = promiseReactions[p]
        if (rs == null || rs.isEmpty()) return
        val st = promiseState(p)
        if (st == null) return
        val ok = st != "rejected"
        val value = promiseValue(p)
        val snapshot = ArrayList(rs)
        rs.clear()
        for (r in snapshot) {
            if (r.onF == null && r.onR == null) {
                val ch0 = r.child
                if (ch0 != null) settlePromise(ch0, ok, value)
            } else {
                val h = if (ok) r.onF else r.onR
                val ch = r.child
                JsRuntime.microtasks.addLast {
                    var out: JsValue? = null
                    if (h == null) {
                        if (ch != null) settlePromise(ch, ok, value)
                    } else {
                        try {
                            val rv = callFunction(h, JsUndefined, listOf(value))
                            if (ch != null) resolvePromiseValue(ch, rv)
                            if (rv !is JsUndefined) out = rv
                        } catch (ex: Exception) {
                            if (ex is JsThrown) {
                                if (ch != null) settlePromise(ch, false, ex.value)
                            } else throw ex
                        }
                    }
                    out
                }
            }
        }
    }

    private fun handlerOrNull(args: List<JsValue>, i: Int): JsValue? {
        if (args.size <= i) return null
        val v = args[i]
        if (v is JsUndefined || v is JsNull) return null
        return v
    }

    private fun installPromise(genv: Env) {
        JsBuiltins.protoPromise.props["then"] = JsProp.Value(JsBuiltins.fn("then") { args, thiz ->
            val p = thiz as? JsObj
            if (p == null || !isPromise(p)) {
                throw JsRuntime.throwNative("TypeError", "Promise.prototype.then called on a non-Promise")
            }
            val child = makePromise()
            addReaction(p, PReaction(handlerOrNull(args, 0), handlerOrNull(args, 1), child))
            child
        })
        JsBuiltins.protoPromise.props["catch"] = JsProp.Value(JsBuiltins.fn("catch") { args, thiz ->
            val p = thiz as? JsObj
            if (p == null || !isPromise(p)) {
                throw JsRuntime.throwNative("TypeError", "Promise.prototype.catch called on a non-Promise")
            }
            val child = makePromise()
            addReaction(p, PReaction(null, handlerOrNull(args, 0), child))
            child
        })
        val ctor = JsNative("Promise", construct = { args ->
            val p = makePromise()
            val exec = JsBuiltins.a0(args)
            val resFn = JsBuiltins.fn("resolve") { a, _ ->
                resolvePromiseValue(p, JsBuiltins.a0(a))
                JsUndefined
            }
            val rejFn = JsBuiltins.fn("reject") { a, _ ->
                settlePromise(p, false, JsBuiltins.a0(a))
                JsUndefined
            }
            try {
                callFunction(exec, JsUndefined, listOf(resFn, rejFn))
            } catch (ex: Exception) {
                if (ex is JsThrown) settlePromise(p, false, ex.value) else throw ex
            }
            p
        })
        ctor.props["resolve"] = JsProp.Value(JsBuiltins.fn("resolve") { args, _ ->
            val v = JsBuiltins.a0(args)
            if (isPromise(v)) return@fn v
            val p = makePromise()
            settlePromise(p, true, v)
            p
        })
        ctor.props["reject"] = JsProp.Value(JsBuiltins.fn("reject") { args, _ ->
            val p = makePromise()
            settlePromise(p, false, JsBuiltins.a0(args))
            p
        })
        genv.declare("Promise", ctor)
    }

    // ---- Entrada del programa ----

    fun evaluate(source: String): JsEvalResult = evaluate(source, JsLimits())

    /** Evaluacion con cuotas explicitas (T021): sesion de JsQuotas de begin a end. */
    fun evaluate(source: String, limits: JsLimits): JsEvalResult {
        val parse = JsParser.parse(source)
        val genv = Env(null, true)
        genv.declare("undefined", JsUndefined)
        genv.declare("NaN", JsNum(Double.NaN))
        genv.declare("Infinity", JsNum(Double.POSITIVE_INFINITY))
        genv.declare("Error", JsNative("Error"))
        JsBuiltins.install(genv)
        installCallbackBuiltins(genv)
        JsDomBindings.installConsole(genv)
        keepAliveRunners = false
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        var aborted: JsAbortReason? = null
        JsQuotas.begin(limits)
        try {
            hoist(parse.program.statements, genv)
            for (s in parse.program.statements) {
                val c = runStatement(s, genv, emptyList())
                if (c.type != CType.NORMAL) break
                if (c.value != null) last = c.value
            }
        } catch (ex: JsAbort) {
            aborted = ex.reason
        } catch (ex: Exception) {
            if (ex is JsThrown) thrown = ex.value else throw ex
        }
        if (aborted == null) {
            try {
                val mv = drainMicrotasks()
                if (mv != null) last = mv
            } catch (ex: JsAbort) {
                aborted = ex.reason
            }
        } else {
            cleanupRunners()
        }
        JsQuotas.end()
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /** Evaluacion con bindings DOM (T022, RES-0017): instala document sobre doc. */
    fun evaluateWithDom(source: String, doc: DomDocument): JsEvalResult =
        evaluateWithDom(source, doc, JsLimits())

    /** Evaluacion con bindings DOM y cuotas explicitas (T021). */
    fun evaluateWithDom(
        source: String,
        doc: DomDocument,
        limits: JsLimits,
        fetch: JsFetchApi? = null,
        storage: JsStorageApi? = null
    ): JsEvalResult {
        val parse = JsParser.parse(source)
        val genv = Env(null, true)
        genv.declare("undefined", JsUndefined)
        genv.declare("NaN", JsNum(Double.NaN))
        genv.declare("Infinity", JsNum(Double.POSITIVE_INFINITY))
        genv.declare("Error", JsNative("Error"))
        JsBuiltins.install(genv)
        installCallbackBuiltins(genv)
        JsDomBindings.installConsole(genv)
        JsDomBindings.install(genv, doc)
        keepAliveRunners = false
        if (fetch != null) fetch.install(genv)
        if (storage != null) storage.install(genv)
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        var aborted: JsAbortReason? = null
        JsQuotas.begin(limits)
        try {
            hoist(parse.program.statements, genv)
            for (s in parse.program.statements) {
                val c = runStatement(s, genv, emptyList())
                if (c.type != CType.NORMAL) break
                if (c.value != null) last = c.value
            }
        } catch (ex: JsAbort) {
            aborted = ex.reason
        } catch (ex: Exception) {
            if (ex is JsThrown) thrown = ex.value else throw ex
        }
        if (aborted == null) {
            try {
                val mv = drainMicrotasks()
                if (mv != null) last = mv
            } catch (ex: JsAbort) {
                aborted = ex.reason
            }
        } else {
            cleanupRunners()
        }
        JsQuotas.end()
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /**
     * Pide la interrupcion de la evaluacion en curso (T021): conmuta el flag
     * volatile de la sesion de cuotas y el hilo que ejecuta JS aborta en el
     * siguiente safepoint con JsAbortReason.INTERRUPTED.
     */
    fun interrupt() {
        JsQuotas.interrupt()
    }

    // ---- Sesion del event loop (T025 v1, RES-0032) ----
    // La sesion del loop se mantiene entre pumps: genv, cuotas y runners
    // de async/await vivos mientras el host avanza el reloj (un await
    // asentado por el callback de un setTimeout continua en el pump
    // siguiente); endLoopSession corta y limpia. El evaluate clasico
    // fija keepAliveRunners = false en su entrada, asi que nunca se ve
    // afectado por una sesion del loop que quedo abierta.

    private var keepAliveRunners = false

    /** Abre la sesion del loop: genv con los builtins, runners vivos y cuotas. */
    internal fun startLoopSession(limits: JsLimits): Env {
        val genv = Env(null, true)
        genv.declare("undefined", JsUndefined)
        genv.declare("NaN", JsNum(Double.NaN))
        genv.declare("Infinity", JsNum(Double.POSITIVE_INFINITY))
        genv.declare("Error", JsNative("Error"))
        JsBuiltins.install(genv)
        installCallbackBuiltins(genv)
        JsDomBindings.installConsole(genv)
        keepAliveRunners = true
        JsQuotas.begin(limits)
        return genv
    }

    /** Corre source en la sesion abierta (mismo genv) y drena microtasks sin limpiar runners. */
    internal fun runInSession(source: String, genv: Env): JsEvalResult {
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        var aborted: JsAbortReason? = null
        try {
            val parse = JsParser.parse(source)
            hoist(parse.program.statements, genv)
            for (s in parse.program.statements) {
                val c = runStatement(s, genv, emptyList())
                if (c.type != CType.NORMAL) break
                if (c.value != null) last = c.value
            }
        } catch (ex: JsAbort) {
            aborted = ex.reason
        } catch (ex: Exception) {
            if (ex is JsThrown) thrown = ex.value else throw ex
        }
        if (aborted == null) {
            try {
                val mv = drainMicrotasks()
                if (mv != null) last = mv
            } catch (ex: JsAbort) {
                aborted = ex.reason
            }
        }
        return JsEvalResult(last, thrown != null, thrown, aborted)
    }

    /** Drena microtasks dentro de la sesion del loop (sin limpiar runners). */
    internal fun drainInSession(): JsValue? = drainMicrotasks()

    /** Cierra la sesion del loop: corta runners, limpia colas/registros y las cuotas. */
    internal fun endLoopSession() {
        keepAliveRunners = false
        cleanupRunners()
        JsQuotas.end()
    }

    // ---- Workers (T034 v1, RES-0039) ----

    /**
     * Abre la sesion del MOTOR para los workers (T034): como el loop de T025
     * (runners vivos entre pumps y cuotas activas) pero SIN crear el genv,
     * porque cada worker crea el suyo con makeWorkerGlobal. La sesion es
     * unica por motor: las cuotas (T021) son del motor y quedan compartidas
     * entre los workers (limitacion documentada de la v1).
     */
    internal fun beginWorkerSession(limits: JsLimits) {
        keepAliveRunners = true
        JsQuotas.begin(limits)
    }

    /**
     * Global nuevo para un worker (T034): el mismo equipamiento del loop de
     * T025 (builtins, callbacks, consola) SIN la API de timers; cada worker
     * instala la suya sobre su reloj virtual propio.
     */
    internal fun makeWorkerGlobal(): Env {
        val genv = Env(null, true)
        genv.declare("undefined", JsUndefined)
        genv.declare("NaN", JsNum(Double.NaN))
        genv.declare("Infinity", JsNum(Double.POSITIVE_INFINITY))
        genv.declare("Error", JsNative("Error"))
        JsBuiltins.install(genv)
        installCallbackBuiltins(genv)
        JsDomBindings.installConsole(genv)
        return genv
    }


    // ---- Fetch JS (T027 v1, RES-0033) ----

    /** Promise nueva para un builtin nativo (fetch); el builtin la asienta. */
    internal fun nativePromise(): JsObj = makePromise()

    /** Asienta una Promise de un builtin nativo (fetch): reacciones y awaiters incluidos. */
    internal fun settleNativePromise(p: JsObj, ok: Boolean, v: JsValue) {
        settlePromise(p, ok, v)
    }

    // ---- Debugger JS (T028 v1, RES-0034) ----

    /**
     * Hook del debugger (T028): lo instala JsDebugger para observar cada
     * sentencia ejecutada (safepoint por sentencia, junto al tick de T021);
     * null cuando no hay debugger activo.
     */
    internal var debugHook: ((JsStmt, Env) -> Unit)? = null

    // ---- Call stack del debugger (T028 v4, RES-0048) ----

    /** Frame de llamada activa (T028 v4): nombre de la funcion y offset
     * de la sentencia del llamador en curso (lo fija runStatement). */
    internal class JsCallFrame(val name: String?, val callStart: Int)

    /** Offset de la sentencia en curso; lo leen los frames de llamada. */
    private var curStmtStart = -1

    /** Pila de llamadas sincronas activas; vacia en top-level. */
    private val callFrames = ArrayDeque<JsCallFrame>()

    /** Copia del call stack activo (mas interno primero) para el debugger. */
    internal fun callStack(): List<JsCallFrame> = callFrames.toList()

    /**
     * Evalua una expresion en el entorno pausado del debugger (T028 v2):
     * parsea UNA sentencia de expresion y la evalua con evalExpr en el
     * env dado (bindings visibles del scope, incluidos los de closure).
     * La llama DebugSession.inspect desde el propio hilo de JS pausado.
     */
    internal fun debugEvalExpression(src: String, env: Env): JsValue {
        val parse = JsParser.parse(src)
        require(parse.program.statements.size == 1) { "se espera una sola expresion" }
        val s = parse.program.statements[0]
        require(s is JsStmt.ExprStmt) { "se espera una expresion" }
        return evalExpr(s.expr, env)
    }




    // ---- Runners reanudables (T018 v5): async/await y generators ----
    // Handoff estricto: en cada momento SOLO un hilo ejecuta JS. Un Runner
    // corre en su propio hilo (la pila del hilo ES la maquina de estados:
    // suspender es bloquearse en su inbox) y quien lo lanza o reanuda espera
    // su senal de parada (Paused/Yield/Done/ThrowErr) antes de continuar.

    private class AbortRunner : RuntimeException()

    private sealed class InMsg {
        object NEXT : InMsg()
        object ABORT : InMsg()
        class Resume(val v: JsValue) : InMsg()
        class Rej(val v: JsValue) : InMsg()
    }

    private sealed class OutMsg {
        object Paused : OutMsg()
        class Yield(val v: JsValue) : OutMsg()
        class Done(val v: JsValue) : OutMsg()
        class ThrowErr(val v: JsValue) : OutMsg()
        class Aborted(val reason: JsAbortReason) : OutMsg()
    }

    private class Runner {
        val inbox = LinkedBlockingQueue<InMsg>()
        val out = LinkedBlockingQueue<OutMsg>()
        var thread: Thread? = null
        var finished = false
        var asyncPromise: JsObj? = null
    }

    private class GenState(val f: JsFun, val thisArg: JsValue, val args: List<JsValue>) {
        var runner: Runner? = null
        var done = false
    }

    private val currentRunner = ThreadLocal<Runner?>()

    private val activeRunners = ArrayList<Runner>()

    private val awaiters = LinkedHashMap<JsObj, MutableList<Runner>>()

    private val genStates = LinkedHashMap<JsObj, GenState>()

    private fun startRunner(body: () -> JsValue): Runner {
        val r = Runner()
        activeRunners.add(r)
        val t = Thread {
            currentRunner.set(r)
            try {
                val v = body()
                r.finished = true
                r.out.put(OutMsg.Done(v))
            } catch (e: AbortRunner) {
                r.finished = true
            } catch (e: JsThrown) {
                r.finished = true
                r.out.put(OutMsg.ThrowErr(e.value))
            } catch (e: JsAbort) {
                r.finished = true
                r.out.put(OutMsg.Aborted(e.reason))
            } catch (e: Throwable) {
                r.finished = true
                r.out.put(OutMsg.ThrowErr(JsRuntime.makeErrorObj("Error", "internal runner error")))
            } finally {
                currentRunner.set(null)
            }
        }
        r.thread = t
        t.isDaemon = true
        t.start()
        return r
    }

    private fun startAsync(f: JsFun, thisArg: JsValue, args: List<JsValue>): JsObj {
        val p = makePromise()
        val r = startRunner { runFunBody(f, thisArg, args) }
        val s = r.out.take()
        if (s is OutMsg.Aborted) throw JsAbort(s.reason)
        if (s is OutMsg.Done) settlePromise(p, true, s.v)
        if (s is OutMsg.ThrowErr) settlePromise(p, false, s.v)
        // Pausa real: la promise p la asentara el microtask del awaiter cuando el Runner termine.
        if (s is OutMsg.Paused) r.asyncPromise = p
        return p
    }

    private fun makeGenerator(f: JsFun, thisArg: JsValue, args: List<JsValue>): JsObj {
        val g = JsRuntime.newObj()
        genStates[g] = GenState(f, thisArg, args)
        g.props["next"] = JsProp.Value(JsBuiltins.fn("next") { _, thiz ->
            val go = thiz as? JsObj
            if (go == null || !genStates.containsKey(go)) {
                throw JsRuntime.throwNative("TypeError", "Generator next called on incompatible receiver")
            }
            genNext(go)
        })
        return g
    }

    private fun genNext(g: JsObj): JsObj {
        val st = genStates[g]
        if (st == null || st.done) return genResult(JsUndefined, true)
        var r = st.runner
        if (r == null) {
            r = startRunner { runFunBody(st.f, st.thisArg, st.args) }
            st.runner = r
        } else {
            r.inbox.put(InMsg.NEXT)
        }
        val s = r.out.take()
        if (s is OutMsg.Aborted) {
            st.done = true
            throw JsAbort(s.reason)
        }
        if (s is OutMsg.Yield) return genResult(s.v, false)
        if (s is OutMsg.Done) {
            st.done = true
            return genResult(s.v, true)
        }
        if (s is OutMsg.ThrowErr) {
            st.done = true
            throw JsThrown(s.v)
        }
        st.done = true
        return genResult(JsUndefined, true)
    }

    private fun genResult(v: JsValue, done: Boolean): JsObj {
        val o = JsRuntime.newObj()
        o.props["value"] = JsProp.Value(v)
        o.props["done"] = JsProp.Value(JsBool(done))
        return o
    }

    private fun doAwait(v: JsValue): JsValue {
        if (v !is JsObj || !isPromise(v)) return v
        val st = promiseState(v)
        if (st == "fulfilled") return promiseValue(v)
        if (st == "rejected") throw JsThrown(promiseValue(v))
        val r = currentRunner.get()
        if (r == null) {
            throw JsRuntime.throwNative("TypeError", "await is only valid inside async functions")
        }
        val list = awaiters[v]
        if (list == null) awaiters[v] = mutableListOf(r) else list.add(r)
        r.out.put(OutMsg.Paused)
        val m = r.inbox.take()
        return when (m) {
            is InMsg.Resume -> m.v
            is InMsg.Rej -> throw JsThrown(m.v)
            is InMsg.NEXT -> JsUndefined
            is InMsg.ABORT -> throw AbortRunner()
        }
    }

    private fun doYield(e: JsExpr.Yield, env: Env): JsValue {
        val r = currentRunner.get()
        if (r == null) {
            throw JsRuntime.throwNative("TypeError", "yield is only valid inside generator functions")
        }
        if (e.star) {
            val a = e.arg
            if (a == null) return JsUndefined
            return delegateYield(r, evalExpr(a, env))
        }
        val a = e.arg
        val v = if (a == null) JsUndefined else evalExpr(a, env)
        return suspendYield(r, v)
    }

    private fun suspendYield(r: Runner, v: JsValue): JsValue {
        r.out.put(OutMsg.Yield(v))
        val m = r.inbox.take()
        if (m is InMsg.ABORT) throw AbortRunner()
        return JsUndefined
    }

    private fun delegateYield(r: Runner, src: JsValue): JsValue {
        var last: JsValue = JsUndefined
        if (src is JsObj && genStates.containsKey(src)) {
            while (true) {
                val nx = genNext(src)
                val dn = nx.props["done"]
                if (dn is JsProp.Value && dn.v is JsBool && dn.v.value) {
                    val vv = nx.props["value"]
                    if (vv is JsProp.Value) last = vv.v
                    break
                }
                val vv = nx.props["value"]
                if (vv is JsProp.Value) suspendYield(r, vv.v)
            }
            return last
        }
        if (src is JsObj && src.isArr) {
            for (el in src.elems) {
                last = el
                suspendYield(r, el)
            }
            return last
        }
        if (src is JsStr) {
            for (c in src.value) {
                last = JsStr(c.toString())
                suspendYield(r, last)
            }
            return last
        }
        throw JsRuntime.throwNative("TypeError", "yield* argument is not iterable")
    }

    /**
     * Drena la cola de microtasks (T023 + T018 v5): cada microtask devuelve el
     * valor del handler; el ultimo no-undefined se convierte en completion
     * value del REPL. Tope de 10000 para bucles que encolan sin fin. Al final
     * aborta los runners vivos (awaits de promises que nadie cumplira,
     * generadores sin consumir), limpia la cola, las reacciones y los
     * registros de runners (aislamiento entre evaluaciones).
     */
    private fun drainMicrotasks(): JsValue? {
        var res: JsValue? = null
        try {
            var n = 0
            while (JsRuntime.microtasks.isNotEmpty() && n < 10000) {
                val f = JsRuntime.microtasks.removeFirst()
                n++
                try {
                    val v = f()
                    if (v != null && v !is JsUndefined) res = v
                } catch (ex: Exception) {
                    if (ex !is JsThrown) throw ex
                }
            }
        } finally {
            if (!keepAliveRunners) cleanupRunners()
        }
        return res
    }

    /**
     * Corte de sesion: aborta los runners vivos y limpia los registros
     * compartidos (aislamiento entre evaluaciones). Con cuotas (T021) es
     * tambien el camino de limpieza del abort: un JsAbort nunca deja
     * runners ni colas vivas detras de si.
     */
    private fun cleanupRunners() {
        for (r in ArrayList(activeRunners)) {
            if (!r.finished) {
                r.inbox.put(InMsg.ABORT)
                val t = r.thread
                if (t != null) {
                    try {
                        t.join(50)
                    } catch (e: InterruptedException) {
                    }
                }
            }
        }
        activeRunners.clear()
        JsRuntime.microtasks.clear()
        promiseReactions.clear()
        awaiters.clear()
        genStates.clear()
    }

    fun dumpResult(r: JsEvalResult): String {
        val reason = r.abortReason
        if (reason != null) {
            if (reason == JsAbortReason.INTERRUPTED) return "interrupted"
            if (reason == JsAbortReason.STEP_QUOTA) return "interrupted step-quota"
            if (reason == JsAbortReason.DEBUG_PAUSE) return "interrupted debugger"
            return "interrupted heap-quota"
        }
        if (r.thrown) {
            val tv = r.thrownValue
            if (tv != null) return "uncaught " + JsRuntime.dumpValue(tv)
            return "uncaught undefined"
        }
        return JsRuntime.dumpValue(r.value)
    }
}

