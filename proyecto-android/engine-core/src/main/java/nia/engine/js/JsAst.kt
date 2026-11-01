package nia.engine.js

/**
 * AST del parser ECMAScript (T018 v2 v1). Cada nodo cubre el rango de fuente
 * [start, end). El programa acumula los errores de parseo tipificados con
 * linea/columna 1-based (la recuperacion es a nivel de sentencia); los
 * errores lexicos viajan aparte en JsParseResult tal como los emite el lexer.
 */
sealed class JsNode(val start: Int, val end: Int)

class JsParseError(val code: String, start: Int, end: Int, val line: Int, val column: Int)

class JsProgram(start: Int, end: Int, val statements: List<JsStmt>, val errors: List<JsParseError>) : JsNode(start, end)

class JsParseResult(val program: JsProgram, val lexErrors: List<JsLexError>)

sealed class JsStmt(start: Int, end: Int) : JsNode(start, end) {

    class Empty(start: Int, end: Int) : JsStmt(start, end)

    class Block(start: Int, end: Int, val statements: List<JsStmt>) : JsStmt(start, end)

    class Var(start: Int, end: Int, val kind: String, val declarators: List<Declarator>) : JsStmt(start, end)

    class Function(start: Int, end: Int, val name: String, val params: List<Param>, val body: Block) : JsStmt(start, end)

    class Return(start: Int, end: Int, val value: JsExpr?) : JsStmt(start, end)

    class If(start: Int, end: Int, val test: JsExpr, val thenBranch: JsStmt, val elseBranch: JsStmt?) : JsStmt(start, end)

    class While(start: Int, end: Int, val test: JsExpr, val body: JsStmt) : JsStmt(start, end)

    class DoWhile(start: Int, end: Int, val body: JsStmt, val test: JsExpr) : JsStmt(start, end)

    class For(start: Int, end: Int, val init: JsStmt?, val test: JsExpr?, val update: JsExpr?, val body: JsStmt) : JsStmt(start, end)

    class ForInOf(start: Int, end: Int, val isOf: Boolean, val leftKind: String?, val leftName: String?, val leftExpr: JsExpr?, val right: JsExpr, val body: JsStmt) : JsStmt(start, end)

    class Break(start: Int, end: Int) : JsStmt(start, end)

    class Continue(start: Int, end: Int) : JsStmt(start, end)

    class Throw(start: Int, end: Int, val value: JsExpr) : JsStmt(start, end)

    class Try(start: Int, end: Int, val block: Block, val catchParam: String?, val catchBlock: Block?, val finallyBlock: Block?) : JsStmt(start, end)

    class Switch(start: Int, end: Int, val disc: JsExpr, val cases: List<SwitchCase>) : JsStmt(start, end)

    class ExprStmt(start: Int, end: Int, val expr: JsExpr) : JsStmt(start, end)
}

class Declarator(start: Int, end: Int, val name: String, val init: JsExpr?) : JsNode(start, end)

class Param(start: Int, end: Int, val name: String, val def: JsExpr?) : JsNode(start, end)

class SwitchCase(start: Int, end: Int, val test: JsExpr?, val statements: List<JsStmt>) : JsNode(start, end)

sealed class JsExpr(start: Int, end: Int) : JsNode(start, end) {

    class Ident(start: Int, end: Int, val name: String) : JsExpr(start, end)

    class Number(start: Int, end: Int, val raw: String, val value: Double) : JsExpr(start, end)

    class BigInt(start: Int, end: Int, val raw: String) : JsExpr(start, end)

    class Str(start: Int, end: Int, val value: String) : JsExpr(start, end)

    class Template(start: Int, end: Int, val quasis: List<String>, val exprs: List<JsExpr>) : JsExpr(start, end)

    class RegExp(start: Int, end: Int, val body: String, val flags: String) : JsExpr(start, end)

    class Bool(start: Int, end: Int, val value: Boolean) : JsExpr(start, end)

    class Null(start: Int, end: Int) : JsExpr(start, end)

    class This(start: Int, end: Int) : JsExpr(start, end)

    class ArrayLit(start: Int, end: Int, val items: List<JsExpr>) : JsExpr(start, end)

    class ObjectLit(start: Int, end: Int, val props: List<Prop>) : JsExpr(start, end)

    class Paren(start: Int, end: Int, val expr: JsExpr) : JsExpr(start, end)

    class Unary(start: Int, end: Int, val op: String, val operand: JsExpr) : JsExpr(start, end)

    class Update(start: Int, end: Int, val op: String, val operand: JsExpr, val prefix: Boolean) : JsExpr(start, end)

    class Spread(start: Int, end: Int, val expr: JsExpr) : JsExpr(start, end)

    class Binary(start: Int, end: Int, val op: String, val left: JsExpr, val right: JsExpr) : JsExpr(start, end)

    class Logical(start: Int, end: Int, val op: String, val left: JsExpr, val right: JsExpr) : JsExpr(start, end)

    class Assign(start: Int, end: Int, val op: String, val target: JsExpr, val value: JsExpr) : JsExpr(start, end)

    class Cond(start: Int, end: Int, val test: JsExpr, val cons: JsExpr, val alt: JsExpr) : JsExpr(start, end)

    class Call(start: Int, end: Int, val callee: JsExpr, val args: List<JsExpr>) : JsExpr(start, end)

    class New(start: Int, end: Int, val callee: JsExpr, val args: List<JsExpr>) : JsExpr(start, end)

    class Member(start: Int, end: Int, val obj: JsExpr, val optional: Boolean, val name: String?, val computed: JsExpr?) : JsExpr(start, end)

    class Seq(start: Int, end: Int, val exprs: List<JsExpr>) : JsExpr(start, end)

    class FunctionExpr(start: Int, end: Int, val name: String?, val params: List<Param>, val body: JsStmt.Block) : JsExpr(start, end)
}

sealed class Prop(start: Int, end: Int) : JsNode(start, end) {

    class KeyValue(start: Int, end: Int, val key: Key, val value: JsExpr) : Prop(start, end)

    class Method(start: Int, end: Int, val key: Key, val params: List<Param>, val body: JsStmt.Block) : Prop(start, end)

    class Shorthand(start: Int, end: Int, val name: String) : Prop(start, end)

    class SpreadProp(start: Int, end: Int, val expr: JsExpr) : Prop(start, end)
}

sealed class Key(start: Int, end: Int) : JsNode(start, end) {

    class IdentKey(start: Int, end: Int, val name: String) : Key(start, end)

    class StringKey(start: Int, end: Int, val value: String) : Key(start, end)

    class NumberKey(start: Int, end: Int, val raw: String) : Key(start, end)

    class ComputedKey(start: Int, end: Int, val expr: JsExpr) : Key(start, end)
}

/**
 * Dump canonico en forma S del AST. Espejo exacto del dump del gemelo TS/JS:
 * misma forma, mismo orden, mismo escape de cadenas.
 */
object JsAstDump {

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

    fun program(p: JsProgram): String {
        val sb = StringBuilder("(program")
        for (s in p.statements) sb.append(" ").append(statement(s))
        return sb.append(")").toString()
    }

    fun parseErrors(p: JsProgram): String =
        p.errors.joinToString(";") { it.code + "@" + it.start + "-" + it.end + ":" + it.line + ":" + it.column }

    fun lexErrors(errs: List<JsLexError>): String =
        errs.joinToString(";") { it.code + "@" + it.start + ":" + it.line + ":" + it.column }

    private fun params(list: List<Param>): String {
        val sb = StringBuilder("(params")
        for (p in list) {
            sb.append(" (p ").append(p.name)
            if (p.def != null) sb.append(" ").append(expression(p.def))
            sb.append(")")
        }
        return sb.append(")").toString()
    }

    private fun key(k: Key): String = when (k) {
        is Key.IdentKey -> "(key " + k.name + ")"
        is Key.StringKey -> "(keystr \"" + esc(k.value) + "\")"
        is Key.NumberKey -> "(keyn " + k.raw + ")"
        is Key.ComputedKey -> "(keyc " + expression(k.expr) + ")"
    }

    private fun statement(s: JsStmt): String = when (s) {
        is JsStmt.Empty -> "(empty)"
        is JsStmt.Block -> {
            val sb = StringBuilder("(block")
            for (x in s.statements) sb.append(" ").append(statement(x))
            sb.append(")").toString()
        }
        is JsStmt.Var -> {
            val sb = StringBuilder("(" + s.kind)
            for (d in s.declarators) {
                sb.append(" (decl ").append(d.name)
                if (d.init != null) sb.append(" ").append(expression(d.init))
                sb.append(")")
            }
            sb.append(")").toString()
        }
        is JsStmt.Function -> "(fun " + s.name + " " + params(s.params) + " " + statement(s.body) + ")"
        is JsStmt.Return -> if (s.value == null) "(return)" else "(return " + expression(s.value) + ")"
        is JsStmt.If -> {
            var r = "(if " + expression(s.test) + " " + statement(s.thenBranch)
            if (s.elseBranch != null) r += " " + statement(s.elseBranch)
            r + ")"
        }
        is JsStmt.While -> "(while " + expression(s.test) + " " + statement(s.body) + ")"
        is JsStmt.DoWhile -> "(do " + statement(s.body) + " " + expression(s.test) + ")"
        is JsStmt.For -> {
            var r = "(for " + (if (s.init == null) "-" else statement(s.init))
            r += " " + (if (s.test == null) "-" else expression(s.test))
            r += " " + (if (s.update == null) "-" else expression(s.update))
            r + " " + statement(s.body) + ")"
        }
        is JsStmt.ForInOf -> {
            val left = if (s.leftKind != null) "(bind " + s.leftName + ")" else expression(s.leftExpr!!)
            "(" + (if (s.isOf) "forof" else "forin") + " " + left + " " + expression(s.right) + " " + statement(s.body) + ")"
        }
        is JsStmt.Break -> "(break)"
        is JsStmt.Continue -> "(continue)"
        is JsStmt.Throw -> "(throw " + expression(s.value) + ")"
        is JsStmt.Try -> {
            var r = "(try " + statement(s.block)
            if (s.catchBlock != null) r += " (catch " + s.catchParam + " " + statement(s.catchBlock) + ")"
            if (s.finallyBlock != null) r += " (finally " + statement(s.finallyBlock) + ")"
            r + ")"
        }
        is JsStmt.Switch -> {
            val sb = StringBuilder("(switch " + expression(s.disc))
            for (c in s.cases) {
                sb.append(" (")
                if (c.test == null) sb.append("default") else sb.append("case ").append(expression(c.test))
                for (x in c.statements) sb.append(" ").append(statement(x))
                sb.append(")")
            }
            sb.append(")").toString()
        }
        is JsStmt.ExprStmt -> "(expr " + expression(s.expr) + ")"
    }

    private fun expression(e: JsExpr): String = when (e) {
        is JsExpr.Ident -> "(id " + e.name + ")"
        is JsExpr.Number -> "(num " + e.raw + ")"
        is JsExpr.BigInt -> "(bigint " + e.raw + ")"
        is JsExpr.Str -> "(str \"" + esc(e.value) + "\")"
        is JsExpr.Template -> {
            val sb = StringBuilder("(tmpl (q")
            for (q in e.quasis) sb.append(" \"").append(esc(q)).append("\"")
            sb.append(")")
            for (x in e.exprs) sb.append(" ").append(expression(x))
            sb.append(")").toString()
        }
        is JsExpr.RegExp -> "(re \"" + esc(e.body) + "\" " + (if (e.flags == "") "-" else e.flags) + ")"
        is JsExpr.Bool -> "(bool " + (if (e.value) "true" : "false") + ")"
        is JsExpr.Null -> "(null)"
        is JsExpr.This -> "(this)"
        is JsExpr.ArrayLit -> {
            val sb = StringBuilder("(arr")
            for (it in e.items) sb.append(" ").append(expression(it))
            sb.append(")").toString()
        }
        is JsExpr.ObjectLit -> {
            val sb = StringBuilder("(obj")
            for (p in e.props) {
                when (p) {
                    is Prop.KeyValue -> sb.append(" (kv ").append(key(p.key)).append(" ").append(expression(p.value)).append(")")
                    is Prop.Method -> sb.append(" (meth ").append(key(p.key)).append(" ").append(params(p.params)).append(" ").append(statement(p.body)).append(")")
                    is Prop.Shorthand -> sb.append(" (short ").append(p.name).append(")")
                    is Prop.SpreadProp -> sb.append(" (spr ").append(expression(p.expr)).append(")")
                }
            }
            sb.append(")").toString()
        }
        is JsExpr.Paren -> "(paren " + expression(e.expr) + ")"
        is JsExpr.Unary -> "(un " + e.op + " " + expression(e.operand) + ")"
        is JsExpr.Update -> "(" + (if (e.prefix) "pre" else "post") + e.op + " " + expression(e.operand) + ")"
        is JsExpr.Spread -> "(spr " + expression(e.expr) + ")"
        is JsExpr.Binary -> "(bin " + e.op + " " + expression(e.left) + " " + expression(e.right) + ")"
        is JsExpr.Logical -> "(log " + e.op + " " + expression(e.left) + " " + expression(e.right) + ")"
        is JsExpr.Assign -> "(asg " + e.op + " " + expression(e.target) + " " + expression(e.value) + ")"
        is JsExpr.Cond -> "(cond " + expression(e.test) + " " + expression(e.cons) + " " + expression(e.alt) + ")"
        is JsExpr.Call -> {
            val sb = StringBuilder("(call " + expression(e.callee))
            for (a in e.args) sb.append(" ").append(expression(a))
            sb.append(")").toString()
        }
        is JsExpr.New -> {
            val sb = StringBuilder("(new " + expression(e.callee))
            for (a in e.args) sb.append(" ").append(expression(a))
            sb.append(")").toString()
        }
        is JsExpr.Member -> {
            if (e.name != null) "(mem " + expression(e.obj) + "." + e.name + ")"
            else "(memic " + expression(e.obj) + " " + expression(e.computed!!) + ")"
        }
        is JsExpr.Seq -> {
            val sb = StringBuilder("(seq")
            for (x in e.exprs) sb.append(" ").append(expression(x))
            sb.append(")").toString()
        }
        is JsExpr.FunctionExpr -> {
            var r = "(funx"
            if (e.name != null) r += " " + e.name
            r + " " + params(e.params) + " " + statement(e.body) + ")"
        }
    }
}
