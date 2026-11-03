package nia.engine.js

/**
 * Parser ECMAScript v2 (T018 v4). Recursive descent con precedencias sobre el
 * token stream del lexer v2. AST con spans [start, end), errores tipificados
 * con linea/columna 1-based y recuperacion a nivel de sentencia (skip hasta
 * ';' o '}', con avance garantizado). Espejo del gemelo TS/JS validado.
 * Novedades v2: arrows (parametro unico o parentesis, defaults, cuerpo bloque
 * o expresion), clases (sentencia y expresion: nombre opcional, extends,
 * constructor/metodos/static/get/set/computed keys), labels (etiqueta +
 * break/continue con label), getters/setters en object literal, optional
 * chaining '?.' y spread en call args.
 * T018 v4: destructuring (declaraciones var/let/const, asignacion y
 * parametros) con patrones anidados, defaults y rest.
 * T018 v5: async/await y generators. async function (declaracion, con
 * exclusion tipificada de async generators), async function como expresion
 * y async arrows (parametro unico o parentesis con backtracking); function*
 * con yield (con o sin argumento) y yield*; await y yield como unarios de
 * expresion (solo validos en su contexto de ejecucion). Exclusiones v5:
 * metodos async/generator en clases y object literals (error de parseo
 * natural), yield con valor de next(), patrones en for-in/of, modulos,
 * class fields, with, ASI (los puntos y coma son obligatorios).
 */
object JsParser {

    private val BIN_PREC = mapOf(
        "??" to 1, "||" to 2, "&&" to 3, "|" to 4, "^" to 5, "&" to 6,
        "===" to 7, "!==" to 7, "==" to 7, "!=" to 7,
        "<" to 8, ">" to 8, "<=" to 8, ">=" to 8,
        "<<" to 9, ">>" to 9, ">>>" to 9,
        "+" to 10, "-" to 10, "*" to 11, "/" to 11, "%" to 11, "**" to 12
    )

    private val LOGICAL_OPS = setOf("??", "||", "&&")

    private val ASSIGN_OPS = setOf(
        "=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??="
    )

    private val PREFIX_OPS = setOf("!", "~", "+", "-")

    private val PREFIX_KEYWORD_OPS = setOf("typeof", "void", "delete")

    fun parse(source: String): JsParseResult {
        val lex = JsLexer.lex(source)
        val p = Parser(source, lex.tokens)
        val program = p.parseProgram()
        return JsParseResult(program, lex.errors)
    }

    private class ParseAbort(val code: String, val start: Int, val end: Int) : Exception()

    private class Args(val list: List<JsExpr>, val closeEnd: Int)

    private class Members(val list: List<ClassMember>, val closeEnd: Int)

    private class Parser(private val source: String, private val toks: List<JsToken>) {

        val errors = mutableListOf<JsParseError>()
        private val n = toks.size
        private var i = 0

        fun cur(): JsToken = toks[minOf(i, n - 1)]

        fun peek(o: Int): JsToken = toks[minOf(i + o, n - 1)]

        fun next(): JsToken {
            val t = toks[minOf(i, n - 1)]
            if (i < n - 1) i++
            return t
        }

        fun isP(v: String): Boolean {
            val t = cur()
            return t is JsToken.Punctuator && t.value == v
        }

        fun isKw(v: String): Boolean {
            val t = cur()
            return t is JsToken.Keyword && t.value == v
        }

        fun isIdentOf(v: String): Boolean {
            val t = cur()
            return t is JsToken.Identifier && t.value == v
        }

        private fun pos(p: Int): Pair<Int, Int> {
            var line = 1
            var last = -1
            var j = 0
            while (j < p && j < source.length) {
                if (source[j] == '\n') {
                    line++
                    last = j
                }
                j++
            }
            return Pair(line, p - last)
        }

        private fun addError(code: String, start: Int, end: Int) {
            val q = pos(start)
            errors.add(JsParseError(code, start, end, q.first, q.second))
        }

        private fun abort(code: String, start: Int, end: Int): ParseAbort = ParseAbort(code, start, end)

        // ---- Programa y sentencias ----

        fun parseProgram(): JsProgram {
            val stmts = stmtList { t -> t is JsToken.Eof }
            return JsProgram(0, source.length, stmts, errors)
        }

        fun stmtList(isStop: (JsToken) -> Boolean): MutableList<JsStmt> {
            val out = mutableListOf<JsStmt>()
            while (true) {
                val t = cur()
                if (isStop(t)) break
                try {
                    out.add(parseStatement())
                } catch (e: ParseAbort) {
                    addError(e.code, e.start, e.end)
                    sync()
                }
            }
            return out
        }

        // Recuperacion: consume hasta pasar ';'. Se detiene antes de '}' (cierra
        // el bloque exterior) salvo que sea el primer token, para garantizar avance.
        fun sync() {
            val t = cur()
            if (t is JsToken.Eof) return
            if (t is JsToken.Punctuator && t.value == "}") {
                next()
                return
            }
            while (cur() !is JsToken.Eof) {
                val c = cur()
                if (c is JsToken.Punctuator && c.value == ";") {
                    next()
                    return
                }
                if (c is JsToken.Punctuator && c.value == "}") return
                next()
            }
        }

        private fun letDeclFollows(): Boolean {
            val p1 = peek(1)
            if (p1 is JsToken.Identifier) return true
            if (p1 is JsToken.Punctuator && (p1.value == "[" || p1.value == "{")) return true
            return false
        }

        fun parseStatement(): JsStmt {
            val t = cur()
            if (t is JsToken.Punctuator) {
                if (t.value == ";") {
                    next()
                    return JsStmt.Empty(t.start, t.end)
                }
                if (t.value == "{") return parseBlock()
            }
            if (t is JsToken.Keyword) {
                when (t.value) {
                    "var", "const" -> return parseVarStatement(t.value)
                    "function" -> return parseFunctionStmt()
                    "return" -> return parseReturn()
                    "if" -> return parseIf()
                    "while" -> return parseWhile()
                    "do" -> return parseDoWhile()
                    "for" -> return parseFor()
                    "break", "continue" -> return parseBreakContinue(t.value)
                    "throw" -> return parseThrow()
                    "try" -> return parseTry()
                    "switch" -> return parseSwitch()
                    "class" -> return parseClassStmt()
                    "import", "export", "with", "debugger" -> {
                        next()
                        throw abort("unsupported-statement", t.start, t.end)
                    }
                    else -> {}
                }
            }
            val p1 = peek(1)
            if (t is JsToken.Identifier && p1 is JsToken.Punctuator && p1.value == ":") {
                next()
                next()
                val body = parseStatement()
                return JsStmt.Labeled(t.start, body.end, t.value, body)
            }
            if (t is JsToken.Identifier && t.value == "let" && letDeclFollows()) {
                return parseVarStatement("let")
            }
            if (t is JsToken.Identifier && t.value == "async") {
                val p1 = peek(1)
                if (p1 is JsToken.Keyword && p1.value == "function") {
                    next()
                    return parseFunctionStmt(true)
                }
            }
            return parseExprStatement()
        }

        // ';' obligatorio (sin ASI). Si falta, error no fatal y la sentencia cierra.
        fun expectSemi(prevEnd: Int): Int {
            val t = cur()
            if (t is JsToken.Punctuator && t.value == ";") {
                next()
                return t.end
            }
            addError("missing-semicolon", prevEnd, prevEnd)
            return prevEnd
        }

        fun parseBlock(): JsStmt.Block {
            val open = next()
            val stmts = stmtList { t -> t is JsToken.Eof || (t is JsToken.Punctuator && t.value == "}") }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == "}")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t.start)
            }
            val close = next()
            return JsStmt.Block(open.start, close.end, stmts)
        }

        fun parseVarStatement(kind: String): JsStmt.Var {
            val kw = next()
            val declarators = mutableListOf<Declarator>()
            while (true) {
                val t = cur()
                if (isP("[") || isP("{")) {
                    val pat = parsePattern()
                    if (!isP("=")) throw abort("invalid-destructure", t.start, cur().end)
                    next()
                    val init = parseAssign()
                    declarators.add(Declarator(pat.start, init.end, "", init, pat))
                    if (isP(",")) {
                        next()
                        continue
                    }
                    break
                }
                if (t !is JsToken.Identifier) throw abort("invalid-binding", t.start, t.end)
                val nameTok = next()
                var init: JsExpr? = null
                if (isP("=")) {
                    next()
                    init = parseAssign()
                }
                val end = if (init == null) nameTok.end else init.end
                declarators.add(Declarator(nameTok.start, end, t.value, init))
                if (isP(",")) {
                    next()
                    continue
                }
                break
            }
            val last = declarators[declarators.size - 1]
            val end = expectSemi(last.end)
            return JsStmt.Var(kw.start, end, kind, declarators)
        }

        // ---- Destructuring (T018 v4) ----

        fun parsePattern(): JsPat {
            val t = cur()
            if (isP("[")) {
                val open = next()
                val elems = mutableListOf<JsPat?>()
                var rest: JsPat? = null
                while (!isP("]")) {
                    if (isP(",")) {
                        next()
                        elems.add(null)
                        continue
                    }
                    if (isP("...")) {
                        next()
                        rest = parsePatternTarget()
                        if (isP(",")) next()
                        break
                    }
                    elems.add(parsePatternElem())
                    if (isP(",")) {
                        next()
                        continue
                    }
                    break
                }
                val t2 = cur()
                if (!(t2 is JsToken.Punctuator && t2.value == "]")) {
                    throw abort(if (t2 is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t2.start)
                }
                val close = next()
                return JsPat.PArray(open.start, close.end, elems, rest)
            }
            if (isP("{")) {
                val open = next()
                val entries = mutableListOf<PEntry>()
                var rest: JsPat? = null
                while (!isP("}")) {
                    if (isP("...")) {
                        next()
                        rest = parsePatternTarget()
                        if (isP(",")) next()
                        break
                    }
                    val key = parseKeyToken()
                    if (isP(":")) {
                        next()
                        val pat = parsePatternElem()
                        entries.add(PEntry(key.start, pat.end, key, pat))
                    } else {
                        if (key !is Key.IdentKey) throw abort("unexpected-token", cur().start, cur().end)
                        var def: JsExpr? = null
                        var end = key.end
                        if (isP("=")) {
                            next()
                            def = parseAssign()
                            end = def.end
                        }
                        entries.add(PEntry(key.start, end, key, JsPat.PIdent(key.start, end, key.name, def)))
                    }
                    if (isP(",")) {
                        next()
                        continue
                    }
                    break
                }
                val t2 = cur()
                if (!(t2 is JsToken.Punctuator && t2.value == "}")) {
                    throw abort(if (t2 is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t2.start)
                }
                val close = next()
                return JsPat.PObject(open.start, close.end, entries, rest)
            }
            throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, t.end)
        }

        fun parsePatternTarget(): JsPat {
            val t = cur()
            if (t is JsToken.Identifier) {
                next()
                return JsPat.PIdent(t.start, t.end, t.value, null)
            }
            if (isP("[") || isP("{")) return parsePattern()
            throw abort("invalid-binding", t.start, t.end)
        }

        fun parsePatternElem(): JsPat {
            val pat = parsePatternTarget()
            if (isP("=")) {
                next()
                val def = parseAssign()
                if (pat !is JsPat.PIdent) throw abort("invalid-destructure", pat.start, def.end)
                return JsPat.PIdent(pat.start, def.end, pat.name, def)
            }
            return pat
        }

        fun parseParams(): List<Param> {
            val open = next()
            if (!(open is JsToken.Punctuator && open.value == "(")) {
                throw abort(if (open is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, open.end)
            }
            val params = mutableListOf<Param>()
            while (!isP(")")) {
                val t = cur()
                if (isP("[") || isP("{")) {
                    val pat = parsePattern()
                    var def: JsExpr? = null
                    if (isP("=")) {
                        next()
                        def = parseAssign()
                    }
                    val end = if (def == null) pat.end else def.end
                    params.add(Param(pat.start, end, "", def, pat))
                    if (isP(",")) {
                        next()
                        continue
                    }
                    break
                }
                if (t !is JsToken.Identifier) throw abort("invalid-binding", t.start, t.end)
                val nameTok = next()
                var def: JsExpr? = null
                if (isP("=")) {
                    next()
                    def = parseAssign()
                }
                val end = if (def == null) nameTok.end else def.end
                params.add(Param(nameTok.start, end, t.value, def))
                if (isP(",")) {
                    next()
                    continue
                }
                break
            }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == ")")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t.start)
            }
            next()
            return params
        }

        fun parseFunctionStmt(async: Boolean = false): JsStmt.Function {
            val kw = next()
            var gen = false
            if (isP("*")) {
                next()
                gen = true
            }
            if (async && gen) throw abort("unsupported-statement", kw.start, kw.end)
            val t = cur()
            if (t !is JsToken.Identifier) throw abort("invalid-binding", t.start, t.end)
            val nameTok = next()
            val params = parseParams()
            val body = parseBlock()
            return JsStmt.Function(kw.start, body.end, t.value, params, body, async, gen)
        }

        fun parseFunctionExpr(async: Boolean = false): JsExpr.FunctionExpr {
            val kw = next()
            var gen = false
            if (isP("*")) {
                next()
                gen = true
            }
            if (async && gen) throw abort("unsupported-statement", kw.start, kw.end)
            var name: String? = null
            val t = cur()
            if (t is JsToken.Identifier) {
                val nt = next()
                name = t.value
            }
            val params = parseParams()
            val body = parseBlock()
            return JsExpr.FunctionExpr(kw.start, body.end, name, params, body, async, gen)
        }

        fun parseReturn(): JsStmt.Return {
            val kw = next()
            val t = cur()
            var value: JsExpr? = null
            var end = kw.end
            if (!(t is JsToken.Punctuator && (t.value == ";" || t.value == "}")) && t !is JsToken.Eof) {
                value = parseExpr()
                end = value.end
            }
            end = expectSemi(end)
            return JsStmt.Return(kw.start, end, value)
        }

        fun parseIf(): JsStmt.If {
            val kw = next()
            expectP("(")
            val test = parseExpr()
            expectP(")")
            val thenBranch = parseStatement()
            var elseBranch: JsStmt? = null
            var end = thenBranch.end
            if (isKw("else")) {
                next()
                elseBranch = parseStatement()
                end = elseBranch.end
            }
            return JsStmt.If(kw.start, end, test, thenBranch, elseBranch)
        }

        fun expectP(v: String) {
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == v)) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, t.end)
            }
            next()
        }

        fun parseWhile(): JsStmt.While {
            val kw = next()
            expectP("(")
            val test = parseExpr()
            expectP(")")
            val body = parseStatement()
            return JsStmt.While(kw.start, body.end, test, body)
        }

        fun parseDoWhile(): JsStmt.DoWhile {
            val kw = next()
            val body = parseStatement()
            val t = cur()
            if (!isKw("while")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", kw.start, t.start)
            }
            next()
            expectP("(")
            val test = parseExpr()
            expectP(")")
            val end = expectSemi(test.end)
            return JsStmt.DoWhile(kw.start, end, body, test)
        }

        fun parseFor(): JsStmt {
            val kw = next()
            expectP("(")
            var init: JsStmt? = null
            var leftKind: String? = null
            var leftName: String? = null
            var leftExpr: JsExpr? = null
            var right: JsExpr? = null
            var isOf = false
            var isForInOf = false
            if (!isP(";")) {
                var kind: String? = null
                if (isKw("var") || isKw("const")) kind = (cur() as JsToken.Keyword).value
                else if (isIdentOf("let") && peek(1) is JsToken.Identifier) kind = "let"
                if (kind != null) {
                    next()
                    val d = cur()
                    if (d !is JsToken.Identifier) throw abort("invalid-binding", d.start, d.end)
                    val nameTok = next()
                    val c1 = cur()
                    if (isKw("in") || (c1 is JsToken.Identifier && c1.value == "of")) {
                        isOf = c1 is JsToken.Identifier
                        next()
                        leftKind = kind
                        leftName = d.value
                        right = parseExpr()
                        isForInOf = true
                    } else {
                        val declarators = mutableListOf<Declarator>()
                        var init0: JsExpr? = null
                        if (isP("=")) {
                            next()
                            init0 = parseAssign()
                        }
                        var end0 = if (init0 == null) nameTok.end else init0.end
                        declarators.add(Declarator(nameTok.start, end0, d.value, init0))
                        while (isP(",")) {
                            next()
                            val d2 = cur()
                            if (d2 !is JsToken.Identifier) throw abort("invalid-binding", d2.start, d2.end)
                            val nt2 = next()
                            var ini2: JsExpr? = null
                            if (isP("=")) {
                                next()
                                ini2 = parseAssign()
                            }
                            val end2 = if (ini2 == null) nt2.end else ini2.end
                            declarators.add(Declarator(nt2.start, end2, d2.value, ini2))
                        }
                        val last = declarators[declarators.size - 1]
                        init = JsStmt.Var(kw.start, last.end, kind, declarators)
                        expectP(";")
                    }
                } else {
                    val e = parseExpr(true)
                    val c1 = cur()
                    if (isKw("in") || (c1 is JsToken.Identifier && c1.value == "of")) {
                        if (!isForTarget(e)) throw abort("invalid-assignment-target", c1.start, c1.end)
                        isOf = c1 is JsToken.Identifier
                        next()
                        leftExpr = e
                        right = parseExpr()
                        isForInOf = true
                    } else {
                        init = JsStmt.ExprStmt(e.start, e.end, e)
                        expectP(";")
                    }
                }
            } else {
                next()
            }
            if (isForInOf) {
                expectP(")")
                val body = parseStatement()
                return JsStmt.ForInOf(kw.start, body.end, isOf, leftKind, leftName, leftExpr, right!!, body)
            }
            var test: JsExpr? = null
            if (!isP(";")) test = parseExpr()
            expectP(";")
            var update: JsExpr? = null
            if (!isP(")")) update = parseExpr()
            expectP(")")
            val body = parseStatement()
            return JsStmt.For(kw.start, body.end, init, test, update, body)
        }

        fun isForTarget(e: JsExpr): Boolean {
            return e is JsExpr.Ident || e is JsExpr.Member ||
                (e is JsExpr.Paren && isForTarget(e.expr))
        }

        fun parseBreakContinue(kw: String): JsStmt {
            val t = next()
            var label: String? = null
            val c = cur()
            if (c is JsToken.Identifier) {
                next()
                label = c.value
            }
            val prevEnd = if (label == null) t.end else toks[i - 1].end
            val end = expectSemi(prevEnd)
            return if (kw == "break") JsStmt.Break(t.start, end, label) else JsStmt.Continue(t.start, end, label)
        }

        fun parseThrow(): JsStmt.Throw {
            val kw = next()
            val t = cur()
            if (t is JsToken.Eof || (t is JsToken.Punctuator && (t.value == ";" || t.value == "}"))) {
                throw abort("unexpected-token", kw.start, t.start)
            }
            val value = parseExpr()
            val end = expectSemi(value.end)
            return JsStmt.Throw(kw.start, end, value)
        }

        fun parseTry(): JsStmt.Try {
            val kw = next()
            val block = parseBlock()
            var catchParam: String? = null
            var catchBlock: JsStmt.Block? = null
            var finallyBlock: JsStmt.Block? = null
            if (isKw("catch")) {
                next()
                expectP("(")
                val t = cur()
                if (t !is JsToken.Identifier) throw abort("invalid-binding", t.start, t.end)
                val nt = next()
                catchParam = t.value
                expectP(")")
                catchBlock = parseBlock()
            }
            if (isKw("finally")) {
                next()
                finallyBlock = parseBlock()
            }
            if (catchBlock == null && finallyBlock == null) {
                val t = cur()
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", kw.start, t.start)
            }
            val end = if (finallyBlock != null) finallyBlock.end else catchBlock!!.end
            return JsStmt.Try(kw.start, end, block, catchParam, catchBlock, finallyBlock)
        }

        fun parseSwitch(): JsStmt.Switch {
            val kw = next()
            expectP("(")
            val disc = parseExpr()
            expectP(")")
            expectP("{")
            val cases = mutableListOf<SwitchCase>()
            while (!(isP("}") || cur() is JsToken.Eof)) {
                val t = cur()
                var test: JsExpr? = null
                val cstart = t.start
                if (isKw("case")) {
                    next()
                    test = parseExpr()
                    expectP(":")
                } else if (isKw("default")) {
                    next()
                    expectP(":")
                } else {
                    throw abort("unexpected-token", t.start, t.end)
                }
                val stmts = stmtList { x ->
                    x is JsToken.Eof ||
                        (x is JsToken.Punctuator && x.value == "}") ||
                        (x is JsToken.Keyword && (x.value == "case" || x.value == "default"))
                }
                val cend = if (stmts.size == 0) cur().start else stmts[stmts.size - 1].end
                cases.add(SwitchCase(cstart, cend, test, stmts))
            }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == "}")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", kw.start, t.start)
            }
            val close = next()
            return JsStmt.Switch(kw.start, close.end, disc, cases)
        }

        fun parseExprStatement(): JsStmt.ExprStmt {
            val e = parseExpr()
            val end = expectSemi(e.end)
            return JsStmt.ExprStmt(e.start, end, e)
        }

        // ---- Clases (v2) ----

        fun isKeyStart(t: JsToken): Boolean {
            return t is JsToken.Identifier || t is JsToken.Keyword || t is JsToken.StringLiteral ||
                t is JsToken.NumberLiteral || t is JsToken.BigIntLiteral ||
                (t is JsToken.Punctuator && t.value == "[")
        }

        fun parseKeyToken(): Key {
            val t = cur()
            if (t is JsToken.Identifier || t is JsToken.Keyword) {
                next()
                return Key.IdentKey(t.start, t.end, if (t is JsToken.Identifier) t.value else (t as JsToken.Keyword).value)
            }
            if (t is JsToken.StringLiteral) {
                next()
                return Key.StringKey(t.start, t.end, t.value)
            }
            if (t is JsToken.NumberLiteral || t is JsToken.BigIntLiteral) {
                next()
                return Key.NumberKey(t.start, t.end, if (t is JsToken.NumberLiteral) t.raw else (t as JsToken.BigIntLiteral).raw)
            }
            if (t is JsToken.Punctuator && t.value == "[") {
                val open = next()
                val e = parseAssign()
                expectP("]")
                return Key.ComputedKey(open.start, toks[i - 1].end, e)
            }
            throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, t.end)
        }

        fun parseClassMembers(): Members {
            val members = mutableListOf<ClassMember>()
            while (!isP("}")) {
                val t = cur()
                if (t is JsToken.Eof) throw abort("unexpected-eof", t.start, t.end)
                if (isP(";")) {
                    next()
                    continue
                }
                val mstart = t.start
                var isStatic = false
                var kind = "method"
                if (t is JsToken.Identifier && t.value == "static" && isKeyStart(peek(1))) {
                    next()
                    isStatic = true
                    val t2 = cur()
                    if (t2 is JsToken.Identifier && (t2.value == "get" || t2.value == "set") && isKeyStart(peek(1))) {
                        kind = t2.value
                        next()
                    }
                } else if (t is JsToken.Identifier && (t.value == "get" || t.value == "set") && isKeyStart(peek(1))) {
                    kind = t.value
                    next()
                }
                val key = parseKeyToken()
                val params = parseParams()
                val body = parseBlock()
                members.add(ClassMember(mstart, body.end, isStatic, kind, key, params, body))
            }
            val close = next()
            return Members(members, close.end)
        }

        fun parseClassStmt(): JsStmt.ClassStmt {
            val kw = next()
            var name: String? = null
            val t = cur()
            if (t is JsToken.Identifier) {
                next()
                name = t.value
            }
            var superClass: JsExpr? = null
            if (isKw("extends")) {
                next()
                superClass = parseCallMember()
            }
            expectP("{")
            val m = parseClassMembers()
            return JsStmt.ClassStmt(kw.start, m.closeEnd, name, superClass, m.list)
        }

        fun parseClassExpr(): JsExpr.ClassExpr {
            val kw = next()
            var name: String? = null
            val t = cur()
            if (t is JsToken.Identifier) {
                next()
                name = t.value
            }
            var superClass: JsExpr? = null
            if (isKw("extends")) {
                next()
                superClass = parseCallMember()
            }
            expectP("{")
            val m = parseClassMembers()
            return JsExpr.ClassExpr(kw.start, m.closeEnd, name, superClass, m.list)
        }

        // ---- Expresiones ----

        fun parseExpr(noIn: Boolean = false): JsExpr {
            val first = parseAssign(noIn)
            if (!isP(",")) return first
            val exprs = mutableListOf(first)
            while (isP(",")) {
                next()
                exprs.add(parseAssign(noIn))
            }
            val last = exprs[exprs.size - 1]
            return JsExpr.Seq(first.start, last.end, exprs)
        }

        fun isAssignTarget(e: JsExpr): Boolean {
            return e is JsExpr.Ident || e is JsExpr.Member ||
                (e is JsExpr.Paren && isAssignTarget(e.expr))
        }

        fun parseArrowSingle(t: JsToken.Identifier): JsExpr.Arrow {
            next()
            next()
            val params = listOf(Param(t.start, t.end, t.value, null))
            return finishArrow(t.start, params)
        }

        fun finishArrow(start: Int, params: List<Param>, async: Boolean = false): JsExpr.Arrow {
            if (isP("{")) {
                val body = parseBlock()
                return JsExpr.Arrow(start, body.end, params, null, body, async)
            }
            val e = parseAssign()
            return JsExpr.Arrow(start, e.end, params, e, null, async)
        }

        fun parseAssign(noIn: Boolean = false): JsExpr {
            val t = cur()
            val p1 = peek(1)
            if (t is JsToken.Identifier && p1 is JsToken.Punctuator && p1.value == "=>") {
                return parseArrowSingle(t)
            }
            if (t is JsToken.Punctuator && t.value == "(") {
                val save = i
                try {
                    val params = parseParams()
                    if (isP("=>")) {
                        next()
                        return finishArrow(t.start, params)
                    }
                } catch (e: ParseAbort) {
                }
                i = save
            }
            val left = parseCond(noIn)
            val t2 = cur()
            if (t2 is JsToken.Punctuator && t2.value in ASSIGN_OPS) {
                val destructure = t2.value == "=" && (left is JsExpr.ArrayLit || left is JsExpr.ObjectLit)
                if (!destructure && !isAssignTarget(left)) throw abort("invalid-assignment-target", t2.start, t2.end)
                next()
                val right = parseAssign(noIn)
                return JsExpr.Assign(left.start, right.end, t2.value, left, right)
            }
            return left
        }

        fun parseCond(noIn: Boolean = false): JsExpr {
            val test = parseBinary(1, noIn)
            if (isP("?")) {
                next()
                val cons = parseAssign(noIn)
                expectP(":")
                val alt = parseAssign(noIn)
                return JsExpr.Cond(test.start, alt.end, test, cons, alt)
            }
            return test
        }

        fun parseBinary(minPrec: Int, noIn: Boolean): JsExpr {
            var left = parseUnary()
            while (true) {
                val t = cur()
                var op: String? = null
                var prec = 0
                var logical = false
                if (t is JsToken.Punctuator) {
                    val p = BIN_PREC[t.value]
                    if (p != null) {
                        op = t.value
                        prec = p
                        logical = t.value in LOGICAL_OPS
                    }
                } else if (t is JsToken.Keyword && (t.value == "instanceof" || (t.value == "in" && !noIn))) {
                    op = t.value
                    prec = 8
                }
                if (op == null) break
                if (prec < minPrec) break
                next()
                val right = parseBinary(if (op == "**") prec else prec + 1, noIn)
                val o = op
                left = if (logical) JsExpr.Logical(left.start, right.end, o, left, right)
                else JsExpr.Binary(left.start, right.end, o, left, right)
            }
            return left
        }

        fun parseUnary(): JsExpr {
            val t = cur()
            if (t is JsToken.Punctuator && t.value in PREFIX_OPS) {
                next()
                val operand = parseUnary()
                return JsExpr.Unary(t.start, operand.end, t.value, operand)
            }
            if (t is JsToken.Keyword && t.value in PREFIX_KEYWORD_OPS) {
                next()
                val operand = parseUnary()
                return JsExpr.Unary(t.start, operand.end, t.value, operand)
            }
            if (t is JsToken.Keyword && t.value == "await") {
                next()
                val operand = parseUnary()
                return JsExpr.Await(t.start, operand.end, operand)
            }
            if (t is JsToken.Keyword && t.value == "yield") {
                next()
                var star = false
                if (isP("*")) {
                    next()
                    star = true
                }
                var arg: JsExpr? = null
                val nt = cur()
                val noArg = nt is JsToken.Eof || (nt is JsToken.Punctuator && (nt.value == ";" || nt.value == ")" || nt.value == "]" || nt.value == "}" || nt.value == "," || nt.value == ":"))
                if (!noArg) arg = parseAssign()
                val end = if (arg != null) arg.end else t.end
                return JsExpr.Yield(t.start, end, arg, star)
            }
            if (t is JsToken.Punctuator && (t.value == "++" || t.value == "--")) {
                next()
                val operand = parseUnary()
                return JsExpr.Update(t.start, operand.end, t.value, operand, true)
            }
            return parsePostfix()
        }

        fun parsePostfix(): JsExpr {
            val e = parseCallMember()
            val t = cur()
            if (t is JsToken.Punctuator && (t.value == "++" || t.value == "--") && isAssignTarget(e)) {
                next()
                return JsExpr.Update(e.start, t.end, t.value, e, false)
            }
            return e
        }

        fun parseCallMember(): JsExpr {
            var e = parsePrimary()
            while (true) {
                val t = cur()
                if (t is JsToken.Punctuator && t.value == ".") {
                    next()
                    val p = next()
                    val pname = when (p) {
                        is JsToken.Identifier -> p.value
                        is JsToken.Keyword -> p.value
                        else -> throw abort("unexpected-token", p.start, p.end)
                    }
                    e = JsExpr.Member(e.start, p.end, e, false, pname, null)
                } else if (t is JsToken.Punctuator && t.value == "?.") {
                    next()
                    val p = cur()
                    if (p is JsToken.Punctuator && p.value == "(") {
                        val args = parseArgs()
                        e = JsExpr.Call(e.start, args.closeEnd, e, args.list, true)
                    } else if (p is JsToken.Punctuator && p.value == "[") {
                        next()
                        val idx = parseExpr()
                        expectP("]")
                        e = JsExpr.Member(e.start, toks[i - 1].end, e, true, null, idx)
                    } else {
                        val pname = when (p) {
                            is JsToken.Identifier -> p.value
                            is JsToken.Keyword -> p.value
                            else -> throw abort("unexpected-token", p.start, p.end)
                        }
                        next()
                        e = JsExpr.Member(e.start, p.end, e, true, pname, null)
                    }
                } else if (t is JsToken.Punctuator && t.value == "[") {
                    next()
                    val idx = parseExpr()
                    expectP("]")
                    e = JsExpr.Member(e.start, toks[i - 1].end, e, false, null, idx)
                } else if (t is JsToken.Punctuator && t.value == "(") {
                    val args = parseArgs()
                    e = JsExpr.Call(e.start, args.closeEnd, e, args.list, false)
                } else {
                    break
                }
            }
            return e
        }

        fun parseArgs(): Args {
            val open = next()
            val list = mutableListOf<JsExpr>()
            while (!isP(")")) {
                if (isP("...")) {
                    val spread = next()
                    val e = parseAssign()
                    list.add(JsExpr.Spread(spread.start, e.end, e))
                } else {
                    list.add(parseAssign())
                }
                if (isP(",")) {
                    next()
                    continue
                }
                break
            }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == ")")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t.start)
            }
            val close = next()
            return Args(list, close.end)
        }

        fun parsePrimary(): JsExpr {
            val t = cur()
            when (t) {
                is JsToken.Identifier -> {
                    if (t.value == "async") {
                        val p1 = peek(1)
                        if (p1 is JsToken.Keyword && p1.value == "function") {
                            next()
                            return parseFunctionExpr(true)
                        }
                        if (p1 is JsToken.Identifier && peek(2) is JsToken.Punctuator && (peek(2) as JsToken.Punctuator).value == "=>") {
                            next()
                            next()
                            next()
                            val ps = listOf(Param(p1.start, p1.end, p1.value, null))
                            return finishArrow(t.start, ps, true)
                        }
                        if (p1 is JsToken.Punctuator && p1.value == "(") {
                            val save = i
                            next()
                            try {
                                val ps = parseParams()
                                val p3 = cur()
                                if (p3 is JsToken.Punctuator && p3.value == "=>") {
                                    next()
                                    return finishArrow(t.start, ps, true)
                                }
                            } catch (e: ParseAbort) {
                            }
                            i = save
                        }
                    }
                    next()
                    return JsExpr.Ident(t.start, t.end, t.value)
                }
                is JsToken.NumberLiteral -> {
                    next()
                    return JsExpr.Number(t.start, t.end, t.raw, t.value)
                }
                is JsToken.BigIntLiteral -> {
                    next()
                    return JsExpr.BigInt(t.start, t.end, t.raw)
                }
                is JsToken.StringLiteral -> {
                    next()
                    return JsExpr.Str(t.start, t.end, t.value)
                }
                is JsToken.TemplateLiteral -> {
                    next()
                    return JsExpr.Template(t.start, t.end, listOf(t.value), emptyList())
                }
                is JsToken.TemplateHead -> return parseTemplate()
                is JsToken.RegExpLiteral -> {
                    next()
                    return JsExpr.RegExp(t.start, t.end, t.body, t.flags)
                }
                is JsToken.Keyword -> {
                    when (t.value) {
                        "this" -> {
                            next()
                            return JsExpr.This(t.start, t.end)
                        }
                        "true", "false" -> {
                            next()
                            return JsExpr.Bool(t.start, t.end, t.value == "true")
                        }
                        "null" -> {
                            next()
                            return JsExpr.Null(t.start, t.end)
                        }
                        "function" -> return parseFunctionExpr()
                        "new" -> return parseNew()
                        "class" -> return parseClassExpr()
                    }
                    throw abort("unexpected-token", t.start, t.end)
                }
                is JsToken.Punctuator -> {
                    if (t.value == "(") {
                        next()
                        val e = parseExpr()
                        val close = cur()
                        if (!(close is JsToken.Punctuator && close.value == ")")) {
                            throw abort(if (close is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, close.start)
                        }
                        next()
                        return JsExpr.Paren(t.start, close.end, e)
                    }
                    if (t.value == "[") return parseArray()
                    if (t.value == "{") return parseObject()
                    throw abort("unexpected-token", t.start, t.end)
                }
                is JsToken.Eof -> throw abort("unexpected-eof", t.start, t.end)
                else -> throw abort("unexpected-token", t.start, t.end)
            }
        }

        fun parseTemplate(): JsExpr {
            val head = next()
            val quasis = mutableListOf((head as JsToken.TemplateHead).value)
            val exprs = mutableListOf<JsExpr>()
            while (true) {
                exprs.add(parseAssign())
                val t = cur()
                if (t is JsToken.TemplateMiddle) {
                    next()
                    quasis.add(t.value)
                    continue
                }
                if (t is JsToken.TemplateTail) {
                    next()
                    quasis.add(t.value)
                    return JsExpr.Template(head.start, t.end, quasis, exprs)
                }
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, t.end)
            }
        }

        fun parseArray(): JsExpr.ArrayLit {
            val open = next()
            val items = mutableListOf<JsExpr>()
            while (!isP("]")) {
                if (isP("...")) {
                    val spread = next()
                    val e = parseAssign()
                    items.add(JsExpr.Spread(spread.start, e.end, e))
                } else {
                    items.add(parseAssign())
                }
                if (isP(",")) {
                    next()
                    continue
                }
                break
            }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == "]")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t.start)
            }
            val close = next()
            return JsExpr.ArrayLit(open.start, close.end, items)
        }

        fun parseObject(): JsExpr.ObjectLit {
            val open = next()
            val props = mutableListOf<Prop>()
            while (!isP("}")) {
                val t = cur()
                if (t is JsToken.Punctuator && t.value == "...") {
                    next()
                    val e = parseAssign()
                    props.add(Prop.SpreadProp(t.start, e.end, e))
                } else if (t is JsToken.Identifier && (t.value == "get" || t.value == "set") && isKeyStart(peek(1))) {
                    next()
                    val isGetter = t.value == "get"
                    val key = parseKeyToken()
                    val params = parseParams()
                    val body = parseBlock()
                    props.add(Prop.Accessor(t.start, body.end, isGetter, key, params, body))
                } else {
                    var key: Key? = null
                    if (t is JsToken.Identifier || t is JsToken.Keyword) {
                        next()
                        key = Key.IdentKey(t.start, t.end, if (t is JsToken.Identifier) t.value else (t as JsToken.Keyword).value)
                    } else if (t is JsToken.StringLiteral) {
                        next()
                        key = Key.StringKey(t.start, t.end, t.value)
                    } else if (t is JsToken.NumberLiteral || t is JsToken.BigIntLiteral) {
                        next()
                        key = Key.NumberKey(t.start, t.end, if (t is JsToken.NumberLiteral) t.raw else (t as JsToken.BigIntLiteral).raw)
                    } else if (t is JsToken.Punctuator && t.value == "[") {
                        next()
                        val e = parseAssign()
                        expectP("]")
                        key = Key.ComputedKey(t.start, toks[i - 1].end, e)
                    } else {
                        throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", t.start, t.end)
                    }
                    val k = key
                    if (isP(":")) {
                        next()
                        val v = parseAssign()
                        props.add(Prop.KeyValue(k.start, v.end, k, v))
                    } else if (isP("(")) {
                        val params = parseParams()
                        val body = parseBlock()
                        props.add(Prop.Method(k.start, body.end, k, params, body))
                    } else {
                        if (k !is Key.IdentKey) throw abort("unexpected-token", cur().start, cur().end)
                        if (isP("=")) {
                            next()
                            val def = parseAssign()
                            val tgt = JsExpr.Ident(k.start, k.end, k.name)
                            props.add(Prop.KeyValue(k.start, def.end, k, JsExpr.Assign(k.start, def.end, "=", tgt, def)))
                        } else {
                            props.add(Prop.Shorthand(k.start, k.end, k.name))
                        }
                    }
                }
                if (isP(",")) {
                    next()
                    continue
                }
                break
            }
            val t = cur()
            if (!(t is JsToken.Punctuator && t.value == "}")) {
                throw abort(if (t is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, t.start)
            }
            val close = next()
            return JsExpr.ObjectLit(open.start, close.end, props)
        }

        fun parseNew(): JsExpr.New {
            val kw = next()
            var callee = parsePrimary()
            while (true) {
                val t = cur()
                if (t is JsToken.Punctuator && t.value == ".") {
                    next()
                    val p = next()
                    val pname = when (p) {
                        is JsToken.Identifier -> p.value
                        is JsToken.Keyword -> p.value
                        else -> throw abort("unexpected-token", p.start, p.end)
                    }
                    callee = JsExpr.Member(callee.start, p.end, callee, false, pname, null)
                } else if (t is JsToken.Punctuator && t.value == "?.") {
                    next()
                    val p = cur()
                    if (p is JsToken.Punctuator && p.value == "[") {
                        next()
                        val idx = parseExpr()
                        expectP("]")
                        callee = JsExpr.Member(callee.start, toks[i - 1].end, callee, true, null, idx)
                    } else {
                        val pname = when (p) {
                            is JsToken.Identifier -> p.value
                            is JsToken.Keyword -> p.value
                            else -> throw abort("unexpected-token", p.start, p.end)
                        }
                        next()
                        callee = JsExpr.Member(callee.start, p.end, callee, true, pname, null)
                    }
                } else if (t is JsToken.Punctuator && t.value == "[") {
                    next()
                    val idx = parseExpr()
                    expectP("]")
                    callee = JsExpr.Member(callee.start, toks[i - 1].end, callee, false, null, idx)
                } else {
                    break
                }
            }
            var args: List<JsExpr> = emptyList()
            var end = callee.end
            if (isP("(")) {
                val a = parseArgs()
                args = a.list
                end = a.closeEnd
            }
            return JsExpr.New(kw.start, end, callee, args)
        }
    }
}
