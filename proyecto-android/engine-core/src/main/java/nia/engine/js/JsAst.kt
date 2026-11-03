package nia.engine.js

/**
 * AST del parser ECMAScript (T018 v5). Cada nodo cubre el rango de fuente
 * [start, end). El programa acumula los errores de parseo tipificados con
 * linea/columna 1-based (la recuperacion es a nivel de sentencia); los
 * errores lexicos viajan aparte en JsParseResult tal como los emite el lexer.
 */
sealed class JsNode(val start: Int, val end: Int)

class JsParseError(val code: String, val start: Int, val end: Int, val line: Int, val column: Int)

class JsProgram(start: Int, end: Int, val statements: List<JsStmt>, val errors: List<JsParseError>) : JsNode(start, end)

class JsParseResult(val program: JsProgram, val lexErrors: List<JsLexError>)

sealed class JsStmt(start: Int, end: Int) : JsNode(start, end) {

    class Empty(start: Int, end: Int) : JsStmt(start, end)

    class Block(start: Int, end: Int, val statements: List<JsStmt>) : JsStmt(start, end)

    class Var(start: Int, end: Int, val kind: String, val declarators: List<Declarator>) : JsStmt(start, end)

    class Function(start: Int, end: Int, val name: String, val params: List<Param>, val body: Block, val async: Boolean = false, val gen: Boolean = false) : JsStmt(start, end)

    class Return(start: Int, end: Int, val value: JsExpr?) : JsStmt(start, end)

    class If(start: Int, end: Int, val test: JsExpr, val thenBranch: JsStmt, val elseBranch: JsStmt?) : JsStmt(start, end)

    class While(start: Int, end: Int, val test: JsExpr, val body: JsStmt) : JsStmt(start, end)

    class DoWhile(start: Int, end: Int, val body: JsStmt, val test: JsExpr) : JsStmt(start, end)

    class For(start: Int, end: Int, val init: JsStmt?, val test: JsExpr?, val update: JsExpr?, val body: JsStmt) : JsStmt(start, end)

    class ForInOf(start: Int, end: Int, val isOf: Boolean, val leftKind: String?, val leftName: String?, val leftExpr: JsExpr?, val right: JsExpr, val body: JsStmt) : JsStmt(start, end)

    class Break(start: Int, end: Int, val label: String?) : JsStmt(start, end)

    class Continue(start: Int, end: Int, val label: String?) : JsStmt(start, end)

    class Throw(start: Int, end: Int, val value: JsExpr) : JsStmt(start, end)

    class Try(start: Int, end: Int, val block: Block, val catchParam: String?, val catchBlock: Block?, val finallyBlock: Block?) : JsStmt(start, end)

    class Switch(start: Int, end: Int, val disc: JsExpr, val cases: List<SwitchCase>) : JsStmt(start, end)

    class ExprStmt(start: Int, end: Int, val expr: JsExpr) : JsStmt(start, end)

    class Labeled(start: Int, end: Int, val label: String, val body: JsStmt) : JsStmt(start, end)

    class ClassStmt(start: Int, end: Int, val name: String?, val superClass: JsExpr?, val members: List<ClassMember>) : JsStmt(start, end)
}

class Declarator(start: Int, end: Int, val name: String, val init: JsExpr?, val pat: JsPat? = null) : JsNode(start, end)

class Param(start: Int, end: Int, val name: String, val def: JsExpr?, val pat: JsPat? = null) : JsNode(start, end)

/** Patron de destructuring (T018 v4): ident/miembro con default, array u objeto. */
sealed class JsPat(start: Int, end: Int) : JsNode(start, end) {

    class PIdent(start: Int, end: Int, val name: String, val def: JsExpr?) : JsPat(start, end)

    class PMember(start: Int, end: Int, val target: JsExpr, val def: JsExpr?) : JsPat(start, end)

    class PArray(start: Int, end: Int, val elems: List<JsPat?>, val rest: JsPat?) : JsPat(start, end)

    class PObject(start: Int, end: Int, val entries: List<PEntry>, val rest: JsPat?) : JsPat(start, end)
}

class PEntry(start: Int, end: Int, val key: Key, val pat: JsPat) : JsNode(start, end)

class SwitchCase(start: Int, end: Int, val test: JsExpr?, val statements: List<JsStmt>) : JsNode(start, end)

class ClassMember(start: Int, end: Int, val isStatic: Boolean, val kind: String, val key: Key, val params: List<Param>, val body: JsStmt.Block) : JsNode(start, end)

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

    class Call(start: Int, end: Int, val callee: JsExpr, val args: List<JsExpr>, val optional: Boolean) : JsExpr(start, end)

    class New(start: Int, end: Int, val callee: JsExpr, val args: List<JsExpr>) : JsExpr(start, end)

    class Member(start: Int, end: Int, val obj: JsExpr, val optional: Boolean, val name: String?, val computed: JsExpr?) : JsExpr(start, end)

    class Seq(start: Int, end: Int, val exprs: List<JsExpr>) : JsExpr(start, end)

    class FunctionExpr(start: Int, end: Int, val name: String?, val params: List<Param>, val body: JsStmt.Block, val async: Boolean = false, val gen: Boolean = false) : JsExpr(start, end)

    class Arrow(start: Int, end: Int, val params: List<Param>, val bodyExpr: JsExpr?, val bodyBlock: JsStmt.Block?, val async: Boolean = false) : JsExpr(start, end)

    class ClassExpr(start: Int, end: Int, val name: String?, val superClass: JsExpr?, val members: List<ClassMember>) : JsExpr(start, end)

    /** Await de una promesa (T018 v5): solo valido dentro de funciones async. */
    class Await(start: Int, end: Int, val arg: JsExpr) : JsExpr(start, end)

    /** Yield con argumento opcional y delegacion con '*' (T018 v5): solo dentro de generators. */
    class Yield(start: Int, end: Int, val arg: JsExpr?, val star: Boolean) : JsExpr(start, end)
}

sealed class Prop(start: Int, end: Int) : JsNode(start, end) {

    class KeyValue(start: Int, end: Int, val key: Key, val value: JsExpr) : Prop(start, end)

    class Method(start: Int, end: Int, val key: Key, val params: List<Param>, val body: JsStmt.Block) : Prop(start, end)

    class Shorthand(start: Int, end: Int, val name: String) : Prop(start, end)

    class SpreadProp(start: Int, end: Int, val expr: JsExpr) : Prop(start, end)

    class Accessor(start: Int, end: Int, val isGetter: Boolean, val key: Key, val params: List<Param>, val body: JsStmt.Block) : Prop(start, end)
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
            if (p.pat != null) {
                sb.append(" (pp ").append(pattern(p.pat))
                if (p.def != null) sb.append(" ").append(expression(p.def))
                sb.append(")")
                continue
            }
            sb.append(" (p ").append(p.name)
            if (p.def != null) sb.append(" ").append(expression(p.def))
            sb.append(")")
        }
        return sb.append(")").toString()
    }

    private fun pattern(p: JsPat): String = when (p) {
        is JsPat.PIdent -> {
            var r = "(pid " + p.name
            if (p.def != null) r += " " + expression(p.def)
            r + ")"
        }
        is JsPat.PMember -> {
            var r = "(pmem " + expression(p.target)
            if (p.def != null) r += " " + expression(p.def)
            r + ")"
        }
        is JsPat.PArray -> {
            val sb = StringBuilder("(parr")
            for (el in p.elems) sb.append(" ").append(if (el == null) "-" else pattern(el))
            if (p.rest != null) sb.append(" (rest ").append(pattern(p.rest)).append(")")
            sb.append(")").toString()
        }
        is JsPat.PObject -> {
            val sb = StringBuilder("(pobj")
            for (en in p.entries) sb.append(" (pe ").append(key(en.key)).append(" ").append(pattern(en.pat)).append(")")
            if (p.rest != null) sb.append(" (rest ").append(pattern(p.rest)).append(")")
            sb.append(")").toString()
        }
    }

    private fun key(k: Key): String = when (k) {
        is Key.IdentKey -> "(key " + k.name + ")"
        is Key.StringKey -> "(keystr \"" + esc(k.value) + "\")"
        is Key.NumberKey -> "(keyn " + k.raw + ")"
        is Key.ComputedKey -> "(keyc " + expression(k.expr) + ")"
    }

    private fun classMember(m: ClassMember): String {
        var r = "(cm"
        if (m.isStatic) r += " static"
        if (m.kind != "method") r += " " + m.kind
        return r + " " + key(m.key) + " " + params(m.params) + " " + statement(m.body) + ")"
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
                if (d.pat != null) {
                    sb.append(" (declp ").append(pattern(d.pat))
                    if (d.init != null) sb.append(" ").append(expression(d.init))
                    sb.append(")")
                    continue
                }
                sb.append(" (decl ").append(d.name)
                if (d.init != null) sb.append(" ").append(expression(d.init))
                sb.append(")")
            }
            sb.append(")").toString()
        }
        is JsStmt.Function -> {
            var r = if (s.async) "(async fun" else "(fun"
            if (s.gen) r += "*"
            r + " " + s.name + " " + params(s.params) + " " + statement(s.body) + ")"
        }
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
        is JsStmt.Break -> if (s.label == null) "(break)" else "(break " + s.label + ")"
        is JsStmt.Continue -> if (s.label == null) "(continue)" else "(continue " + s.label + ")"
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
        is JsStmt.Labeled -> "(label " + s.label + " " + statement(s.body) + ")"
        is JsStmt.ClassStmt -> {
            var r = "(class"
            if (s.name != null) r += " " + s.name
            if (s.superClass != null) r += " (extends " + expression(s.superClass) + ")"
            for (m in s.members) r += " " + classMember(m)
            r + ")"
        }
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
        is JsExpr.Bool -> "(bool " + (if (e.value) "true" else "false") + ")"
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
                    is Prop.Accessor -> sb.append(" (").append(if (p.isGetter) "get" else "set").append(" ").append(key(p.key)).append(" ").append(params(p.params)).append(" ").append(statement(p.body)).append(")")
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
            val sb = StringBuilder("(call")
            if (e.optional) sb.append("?")
            sb.append(" ").append(expression(e.callee))
            for (a in e.args) sb.append(" ").append(expression(a))
            sb.append(")").toString()
        }
        is JsExpr.New -> {
            val sb = StringBuilder("(new " + expression(e.callee))
            for (a in e.args) sb.append(" ").append(expression(a))
            sb.append(")").toString()
        }
        is JsExpr.Member -> {
            if (e.name != null) "(mem" + (if (e.optional) "?" else "") + " " + expression(e.obj) + "." + e.name + ")"
            else "(memic" + (if (e.optional) "?" else "") + " " + expression(e.obj) + " " + expression(e.computed!!) + ")"
        }
        is JsExpr.Seq -> {
            val sb = StringBuilder("(seq")
            for (x in e.exprs) sb.append(" ").append(expression(x))
            sb.append(")").toString()
        }
        is JsExpr.FunctionExpr -> {
            var r = if (e.async) "(async funx" else "(funx"
            if (e.gen) r += "*"
            if (e.name != null) r += " " + e.name
            r + " " + params(e.params) + " " + statement(e.body) + ")"
        }
        is JsExpr.Arrow -> (if (e.async) "(async arrow " else "(arrow ") + params(e.params) + " " + (if (e.bodyExpr != null) expression(e.bodyExpr) else statement(e.bodyBlock!!)) + ")"
        is JsExpr.Await -> "(await " + expression(e.arg) + ")"
        is JsExpr.Yield -> {
            val a = e.arg
            if (a == null) {
                if (e.star) "(yield*)" else "(yield)"
            } else if (e.star) {
                "(yield* " + expression(a) + ")"
            } else {
                "(yield " + expression(a) + ")"
            }
        }
        is JsExpr.ClassExpr -> {
            var r = "(classx"
            if (e.name != null) r += " " + e.name
            if (e.superClass != null) r += " (extends " + expression(e.superClass) + ")"
            for (m in e.members) r += " " + classMember(m)
            r + ")"
        }
    }
}
