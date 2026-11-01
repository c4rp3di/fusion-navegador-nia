package nia.engine.js

/**
 * Lexer ECMAScript v1 (T018). Subconjunto practico de la gramatica lexica:
 * comentarios, puntuadores, keywords estrictas, identificadores (ASCII mas
 * escapes \\uXXXX), numeros (decimales con separadores _, hex, octal, binario,
 * BigInt, legacy octal), strings con escapes, templates con sustituciones
 * anidadas, literales RegExp frente a division y errores tipificados con
 * linea/columna. Espejo validado del gemelo TS/JS del diseno.
 */
object JsLexer {

    fun lex(source: String): JsLexResult {
        val m = Machine(source)
        m.run()
        return JsLexResult(m.tokens, m.errors)
    }

    private class Machine(private val source: String) {

        val tokens = mutableListOf<JsToken>()
        val errors = mutableListOf<JsLexError>()

        private val n = source.length
        private var i = 0
        private val tmplStack = mutableListOf<Int>()

        private val KW = setOf(
            "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends",
            "finally", "for", "function", "if", "import", "in", "instanceof",
            "new", "return", "super", "switch", "this", "throw", "try",
            "typeof", "var", "void", "while", "with", "yield",
            "null", "true", "false"
        )

        private val PUNCTS = listOf(
            ">>>=", "...", "===", "!==", "**=", "**", "<<=", ">>=", ">>>",
            "&&=", "||=", "??=", "=>", "==", "!=", "<=", ">=",
            "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "%=",
            "&=", "|=", "^=", "<<", ">>",
            "{", "}", "(", ")", "[", "]", ";", ",",
            "<", ">", "+", "-", "*", "%", "&", "|", "^", "!", "~", "?", ":", "=", "."
        )

        fun run() {
            while (i < n) {
                val c = ch(i)
                if (isWs(c)) { i++; continue }
                if (c == '/' && ch(i + 1) == '/') { i += 2; while (i < n && ch(i) != '\n') i++; continue }
                if (c == '/' && ch(i + 1) == '*') {
                    val cs = i
                    i += 2
                    var closed = false
                    while (i < n) {
                        if (ch(i) == '*' && ch(i + 1) == '/') { i += 2; closed = true; break }
                        i++
                    }
                    if (!closed) err("unterminated-comment", cs)
                    continue
                }
                if (tmplStack.isNotEmpty() && c == '}' && tmplStack[tmplStack.size - 1] == 0) {
                    val rs = i
                    i++
                    lexTemplateText(rs, true)
                    continue
                }
                if (c == '`') { val ts = i; i++; lexTemplateText(ts, false); continue }
                if (c == '\'' || c == '"') { lexString(); continue }
                if (isDigit(c) || (c == '.' && isDigit(ch(i + 1)))) { lexNumber(); continue }
                if (identStartHere()) { lexIdent(); continue }
                if (c == '/') {
                    if (regexAllowed()) { lexRegex(); continue }
                    if (ch(i + 1) == '=') { tokens.add(JsToken.Punctuator("/=", i, i + 2)); i += 2 }
                    else { tokens.add(JsToken.Punctuator("/", i, i + 1)); i++ }
                    continue
                }
                val matched = PUNCTS.firstOrNull { source.startsWith(it, i) }
                if (matched != null) {
                    if (matched == "{" && tmplStack.isNotEmpty()) {
                        val t = tmplStack.size - 1
                        tmplStack[t] = tmplStack[t] + 1
                    }
                    if (matched == "}" && tmplStack.isNotEmpty() && tmplStack[tmplStack.size - 1] > 0) {
                        val t = tmplStack.size - 1
                        tmplStack[t] = tmplStack[t] - 1
                    }
                    tokens.add(JsToken.Punctuator(matched, i, i + matched.length))
                    i += matched.length
                    continue
                }
                err("unexpected-character", i)
                i++
            }
            if (tmplStack.isNotEmpty()) err("unterminated-template", n)
            tokens.add(JsToken.Eof(n, n))
        }

        private fun ch(p: Int): Char = if (p >= 0 && p < n) source[p] else 0.toChar()

        private fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
                c == 12.toChar() || c == 160.toChar() || c == 65279.toChar()

        private fun isDigit(c: Char): Boolean = c in '0'..'9'

        private fun isHex(c: Char): Boolean =
            isDigit(c) || (c in 'a'..'f') || (c in 'A'..'F')

        private fun isIdentStart(c: Char): Boolean =
            (c in 'a'..'z') || (c in 'A'..'Z') || c == '$' || c == '_'

        private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)

        private fun hexVal(c: Char): Int = when {
            c in '0'..'9' -> c - '0'
            c in 'a'..'f' -> c - 'a' + 10
            else -> c - 'A' + 10
        }

        private fun hex4At(p: Int): Boolean {
            if (p < 0 || p + 4 > n) return false
            return isHex(source[p]) && isHex(source[p + 1]) &&
                isHex(source[p + 2]) && isHex(source[p + 3])
        }

        private fun hex4Val(p: Int): Int =
            hexVal(source[p]) * 4096 + hexVal(source[p + 1]) * 256 +
                hexVal(source[p + 2]) * 16 + hexVal(source[p + 3])

        private fun pos(p: Int): Pair<Int, Int> {
            var line = 1
            var last = -1
            var j = 0
            while (j < p && j < n) {
                if (source[j] == '\n') { line++; last = j }
                j++
            }
            return Pair(line, p - last)
        }

        private fun err(code: String, p: Int) {
            val q = pos(p)
            errors.add(JsLexError(code, p, q.first, q.second))
        }

        private fun regexAllowed(): Boolean {
            if (tokens.isEmpty()) return true
            val t = tokens[tokens.size - 1]
            if (t is JsToken.Punctuator) {
                return !(t.value == ")" || t.value == "]" || t.value == "}" ||
                    t.value == "++" || t.value == "--")
            }
            if (t is JsToken.Keyword) {
                return !(t.value == "this" || t.value == "null" ||
                    t.value == "true" || t.value == "false")
            }
            return false
        }

        // i apunta al caracter siguiente a la barra invertida.
        // Devuelve (texto o null, caracteres consumidos).
        private fun readEscape(): Pair<String?, Int> {
            val c = ch(i)
            if (c == 'n') return Pair("\n", 1)
            if (c == 't') return Pair("\t", 1)
            if (c == 'r') return Pair("\r", 1)
            if (c == 'b') return Pair(8.toChar().toString(), 1)
            if (c == 'f') return Pair(12.toChar().toString(), 1)
            if (c == 'v') return Pair(11.toChar().toString(), 1)
            if (c == 'x') {
                val h1 = ch(i + 1)
                val h2 = ch(i + 2)
                if (isHex(h1) && isHex(h2)) {
                    return Pair((hexVal(h1) * 16 + hexVal(h2)).toChar().toString(), 3)
                }
                err("invalid-hex-escape", i - 1)
                return Pair(null, 1)
            }
            if (c == 'u') {
                if (ch(i + 1) == '{') {
                    val e = source.indexOf('}', i + 2)
                    if (e > 0) {
                        val hs = source.substring(i + 2, e)
                        var ok = hs.length >= 1 && hs.length <= 6
                        var k = 0
                        while (ok && k < hs.length) {
                            if (!isHex(hs[k])) ok = false
                            k++
                        }
                        if (ok) {
                            var cp = 0
                            k = 0
                            while (k < hs.length) { cp = cp * 16 + hexVal(hs[k]); k++ }
                            if (cp <= 0x10FFFF) {
                                val s = if (cp == 0) 0xFFFD.toChar().toString() else String(Character.toChars(cp))
                                return Pair(s, e - i + 1)
                            }
                        }
                    }
                    err("invalid-unicode-escape", i - 1)
                    return Pair(null, 2)
                }
                if (hex4At(i + 1)) return Pair(hex4Val(i + 1).toChar().toString(), 5)
                err("invalid-unicode-escape", i - 1)
                return Pair(null, 1)
            }
            if (c == '0') {
                if (isDigit(ch(i + 1))) {
                    err("octal-escape-unsupported", i - 1)
                    return Pair(null, 1)
                }
                return Pair(0.toChar().toString(), 1)
            }
            if (c == '\n') return Pair(null, 1)
            if (c == '\r') {
                if (ch(i + 1) == '\n') return Pair(null, 2)
                return Pair(null, 1)
            }
            return Pair(c.toString(), 1)
        }

        private fun lexString() {
            val start = i
            val q = ch(i)
            i++
            val cooked = StringBuilder()
            while (true) {
                if (i >= n) { err("unterminated-string", start); break }
                val c = ch(i)
                if (c == '\n' || c == '\r') { err("unterminated-string", start); break }
                if (c == q) { i++; break }
                if (c == '\\') {
                    i++
                    val r = readEscape()
                    r.first?.let { cooked.append(it) }
                    i += r.second
                    if (i > n) i = n
                    continue
                }
                cooked.append(c)
                i++
            }
            tokens.add(JsToken.StringLiteral(cooked.toString(), q, start, i))
        }

        // Lee texto de template desde i; isRest=true tras consumir '}' de sustitucion.
        private fun lexTemplateText(start: Int, isRest: Boolean) {
            val cooked = StringBuilder()
            while (true) {
                if (i >= n) {
                    err("unterminated-template", start)
                    if (isRest) {
                        tokens.add(JsToken.TemplateTail(cooked.toString(), start, i))
                        tmplStack.removeAt(tmplStack.size - 1)
                    } else {
                        tokens.add(JsToken.TemplateHead(cooked.toString(), start, i))
                    }
                    return
                }
                val c = ch(i)
                if (c == '`') {
                    i++
                    if (isRest) {
                        tokens.add(JsToken.TemplateTail(cooked.toString(), start, i))
                        tmplStack.removeAt(tmplStack.size - 1)
                    } else {
                        tokens.add(JsToken.TemplateLiteral(cooked.toString(), start, i))
                    }
                    return
                }
                if (c == '$' && ch(i + 1) == '{') {
                    i += 2
                    if (isRest) {
                        tokens.add(JsToken.TemplateMiddle(cooked.toString(), start, i))
                        tmplStack[tmplStack.size - 1] = 0
                    } else {
                        tokens.add(JsToken.TemplateHead(cooked.toString(), start, i))
                        tmplStack.add(0)
                    }
                    return
                }
                if (c == '\\') {
                    i++
                    val r = readEscape()
                    r.first?.let { cooked.append(it) }
                    i += r.second
                    if (i > n) i = n
                    continue
                }
                if (c == '\r') {
                    cooked.append('\n')
                    i++
                    if (ch(i) == '\n') i++
                    continue
                }
                cooked.append(c)
                i++
            }
        }

        private fun lexRegex() {
            val start = i
            i++
            val body = StringBuilder()
            var inClass = false
            while (true) {
                if (i >= n) { err("unterminated-regexp", start); break }
                val c = ch(i)
                if (c == '\n' || c == '\r') { err("unterminated-regexp", start); break }
                if (c == '\\') {
                    body.append(c)
                    i++
                    if (i < n && ch(i) != '\n' && ch(i) != '\r') { body.append(ch(i)); i++ }
                    continue
                }
                if (inClass) { if (c == ']') inClass = false; body.append(c); i++; continue }
                if (c == '[') { inClass = true; body.append(c); i++; continue }
                if (c == '/') { i++; break }
                body.append(c)
                i++
            }
            val flags = StringBuilder()
            while (i < n && ((ch(i) in 'a'..'z') || (ch(i) in 'A'..'Z'))) {
                flags.append(ch(i))
                i++
            }
            var fOk = true
            var k = 0
            while (k < flags.length) {
                if ("dgimsuvy".indexOf(flags[k]) < 0) { fOk = false; break }
                k++
            }
            if (!fOk) err("invalid-regexp-flags", start)
            tokens.add(JsToken.RegExpLiteral(body.toString(), flags.toString(), start, i))
        }

        private fun digits(re: (Char) -> Boolean): String {
            val sb = StringBuilder()
            while (i < n) {
                val c = ch(i)
                if (c == '_') {
                    if (!(i + 1 < n && re(ch(i + 1)))) err("invalid-numeric-literal", i)
                    i++
                    continue
                }
                if (re(c)) { sb.append(c); i++; continue }
                break
            }
            return sb.toString()
        }

        private fun lexNumber() {
            val start = i
            val c0 = ch(i)
            if (c0 == '0' && (ch(i + 1) == 'x' || ch(i + 1) == 'X')) {
                i += 2
                val hs = digits { isHex(it) }
                if (hs.isEmpty()) err("invalid-numeric-literal", start)
                var value = 0.0
                var k = 0
                while (k < hs.length) { value = value * 16 + hexVal(hs[k]); k++ }
                if (ch(i) == 'n') {
                    i++
                    tokens.add(JsToken.BigIntLiteral(source.substring(start, i), start, i))
                    return
                }
                tokens.add(JsToken.NumberLiteral(source.substring(start, i), value, start, i))
                return
            }
            if (c0 == '0' && (ch(i + 1) == 'b' || ch(i + 1) == 'B')) {
                i += 2
                val bs = digits { it == '0' || it == '1' }
                if (bs.isEmpty()) err("invalid-numeric-literal", start)
                var value = 0.0
                var k = 0
                while (k < bs.length) { value = value * 2 + (bs[k] - '0'); k++ }
                if (ch(i) == 'n') {
                    i++
                    tokens.add(JsToken.BigIntLiteral(source.substring(start, i), start, i))
                    return
                }
                tokens.add(JsToken.NumberLiteral(source.substring(start, i), value, start, i))
                return
            }
            if (c0 == '0' && (ch(i + 1) == 'o' || ch(i + 1) == 'O')) {
                i += 2
                val os = digits { it in '0'..'7' }
                if (os.isEmpty()) err("invalid-numeric-literal", start)
                var value = 0.0
                var k = 0
                while (k < os.length) { value = value * 8 + (os[k] - '0'); k++ }
                if (ch(i) == 'n') {
                    i++
                    tokens.add(JsToken.BigIntLiteral(source.substring(start, i), start, i))
                    return
                }
                tokens.add(JsToken.NumberLiteral(source.substring(start, i), value, start, i))
                return
            }
            val legacy: Boolean
            val intPart: String
            if (c0 == '0' && isDigit(ch(i + 1))) {
                intPart = digits { isDigit(it) }
                err("legacy-octal-literal", start)
                legacy = true
            } else {
                intPart = digits { isDigit(it) }
                legacy = false
            }
            var hadDot = false
            var raw2 = intPart
            if (ch(i) == '.') {
                i++
                hadDot = true
                val fs = digits { isDigit(it) }
                raw2 = intPart + "." + fs
            }
            if (ch(i) == 'e' || ch(i) == 'E') {
                val save = i
                i++
                var neg = false
                if (ch(i) == '+' || ch(i) == '-') { neg = ch(i) == '-'; i++ }
                val es = digits { isDigit(it) }
                if (es.isEmpty()) i = save
                else raw2 = raw2 + "e" + (if (neg) "-" else "") + es
            }
            if (ch(i) == 'n' && !hadDot) {
                i++
                tokens.add(JsToken.BigIntLiteral(source.substring(start, i), start, i))
                return
            }
            if (ch(i) == 'n') { err("invalid-bigint-literal", start); i++ }
            val value = if (legacy) {
                if (intPart.isEmpty()) 0.0 else intPart.toDouble()
            } else {
                if (raw2.isEmpty()) 0.0 else raw2.toDouble()
            }
            tokens.add(JsToken.NumberLiteral(source.substring(start, i), value, start, i))
        }

        private fun lexIdent() {
            val start = i
            val sb = StringBuilder()
            while (i < n) {
                val c = ch(i)
                if (isIdentPart(c)) { sb.append(c); i++; continue }
                if (c == '\\') {
                    if (ch(i + 1) == 'u' && hex4At(i + 2)) {
                        sb.append(hex4Val(i + 2).toChar())
                        i += 6
                        continue
                    }
                    err("invalid-unicode-escape", i)
                    i++
                    break
                }
                break
            }
            val s = sb.toString()
            if (KW.contains(s)) tokens.add(JsToken.Keyword(s, start, i))
            else tokens.add(JsToken.Identifier(s, start, i))
        }

        private fun identStartHere(): Boolean {
            val c = ch(i)
            if (isIdentStart(c)) return true
            return c == '\\' && ch(i + 1) == 'u' && hex4At(i + 2)
        }
    }
}
