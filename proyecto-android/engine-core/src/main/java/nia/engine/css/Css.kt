package nia.engine.css

/**
 * CSS v2 (R0023): parser de hojas de estilo y modelo de selectores.
 * Selectores soportados: tipo, universal, clase, id y atributo
 * ([a], [a=v], [a~=v]); combinadores descendente, hijo, hermano adyacente
 * (+) y hermano general (~); pseudo-clases estaticas :first-child,
 * :last-child, :only-child y :nth-child(an+b, odd, even, entero); agrupacion
 * por comas. Errores tipificados: unsupported-at-rule, invalid-selector,
 * unsupported-pseudo, unterminated-block, invalid-declaration,
 * unexpected-character. v3 (R0090, RES-0050): @media con min-width y
 * max-width; el query se guarda por regla y lo evalua la cascada contra el
 * viewport. Limitaciones v3: resto de @-rules sin soporte, sin pseudo-clases
 * dinamicas u otras (hover, :not, :root...), nombres de pseudo-clase solo
 * en minusculas.
 */
data class CssNth(val a: Int, val b: Int)

sealed class CssSimpleSelector {
    object Universal : CssSimpleSelector()
    data class Type(val name: String) : CssSimpleSelector()
    data class Id(val value: String) : CssSimpleSelector()
    data class Cls(val value: String) : CssSimpleSelector()
    data class Attr(val name: String, val op: String, val value: String?) : CssSimpleSelector()
    data class Pseudo(val name: String, val nth: CssNth?) : CssSimpleSelector()
}

/** Compound: todos los simples deben matchear sobre el mismo elemento. */
data class CssCompoundSelector(val simples: List<CssSimpleSelector>)

/** Complejo: cadena de compounds unidos por combinadores ("desc" | "child" | "adj" | "gen"). */
data class CssComplexSelector(
    val compounds: List<CssCompoundSelector>,
    val combinators: List<String>
)

/** Regla: lista de selectores que comparte un bloque de declaraciones. */
data class CssRule(
    val selectors: List<CssComplexSelector>,
    val declarations: List<Pair<String, String>>,
    val order: Int,
    val media: String? = null
)

class CssStylesheet private constructor(val rules: List<CssRule>, val errors: List<String>) {

    companion object {
        fun parse(text: String): CssStylesheet = Machine(text).run()
    }

    private class Machine(private val text: String) {
        private var order = 0
        private val rules = mutableListOf<CssRule>()
        private val errors = mutableListOf<String>()
        private var i = 0
        private val n = text.length
        private val mediaStack = mutableListOf<String>()

        fun run(): CssStylesheet {
            parseLoop()
            return CssStylesheet(rules.toList(), errors.toList())
        }

        private fun isIdentStart(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z') || c == '_'

        private fun isIdent(c: Char): Boolean = isIdentStart(c) || (c in '0'..'9') || c == '-'

        private fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

        private fun skipWs() {
            while (i < n && isWs(text[i])) i++
        }

        private fun skipComment(): Boolean {
            if (i + 1 < n && text[i] == '/' && text[i + 1] == '*') {
                val e = text.indexOf("*/", i + 2)
                if (e < 0) {
                    i = n
                    return true
                }
                i = e + 2
                return true
            }
            return false
        }

        private fun skipNoise() {
            while (true) {
                skipWs()
                if (!skipComment()) return
            }
        }

        private fun skipAtRule() {
            var j = i
            while (j < n && text[j] != '{' && text[j] != ';' && text[j] != '}') j++
            if (j >= n) {
                i = n
                return
            }
            if (text[j] == ';' || text[j] == '}') {
                i = j + 1
                return
            }
            var depth = 0
            i = j
            while (i < n) {
                val c = text[i]
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        i++
                        return
                    }
                }
                i++
            }
        }

        /**
         * @-rules v3 (R0090): solo @media. Otro ident da unsupported-at-rule
         * y se salta con skipAtRule (comportamiento v2 para el resto). @media
         * captura el prelude hasta la llave, lo apila en mediaStack y las
         * reglas internas heredan el query; el cierre del bloque se consume
         * en parseLoop tras la ultima regla interna.
         */
        private fun handleAtRule() {
            i++
            val sb = StringBuilder()
            while (i < n && isIdent(text[i])) {
                sb.append(text[i])
                i++
            }
            val name = sb.toString()
            if (name != "media") {
                errors.add("unsupported-at-rule")
                skipAtRule()
                return
            }
            skipNoise()
            val psb = StringBuilder()
            while (i < n && text[i] != '{' && text[i] != ';') {
                psb.append(text[i])
                i++
            }
            if (i >= n || text[i] == ';') {
                errors.add("unsupported-at-rule")
                skipAtRule()
                return
            }
            val query = psb.toString().trim()
            if (query.isEmpty()) {
                errors.add("unsupported-at-rule")
                i++
                skipAtRule()
                return
            }
            mediaStack.add(query)
            i++
            skipNoise()
            if (i < n && text[i] == '}') {
                i++
                mediaStack.removeAt(mediaStack.size - 1)
            }
        }

        /** Argumento an+b de :nth-child: odd/even, entero, an, an+b, -n+b, an-b. */
        private fun parseNth(arg: String): CssNth? {
            val s = arg.replace(Regex("\\s+"), "")
            if (s.isEmpty()) return null
            if (s == "odd") return CssNth(2, 1)
            if (s == "even") return CssNth(2, 0)
            val m = Regex("^([+-]?\\d*)n([+-]\\d+)?$").find(s)
            if (m != null) {
                val head = m.groupValues[1]
                val a = when {
                    head.isEmpty() || head == "+" -> 1
                    head == "-" -> -1
                    else -> head.toInt()
                }
                val tail = m.groupValues[2]
                val b = if (tail.isEmpty()) 0 else tail.toInt()
                return CssNth(a, b)
            }
            val mi = Regex("^[+-]?\\d+$").find(s)
            if (mi != null) return CssNth(0, s.toInt())
            return null
        }

        private sealed class Cmp {
            data class Ok(val compound: CssCompoundSelector) : Cmp()
            data class Bad(val code: String) : Cmp()
        }

        private fun parseCompound(): Cmp {
            val simples = mutableListOf<CssSimpleSelector>()
            while (true) {
                if (i >= n) break
                val c = text[i]
                if (c == '*') {
                    simples.add(CssSimpleSelector.Universal)
                    i++
                } else if (isIdentStart(c)) {
                    val sb = StringBuilder()
                    while (i < n && isIdent(text[i])) {
                        sb.append(text[i])
                        i++
                    }
                    simples.add(CssSimpleSelector.Type(sb.toString()))
                } else if (c == '.') {
                    i++
                    if (i < n && isIdent(text[i])) {
                        val sb = StringBuilder()
                        while (i < n && isIdent(text[i])) {
                            sb.append(text[i])
                            i++
                        }
                        simples.add(CssSimpleSelector.Cls(sb.toString()))
                    } else {
                        return Cmp.Bad("invalid-selector")
                    }
                } else if (c == '#') {
                    i++
                    if (i < n && isIdent(text[i])) {
                        val sb = StringBuilder()
                        while (i < n && isIdent(text[i])) {
                            sb.append(text[i])
                            i++
                        }
                        simples.add(CssSimpleSelector.Id(sb.toString()))
                    } else {
                        return Cmp.Bad("invalid-selector")
                    }
                } else if (c == '[') {
                    i++
                    skipNoise()
                    val sb = StringBuilder()
                    while (i < n && isIdent(text[i])) {
                        sb.append(text[i])
                        i++
                    }
                    val name = sb.toString()
                    skipNoise()
                    var op = ""
                    var value: String? = null
                    if (i + 1 < n && text[i] == '~' && text[i + 1] == '=') {
                        op = "~="
                        i += 2
                    } else if (i < n && text[i] == '=') {
                        op = "="
                        i++
                    }
                    if (op.isNotEmpty()) {
                        skipNoise()
                        var q: Char? = null
                        if (i < n && (text[i] == '"' || text[i] == '\'')) {
                            q = text[i]
                            i++
                        }
                        val vsb = StringBuilder()
                        while (i < n && text[i] != ']' && (q == null || text[i] != q)) {
                            vsb.append(text[i])
                            i++
                        }
                        if (q != null && i < n && text[i] == q) i++
                        value = vsb.toString().trim()
                    }
                    skipNoise()
                    if (i < n && text[i] == ']' && name.isNotEmpty()) {
                        i++
                        simples.add(CssSimpleSelector.Attr(name, op, value))
                    } else {
                        return Cmp.Bad("invalid-selector")
                    }
                } else if (c == ':') {
                    i++
                    if (i >= n || !isIdentStart(text[i])) return Cmp.Bad("invalid-selector")
                    val sb = StringBuilder()
                    while (i < n && isIdent(text[i])) {
                        sb.append(text[i])
                        i++
                    }
                    val name = sb.toString()
                    if (name == "first-child" || name == "last-child" || name == "only-child") {
                        if (i < n && text[i] == '(') return Cmp.Bad("invalid-selector")
                        simples.add(CssSimpleSelector.Pseudo(name, null))
                    } else if (name == "nth-child") {
                        if (i >= n || text[i] != '(') return Cmp.Bad("invalid-selector")
                        i++
                        skipNoise()
                        val asb = StringBuilder()
                        while (i < n && text[i] != ')') {
                            asb.append(text[i])
                            i++
                        }
                        if (i >= n) return Cmp.Bad("invalid-selector")
                        i++
                        val nth = parseNth(asb.toString())
                        if (nth == null) return Cmp.Bad("invalid-selector")
                        simples.add(CssSimpleSelector.Pseudo("nth-child", nth))
                    } else {
                        return Cmp.Bad("unsupported-pseudo")
                    }
                } else {
                    break
                }
            }
            if (simples.isEmpty()) return Cmp.Bad("invalid-selector")
            return Cmp.Ok(CssCompoundSelector(simples.toList()))
        }

        private sealed class Cx {
            data class Ok(val complex: CssComplexSelector) : Cx()
            data class Bad(val code: String) : Cx()
        }

        private fun parseComplex(): Cx {
            val compounds = mutableListOf<CssCompoundSelector>()
            val combinators = mutableListOf<String>()
            while (true) {
                val cp = parseCompound()
                if (cp is Cmp.Bad) return Cx.Bad(cp.code)
                compounds.add((cp as Cmp.Ok).compound)
                skipNoise()
                if (i < n && text[i] == '>') {
                    combinators.add("child")
                    i++
                    skipNoise()
                    continue
                }
                if (i < n && text[i] == '+') {
                    combinators.add("adj")
                    i++
                    skipNoise()
                    continue
                }
                if (i < n && text[i] == '~') {
                    combinators.add("gen")
                    i++
                    skipNoise()
                    continue
                }
                if (i < n && (isIdentStart(text[i]) || text[i] == '*' || text[i] == '.' || text[i] == '#' || text[i] == '[')) {
                    combinators.add("desc")
                    continue
                }
                break
            }
            return Cx.Ok(CssComplexSelector(compounds.toList(), combinators.toList()))
        }

        private fun parseLoop() {
            while (true) {
                skipNoise()
                if (i >= n) return
                val c = text[i]
                if (c == '@') {
                    handleAtRule()
                    continue
                }
                if (c == '}' || c == ';' || c == ',') {
                    errors.add("unexpected-character")
                    i++
                    continue
                }

                val selectors = mutableListOf<CssComplexSelector>()
                var anyBad = false
                while (true) {
                    skipNoise()
                    if (i < n && text[i] == '{') {
                        errors.add("invalid-selector")
                        anyBad = true
                        break
                    }
                    val cx = parseComplex()
                    if (cx is Cx.Bad) {
                        anyBad = true
                        errors.add(cx.code)
                        while (i < n && text[i] != ',' && text[i] != '{') i++
                    } else {
                        selectors.add((cx as Cx.Ok).complex)
                    }
                    skipNoise()
                    if (i < n && text[i] == ',') {
                        i++
                        continue
                    }
                    break
                }
                if (i >= n) {
                    errors.add("unterminated-block")
                    return
                }
                if (text[i] != '{') {
                    errors.add("unexpected-character")
                    i++
                    continue
                }
                i++

                val decls = mutableListOf<Pair<String, String>>()
                var aborted = false
                while (true) {
                    skipNoise()
                    if (i >= n) {
                        errors.add("unterminated-block")
                        aborted = true
                        break
                    }
                    if (text[i] == '}') {
                        i++
                        break
                    }
                    val psb = StringBuilder()
                    while (i < n && isIdent(text[i])) {
                        psb.append(text[i])
                        i++
                    }
                    val prop = psb.toString()
                    skipNoise()
                    if (i >= n || text[i] != ':' || prop.isEmpty()) {
                        errors.add("invalid-declaration")
                        while (i < n && text[i] != ';' && text[i] != '}') i++
                        if (i < n && text[i] == ';') i++
                        continue
                    }
                    i++
                    val vsb = StringBuilder()
                    while (i < n && text[i] != ';' && text[i] != '}') {
                        vsb.append(text[i])
                        i++
                    }
                    val value = vsb.toString().trim()
                    if (value.isEmpty()) {
                        errors.add("invalid-declaration")
                    } else {
                        decls.add(prop.lowercase() to value)
                    }
                    if (i < n && text[i] == ';') i++
                }
                if (!anyBad && !aborted && selectors.isNotEmpty()) {
                    rules.add(CssRule(selectors.toList(), decls.toList(), order++, mediaStack.lastOrNull()))
                }
                if (mediaStack.isNotEmpty()) {
                    skipNoise()
                    if (i < n && text[i] == '}') {
                        i++
                        mediaStack.removeAt(mediaStack.size - 1)
                    }
                }
            }
        }
    }
}
