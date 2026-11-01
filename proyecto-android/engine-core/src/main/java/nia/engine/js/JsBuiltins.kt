package nia.engine.js

/**
 * Builtins JS v1 (T020): Math, Number, globales de conversion,
 * Object.keys, Array.isArray y JSON.parse/stringify (subconjunto).
 * Expectativas de los tests generadas contra el oraculo ECMAScript
 * (JS nativo), nunca transcritas a mano.
 * Exclusiones v1: metodos de prototipo y Object.prototype real
 * (v2 con Event/EventTarget), console (T022), Date/Promise/RegExp.exec.
 */
object JsBuiltins {

    private fun d(x: Double): JsValue = JsNum(x)

    private fun a0(args: List<JsValue>): JsValue {
        if (args.isNotEmpty()) return args[0]
        return JsUndefined
    }

    private fun a1(args: List<JsValue>): JsValue {
        if (args.size > 1) return args[1]
        return JsUndefined
    }

    private fun fn(name: String, f: (List<JsValue>, JsValue) -> JsValue): JsNative {
        return JsNative(name, f)
    }

    private fun put(o: JsValue, k: String, v: JsValue) {
        if (o is JsObj) {
            o.props[k] = JsProp.Value(v)
            return
        }
        if (o is JsNative) o.props[k] = JsProp.Value(v)
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
        genv.declare("String", fn("String") { args, _ ->
            if (args.isEmpty()) JsStr("") else JsStr(JsRuntime.toStringJs(args[0]))
        })
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
            d(kotlin.math.pow(JsRuntime.toNumber(a0(args)), JsRuntime.toNumber(a1(args))))
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
        genv.declare("Object", obj)

        // Array: constructor simple con isArray
        val array = JsNative("Array", { args, _ ->
            val a = JsRuntime.newArr()
            if (args.size == 1 && args[0] is JsNum) {
                val n = (args[0] as JsNum).value
                if (n < 0 || n != kotlin.math.floor(n)) {
                    return@JsNative JsRuntime.throwNative("RangeError", "Invalid array length")
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
    }
}
