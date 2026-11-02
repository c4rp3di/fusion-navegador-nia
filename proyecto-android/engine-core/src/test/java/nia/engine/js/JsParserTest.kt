package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del parser ECMAScript v2 (T018 v4). Espejo 1:1 del gemelo TS/JS
 * validado: mismas fuentes, mismas expectativas (generadas desde las
 * salidas del gemelo, nunca transcritas a mano).
 */
class JsParserTest {

    private fun parse(src: String): JsParseResult = JsParser.parse(src)

    @Test
    fun programaVacio() {
        val r = parse("")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun expresionSimple() {
        val r = parse("1;")
        assertEquals("(program (expr (num 1)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun expresionesMultiples() {
        val r = parse("a; b;")
        assertEquals("(program (expr (id a)) (expr (id b)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun declaracionVar() {
        val r = parse("var x;")
        assertEquals("(program (var (decl x)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun declaracionConInicializador() {
        val r = parse("var x = 1;")
        assertEquals("(program (var (decl x (num 1))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun letMultipleDeclaradores() {
        val r = parse("let a = 1, b = 2, c;")
        assertEquals("(program (let (decl a (num 1)) (decl b (num 2)) (decl c)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun constCadena() {
        val r = parse("const s = 'hi';")
        assertEquals("(program (const (decl s (str \"hi\"))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asignacionSimple() {
        val r = parse("x = 5;")
        assertEquals("(program (expr (asg = (id x) (num 5))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asignacionCompuesta() {
        val r = parse("x += 2; y **= 2;")
        assertEquals("(program (expr (asg += (id x) (num 2))) (expr (asg **= (id y) (num 2))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun precedenciaAritmetica() {
        val r = parse("1 + 2 * 3;")
        assertEquals("(program (expr (bin + (num 1) (bin * (num 2) (num 3)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun parentesis() {
        val r = parse("(1 + 2) * 3;")
        assertEquals("(program (expr (bin * (paren (bin + (num 1) (num 2))) (num 3))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun unarios() {
        val r = parse("typeof x; void 0; delete o.p; !a; ~b; -c; +d;")
        assertEquals("(program (expr (un typeof (id x))) (expr (un void (num 0))) (expr (un delete (mem (id o).p))) (expr (un ! (id a))) (expr (un ~ (id b))) (expr (un - (id c))) (expr (un + (id d))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun incrementos() {
        val r = parse("x++; ++x; x--; --x;")
        assertEquals("(program (expr (post++ (id x))) (expr (pre++ (id x))) (expr (post-- (id x))) (expr (pre-- (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun condicional() {
        val r = parse("a ? b : c;")
        assertEquals("(program (expr (cond (id a) (id b) (id c))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun logicos() {
        val r = parse("a && b || c;")
        assertEquals("(program (expr (log || (log && (id a) (id b)) (id c))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun comparacionesYBits() {
        val r = parse("a < b === c; a << 2 & d;")
        assertEquals("(program (expr (bin === (bin < (id a) (id b)) (id c))) (expr (bin & (bin << (id a) (num 2)) (id d))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun potenciaAsociativaDerecha() {
        val r = parse("2 ** 3 ** 2;")
        assertEquals("(program (expr (bin ** (num 2) (bin ** (num 3) (num 2)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun miembroEIndex() {
        val r = parse("a.b.c[0].d;")
        assertEquals("(program (expr (mem (memic (mem (mem (id a).b).c) (num 0)).d)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun llamadas() {
        val r = parse("f(); f(1, 2); g(x)(y);")
        assertEquals("(program (expr (call (id f))) (expr (call (id f) (num 1) (num 2))) (expr (call (call (id g) (id x)) (id y))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun nuevo() {
        val r = parse("new A(); new A; new a.B(1);")
        assertEquals("(program (expr (new (id A))) (expr (new (id A))) (expr (new (mem (id a).B) (num 1))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun thisNullBooleanos() {
        val r = parse("this; null; true; false;")
        assertEquals("(program (expr (this)) (expr (null)) (expr (bool true)) (expr (bool false)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun cadenasEscapes() {
        val r = parse("\"a\\tb\\n\";")
        assertEquals("(program (expr (str \"a\\tb\\n\")))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun templateSimple() {
        val r = parse("`abc`;")
        assertEquals("(program (expr (tmpl (q \"abc\"))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun templateSustituciones() {
        val r = parse("`a\${x}b\${y + 1}c`;")
        assertEquals("(program (expr (tmpl (q \"a\" \"b\" \"c\") (id x) (bin + (id y) (num 1)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun templateAnidado() {
        val r = parse("`a\${`i\${j}k`}b`;")
        assertEquals("(program (expr (tmpl (q \"a\" \"b\") (tmpl (q \"i\" \"k\") (id j)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun templateConObjeto() {
        val r = parse("f(`x\${ {a: 1} }y`);")
        assertEquals("(program (expr (call (id f) (tmpl (q \"x\" \"y\") (obj (kv (key a) (num 1)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrays() {
        val r = parse("[1, 2, 3]; [1, 2,]; [...a, b];")
        assertEquals("(program (expr (arr (num 1) (num 2) (num 3))) (expr (arr (num 1) (num 2))) (expr (arr (spr (id a)) (id b))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun objetos() {
        val r = parse("({a: 1, 'b': 2, 3: 3, [k]: 4, c, m() { return 1; }, ...r});")
        assertEquals("(program (expr (paren (obj (kv (key a) (num 1)) (kv (keystr \"b\") (num 2)) (kv (keyn 3) (num 3)) (kv (keyc (id k)) (num 4)) (short c) (meth (key m) (params) (block (return (num 1)))) (spr (id r))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun funcionDeclarada() {
        val r = parse("function f(a, b = 2) { return a + b; }")
        assertEquals("(program (fun f (params (p a) (p b (num 2))) (block (return (bin + (id a) (id b))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun funcionExpresion() {
        val r = parse("var f = function(x) { return x; };")
        assertEquals("(program (var (decl f (funx (params (p x)) (block (return (id x)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun siNo() {
        val r = parse("if (a) b; else if (c) d; else e;")
        assertEquals("(program (if (id a) (expr (id b)) (if (id c) (expr (id d)) (expr (id e)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun bloque() {
        val r = parse("{ var x; }")
        assertEquals("(program (block (var (decl x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun mientras() {
        val r = parse("while (a < 10) a++;")
        assertEquals("(program (while (bin < (id a) (num 10)) (expr (post++ (id a)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun hacerMientras() {
        val r = parse("do { x; } while (y);")
        assertEquals("(program (do (block (expr (id x))) (id y)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraClasico() {
        val r = parse("for (var i = 0; i < 10; i++) f(i);")
        assertEquals("(program (for (var (decl i (num 0))) (bin < (id i) (num 10)) (post++ (id i)) (expr (call (id f) (id i)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraVacio() {
        val r = parse("for (;;) break;")
        assertEquals("(program (for - - - (break)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraIn() {
        val r = parse("for (var k in o) f(k);")
        assertEquals("(program (forin (bind k) (id o) (expr (call (id f) (id k)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraOf() {
        val r = parse("for (const v of arr) g(v);")
        assertEquals("(program (forof (bind v) (id arr) (expr (call (id g) (id v)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraExpresionIn() {
        val r = parse("for (x in o) h(x);")
        assertEquals("(program (forin (id x) (id o) (expr (call (id h) (id x)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun romperContinuar() {
        val r = parse("while (1) { if (a) break; else continue; }")
        assertEquals("(program (while (num 1) (block (if (id a) (break) (continue)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun lanzar() {
        val r = parse("throw new Error('x');")
        assertEquals("(program (throw (new (id Error) (str \"x\"))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun intentarCompleto() {
        val r = parse("try { f(); } catch (e) { g(e); } finally { h(); }")
        assertEquals("(program (try (block (expr (call (id f)))) (catch e (block (expr (call (id g) (id e))))) (finally (block (expr (call (id h)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun intentarSoloFinally() {
        val r = parse("try { f(); } finally { g(); }")
        assertEquals("(program (try (block (expr (call (id f)))) (finally (block (expr (call (id g)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun switchCase() {
        val r = parse("switch (x) { case 1: f(); break; case 2: g(); default: h(); }")
        assertEquals("(program (switch (id x) (case (num 1) (expr (call (id f))) (break)) (case (num 2) (expr (call (id g)))) (default (expr (call (id h))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun retornos() {
        val r = parse("function f() { return; } function g() { return 1; }")
        assertEquals("(program (fun f (params) (block (return))) (fun g (params) (block (return (num 1)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun secuencia() {
        val r = parse("a, b, c;")
        assertEquals("(program (expr (seq (id a) (id b) (id c))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun paraConSecuencia() {
        val r = parse("for (i = 0, j = 1;;) x;")
        assertEquals("(program (for (expr (seq (asg = (id i) (num 0)) (asg = (id j) (num 1)))) - - (expr (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun inEinstanceof() {
        val r = parse("a in b; c instanceof D;")
        assertEquals("(program (expr (bin in (id a) (id b))) (expr (bin instanceof (id c) (id D))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun bigints() {
        val r = parse("1n + 2n;")
        assertEquals("(program (expr (bin + (bigint 1n) (bigint 2n))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun numeros() {
        val r = parse("0x1F; 1e3; .5 + .25; 1_000;")
        assertEquals("(program (expr (num 0x1F)) (expr (num 1e3)) (expr (bin + (num .5) (num .25))) (expr (num 1_000)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun regexLiteral() {
        val r = parse("var re = /ab+c/g;")
        assertEquals("(program (var (decl re (re \"ab+c\" g))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun division() {
        val r = parse("a / b / c;")
        assertEquals("(program (expr (bin / (bin / (id a) (id b)) (id c))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun letComoIdentificador() {
        val r = parse("let = 5;")
        assertEquals("(program (expr (asg = (id let) (num 5))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun keywordsComoClaves() {
        val r = parse("a.in; ({in: 1, class: 2});")
        assertEquals("(program (expr (mem (id a).in)) (expr (paren (obj (kv (key in) (num 1)) (kv (key class) (num 2))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun nuevaCadena() {
        val r = parse("new a.B().c();")
        assertEquals("(program (expr (call (mem (new (mem (id a).B)).c))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun funcionAnonima() {
        val r = parse("f(function() { return 1; });")
        assertEquals("(program (expr (call (id f) (funx (params) (block (return (num 1)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun spreadLlamada() {
        val r = parse("f(...a, b);")
        assertEquals("(program (expr (call (id f) (spr (id a)) (id b))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorFaltaPuntoComa() {
        val r = parse("a b;")
        assertEquals("(program (expr (id a)) (expr (id b)))", JsAstDump.program(r.program))
        assertEquals("missing-semicolon@1-1:1:2", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorFaltaPuntoComaMultiLinea() {
        val r = parse("x = 1\ny = 2;")
        assertEquals("(program (expr (asg = (id x) (num 1))) (expr (asg = (id y) (num 2))))", JsAstDump.program(r.program))
        assertEquals("missing-semicolon@5-5:1:6", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorBindingInvalido() {
        val r = parse("var 1; x = 2;")
        assertEquals("(program (expr (asg = (id x) (num 2))))", JsAstDump.program(r.program))
        assertEquals("invalid-binding@4-5:1:5", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorBindingMultiLinea() {
        val r = parse("var a = 1\nvar 2;")
        assertEquals("(program (var (decl a (num 1))))", JsAstDump.program(r.program))
        assertEquals("missing-semicolon@9-9:1:10;invalid-binding@14-15:2:5", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorAsignacionInvalida() {
        val r = parse("a + b = c;")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("invalid-assignment-target@6-7:1:7", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorSentenciaNoSoportada() {
        val r = parse("with (x) y; x;")
        assertEquals("(program (expr (id x)))", JsAstDump.program(r.program))
        assertEquals("unsupported-statement@0-4:1:1", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorParentesisDeIf() {
        val r = parse("if (a {")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("unexpected-token@6-7:1:7", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorTemplateSinCerrar() {
        val r = parse("`\${")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("unexpected-eof@3-3:1:4", JsAstDump.parseErrors(r.program))
        assertEquals("unterminated-template@3:1:4", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorObjetoSinCerrar() {
        val r = parse("({a: 1")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("unexpected-eof@1-6:1:2", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronVarObjeto() {
        val r = parse("var {a} = o;")
        assertEquals("(program (var (declp (pobj (pe (key a) (pid a))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun objetoShorthandDefault() {
        val r = parse("({a = 1});")
        assertEquals("(program (expr (paren (obj (kv (key a) (asg = (id a) (num 1)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorLegacyOctal() {
        val r = parse("077;")
        assertEquals("(program (expr (num 077)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("legacy-octal-literal@0:1:1", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorParametroInvalido() {
        val r = parse("function f(1) {}")
        assertEquals("(program)", JsAstDump.program(r.program))
        assertEquals("invalid-binding@11-12:1:12;unexpected-token@15-16:1:16", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowSingle() {
        val r = parse("var f = x => x + 1;")
        assertEquals("(program (var (decl f (arrow (params (p x)) (bin + (id x) (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowParentesis() {
        val r = parse("var f = (a, b) => a + b;")
        assertEquals("(program (var (decl f (arrow (params (p a) (p b)) (bin + (id a) (id b))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowDefaults() {
        val r = parse("var f = (a, b = 2) => a * b;")
        assertEquals("(program (var (decl f (arrow (params (p a) (p b (num 2))) (bin * (id a) (id b))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowCuerpoBloque() {
        val r = parse("var f = (a) => { return a; };")
        assertEquals("(program (var (decl f (arrow (params (p a)) (block (return (id a)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowVacio() {
        val r = parse("var f = () => 1;")
        assertEquals("(program (var (decl f (arrow (params) (num 1)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowEnArgs() {
        val r = parse("g(a => a * 2, b => b);")
        assertEquals("(program (expr (call (id g) (arrow (params (p a)) (bin * (id a) (num 2))) (arrow (params (p b)) (id b)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun arrowEncadenado() {
        val r = parse("var f = x => y => x + y;")
        assertEquals("(program (var (decl f (arrow (params (p x)) (arrow (params (p y)) (bin + (id x) (id y)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun parenNoArrow() {
        val r = parse("var x = (a);")
        assertEquals("(program (var (decl x (paren (id a)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun parenComaNoArrow() {
        val r = parse("var x = (a, b);")
        assertEquals("(program (var (decl x (paren (seq (id a) (id b))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun parenExprNoArrow() {
        val r = parse("var x = (a + b);")
        assertEquals("(program (var (decl x (paren (bin + (id a) (id b))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseVacia() {
        val r = parse("class A {}")
        assertEquals("(program (class A))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseAnonimaVacia() {
        val r = parse("var C = class {};")
        assertEquals("(program (var (decl C (classx))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMetodo() {
        val r = parse("class A { m() { return 1; } }")
        assertEquals("(program (class A (cm (key m) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseConstructor() {
        val r = parse("class A { constructor(a) { this.x = a; } }")
        assertEquals("(program (class A (cm (key constructor) (params (p a)) (block (expr (asg = (mem (this).x) (id a)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseStatic() {
        val r = parse("class A { static s() { return 1; } }")
        assertEquals("(program (class A (cm static (key s) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseGet() {
        val r = parse("class A { get x() { return 1; } }")
        assertEquals("(program (class A (cm get (key x) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseSet() {
        val r = parse("class A { set x(v) { this.y = v; } }")
        assertEquals("(program (class A (cm set (key x) (params (p v)) (block (expr (asg = (mem (this).y) (id v)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseStaticGet() {
        val r = parse("class A { static get x() { return 1; } }")
        assertEquals("(program (class A (cm static get (key x) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseComputed() {
        val r = parse("class A { [k](v) { return v; } }")
        assertEquals("(program (class A (cm (keyc (id k)) (params (p v)) (block (return (id v))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMetodoKeyword() {
        val r = parse("class A { delete() { return 1; } }")
        assertEquals("(program (class A (cm (key delete) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMetodoString() {
        val r = parse("class A { \"m n\"() { return 1; } }")
        assertEquals("(program (class A (cm (keystr \"m n\") (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMetodoNumero() {
        val r = parse("class A { 0() { return 1; } }")
        assertEquals("(program (class A (cm (keyn 0) (params) (block (return (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseExtends() {
        val r = parse("class B extends A {}")
        assertEquals("(program (class B (extends (id A))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseExtendsMiembro() {
        val r = parse("class B extends a.b {}")
        assertEquals("(program (class B (extends (mem (id a).b))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseExpresion() {
        val r = parse("var C = class { m() { return 1; } };")
        assertEquals("(program (var (decl C (classx (cm (key m) (params) (block (return (num 1))))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseExpresionNombre() {
        val r = parse("var C = class D extends B {};")
        assertEquals("(program (var (decl C (classx D (extends (id B))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMiembrosMultiples() {
        val r = parse("class A { constructor() { x = 1; } m(a) { return a; } static get v() { return 2; } }")
        assertEquals("(program (class A (cm (key constructor) (params) (block (expr (asg = (id x) (num 1))))) (cm (key m) (params (p a)) (block (return (id a)))) (cm static get (key v) (params) (block (return (num 2))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun claseMetodoArrow() {
        val r = parse("class A { m() { return x => x; } }")
        assertEquals("(program (class A (cm (key m) (params) (block (return (arrow (params (p x)) (id x)))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun errorClaseSinLlave() {
        val r = parse("class A; x;")
        assertEquals("(program (expr (id x)))", JsAstDump.program(r.program))
        assertEquals("unexpected-token@7-8:1:8", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun labelWhile() {
        val r = parse("outer: while (true) { break outer; }")
        assertEquals("(program (label outer (while (bool true) (block (break outer)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun labelForContinue() {
        val r = parse("outer: for (;;) { continue outer; }")
        assertEquals("(program (label outer (for - - - (block (continue outer)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun labelSentencia() {
        val r = parse("a: x = 1;")
        assertEquals("(program (label a (expr (asg = (id x) (num 1)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun breakLabelSolo() {
        val r = parse("while (true) { break x; }")
        assertEquals("(program (while (bool true) (block (break x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun getterEnObjeto() {
        val r = parse("var o = { get x() { return 1; } };")
        assertEquals("(program (var (decl o (obj (get (key x) (params) (block (return (num 1))))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun setterEnObjeto() {
        val r = parse("var o = { set x(v) { y = v; } };")
        assertEquals("(program (var (decl o (obj (set (key x) (params (p v)) (block (expr (asg = (id y) (id v)))))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun accessorComputedObjeto() {
        val r = parse("var o = { get [k]() { return 1; } };")
        assertEquals("(program (var (decl o (obj (get (keyc (id k)) (params) (block (return (num 1))))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun metodoNombreGet() {
        val r = parse("var o = { get: 1 };")
        assertEquals("(program (var (decl o (obj (kv (key get) (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun metodoGetNormal() {
        val r = parse("var o = { get() { return 1; } };")
        assertEquals("(program (var (decl o (obj (meth (key get) (params) (block (return (num 1))))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun opcionalMiembro() {
        val r = parse("a?.b;")
        assertEquals("(program (expr (mem? (id a).b)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun opcionalIndice() {
        val r = parse("a?.[0];")
        assertEquals("(program (expr (memic? (id a) (num 0))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun opcionalLlamada() {
        val r = parse("f?.(1);")
        assertEquals("(program (expr (call? (id f) (num 1))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun opcionalCadena() {
        val r = parse("a?.b?.[0]?.c?.(2);")
        assertEquals("(program (expr (call? (mem? (memic? (mem? (id a).b) (num 0)).c) (num 2))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun opcionalMiembroLlamada() {
        val r = parse("a?.b(1);")
        assertEquals("(program (expr (call (mem? (id a).b) (num 1))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun newMiembroOpcional() {
        val r = parse("var x = new a?.b;")
        assertEquals("(program (var (decl x (new (mem? (id a).b)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronVarArray() {
        val r = parse("var [a, b] = x;")
        assertEquals("(program (var (declp (parr (pid a) (pid b)) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronLetArray() {
        val r = parse("let [a, b] = x;")
        assertEquals("(program (let (declp (parr (pid a) (pid b)) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronConstObjeto() {
        val r = parse("const {a, b} = o;")
        assertEquals("(program (const (declp (pobj (pe (key a) (pid a)) (pe (key b) (pid b))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronHueco() {
        val r = parse("var [a, , b] = x;")
        assertEquals("(program (var (declp (parr (pid a) - (pid b)) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronRest() {
        val r = parse("var [a, ...r] = x;")
        assertEquals("(program (var (declp (parr (pid a) (rest (pid r))) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronDefault() {
        val r = parse("var {a = 1} = o;")
        assertEquals("(program (var (declp (pobj (pe (key a) (pid a (num 1)))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronAnidado() {
        val r = parse("var [a, [b]] = x;")
        assertEquals("(program (var (declp (parr (pid a) (parr (pid b))) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronRenombrar() {
        val r = parse("var {a: x} = o;")
        assertEquals("(program (var (declp (pobj (pe (key a) (pid x))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronComputed() {
        val r = parse("var {[k]: v} = o;")
        assertEquals("(program (var (declp (pobj (pe (keyc (id k)) (pid v))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronStringKey() {
        val r = parse("var {'s': x} = o;")
        assertEquals("(program (var (declp (pobj (pe (keystr \"s\") (pid x))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronObjRest() {
        val r = parse("var {a, ...r} = o;")
        assertEquals("(program (var (declp (pobj (pe (key a) (pid a)) (rest (pid r))) (id o))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronDefaultArray() {
        val r = parse("var [a = 1] = x;")
        assertEquals("(program (var (declp (parr (pid a (num 1))) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronVarDoble() {
        val r = parse("var [a] = x, b = 2;")
        assertEquals("(program (var (declp (parr (pid a)) (id x)) (decl b (num 2))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronAsignacion() {
        val r = parse("[a, b] = x;")
        assertEquals("(program (expr (asg = (arr (id a) (id b)) (id x))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronAsignacionObjeto() {
        val r = parse("({a} = o);")
        assertEquals("(program (expr (paren (asg = (obj (short a)) (id o)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronShorthandDefault() {
        val r = parse("({a = 1} = o);")
        assertEquals("(program (expr (paren (asg = (obj (kv (key a) (asg = (id a) (num 1)))) (id o)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronParametros() {
        val r = parse("function f([a], {b}) {}")
        assertEquals("(program (fun f (params (pp (parr (pid a))) (pp (pobj (pe (key b) (pid b))))) (block)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun patronArrow() {
        val r = parse("var f = ([a]) => a;")
        assertEquals("(program (var (decl f (arrow (params (pp (parr (pid a)))) (id a)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncFuncion() {
        val r = parse("async function f(){}")
        assertEquals("(program (async fun f (params) (block)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncFuncionParams() {
        val r = parse("async function f(a){}")
        assertEquals("(program (async fun f (params (p a)) (block)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun genFuncion() {
        val r = parse("function* g(){}")
        assertEquals("(program (fun* g (params) (block)))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun genFuncionExpr() {
        val r = parse("var g = function*(){};")
        assertEquals("(program (var (decl g (funx* (params) (block)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncFuncionExpr() {
        val r = parse("var f = async function(){};")
        assertEquals("(program (var (decl f (async funx (params) (block)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncAwaitDump() {
        val r = parse("async function f(){ await x; }")
        assertEquals("(program (async fun f (params) (block (expr (await (id x))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun genYieldDump() {
        val r = parse("function* g(){ yield 1; }")
        assertEquals("(program (fun* g (params) (block (expr (yield (num 1))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun genYieldSinValorDump() {
        val r = parse("function* g(){ yield; }")
        assertEquals("(program (fun* g (params) (block (expr (yield)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun genYieldStarDump() {
        val r = parse("function* g(){ yield* i; }")
        assertEquals("(program (fun* g (params) (block (expr (yield* (id i))))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncArrowSingle() {
        val r = parse("var h = async v => v;")
        assertEquals("(program (var (decl h (async arrow (params (p v)) (id v)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncArrowParentesis() {
        val r = parse("var h = async (a, b) => a;")
        assertEquals("(program (var (decl h (async arrow (params (p a) (p b)) (id a)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncLlamadaNoArrow() {
        val r = parse("var f = async(1);")
        assertEquals("(program (var (decl f (call (id async) (num 1)))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

    @Test
    fun asyncIdentNormal() {
        val r = parse("var async = 1;")
        assertEquals("(program (var (decl async (num 1))))", JsAstDump.program(r.program))
        assertEquals("", JsAstDump.parseErrors(r.program))
        assertEquals("", JsAstDump.lexErrors(r.lexErrors))
    }

}
