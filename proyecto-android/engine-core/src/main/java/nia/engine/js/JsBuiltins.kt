package nia.engine.js

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.pow

/**
 * Builtins JS v1+v2 (T020): Math, Number, globales de conversion,
 * Object.keys, Array.isArray, JSON.parse/stringify (v1) y prototipos
 * reales con metodos esenciales de Object/Array/String/Number/Boolean
 * mas Event (v2). Expectativas generadas contra el oraculo ECMAScript
 * (JS nativo; Event/EventTarget por semantica del estandar DOM),
 * nunca transcritas a mano.
 * T023 v1 anade Date (epoch ms) y RegExp (exec/test sobre java.util.regex);
 * Promise vive en el interprete (necesita callFunction) con la cola de
 * microtasks del runtime.
 * Exclusiones: console (T022), Date sin TZ configurables ni toString local,
 * Promise sin all/race/finally, RegExp sin sticky/unicode, fracciones en
 * Number.prototype.toString(radix) y metodos menos comunes.
 */
object JsBuiltins {

    private fun d(x: Double): JsValue = JsNum(x)

    internal fun a0(args: List<JsValue>): JsValue {
        if (args.isNotEmpty()) return args[0]
        return JsUndefined
    }

    internal fun a1(args: List<JsValue>): JsValue {
        if (args.size > 1) return args[1]
        return JsUndefined
    }

    internal fun fn(name: String, f: (List<JsValue>, JsValue) -> JsValue): JsNative {
        return JsNative(name, f)
    }

    private fun put(o: JsValue, k: String, v: JsValue) {
        if (o is JsObj) {
            o.props[k] = JsProp.Value(v)
            return
        }
        if (o is JsNative) o.props[k] = JsProp.Value(v)
    }

    // ---- Prototipos v2 (T020): raices de la cadena de busqueda ----

    val protoObj: JsObj = JsObj(LinkedHashMap(), null, false, ArrayList())
    val protoArr: JsObj = JsObj(LinkedHashMap(), protoObj, true, ArrayList())
    val protoStr: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoNum: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoBool: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoFun: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoDate: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoReg: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())
    val protoPromise: JsObj = JsObj(LinkedHashMap(), protoObj, false, ArrayList())

    private fun str(s: String): JsValue = JsStr(s)

    private fun strOf(v: JsValue): String = JsRuntime.toStringJs(v)

    /** this de un metodo de array: exige objeto array. */
    internal fun arrThis(thiz: JsValue): JsObj {
        val o = thiz as? JsObj
        if (o == null || !o.isArr) {
            throw JsRuntime.throwNative("TypeError", "Array method called on non-array")
        }
        return o
    }

    /** Igualdad estricta espejo (indexOf de arrays y listeners). */
    internal fun same(a: JsValue, b: JsValue): Boolean {
        if (a is JsUndefined && b is JsUndefined) return true
        if (a is JsNull && b is JsNull) return true
        if (a is JsBool && b is JsBool) return a.value == b.value
        if (a is JsNum && b is JsNum) return a.value == b.value
        if (a is JsStr && b is JsStr) return a.value == b.value
        if (a is JsObj && b is JsObj) return a === b
        if (a is JsFun && b is JsFun) return a === b
        if (a is JsNative && b is JsNative) return a === b
        if (a is JsClass && b is JsClass) return a === b
        if (a is JsRegExp && b is JsRegExp) return a === b
        return false
    }

    /** Normaliza un indice relativo: negativo desde el final, clamped. */
    private fun normIdx(v: JsValue, len: Int, def: Int): Int {
        if (v is JsUndefined) return def
        var n = JsRuntime.toInt32(v)
        if (n < 0) n += len
        if (n < 0) n = 0
        if (n > len) n = len
        return n
    }

    private fun joinStrings(o: JsObj, sep: String): String {
        val sb = StringBuilder()
        var first = true
        for (e in o.elems) {
            if (!first) sb.append(sep)
            first = false
            if (e is JsUndefined || e is JsNull) continue
            sb.append(JsRuntime.toStringJs(e))
        }
        return sb.toString()
    }

    // ---- parseInt / parseFloat (espejo del algoritmo ECMAScript) ----

    private fun parseIntJs(sRaw: String, radixV: JsValue): Double {
        var s = sRaw.trim()
        var sign = 1.0
        if (s.startsWith("-")) {
            sign = -1.0
            s = s.substring(1)
        } else if (s.startsWith("+")) {
            s = s.substring(1)
        }
        var radix = JsRuntime.toNumber(radixV).toInt()
        if (radix == 0 && (s.startsWith("0x") || s.startsWith("0X"))) {
            radix = 16
            s = s.substring(2)
        }
        if (radix == 0) radix = 10
        if (radix < 2 || radix > 36) return Double.NaN
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var i = 0
        var acc = 0.0
        while (i < s.length) {
            val v = digits.indexOf(s[i].lowercaseChar())
            if (v < 0 || v >= radix) break
            acc = acc * radix + v
            i++
        }
        if (i == 0) return Double.NaN
        return sign * acc
    }

    private fun parseFloatJs(sRaw: String): Double {
        val s = sRaw.trim()
        val n = s.length
        var i = 0
        if (i < n && (s[i] == '+' || s[i] == '-')) i++
        var seen = false
        while (i < n && s[i] in '0'..'9') {
            i++
            seen = true
        }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') {
                i++
                seen = true
            }
        }
        if (seen && i < n && (s[i] == 'e' || s[i] == 'E')) {
            val save = i
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            val expStart = i
            while (i < n && s[i] in '0'..'9') i++
            if (i == expStart) i = save
        }
        if (!seen) return Double.NaN
        return s.substring(0, i).toDoubleOrNull() ?: Double.NaN
    }

    // ---- Math ----

    private fun mathMinMax(args: List<JsValue>, isMin: Boolean): JsValue {
        if (args.isEmpty()) {
            if (isMin) return d(Double.POSITIVE_INFINITY)
            return d(Double.NEGATIVE_INFINITY)
        }
        var best = JsRuntime.toNumber(args[0])
        for (j in 1 until args.size) {
            val x = JsRuntime.toNumber(args[j])
            if (x.isNaN()) return d(Double.NaN)
            if (isMin) {
                if (x < best) best = x
            } else {
                if (x > best) best = x
            }
        }
        return d(best)
    }

    // ---- JSON ----

    private class JsonP(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun fail(): Nothing = throw JsRuntime.throwNative("SyntaxError", "Unexpected token in JSON at " + i)

        fun parse(): JsValue {
            val v = parseValue()
            ws()
            if (i < s.length) fail()
            return v
        }

        fun parseValue(): JsValue {
            ws()
            if (i >= s.length) fail()
            val c = s[i]
            if (c == '{') return parseObj()
            if (c == '[') return parseArr()
            if (c == '"') return JsStr(parseStr())
            if (s.startsWith("true", i)) {
                i += 4
                return JsBool(true)
            }
            if (s.startsWith("false", i)) {
                i += 5
                return JsBool(false)
            }
            if (s.startsWith("null", i)) {
                i += 4
                return JsNull
            }
            return parseNum()
        }

        fun parseObj(): JsValue {
            i++
            val o = JsRuntime.newObj()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return o
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = parseStr()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                o.props[k] = JsProp.Value(parseValue())
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    return o
                }
                fail()
            }
        }

        fun parseArr(): JsValue {
            i++
            val a = JsRuntime.newArr()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return a
            }
            while (true) {
                a.elems.add(parseValue())
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    return a
                }
                fail()
            }
        }

        fun parseStr(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    i++
                    if (i >= s.length) fail()
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) fail()
                            val hex = s.substring(i + 1, i + 5)
                            val cp = hex.toIntOrNull(16)
                            if (cp == null) fail()
                            sb.append(cp.toChar())
                            i += 4
                        }
                        else -> fail()
                    }
                    i++
                    continue
                }
                sb.append(c)
                i++
            }
            fail()
        }

        fun parseNum(): JsValue {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val expStart = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == expStart) i = save
            }
            val t = s.substring(start, i)
            val dv = t.toDoubleOrNull()
            if (dv == null) fail()
            return JsNum(dv)
        }
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.append('\"').toString()
    }

    private fun stringifyInto(v: JsValue, sb: StringBuilder): Boolean {
        when (v) {
            is JsUndefined -> return false
            is JsNull -> {
                sb.append("null")
                return true
            }
            is JsBool -> {
                if (v.value) sb.append("true") else sb.append("false")
                return true
            }
            is JsNum -> {
                sb.append(JsRuntime.jsNumToString(v.value))
                return true
            }
            is JsStr -> {
                sb.append(jsonStr(v.value))
                return true
            }
            is JsObj -> {
                if (v.isArr) {
                    sb.append('[')
                    var first = true
                    for (e in v.elems) {
                        if (!first) sb.append(',')
                        first = false
                        if (!stringifyInto(e, sb)) sb.append("null")
                    }
                    sb.append(']')
                    return true
                }
                sb.append('{')
                var first = true
                for ((k, p) in v.props) {
                    if (p !is JsProp.Value) continue
                    val pv = p.v
                    if (pv is JsUndefined) continue
                    if (pv is JsFun) continue
                    if (pv is JsClass) continue
                    if (pv is JsNative) continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append(jsonStr(k)).append(':')
                    stringifyInto(pv, sb)
                }
                sb.append('}')
                return true
            }
            else -> return false
        }
    }

    private fun stringify(args: List<JsValue>): JsValue {
        if (args.isEmpty()) return JsUndefined
        val sb = StringBuilder()
        if (!stringifyInto(args[0], sb)) return JsUndefined
        return JsStr(sb.toString())
    }

    // ---- Instalacion ----

    fun install(genv: Env) {
        genv.declare("parseInt", fn("parseInt") { args, _ ->
            JsNum(parseIntJs(JsRuntime.toStringJs(a0(args)), a1(args)))
        })
        genv.declare("parseFloat", fn("parseFloat") { args, _ ->
            JsNum(parseFloatJs(JsRuntime.toStringJs(a0(args))))
        })
        genv.declare("isNaN", fn("isNaN") { args, _ ->
            JsBool(JsRuntime.toNumber(a0(args)).isNaN())
        })
        genv.declare("isFinite", fn("isFinite") { args, _ ->
            JsBool(JsRuntime.toNumber(a0(args)).isFinite())
        })
        val stringNat = fn("String") { args, _ ->
            if (args.isEmpty()) str("") else str(strOf(args[0]))
        }
        put(stringNat, "fromCharCode", fn("fromCharCode") { args, _ ->
            val sb = StringBuilder()
            for (a in args) sb.append(JsRuntime.toInt32(a).toChar())
            str(sb.toString())
        })
        genv.declare("String", stringNat)
        genv.declare("Number", fn("Number") { args, _ ->
            if (args.isEmpty()) JsNum(0.0) else JsNum(JsRuntime.toNumber(args[0]))
        })
        genv.declare("Boolean", fn("Boolean") { args, _ ->
            JsBool(JsRuntime.toBoolean(a0(args)))
        })

        // Math (objeto no callable)
        val math = JsRuntime.newObj()
        put(math, "PI", d(kotlin.math.PI))
        put(math, "E", d(kotlin.math.E))
        put(math, "abs", fn("abs") { args, _ -> d(kotlin.math.abs(JsRuntime.toNumber(a0(args)))) })
        put(math, "ceil", fn("ceil") { args, _ -> d(kotlin.math.ceil(JsRuntime.toNumber(a0(args)))) })
        put(math, "floor", fn("floor") { args, _ -> d(kotlin.math.floor(JsRuntime.toNumber(a0(args)))) })
        put(math, "round", fn("round") { args, _ ->
            val n = JsRuntime.toNumber(a0(args))
            d(kotlin.math.floor(n + 0.5))
        })
        put(math, "trunc", fn("trunc") { args, _ ->
            val n = JsRuntime.toNumber(a0(args))
            if (n.isNaN() || n.isInfinite()) return@fn d(n)
            if (n >= 0) return@fn d(kotlin.math.floor(n))
            d(kotlin.math.ceil(n))
        })
        put(math, "sign", fn("sign") { args, _ ->
            val n = JsRuntime.toNumber(a0(args))
            if (n.isNaN()) return@fn d(n)
            if (n > 0) return@fn d(1.0)
            if (n < 0) return@fn d(-1.0)
            d(n)
        })
        put(math, "sqrt", fn("sqrt") { args, _ -> d(kotlin.math.sqrt(JsRuntime.toNumber(a0(args)))) })
        put(math, "pow", fn("pow") { args, _ ->
            d(JsRuntime.toNumber(a0(args)).pow(JsRuntime.toNumber(a1(args))))
        })
        put(math, "min", fn("min") { args, _ -> mathMinMax(args, true) })
        put(math, "max", fn("max") { args, _ -> mathMinMax(args, false) })
        put(math, "log", fn("log") { args, _ -> d(kotlin.math.ln(JsRuntime.toNumber(a0(args)))) })
        put(math, "log2", fn("log2") { args, _ -> d(kotlin.math.log2(JsRuntime.toNumber(a0(args)))) })
        put(math, "log10", fn("log10") { args, _ -> d(kotlin.math.log10(JsRuntime.toNumber(a0(args)))) })
        put(math, "exp", fn("exp") { args, _ -> d(kotlin.math.exp(JsRuntime.toNumber(a0(args)))) })
        put(math, "random", fn("random") { _, _ -> d(kotlin.random.Random.nextDouble()) })
        genv.declare("Math", math)

        // Number: funcion callable con miembros
        val number = JsNative("Number", { args, _ ->
            if (args.isEmpty()) JsNum(0.0) else JsNum(JsRuntime.toNumber(args[0]))
        })
        put(number, "isInteger", fn("isInteger") { args, _ ->
            val v = a0(args)
            if (v is JsNum) JsBool(v.value.isFinite() && v.value == kotlin.math.floor(v.value)) else JsBool(false)
        })
        put(number, "isFinite", fn("isFinite") { args, _ ->
            val v = a0(args)
            if (v is JsNum) JsBool(v.value.isFinite()) else JsBool(false)
        })
        put(number, "isNaN", fn("isNaN") { args, _ ->
            val v = a0(args)
            if (v is JsNum) JsBool(v.value.isNaN()) else JsBool(false)
        })
        put(number, "parseInt", fn("parseInt") { args, _ ->
            JsNum(parseIntJs(JsRuntime.toStringJs(a0(args)), a1(args)))
        })
        put(number, "parseFloat", fn("parseFloat") { args, _ ->
            JsNum(parseFloatJs(JsRuntime.toStringJs(a0(args))))
        })
        put(number, "EPSILON", d(2.220446049250313e-16))
        put(number, "MAX_SAFE_INTEGER", d(9007199254740991.0))
        put(number, "MIN_SAFE_INTEGER", d(-9007199254740991.0))
        genv.declare("Number", number)

        // Object: funcion identidad sobre objetos con keys
        val obj = JsNative("Object", { args, _ ->
            val v = a0(args)
            if (v is JsObj) v else JsRuntime.newObj()
        })
        put(obj, "keys", fn("keys") { args, _ ->
            val v = a0(args)
            val arr = JsRuntime.newArr()
            if (v is JsObj) {
                if (v.isArr) {
                    for (j in v.elems.indices) arr.elems.add(JsStr(j.toString()))
                } else {
                    for (k in v.props.keys) arr.elems.add(JsStr(k))
                }
            }
            arr
        })
        put(obj, "prototype", protoObj)
        genv.declare("Object", obj)

        // Array: constructor simple con isArray
        val array = JsNative("Array", { args, _ ->
            val a = JsRuntime.newArr()
            if (args.size == 1 && args[0] is JsNum) {
                val n = (args[0] as JsNum).value
                if (n < 0 || n != kotlin.math.floor(n)) {
                    throw JsRuntime.throwNative("RangeError", "Invalid array length")
                }
                for (j in 0 until n.toInt()) a.elems.add(JsUndefined)
            } else {
                for (x in args) a.elems.add(x)
            }
            a
        })
        put(array, "isArray", fn("isArray") { args, _ ->
            val v = a0(args)
            if (v is JsObj) JsBool(v.isArr) else JsBool(false)
        })
        genv.declare("Array", array)

        // JSON (objeto no callable)
        val json = JsRuntime.newObj()
        put(json, "parse", fn("parse") { args, _ ->
            JsonP(JsRuntime.toStringJs(a0(args))).parse()
        })
        put(json, "stringify", fn("stringify") { args, _ -> stringify(args) })
        genv.declare("JSON", json)

        // ---- v2: prototipos, raiz de todos los objetos y arrays ----
        JsRuntime.objProto = protoObj
        JsRuntime.arrProto = protoArr
        installProtoObj()
        installProtoArr()
        installProtoStr()
        installProtoNum()
        installProtoBool()
        installProtoDate()
        installProtoReg()
        genv.declare("Event", JsNative("Event", construct = { args -> makeEvent(args) }))
        genv.declare("Date", makeDateCtor())
        genv.declare("RegExp", makeRegExpCtor())
    }

    // ---- Object.prototype ----

    private fun installProtoObj() {
        put(protoObj, "hasOwnProperty", fn("hasOwnProperty") { args, thiz ->
            val o = thiz as? JsObj
            if (o == null) return@fn JsBool(false)
            val k = strOf(a0(args))
            if (o.isArr) {
                if (k == "length") return@fn JsBool(true)
                val ix = k.toIntOrNull()
                if (ix != null && ix >= 0 && ix < o.elems.size) return@fn JsBool(true)
            }
            JsBool(o.props.containsKey(k))
        })
        put(protoObj, "toString", fn("toString") { _, thiz ->
            str(JsRuntime.toStringJs(thiz))
        })
    }

    // ---- Array.prototype (los metodos con callbacks van en JsInterpreter) ----

    private fun installProtoArr() {
        put(protoArr, "push", fn("push") { args, thiz ->
            val o = arrThis(thiz)
            for (x in args) o.elems.add(x)
            d(o.elems.size.toDouble())
        })
        put(protoArr, "pop", fn("pop") { _, thiz ->
            val o = arrThis(thiz)
            if (o.elems.isEmpty()) return@fn JsUndefined
            o.elems.removeAt(o.elems.size - 1)
        })
        put(protoArr, "shift", fn("shift") { _, thiz ->
            val o = arrThis(thiz)
            if (o.elems.isEmpty()) return@fn JsUndefined
            o.elems.removeAt(0)
        })
        put(protoArr, "unshift", fn("unshift") { args, thiz ->
            val o = arrThis(thiz)
            o.elems.addAll(0, args)
            d(o.elems.size.toDouble())
        })
        put(protoArr, "slice", fn("slice") { args, thiz ->
            val o = arrThis(thiz)
            val st = normIdx(a0(args), o.elems.size, 0)
            val en = normIdx(a1(args), o.elems.size, o.elems.size)
            val r = JsRuntime.newArr()
            if (en > st) r.elems.addAll(o.elems.subList(st, en))
            r
        })
        put(protoArr, "splice", fn("splice") { args, thiz ->
            val o = arrThis(thiz)
            val len = o.elems.size
            val st = normIdx(a0(args), len, 0)
            var dc = len - st
            if (args.size > 1 && args[1] !is JsUndefined) {
                dc = JsRuntime.toInt32(args[1])
            }
            if (dc < 0) dc = 0
            if (dc > len - st) dc = len - st
            val removed = JsRuntime.newArr()
            for (j in 0 until dc) removed.elems.add(o.elems[st + j])
            for (j in 0 until dc) o.elems.removeAt(st)
            if (args.size > 2) o.elems.addAll(st, args.subList(2, args.size))
            removed
        })
        put(protoArr, "indexOf", fn("indexOf") { args, thiz ->
            val o = arrThis(thiz)
            val target = a0(args)
            var from = 0
            if (args.size > 1 && args[1] !is JsUndefined) {
                from = JsRuntime.toInt32(args[1])
                if (from < 0) from += o.elems.size
                if (from < 0) from = 0
            }
            var j = from
            while (j < o.elems.size) {
                if (same(o.elems[j], target)) return@fn d(j.toDouble())
                j++
            }
            d(-1.0)
        })
        put(protoArr, "lastIndexOf", fn("lastIndexOf") { args, thiz ->
            val o = arrThis(thiz)
            val target = a0(args)
            var j = o.elems.size - 1
            while (j >= 0) {
                if (same(o.elems[j], target)) return@fn d(j.toDouble())
                j--
            }
            d(-1.0)
        })
        put(protoArr, "includes", fn("includes") { args, thiz ->
            val o = arrThis(thiz)
            val target = a0(args)
            for (e in o.elems) {
                if (same(e, target)) return@fn JsBool(true)
                if (e is JsNum && target is JsNum && e.value.isNaN() && target.value.isNaN()) return@fn JsBool(true)
            }
            JsBool(false)
        })
        put(protoArr, "join", fn("join") { args, thiz ->
            val o = arrThis(thiz)
            val sep = if (args.isNotEmpty() && args[0] !is JsUndefined) strOf(args[0]) else ","
            str(joinStrings(o, sep))
        })
        put(protoArr, "concat", fn("concat") { args, thiz ->
            val o = arrThis(thiz)
            val r = JsRuntime.newArr()
            r.elems.addAll(o.elems)
            for (a in args) {
                if (a is JsObj && a.isArr) r.elems.addAll(a.elems) else r.elems.add(a)
            }
            r
        })
        put(protoArr, "reverse", fn("reverse") { _, thiz ->
            val o = arrThis(thiz)
            o.elems.reverse()
            o
        })
        put(protoArr, "toString", fn("toString") { _, thiz ->
            str(joinStrings(arrThis(thiz), ","))
        })
    }

    // ---- String.prototype ----

    private fun installProtoStr() {
        put(protoStr, "charAt", fn("charAt") { args, thiz ->
            val s = strOf(thiz)
            val i = if (args.isNotEmpty() && args[0] !is JsUndefined) JsRuntime.toInt32(args[0]) else 0
            if (i < 0 || i >= s.length) return@fn str("")
            str(s[i].toString())
        })
        put(protoStr, "charCodeAt", fn("charCodeAt") { args, thiz ->
            val s = strOf(thiz)
            val i = if (args.isNotEmpty() && args[0] !is JsUndefined) JsRuntime.toInt32(args[0]) else 0
            if (i < 0 || i >= s.length) return@fn d(Double.NaN)
            d(s[i].code.toDouble())
        })
        put(protoStr, "indexOf", fn("indexOf") { args, thiz ->
            val s = strOf(thiz)
            val q = strOf(a0(args))
            var from = 0
            if (args.size > 1 && args[1] !is JsUndefined) {
                from = JsRuntime.toInt32(args[1])
                if (from < 0) from = 0
            }
            if (from >= s.length) {
                if (q.isEmpty() && from == s.length) return@fn d(from.toDouble())
                return@fn d(-1.0)
            }
            d(s.indexOf(q, from).toDouble())
        })
        put(protoStr, "lastIndexOf", fn("lastIndexOf") { args, thiz ->
            val s = strOf(thiz)
            d(s.lastIndexOf(strOf(a0(args))).toDouble())
        })
        put(protoStr, "includes", fn("includes") { args, thiz ->
            JsBool(strOf(thiz).contains(strOf(a0(args))))
        })
        put(protoStr, "startsWith", fn("startsWith") { args, thiz ->
            JsBool(strOf(thiz).startsWith(strOf(a0(args))))
        })
        put(protoStr, "endsWith", fn("endsWith") { args, thiz ->
            JsBool(strOf(thiz).endsWith(strOf(a0(args))))
        })
        put(protoStr, "slice", fn("slice") { args, thiz ->
            val s = strOf(thiz)
            val st = normIdx(a0(args), s.length, 0)
            val en = normIdx(a1(args), s.length, s.length)
            if (en <= st) return@fn str("")
            str(s.substring(st, en))
        })
        put(protoStr, "substring", fn("substring") { args, thiz ->
            val s = strOf(thiz)
            var st = normIdx(a0(args), s.length, 0)
            var en = normIdx(a1(args), s.length, s.length)
            if (st > en) {
                val tmp = st
                st = en
                en = tmp
            }
            str(s.substring(st, en))
        })
        put(protoStr, "toUpperCase", fn("toUpperCase") { _, thiz -> str(strOf(thiz).uppercase()) })
        put(protoStr, "toLowerCase", fn("toLowerCase") { _, thiz -> str(strOf(thiz).lowercase()) })
        put(protoStr, "trim", fn("trim") { _, thiz -> str(strOf(thiz).trim()) })
        put(protoStr, "split", fn("split") { args, thiz ->
            val s = strOf(thiz)
            val r = JsRuntime.newArr()
            if (args.isEmpty() || args[0] is JsUndefined) {
                r.elems.add(str(s))
                return@fn r
            }
            val sep = strOf(args[0])
            if (sep.isEmpty()) {
                for (c in s) r.elems.add(str(c.toString()))
                return@fn r
            }
            for (p in s.split(sep)) r.elems.add(str(p))
            r
        })
        put(protoStr, "replace", fn("replace") { args, thiz ->
            val s = strOf(thiz)
            val old = strOf(a0(args))
            val nw = strOf(a1(args))
            val i = s.indexOf(old)
            if (i < 0) return@fn str(s)
            str(s.substring(0, i) + nw + s.substring(i + old.length))
        })
        put(protoStr, "repeat", fn("repeat") { args, thiz ->
            val n = JsRuntime.toInt32(a0(args))
            if (n <= 0) return@fn str("")
            str(strOf(thiz).repeat(n))
        })
        put(protoStr, "padStart", fn("padStart") { args, thiz ->
            val s = strOf(thiz)
            val t = JsRuntime.toInt32(a0(args))
            val p = if (args.size > 1 && args[1] !is JsUndefined) strOf(args[1]) else " "
            if (t <= s.length || p.isEmpty()) return@fn str(s)
            var out = p
            while (out.length < t - s.length) out += p
            str(out.substring(0, t - s.length) + s)
        })
        put(protoStr, "padEnd", fn("padEnd") { args, thiz ->
            val s = strOf(thiz)
            val t = JsRuntime.toInt32(a0(args))
            val p = if (args.size > 1 && args[1] !is JsUndefined) strOf(args[1]) else " "
            if (t <= s.length || p.isEmpty()) return@fn str(s)
            var out = p
            while (out.length < t - s.length) out += p
            str(s + out.substring(0, t - s.length))
        })
        put(protoStr, "concat", fn("concat") { args, thiz ->
            val sb = StringBuilder(strOf(thiz))
            for (a in args) sb.append(strOf(a))
            str(sb.toString())
        })
        put(protoStr, "toString", fn("toString") { _, thiz -> str(strOf(thiz)) })
    }

    // ---- Number.prototype y Boolean.prototype ----

    private fun installProtoNum() {
        put(protoNum, "toFixed", fn("toFixed") { args, thiz ->
            val n = JsRuntime.toNumber(thiz)
            if (n.isNaN()) return@fn str("NaN")
            if (n.isInfinite()) return@fn str(if (n > 0) "Infinity" else "-Infinity")
            var f = 0
            if (args.isNotEmpty() && args[0] !is JsUndefined) f = JsRuntime.toInt32(args[0])
            if (f < 0) f = 0
            val bd = BigDecimal(n).setScale(f, RoundingMode.HALF_UP)
            str(bd.toPlainString())
        })
        put(protoNum, "toString", fn("toString") { args, thiz ->
            val n = JsRuntime.toNumber(thiz)
            if (args.isEmpty() || args[0] is JsUndefined) return@fn str(JsRuntime.jsNumToString(n))
            val r = JsRuntime.toInt32(a0(args))
            if (r < 2 || r > 36) throw JsRuntime.throwNative("RangeError", "toString() radix must be between 2 and 36")
            if (n.isNaN()) return@fn str("NaN")
            if (n.isInfinite()) return@fn str(if (n > 0) "Infinity" else "-Infinity")
            if (Math.abs(n) >= 1.0e21) return@fn str(JsRuntime.jsNumToString(n))
            if (n == 0.0) return@fn str("0")
            val neg = n < 0
            var m = kotlin.math.floor(Math.abs(n))
            val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
            var out = ""
            while (m >= 1.0) {
                val rem = (m % r).toInt()
                out = digits[rem].toString() + out
                m = kotlin.math.floor(m / r)
            }
            str(if (neg) "-" + out else out)
        })
    }

    private fun installProtoBool() {
        put(protoBool, "toString", fn("toString") { _, thiz ->
            str(JsRuntime.toStringJs(thiz))
        })
    }

    // ---- Event (semantica del estandar DOM; la propagacion por el arbol llega con T024) ----

    private fun makeEvent(args: List<JsValue>): JsObj {
        val e = JsRuntime.newObj()
        e.props["type"] = JsProp.Value(str(strOf(a0(args))))
        var bubbles = false
        var cancelable = false
        val init = a1(args)
        if (init is JsObj) {
            val b = init.props["bubbles"]
            if (b is JsProp.Value) bubbles = JsRuntime.toBoolean(b.v)
            val c = init.props["cancelable"]
            if (c is JsProp.Value) cancelable = JsRuntime.toBoolean(c.v)
        }
        e.props["bubbles"] = JsProp.Value(JsBool(bubbles))
        e.props["cancelable"] = JsProp.Value(JsBool(cancelable))
        e.props["defaultPrevented"] = JsProp.Value(JsBool(false))
        e.props["target"] = JsProp.Value(JsNull)
        e.props["currentTarget"] = JsProp.Value(JsNull)
        e.props["preventDefault"] = JsProp.Value(fn("preventDefault") { _, thiz ->
            val ev = thiz as? JsObj
            if (ev != null) {
                val cEntry = ev.props["cancelable"]
                val cVal = (cEntry as? JsProp.Value)?.v
                val can = cVal is JsBool && cVal.value
                if (can) ev.props["defaultPrevented"] = JsProp.Value(JsBool(true))
            }
            JsUndefined
        })
        e.props["stopPropagation"] = JsProp.Value(fn("stopPropagation") { _, _ -> JsUndefined })
        return e
    }

    // ---- Date (T023 v1): epoch ms interno, getters UTC y locales, ISO ----

    private fun dateMsOf(o: JsValue): Double {
        val d = o as? JsObj
        if (d != null) {
            val e = d.props["__ms"]
            val v = (e as? JsProp.Value)?.v
            if (v is JsNum) return v.value
        }
        throw JsRuntime.throwNative("TypeError", "Date method called on incompatible receiver")
    }

    private fun utcCal(ms: Double): Calendar {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = ms.toLong()
        return c
    }

    private fun localCal(ms: Double): Calendar {
        val c = Calendar.getInstance()
        c.timeInMillis = ms.toLong()
        return c
    }

    private fun calField(c: Calendar, f: Int): JsValue = JsNum(c.get(f).toDouble())

    private fun isoOf(ms: Double): String {
        val c = utcCal(ms)
        val y = c.get(Calendar.YEAR)
        val y4 = if (y < 0) "-" + String.format(java.util.Locale.ROOT, "%06d", -y) else String.format(java.util.Locale.ROOT, "%04d", y)
        return y4 + "-" +
            String.format(java.util.Locale.ROOT, "%02d", c.get(Calendar.MONTH) + 1) + "-" +
            String.format(java.util.Locale.ROOT, "%02d", c.get(Calendar.DAY_OF_MONTH)) + "T" +
            String.format(java.util.Locale.ROOT, "%02d", c.get(Calendar.HOUR_OF_DAY)) + ":" +
            String.format(java.util.Locale.ROOT, "%02d", c.get(Calendar.MINUTE)) + ":" +
            String.format(java.util.Locale.ROOT, "%02d", c.get(Calendar.SECOND)) + "." +
            String.format(java.util.Locale.ROOT, "%03d", c.get(Calendar.MILLISECOND)) + "Z"
    }

    private val isoPat: Pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,3}))?(Z)?)?")

    /** Date.parse v1: ISO 8601 (solo fecha o con hora; sin Z se asume UTC); NaN si no parsea. */
    internal fun parseIsoDate(s: String): Double {
        val m = isoPat.matcher(s.trim())
        if (!m.matches()) return Double.NaN
        val hhG = m.group(4)
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (hhG == null) {
            c.set(m.group(1).toInt(), m.group(2).toInt() - 1, m.group(3).toInt(), 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis.toDouble()
        }
        val ssG = m.group(6)
        val msG = m.group(7)
        var mss = 0
        if (msG != null) {
            var t = msG
            while (t.length < 3) t += "0"
            mss = t.toInt()
        }
        c.set(m.group(1).toInt(), m.group(2).toInt() - 1, m.group(3).toInt(), hhG.toInt(), m.group(5).toInt(), if (ssG == null) 0 else ssG.toInt())
        c.set(Calendar.MILLISECOND, mss)
        return c.timeInMillis.toDouble()
    }

    private fun dateUtc(args: List<JsValue>): Double {
        val yy = Math.floor(JsRuntime.toNumber(a0(args))).toInt()
        val mo = if (args.size > 1) Math.floor(JsRuntime.toNumber(args[1])).toInt() else 0
        val dd = if (args.size > 2) Math.floor(JsRuntime.toNumber(args[2])).toInt() else 1
        val hh = if (args.size > 3) Math.floor(JsRuntime.toNumber(args[3])).toInt() else 0
        val mi = if (args.size > 4) Math.floor(JsRuntime.toNumber(args[4])).toInt() else 0
        val ss = if (args.size > 5) Math.floor(JsRuntime.toNumber(args[5])).toInt() else 0
        val mss = if (args.size > 6) Math.floor(JsRuntime.toNumber(args[6])).toInt() else 0
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.set(yy, mo, dd, hh, mi, ss)
        c.set(Calendar.MILLISECOND, mss)
        return c.timeInMillis.toDouble()
    }

    private fun makeDate(args: List<JsValue>): JsObj {
        var ms = System.currentTimeMillis().toDouble()
        if (args.isNotEmpty()) {
            if (args.size == 1) {
                val a = args[0]
                if (a is JsNum) {
                    ms = a.value
                } else if (a is JsObj) {
                    val e = a.props["__ms"]
                    val v = (e as? JsProp.Value)?.v
                    if (v is JsNum) ms = v.value else ms = parseIsoDate(strOf(a))
                } else {
                    ms = parseIsoDate(strOf(a))
                }
            } else {
                val yy = Math.floor(JsRuntime.toNumber(args[0])).toInt()
                val mo = Math.floor(JsRuntime.toNumber(args[1])).toInt()
                val dd = if (args.size > 2) Math.floor(JsRuntime.toNumber(args[2])).toInt() else 1
                val hh = if (args.size > 3) Math.floor(JsRuntime.toNumber(args[3])).toInt() else 0
                val mi = if (args.size > 4) Math.floor(JsRuntime.toNumber(args[4])).toInt() else 0
                val ss = if (args.size > 5) Math.floor(JsRuntime.toNumber(args[5])).toInt() else 0
                val mss = if (args.size > 6) Math.floor(JsRuntime.toNumber(args[6])).toInt() else 0
                val c = Calendar.getInstance()
                c.set(yy, mo, dd, hh, mi, ss)
                c.set(Calendar.MILLISECOND, mss)
                ms = c.timeInMillis.toDouble()
            }
        }
        val o = JsRuntime.newObj()
        o.proto = protoDate
        o.props["__ms"] = JsProp.Value(JsNum(ms))
        return o
    }

    private fun makeDateCtor(): JsNative {
        val d = JsNative(
            "Date",
            { _, _ -> JsStr(isoOf(System.currentTimeMillis().toDouble())) },
            LinkedHashMap(),
            { args -> makeDate(args) }
        )
        put(d, "now", fn("now") { _, _ -> JsNum(System.currentTimeMillis().toDouble()) })
        put(d, "UTC", fn("UTC") { args, _ -> JsNum(dateUtc(args)) })
        put(d, "parse", fn("parse") { args, _ -> JsNum(parseIsoDate(strOf(a0(args)))) })
        return d
    }

    private fun installProtoDate() {
        put(protoDate, "getTime", fn("getTime") { _, thiz -> JsNum(dateMsOf(thiz)) })
        put(protoDate, "valueOf", fn("valueOf") { _, thiz -> JsNum(dateMsOf(thiz)) })
        put(protoDate, "toISOString", fn("toISOString") { _, thiz -> JsStr(isoOf(dateMsOf(thiz))) })
        put(protoDate, "getTimezoneOffset", fn("getTimezoneOffset") { _, thiz ->
            JsNum(-(localCal(dateMsOf(thiz)).timeZone.getRawOffset() / 60000.0))
        })
        put(protoDate, "getFullYear", fn("getFullYear") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.YEAR) })
        put(protoDate, "getMonth", fn("getMonth") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.MONTH) })
        put(protoDate, "getDate", fn("getDate") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.DAY_OF_MONTH) })
        put(protoDate, "getDay", fn("getDay") { _, thiz -> JsNum((localCal(dateMsOf(thiz)).get(Calendar.DAY_OF_WEEK) - 1).toDouble()) })
        put(protoDate, "getHours", fn("getHours") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.HOUR_OF_DAY) })
        put(protoDate, "getMinutes", fn("getMinutes") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.MINUTE) })
        put(protoDate, "getSeconds", fn("getSeconds") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.SECOND) })
        put(protoDate, "getMilliseconds", fn("getMilliseconds") { _, thiz -> calField(localCal(dateMsOf(thiz)), Calendar.MILLISECOND) })
        put(protoDate, "getUTCFullYear", fn("getUTCFullYear") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.YEAR) })
        put(protoDate, "getUTCMonth", fn("getUTCMonth") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.MONTH) })
        put(protoDate, "getUTCDate", fn("getUTCDate") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.DAY_OF_MONTH) })
        put(protoDate, "getUTCDay", fn("getUTCDay") { _, thiz -> JsNum((utcCal(dateMsOf(thiz)).get(Calendar.DAY_OF_WEEK) - 1).toDouble()) })
        put(protoDate, "getUTCHours", fn("getUTCHours") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.HOUR_OF_DAY) })
        put(protoDate, "getUTCMinutes", fn("getUTCMinutes") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.MINUTE) })
        put(protoDate, "getUTCSeconds", fn("getUTCSeconds") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.SECOND) })
        put(protoDate, "getUTCMilliseconds", fn("getUTCMilliseconds") { _, thiz -> calField(utcCal(dateMsOf(thiz)), Calendar.MILLISECOND) })
    }

    // ---- RegExp (T023 v1): exec/test con java.util.regex, flags i/m/g ----

    private val reCache = HashMap<String, Pattern>()

    private fun compileRegex(body: String, flags: String): Pattern {
        val key = body + "|" + flags
        val cached = reCache[key]
        if (cached != null) return cached
        var f = 0
        var i = 0
        while (i < flags.length) {
            val ch = flags[i]
            if (ch == 'i') {
                f = f or Pattern.CASE_INSENSITIVE
            } else if (ch == 'm') {
                f = f or Pattern.MULTILINE
            } else if (ch != 'g') {
                throw JsRuntime.throwNative("SyntaxError", "Invalid flags supplied to RegExp '" + ch + "'")
            }
            i++
        }
        val p = try {
            Pattern.compile(body, f)
        } catch (ex: PatternSyntaxException) {
            throw JsRuntime.throwNative("SyntaxError", "Invalid regular expression: /" + body + "/: " + ex.message)
        }
        reCache[key] = p
        return p
    }

    private fun regNew(args: List<JsValue>): JsRegExp {
        val a = a0(args)
        var body = ""
        var flags = ""
        if (a is JsRegExp) {
            body = a.body
            flags = a.flags
        } else if (a is JsStr) {
            body = a.value
        } else if (a !is JsUndefined) {
            body = strOf(a)
        }
        val f = a1(args)
        if (f is JsStr) {
            flags = f.value
        } else if (f !is JsUndefined) {
            flags = strOf(f)
        }
        compileRegex(body, flags)
        return JsRegExp(body, flags)
    }

    private fun regExec(re: JsRegExp, s: String): JsObj? {
        val pat = compileRegex(re.body, re.flags)
        val g = re.flags.contains('g')
        var start = 0
        if (g) {
            start = re.lastIndex.toInt()
            if (start < 0) start = 0
            if (start > s.length) {
                re.lastIndex = 0.0
                return null
            }
        }
        val m = pat.matcher(s)
        if (!m.find(start)) {
            if (g) re.lastIndex = 0.0
            return null
        }
        if (g) re.lastIndex = m.end().toDouble()
        val r = JsRuntime.newArr()
        r.elems.add(JsStr(m.group()))
        var gi = 1
        while (gi <= m.groupCount()) {
            val gv = m.group(gi)
            if (gv == null) r.elems.add(JsUndefined) else r.elems.add(JsStr(gv))
            gi++
        }
        r.props["index"] = JsProp.Value(JsNum(m.start().toDouble()))
        r.props["input"] = JsProp.Value(JsStr(s))
        return r
    }

    private fun makeRegExpCtor(): JsNative {
        return JsNative("RegExp", { args, _ -> regNew(args) }, LinkedHashMap(), { args -> regNew(args) })
    }

    private fun installProtoReg() {
        put(protoReg, "exec", fn("exec") { args, thiz ->
            val re = thiz as? JsRegExp
            if (re == null) {
                throw JsRuntime.throwNative("TypeError", "RegExp.prototype.exec called on incompatible receiver")
            }
            val m = regExec(re, strOf(a0(args)))
            if (m == null) return@fn JsNull
            m
        })
        put(protoReg, "test", fn("test") { args, thiz ->
            val re = thiz as? JsRegExp
            if (re == null) {
                throw JsRuntime.throwNative("TypeError", "RegExp.prototype.test called on incompatible receiver")
            }
            JsBool(regExec(re, strOf(a0(args))) != null)
        })
    }
}
