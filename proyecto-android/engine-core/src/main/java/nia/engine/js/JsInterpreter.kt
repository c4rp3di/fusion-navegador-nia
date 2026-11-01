package nia.engine.js

import nia.engine.dom.DomDocument

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
 * Exclusiones v1: builtins salvo Error, Object.prototype, super, TDZ, strict,
 * arguments, destructuring/async/generators/modulos, regex exec.
 */
class JsEvalResult(val value: JsValue, val thrown: Boolean, val thrownValue: JsValue?)

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
        env: Env, isArrow: Boolean
    ): JsFun = JsFun(name, params, body, bodyExpr, env, isArrow, LinkedHashMap())

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
            else -> null
        }
    }

    fun setMember(o: JsValue, key: String, v: JsValue) {
        if (JsRuntime.isNullish(o)) {
            throw JsRuntime.throwNative("TypeError", "Cannot set properties of " + JsRuntime.toStringJs(o))
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
        val env = Env(f.env, true)
        if (!f.isArrow) {
            env.declare("this", thisArg)
            if (f.name != null) env.declare(f.name, f)
        }
        for (j in f.params.indices) {
            val p = f.params[j]
            var v: JsValue = if (j < args.size) args[j] else JsUndefined
            if (v is JsUndefined && p.def != null) v = evalExpr(p.def, env)
            env.declare(p.name, v)
        }
        if (f.isArrow && f.bodyExpr != null) return evalExpr(f.bodyExpr, env)
        val b = f.body
        if (b == null) return JsUndefined
        hoist(b.statements, env)
        val c = runBlockStatements(b.statements, env, emptyList())
        if (c.type == CType.RETURN) return c.value ?: JsUndefined
        return JsUndefined
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
                    for (d in s.declarators) env.declareFnVar(d.name, JsUndefined)
                }
            }
            is JsStmt.Function -> env.declareFnVar(s.name, makeFun(s.name, s.params, s.body, null, env, false))
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
                    if (s.kind == "var") {
                        if (!env.setExisting(d.name, v)) env.declareFnVar(d.name, v)
                    } else {
                        env.declare(d.name, v)
                    }
                }
                return normal()
            }
            is JsStmt.Function -> {
                env.declareFnVar(s.name, makeFun(s.name, s.params, s.body, null, env, false))
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
            is JsExpr.FunctionExpr -> return makeFun(e.name, e.params, e.body, null, env, false)
            is JsExpr.Arrow -> return makeFun(null, e.params, e.bodyBlock, e.bodyExpr, env, true)
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
    }

    // ---- Entrada del programa ----

    fun evaluate(source: String): JsEvalResult {
        val parse = JsParser.parse(source)
        val genv = Env(null, true)
        genv.declare("undefined", JsUndefined)
        genv.declare("NaN", JsNum(Double.NaN))
        genv.declare("Infinity", JsNum(Double.POSITIVE_INFINITY))
        genv.declare("Error", JsNative("Error"))
        JsBuiltins.install(genv)
        installCallbackBuiltins(genv)
        JsDomBindings.installConsole(genv)
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        try {
            hoist(parse.program.statements, genv)
            for (s in parse.program.statements) {
                val c = runStatement(s, genv, emptyList())
                if (c.type != CType.NORMAL) break
                if (c.value != null) last = c.value
            }
        } catch (ex: Exception) {
            if (ex is JsThrown) thrown = ex.value else throw ex
        }
        return JsEvalResult(last, thrown != null, thrown)
    }

    /** Evaluacion con bindings DOM (T022, RES-0017): instala document sobre doc. */
    fun evaluateWithDom(source: String, doc: DomDocument): JsEvalResult {
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
        var last: JsValue = JsUndefined
        var thrown: JsValue? = null
        try {
            hoist(parse.program.statements, genv)
            for (s in parse.program.statements) {
                val c = runStatement(s, genv, emptyList())
                if (c.type != CType.NORMAL) break
                if (c.value != null) last = c.value
            }
        } catch (ex: Exception) {
            if (ex is JsThrown) thrown = ex.value else throw ex
        }
        return JsEvalResult(last, thrown != null, thrown)
    }

    fun dumpResult(r: JsEvalResult): String {
        if (r.thrown) {
            val tv = r.thrownValue
            if (tv != null) return "uncaught " + JsRuntime.dumpValue(tv)
            return "uncaught undefined"
        }
        return JsRuntime.dumpValue(r.value)
    }
}
