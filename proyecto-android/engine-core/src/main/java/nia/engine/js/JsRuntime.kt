package nia.engine.js

/**
 * Runtime ECMAScript v1 (T019): valores, conversiones y dump canonico.
 * Espejo validado del gemelo TS/JS del diseno (runtime.js @ gemelo R0027).
 * Valores: undefined/null/boolean/number (Double IEEE-754)/string/RegExp
 * (como valor), objetos (propias orden insercion + cadena de prototipos,
 * accessors get/set como descriptores), arrays (con length mantenida),
 * funciones (closure + this por llamada) y clases. Error es el unico builtin.
 */
sealed class JsValue

object JsUndefined : JsValue()

object JsNull : JsValue()

class JsBool(val value: Boolean) : JsValue()

class JsNum(val value: Double) : JsValue()

class JsStr(val value: String) : JsValue()

class JsRegExp(val body: String, val flags: String) : JsValue()

/** Propiedad de objeto: valor directo o descriptor accessor. */
sealed class JsProp {
    class Value(val v: JsValue) : JsProp()
    class Accessor(val get: JsFun?, val set: JsFun?) : JsProp()
}

/** Objeto: propiedades propias en orden de insercion, prototipo y vector de arrays. */
class JsObj(
    val props: LinkedHashMap<String, JsProp>,
    var proto: JsObj?,
    val isArr: Boolean,
    val elems: MutableList<JsValue>
) : JsValue()

/** Funcion: declaracion/expresion/metodo o arrow, con entorno de closure. */
class JsFun(
    val name: String?,
    val params: List<Param>,
    val body: JsStmt.Block?,
    val bodyExpr: JsExpr?,
    val env: Env,
    val isArrow: Boolean,
    val props: LinkedHashMap<String, JsProp>
) : JsValue()

/** Clase: prototipo de instancia, constructor, miembro static y entorno. */
class JsClass(
    val name: String?,
    var superClass: JsClass?,
    val proto: JsObj,
    var ctor: ClassMember?,
    val env: Env,
    val props: LinkedHashMap<String, JsProp>
) : JsValue()

/** Builtin nativo: solo el constructor Error en v1. */
class JsNative(val name: String) : JsValue()

/**
 * Valor lanzado. WritableStackTrace desactivado: los throws internos son
 * control de flujo y capturar stacktraces costaria por cada throw.
 */
class JsThrown(val value: JsValue) : RuntimeException(value.toString(), null, false, false)

/**
 * Entorno de variables: cadena de scopes con frontera de funcion (fnScope).
 * var con hoisting a la funcion (declareFnVar), let/const de bloque (declare).
 * Vive a nivel de paquete porque JsFun/JsClass guardan su Env de closure y
 * el runtime no puede depender de tipos anidados en el interprete.
 */
class Env(val parent: Env?, val fnScope: Boolean) {
    val vars = HashMap<String, JsValue>()

    fun lookupEnv(name: String): Env? {
        var e: Env? = this
        while (e != null) {
            if (e.vars.containsKey(name)) return e
            e = e.parent
        }
        return null
    }

    fun get(name: String): JsValue {
        val e = lookupEnv(name)
        if (e == null) throw JsRuntime.throwNative("ReferenceError", name + " is not defined")
        return e.vars[name] ?: JsUndefined
    }

    fun declare(name: String, v: JsValue) {
        vars[name] = v
    }

    fun declareFnVar(name: String, v: JsValue) {
        var e: Env? = this
        while (e != null && !e.fnScope) e = e.parent
        if (e == null) e = this
        e.vars[name] = v
    }

    fun setExisting(name: String, v: JsValue): Boolean {
        val e = lookupEnv(name)
        if (e == null) return false
        e.vars[name] = v
        return true
    }
}

object JsRuntime {

    fun newObj(): JsObj = JsObj(LinkedHashMap(), null, false, ArrayList())

    fun newArr(): JsObj = JsObj(LinkedHashMap(), null, true, ArrayList())

    fun makeErrorObj(name: String, message: String): JsObj {
        val o = newObj()
        o.props["name"] = JsProp.Value(JsStr(name))
        o.props["message"] = JsProp.Value(JsStr(message))
        return o
    }

    fun throwNative(name: String, message: String): JsThrown {
        return JsThrown(makeErrorObj(name, message))
    }

    // ---- Conversiones ----

    fun toBoolean(v: JsValue): Boolean {
        return when (v) {
            is JsUndefined, is JsNull -> false
            is JsBool -> v.value
            is JsNum -> v.value != 0.0 && v.value == v.value
            is JsStr -> v.value != ""
            else -> true
        }
    }

    fun toNumber(v: JsValue): Double {
        return when (v) {
            is JsUndefined -> Double.NaN
            is JsNull -> 0.0
            is JsBool -> if (v.value) 1.0 else 0.0
            is JsNum -> v.value
            is JsStr -> strToNumber(v.value)
            is JsObj -> strToNumber(toStringJs(v))
            else -> Double.NaN
        }
    }

    /** Espejo de ToNumber(string): trim, "" -> 0, hex 0x, decimal/exponentes, invalido -> NaN. */
    fun strToNumber(s: String): Double {
        var a = 0
        var b = s.length
        while (a < b && isJsWs(s[a])) a++
        while (b > a && isJsWs(s[b - 1])) b--
        if (a >= b) return 0.0
        val t = s.substring(a, b)
        if ((t.startsWith("0x")) || (t.startsWith("0X"))) {
            val h = t.substring(2)
            if (h.isEmpty()) return Double.NaN
            var r = 0.0
            for (c in h) {
                val d = hexDigit(c)
                if (d < 0) return Double.NaN
                r = r * 16 + d
            }
            return r
        }
        val d = t.toDoubleOrNull()
        return d ?: Double.NaN
    }

    private fun isJsWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
            c == '\u000B' || c == '\u000C' || c == '\u00A0' || c == '\uFEFF'

    private fun hexDigit(c: Char): Int {
        if (c >= '0' && c <= '9') return c - '0'
        if (c >= 'a' && c <= 'f') return c - 'a' + 10
        if (c >= 'A' && c <= 'F') return c - 'A' + 10
        return -1
    }

    fun toInt32(v: JsValue): Int {
        val n = toNumber(v)
        return doubleToInt32(n)
    }

    fun toUint32(v: JsValue): Long {
        val n = toNumber(v)
        if (n.isNaN() || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) return 0L
        val t = Math.floor(Math.abs(n)) * (if (n < 0) -1.0 else 1.0)
        val m = ((t % 4294967296.0) + 4294967296.0) % 4294967296.0
        return m.toLong()
    }

    private fun doubleToInt32(n: Double): Int {
        if (n.isNaN() || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) return 0
        val t = Math.floor(Math.abs(n)) * (if (n < 0) -1.0 else 1.0)
        val m = ((t % 4294967296.0) + 4294967296.0) % 4294967296.0
        if (m >= 2147483648.0) return (m - 4294967296.0).toInt()
        return m.toInt()
    }

    /** Espejo de Number.prototype.toString sobre Double.toString (digitos y exponente). */
    fun jsNumToString(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v == Double.POSITIVE_INFINITY) return "Infinity"
        if (v == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (v == 0.0) return "0"
        val neg = v < 0
        val a = if (neg) -v else v
        val s = a.toString()
        val eIdx = s.indexOf('E')
        val mant = if (eIdx >= 0) s.substring(0, eIdx) else s
        val eVal = if (eIdx >= 0) s.substring(eIdx + 1).toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot >= 0) mant.substring(0, dot) else mant
        val fracPart = if (dot >= 0) mant.substring(dot + 1) else ""
        var digits: String
        var exp: Int
        if (intPart == "0") {
            digits = fracPart
            exp = -fracPart.length
            var st = 0
            while (st < digits.length && digits[st] == '0') st++
            digits = digits.substring(st)
        } else {
            digits = intPart + fracPart
            exp = eVal - fracPart.length
            var end = digits.length
            while (end > 1 && digits[end - 1] == '0') {
                end--
                exp++
            }
            digits = digits.substring(0, end)
        }
        if (digits == "0") return "0"
        val k = digits.length
        val n = k + exp
        var r = ""
        if (k <= n && n <= 21) {
            r = digits
            for (j in 0 until (n - k)) r += "0"
        } else if (0 < n && n <= 21) {
            r = digits.substring(0, n) + "." + digits.substring(n)
        } else if (-6 < n && n <= 0) {
            r = "0."
            for (j in 0 until -n) r += "0"
            r += digits
        } else {
            if (k == 1) r = digits
            else r = digits.substring(0, 1) + "." + digits.substring(1)
            val m = n - 1
            if (m >= 0) r += "e+" + m
            else r += "e-" + (-m)
        }
        return if (neg) "-" + r else r
    }

    fun toStringJs(v: JsValue): String {
        return when (v) {
            is JsUndefined -> "undefined"
            is JsNull -> "null"
            is JsBool -> if (v.value) "true" else "false"
            is JsNum -> jsNumToString(v.value)
            is JsStr -> v.value
            is JsRegExp -> "/" + v.body + "/" + v.flags
            is JsObj -> {
                if (v.isArr) {
                    val sb = StringBuilder()
                    var first = true
                    for (e in v.elems) {
                        if (!first) sb.append(",")
                        first = false
                        if (e is JsUndefined || e is JsNull) {
                            // elemento nullish -> vacio en join
                        } else {
                            sb.append(toStringJs(e))
                        }
                    }
                    sb.toString()
                } else {
                    "[object Object]"
                }
            }
            is JsFun -> "function " + (v.name ?: "") + "() { ... }"
            is JsClass -> "class " + (v.name ?: "") + " { ... }"
            is JsNative -> "function " + v.name + "() { ... }"
        }
    }

    /** ToPrimitive basico: objetos -> string por defecto (arrays: join). */
    fun toPrimitiveJs(v: JsValue): JsValue {
        if (v is JsObj) return JsStr(toStringJs(v))
        return v
    }

    fun isNullish(v: JsValue): Boolean = v is JsUndefined || v is JsNull

    // ---- Dump canonico de valores ----

    fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun dumpValue(v: JsValue): String {
        return when (v) {
            is JsUndefined -> "undefined"
            is JsNull -> "null"
            is JsBool -> if (v.value) "true" else "false"
            is JsNum -> jsNumToString(v.value)
            is JsStr -> "\"" + esc(v.value) + "\""
            is JsRegExp -> "/" + v.body + "/" + v.flags
            is JsObj -> {
                if (v.isArr) {
                    val sb = StringBuilder("(arr")
                    for (e in v.elems) sb.append(" ").append(dumpValue(e))
                    sb.append(")").toString()
                } else {
                    val sb = StringBuilder("(obj")
                    for ((k, p) in v.props) {
                        if (p is JsProp.Accessor) sb.append(" ").append(k).append("#acc")
                        else if (p is JsProp.Value) sb.append(" ").append(k).append("=").append(dumpValue(p.v))
                    }
                    sb.append(")").toString()
                }
            }
            is JsFun -> if (v.name != null) "(fun " + v.name + ")" else "(fun)"
            is JsClass -> if (v.name != null) "(class " + v.name + ")" else "(class)"
            is JsNative -> "(native " + v.name + ")"
        }
    }
}
