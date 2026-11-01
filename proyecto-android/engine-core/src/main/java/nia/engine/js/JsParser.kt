package nia.engine.js

/**
 * Parser ECMAScript v1 (T018 v2). Recursive descent con precedencias sobre el
 * token stream del lexer v1. AST con spans [start, end), errores tipificados
 * con linea/columna 1-based y recuperacion a nivel de sentencia (skip hasta
 * ';' o '}', con avance garantizado). Espejo del gemelo TS/JS validado.
 * Exclusiones v1: clases, async/await, generators, arrows, destructuring,
 * labels, with, modulos, getters/setters, optional chaining (el lexer v1 no
 * emite '?.'), ASI (los puntos y coma son obligatorios).
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
                    "class", "import", "export", "with", "debugger" -> {
                        next()
                        throw abort("unsupported-statement", t.start, t.end)
                    }
                    else -> {}
                }
            }
            if (t is JsToken.Identifier && t.value == "let" && peek(1) is JsToken.Identifier) {
                return parseVarStatement("let")
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

        fun parseParams(): List<Param> {
            val open = next()
            if (!(open is JsToken.Punctuator && open.value == "(")) {
                throw abort(if (open is JsToken.Eof) "unexpected-eof" else "unexpected-token", open.start, open.end)
            }
            val params = mutableListOf<Param>()
            while (!isP(")")) {
                val t = cur()
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

        fun parseFunctionStmt(): JsStmt.Function {
            val kw = next()
            val t = cur()
            if (t !is JsToken.Identifier) throw abort("invalid-binding", t.start, t.end)
            val nameTok = next()
            val params = parseParams()
            val body = parseBlock()
            return JsStmt.Function(kw.start, body.end, t.value, params, body)
        }

        fun parseFunctionExpr(): JsExpr.FunctionExpr {
            val kw = next()
            var name: String? = null
            val t = cur()
            if (t is JsToken.Identifier) {
                val nt = next()
                name = t.value
            }
            val params = parseParams()
            val body = parseBlock()
            return JsExpr.FunctionExpr(kw.start, body.end, name, params, body)
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
            val end = expectSemi(t.end)
            return if (kw == "break") JsStmt.Break(t.start, end) else JsStmt.Continue(t.start, end)
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

        fun parseAssign(noIn: Boolean = false): JsExpr {
            val left = parseCond(noIn)
            val t = cur()
            if (t is JsToken.Punctuator && t.value in ASSIGN_OPS) {
                if (!isAssignTarget(left)) throw abort("invalid-assignment-target", t.start, t.end)
                next()
                val right = parseAssign(noIn)
                return JsExpr.Assign(left.start, right.end, t.value, left, right)
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
                } else if (t is JsToken.Punctuator && t.value == "[") {
                    next()
                    val idx = parseExpr()
                    expectP("]")
                    e = JsExpr.Member(e.start, toks[i - 1].end, e, false, null, idx)
                } else if (t is JsToken.Punctuator && t.value == "(") {
                    val args = parseArgs()
                    e = JsExpr.Call(e.start, args.closeEnd, e, args.list)
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
                        props.add(Prop.Shorthand(k.start, k.end, k.name))
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
