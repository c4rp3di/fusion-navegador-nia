package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del interprete ECMAScript v1 (T019). Espejo 1:1 del gemelo TS/JS
 * validado: mismas fuentes, mismas expectativas (generadas desde las
 * salidas del gemelo, nunca transcritas a mano).
 */
class JsInterpreterTest {

    @Test
    fun sumaEnteros() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 + 2;")))
    }

    @Test
    fun concatenacionStrings() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("'a' + 'b';")))
    }

    @Test
    fun sumaMixta() {
        assertEquals("\"1x\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 + 'x';")))
    }

    @Test
    fun restaStrings() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("'5' - 2;")))
    }

    @Test
    fun multiplicacion() {
        assertEquals("42", JsInterpreter.dumpResult(JsInterpreter.evaluate("6 * 7;")))
    }

    @Test
    fun division() {
        assertEquals("2.5", JsInterpreter.dumpResult(JsInterpreter.evaluate("10 / 4;")))
    }

    @Test
    fun moduloSignoDividendo() {
        assertEquals("-1", JsInterpreter.dumpResult(JsInterpreter.evaluate("-7 % 3;")))
    }

    @Test
    fun potencia() {
        assertEquals("1024", JsInterpreter.dumpResult(JsInterpreter.evaluate("2 ** 10;")))
    }

    @Test
    fun numeroHexadecimal() {
        assertEquals("17", JsInterpreter.dumpResult(JsInterpreter.evaluate("0x10 + 1;")))
    }

    @Test
    fun puntoFlotante() {
        assertEquals("0.75", JsInterpreter.dumpResult(JsInterpreter.evaluate("0.5 + 0.25;")))
    }

    @Test
    fun unarioNegativo() {
        assertEquals("-7", JsInterpreter.dumpResult(JsInterpreter.evaluate("-(3 + 4);")))
    }

    @Test
    fun unarioMas() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("+'5';")))
    }

    @Test
    fun bitwiseNot() {
        assertEquals("-1", JsInterpreter.dumpResult(JsInterpreter.evaluate("~0;")))
    }

    @Test
    fun bitwiseAnd() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("6 & 3;")))
    }

    @Test
    fun bitwiseOr() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("6 | 3;")))
    }

    @Test
    fun bitwiseXor() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("6 ^ 3;")))
    }

    @Test
    fun shiftIzquierda() {
        assertEquals("16", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 << 4;")))
    }

    @Test
    fun shiftDerecha() {
        assertEquals("-4", JsInterpreter.dumpResult(JsInterpreter.evaluate("-16 >> 2;")))
    }

    @Test
    fun shiftDerechaSinSigno() {
        assertEquals("15", JsInterpreter.dumpResult(JsInterpreter.evaluate("-1 >>> 28;")))
    }

    @Test
    fun igualdadEstricta() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 === 1;")))
    }

    @Test
    fun igualdadEstrictaTipoDistinto() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 === '1';")))
    }

    @Test
    fun igualdadFlojaNumeroString() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("'3' == 3;")))
    }

    @Test
    fun igualdadFlojaVacioCero() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("'' == 0;")))
    }

    @Test
    fun igualdadNullUndefined() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("null == undefined;")))
    }

    @Test
    fun igualdadEstrictaNullUndefined() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("null === undefined;")))
    }

    @Test
    fun desigualdadFloja() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("'1' != 2;")))
    }

    @Test
    fun nanEstricto() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("NaN === NaN;")))
    }

    @Test
    fun menor() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("1 < 2;")))
    }

    @Test
    fun comparacionStrings() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("'a' < 'b';")))
    }

    @Test
    fun comparacionNaN() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("'x' < 1;")))
    }

    @Test
    fun menorIgual() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("2 <= 2;")))
    }

    @Test
    fun mayor() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("3 > 5;")))
    }

    @Test
    fun yLogico() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("true && false;")))
    }

    @Test
    fun oLogico() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("0 || 5;")))
    }

    @Test
    fun nullish() {
        assertEquals("\"d\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("null ?? 'd';")))
    }

    @Test
    fun nullishCero() {
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluate("0 ?? 5;")))
    }

    @Test
    fun noLogico() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("!0;")))
    }

    @Test
    fun cortocircuitoNoEvalua() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("false && nada;")))
    }

    @Test
    fun typeofNumero() {
        assertEquals("\"number\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("typeof 1;")))
    }

    @Test
    fun typeofNoDeclarado() {
        assertEquals("\"undefined\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("typeof nada;")))
    }

    @Test
    fun typeofNull() {
        assertEquals("\"object\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("typeof null;")))
    }

    @Test
    fun typeofFuncion() {
        assertEquals("\"function\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("typeof (function () {});")))
    }

    @Test
    fun voidCero() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("void 0;")))
    }

    @Test
    fun preIncremento() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 5; ++x;")))
    }

    @Test
    fun postIncremento() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 5; x++;")))
    }

    @Test
    fun postIncrementoSuma() {
        assertEquals("11", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 5; x++ + x;")))
    }

    @Test
    fun compuestoSumaString() {
        assertEquals("\"a1\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 'a'; x += 1;")))
    }

    @Test
    fun compuestoResta() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 5; x -= 2;")))
    }

    @Test
    fun compuestoMultiplicacion() {
        assertEquals("12", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 4; x *= 3;")))
    }

    @Test
    fun condicional() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("true ? 1 : 2;")))
    }

    @Test
    fun secuencia() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("(1, 2, 3);")))
    }

    @Test
    fun templateSustitucion() {
        assertEquals("\"a2b\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("`a\${1 + 1}b`;")))
    }

    @Test
    fun templateSimple() {
        assertEquals("\"hola\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("`hola`;")))
    }

    @Test
    fun stringLength() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("'hola'.length;")))
    }

    @Test
    fun stringIndice() {
        assertEquals("\"b\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = 'ab'; s[1];")))
    }

    @Test
    fun arrayLiteral() {
        assertEquals("(arr 1 2 3)", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1, 2, 3];")))
    }

    @Test
    fun arrayLength() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("[10, 20].length;")))
    }

    @Test
    fun arrayIndice() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1, 2, 3]; a[1];")))
    }

    @Test
    fun arrayExtiendeLength() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1]; a[3] = 9; a.length;")))
    }

    @Test
    fun arrayToString() {
        assertEquals("\"1,2\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("'' + [1, 2];")))
    }

    @Test
    fun arrayMasArray() {
        assertEquals("\"1,23\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2] + [3];")))
    }

    @Test
    fun arraySpread() {
        assertEquals("(arr 1 2 3)", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1, 2]; var b = [...a, 3]; b;")))
    }

    @Test
    fun stringSpread() {
        assertEquals("(arr \"a\" \"b\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("[...'ab'];")))
    }

    @Test
    fun objetoLiteral() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {x: 1, y: 2}; o.x;")))
    }

    @Test
    fun objetoToString() {
        assertEquals("\"[object Object]\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("'' + {};")))
    }

    @Test
    fun objetoClaveComputada() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {['k' + '1']: 5}; o.k1;")))
    }

    @Test
    fun objetoShorthand() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 7; var o = {x}; o.x;")))
    }

    @Test
    fun objetoSpread() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = {x: 1}; var b = {...a, y: 2}; b.x + b.y;")))
    }

    @Test
    fun getterEnObjeto() {
        assertEquals("42", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {get v() { return 42; }}; o.v;")))
    }

    @Test
    fun setterEnObjeto() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {set p(v) { this.w = v; }}; o.p = 7; o.w;")))
    }

    @Test
    fun metodoEnObjeto() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {m() { return 5; }}; o.m();")))
    }

    @Test
    fun propiedadIndefinida() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {}; o.x;")))
    }

    @Test
    fun llamadaIndefinida() {
        assertEquals("uncaught (obj name=\"TypeError\" message=\"undefined is not a function\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {}; o.x();")))
    }

    @Test
    fun accesoSobreNulo() {
        assertEquals("uncaught (obj name=\"TypeError\" message=\"Cannot read properties of null\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = null; o.x;")))
    }

    @Test
    fun accesoSobreUndefined() {
        assertEquals("uncaught (obj name=\"TypeError\" message=\"Cannot read properties of undefined\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = undefined; o.x;")))
    }

    @Test
    fun deletePropiedad() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {x: 1}; delete o.x; o.x;")))
    }

    @Test
    fun operadorIn() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("'a' in {a: 1};")))
    }

    @Test
    fun operadorInFalso() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("'b' in {a: 1};")))
    }

    @Test
    fun optionalChainNulo() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = null; o?.x;")))
    }

    @Test
    fun optionalChainValor() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {x: 5}; o?.x;")))
    }

    @Test
    fun optionalCallNulo() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = null; f?.();")))
    }

    @Test
    fun varHoisting() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("x; var x = 5;")))
    }

    @Test
    fun functionHoisting() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() { return 9; } f();")))
    }

    @Test
    fun letFueraDeBloque() {
        assertEquals("uncaught (obj name=\"ReferenceError\" message=\"x is not defined\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("{ let x = 1; } x;")))
    }

    @Test
    fun asignacionImplicitaGlobal() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("x = 3; x;")))
    }

    @Test
    fun lecturaNoDefinida() {
        assertEquals("uncaught (obj name=\"ReferenceError\" message=\"nada is not defined\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("nada;")))
    }

    @Test
    fun constSimple() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("const c = 2; c;")))
    }

    @Test
    fun ifElse() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = 0; if (1 > 2) { r = 1; } else { r = 2; } r;")))
    }

    @Test
    fun whileSuma() {
        assertEquals("10", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = 0; var i = 0; while (i < 5) { s += i; i++; } s;")))
    }

    @Test
    fun doWhileUnaVez() {
        assertEquals("11", JsInterpreter.dumpResult(JsInterpreter.evaluate("var i = 10; do { i++; } while (i < 5); i;")))
    }

    @Test
    fun forSimple() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = 0; for (var i = 0; i < 3; i++) { s += i; } s;")))
    }

    @Test
    fun forLet() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("let s = 0; for (let i = 0; i < 3; i++) { s += i; } s;")))
    }

    @Test
    fun breakSimple() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var i = 0; for (;;) { if (i === 3) { break; } i++; } i;")))
    }

    @Test
    fun continueSimple() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = 0; for (var i = 0; i < 5; i++) { if (i % 2 === 0) { continue; } s += i; } s;")))
    }

    @Test
    fun labelBreakExterno() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var i = 0; outer: for (i = 0; i < 3; i++) { for (var j = 0; j < 3; j++) { if (j === 1) { break outer; } } } i;")))
    }

    @Test
    fun labelContinueExterno() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("outer: for (var i = 0; i < 3; i++) { for (var j = 0; j < 2; j++) { if (j === 1) { continue outer; } } } i;")))
    }

    @Test
    fun switchCoincide() {
        assertEquals("\"dos\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = ''; switch (2) { case 1: r = 'uno'; break; case 2: r = 'dos'; break; default: r = 'otro'; } r;")))
    }

    @Test
    fun switchFallthrough() {
        assertEquals("\"p\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = ''; switch (1) { case 1: case 2: r = 'p'; break; default: r = 'q'; } r;")))
    }

    @Test
    fun switchDefault() {
        assertEquals("\"z\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = ''; switch (9) { case 1: r = 'a'; break; default: r = 'z'; } r;")))
    }

    @Test
    fun forinObjeto() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = ''; var o = {a: 1, b: 2}; for (var k in o) { s += k; } s;")))
    }

    @Test
    fun forinArray() {
        assertEquals("\"01\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = ''; for (var k in [5, 6]) { s += k; } s;")))
    }

    @Test
    fun forofArray() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = 0; for (var v of [1, 2, 3]) { s += v; } s;")))
    }

    @Test
    fun forofString() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = ''; for (var c of 'ab') { s += c; } s;")))
    }

    @Test
    fun forofNumero() {
        assertEquals("uncaught (obj name=\"TypeError\" message=\"Value is not iterable\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("for (var v of 5) { v; }")))
    }

    @Test
    fun funcionSimple() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() { return 7; } f();")))
    }

    @Test
    fun funcionParametros() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f(a, b) { return a + b; } f(2, 3);")))
    }

    @Test
    fun funcionDefaults() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f(a, b = 5) { return a + b; } f(1);")))
    }

    @Test
    fun funcionExpresion() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = function g() { return 4; }; f();")))
    }

    @Test
    fun recursion() {
        assertEquals("120", JsInterpreter.dumpResult(JsInterpreter.evaluate("function fact(n) { return n <= 1 ? 1 : n * fact(n - 1); } fact(5);")))
    }

    @Test
    fun closureContador() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("function mk() { var c = 0; return function () { c++; return c; }; } var f = mk(); f(); f();")))
    }

    @Test
    fun thisEnMetodo() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {n: 3, m() { return this.n; }}; o.m();")))
    }

    @Test
    fun thisSueltoUndefined() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() { return this; } f();")))
    }

    @Test
    fun arrowParametroUnico() {
        assertEquals("8", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = x => x * 2; f(4);")))
    }

    @Test
    fun arrowParentesis() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = (a, b) => a + b; f(1, 2);")))
    }

    @Test
    fun arrowCuerpoBloque() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = (a) => { return a + 1; }; f(1);")))
    }

    @Test
    fun arrowThisLexico() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {n: 5, m: function () { var g = () => this.n; return g(); }}; o.m();")))
    }

    @Test
    fun arrowCurry() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var add = a => b => a + b; add(2)(3);")))
    }

    @Test
    fun spreadEnLlamada() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f(a, b, c) { return a + b + c; } f(...[1, 2, 3]);")))
    }

    @Test
    fun funcionSinReturn() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() {} f();")))
    }

    @Test
    fun claseMetodo() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { m() { return 1; } } new A().m();")))
    }

    @Test
    fun claseConstructor() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("class P { constructor(x) { this.x = x; } } new P(5).x;")))
    }

    @Test
    fun claseGetter() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { get v() { return 7; } } new A().v;")))
    }

    @Test
    fun claseSetter() {
        assertEquals("8", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { set v(x) { this.w = x * 2; } } var a = new A(); a.v = 4; a.w;")))
    }

    @Test
    fun claseStatic() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { static s() { return 9; } } A.s();")))
    }

    @Test
    fun claseExtendsMetodo() {
        assertEquals("\"a\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { m() { return 'a'; } } class B extends A {} new B().m();")))
    }

    @Test
    fun claseExtendsCtorImplicito() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { constructor(x) { this.x = x; } } class B extends A {} new B(3).x;")))
    }

    @Test
    fun claseExtendsOverride() {
        assertEquals("\"b\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { m() { return 'a'; } } class B extends A { m() { return 'b'; } } new B().m();")))
    }

    @Test
    fun claseComputedKey() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A { ['m' + '1']() { return 2; } } new A().m1();")))
    }

    @Test
    fun claseLlamadaSinNew() {
        assertEquals("uncaught (obj name=\"TypeError\" message=\"Class constructor cannot be invoked without new\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A {} A();")))
    }

    @Test
    fun claseExpresion() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("class C { m() { return 3; } } new (class {}) instanceof C;")))
    }

    @Test
    fun claseExpresionAnonima() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var C = class { m() { return 3; } }; new C().m();")))
    }

    @Test
    fun instanceofClase() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A {} new A() instanceof A;")))
    }

    @Test
    fun instanceofFalso() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("class A {} class B {} new A() instanceof B;")))
    }

    @Test
    fun newFuncion() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("function F() { this.x = 2; } new F().x;")))
    }

    @Test
    fun newRetornaObjeto() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("function F() { return {y: 9}; } new F().y;")))
    }

    @Test
    fun throwSimple() {
        assertEquals("uncaught 42", JsInterpreter.dumpResult(JsInterpreter.evaluate("throw 42;")))
    }

    @Test
    fun tryCatch() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = 0; try { throw 1; } catch (e) { r = e + 1; } r;")))
    }

    @Test
    fun tryFinallyNormal() {
        assertEquals("11", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = 0; try { r = 1; } finally { r = r + 10; } r;")))
    }

    @Test
    fun finallyThrowReemplaza() {
        assertEquals("uncaught 5", JsInterpreter.dumpResult(JsInterpreter.evaluate("try { 1; } finally { throw 5; }")))
    }

    @Test
    fun finallyReturnReemplaza() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() { try { return 1; } finally { return 2; } } f();")))
    }

    @Test
    fun rethrowAnidado() {
        assertEquals("\"capturadox\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = ''; try { try { throw 'x'; } catch (e) { throw e; } } catch (e2) { r = 'capturado' + e2; } r;")))
    }

    @Test
    fun errorBuiltin() {
        assertEquals("\"Error: boom\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var e = new Error('boom'); e.name + ': ' + e.message;")))
    }

    @Test
    fun errorNoCapturado() {
        assertEquals("uncaught (obj name=\"Error\" message=\"mal\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("throw new Error('mal');")))
    }

    @Test
    fun variableError() {
        assertEquals("\"TypeError\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var e = 1; var r = \"\"; try { e.x.y; } catch (err) { r = err.name; } r;")))
    }

    @Test
    fun programaVacio() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("")))
    }

    @Test
    fun sentenciaVacia() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate(";")))
    }

    @Test
    fun ultimaExpresion() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("1; 2; 3;")))
    }

    @Test
    fun asignacionValor() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("var x = 5; x = x + 1;")))
    }

    // ---- Builtins v1 (T020): espejo del oraculo ECMAScript ----

    @Test
    fun mathAbs() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.abs(-5);")))
    }

    @Test
    fun mathAbsString() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.abs('-3');")))
    }

    @Test
    fun mathCeil() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.ceil(4.2);")))
    }

    @Test
    fun mathFloor() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.floor(4.8);")))
    }

    @Test
    fun mathRound() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.round(4.5);")))
    }

    @Test
    fun mathRoundNeg() {
        assertEquals("-4", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.round(-4.5);")))
    }

    @Test
    fun mathTrunc() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.trunc(4.9);")))
    }

    @Test
    fun mathTruncNeg() {
        assertEquals("-4", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.trunc(-4.9);")))
    }

    @Test
    fun mathSign() {
        assertEquals("-1", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.sign(-3);")))
    }

    @Test
    fun mathSignCero() {
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.sign(0);")))
    }

    @Test
    fun mathSqrt() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.sqrt(16);")))
    }

    @Test
    fun mathPow() {
        assertEquals("1024", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.pow(2, 10);")))
    }

    @Test
    fun mathMin() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.min(3, 1, 2);")))
    }

    @Test
    fun mathMax() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.max(3, 1, 2);")))
    }

    @Test
    fun mathPi() {
        assertEquals("3.141592653589793", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.PI;")))
    }

    @Test
    fun mathE() {
        assertEquals("2.718281828459045", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.E;")))
    }

    @Test
    fun mathLog() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.log(Math.E);")))
    }

    @Test
    fun mathLog2() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.log2(8);")))
    }

    @Test
    fun mathLog10() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.log10(1000);")))
    }

    @Test
    fun mathExp() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.exp(0);")))
    }

    @Test
    fun mathRandomRango() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Math.random() >= 0 && Math.random() < 1;")))
    }

    @Test
    fun numberIsInteger() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isInteger(5);")))
    }

    @Test
    fun numberIsIntegerFalso() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isInteger(5.5);")))
    }

    @Test
    fun numberIsFiniteInfinito() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isFinite(1 / 0);")))
    }

    @Test
    fun numberIsFiniteNum() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isFinite(2.5);")))
    }

    @Test
    fun numberIsNaN() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isNaN(NaN);")))
    }

    @Test
    fun numberIsNaNDivision() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.isNaN(0 / 0);")))
    }

    @Test
    fun numberParseIntSufijo() {
        assertEquals("42", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.parseInt('42px');")))
    }

    @Test
    fun numberParseFloat() {
        assertEquals("3.5", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.parseFloat('3.5x');")))
    }

    @Test
    fun numberEpsilon() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.EPSILON > 0;")))
    }

    @Test
    fun numberMaxSafe() {
        assertEquals("9007199254740991", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number.MAX_SAFE_INTEGER;")))
    }

    @Test
    fun parseIntSimple() {
        assertEquals("42", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseInt('42');")))
    }

    @Test
    fun parseIntHex() {
        assertEquals("31", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseInt('0x1F');")))
    }

    @Test
    fun parseIntRadix() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseInt('101', 2);")))
    }

    @Test
    fun parseIntRadix8() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseInt('7', 8);")))
    }

    @Test
    fun parseIntInvalido() {
        assertEquals("NaN", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseInt('zz');")))
    }

    @Test
    fun parseFloatExp() {
        assertEquals("250", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseFloat('2.5e2');")))
    }

    @Test
    fun parseFloatPunto() {
        assertEquals("0.5", JsInterpreter.dumpResult(JsInterpreter.evaluate("parseFloat('.5');")))
    }

    @Test
    fun isNaNString() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("isNaN('x');")))
    }

    @Test
    fun isNaNNumero() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("isNaN('3');")))
    }

    @Test
    fun isFiniteString() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("isFinite('3');")))
    }

    @Test
    fun isFiniteInfinito() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("isFinite(1 / 0);")))
    }

    @Test
    fun stringNumero() {
        assertEquals("\"42\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String(42);")))
    }

    @Test
    fun stringBoolean() {
        assertEquals("\"true\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String(true);")))
    }

    @Test
    fun stringNulo() {
        assertEquals("\"null\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String(null);")))
    }

    @Test
    fun stringIndefinido() {
        assertEquals("\"undefined\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String(undefined);")))
    }

    @Test
    fun stringArray() {
        assertEquals("\"1,2\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String([1, 2]);")))
    }

    @Test
    fun numeroDeString() {
        assertEquals("12", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number('12');")))
    }

    @Test
    fun numeroVacio() {
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number('');")))
    }

    @Test
    fun numeroInvalido() {
        assertEquals("NaN", JsInterpreter.dumpResult(JsInterpreter.evaluate("Number('x');")))
    }

    @Test
    fun booleanCero() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Boolean(0);")))
    }

    @Test
    fun booleanStringVacio() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Boolean('');")))
    }

    @Test
    fun booleanObjeto() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Boolean({});")))
    }

    @Test
    fun objectKeys() {
        assertEquals("(arr \"a\" \"b\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("Object.keys({a: 1, b: 2});")))
    }

    @Test
    fun objectKeysVacio() {
        assertEquals("(arr)", JsInterpreter.dumpResult(JsInterpreter.evaluate("Object.keys({});")))
    }

    @Test
    fun objectKeysArray() {
        assertEquals("(arr \"0\" \"1\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("Object.keys([10, 20]);")))
    }

    @Test
    fun objectKeysAccesor() {
        assertEquals("(arr \"a\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {get a() { return 1; }}; Object.keys(o);")))
    }

    @Test
    fun arrayIsArray() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("Array.isArray([1]);")))
    }

    @Test
    fun arrayIsArrayFalso() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Array.isArray('no');")))
    }

    @Test
    fun arrayIsArrayObjeto() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("Array.isArray({});")))
    }

    @Test
    fun jsonStrObjeto() {
        assertEquals("\"{\\\"a\\\":1}\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify({a: 1});")))
    }

    @Test
    fun jsonStrArray() {
        assertEquals("\"[1,\\\"x\\\",null,true]\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify([1, 'x', null, true]);")))
    }

    @Test
    fun jsonStrString() {
        assertEquals("\"\\\"hola\\\"\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify('hola');")))
    }

    @Test
    fun jsonStrNumero() {
        assertEquals("\"42\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify(42);")))
    }

    @Test
    fun jsonStrDecimal() {
        assertEquals("\"0.5\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify(0.5);")))
    }

    @Test
    fun jsonStrIndefinido() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify(undefined);")))
    }

    @Test
    fun jsonStrFuncion() {
        assertEquals("\"{}\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify({f: function () {}});")))
    }

    @Test
    fun jsonStrOmiteIndefinido() {
        assertEquals("\"{\\\"b\\\":1}\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify({a: undefined, b: 1});")))
    }

    @Test
    fun jsonStrArrayIndefinido() {
        assertEquals("\"[null]\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify([undefined]);")))
    }

    @Test
    fun jsonStrAnidado() {
        assertEquals("\"{\\\"n\\\":{\\\"x\\\":[1,{\\\"y\\\":null}]}}\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify({n: {x: [1, {y: null}]}});")))
    }

    @Test
    fun jsonStrEscapes() {
        assertEquals("\"{\\\"s\\\":\\\"a\\\\\\\"b\\\"}\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.stringify({s: 'a\"b'});")))
    }

    @Test
    fun jsonParseObjeto() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('{\"a\": 1}').a;")))
    }

    @Test
    fun jsonParseArray() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('[1, 2]')[1];")))
    }

    @Test
    fun jsonParseString() {
        assertEquals("\"x\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('\"x\"');")))
    }

    @Test
    fun jsonParseTrue() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('true');")))
    }

    @Test
    fun jsonParseNull() {
        assertEquals("null", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('null');")))
    }

    @Test
    fun jsonParseAnidado() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("JSON.parse('{\"a\": {\"b\": [1, 2]}}').a.b.length;")))
    }

    @Test
    fun jsonParseErrorName() {
        assertEquals("\"SyntaxError\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("try { JSON.parse('{bad}'); } catch (e) { e.name; }")))
    }


    // ---- Builtins v2 (T020): prototipos, Event/EventTarget ----

    @Test
    fun objHasOwnProperty() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {a: 1}; o.hasOwnProperty(\"a\");")))
    }

    @Test
    fun objHasOwnPropertyFalse() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {a: 1}; o.hasOwnProperty(\"b\");")))
    }

    @Test
    fun objHasOwnPropertyHeredado() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {a: 1}; o.hasOwnProperty(\"toString\");")))
    }

    @Test
    fun objToString() {
        assertEquals("\"[object Object]\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("({}).toString();")))
    }

    @Test
    fun objToStringPropio() {
        assertEquals("\"X\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {toString: function() { return \"X\"; }}; o.toString();")))
    }

    @Test
    fun objInPrototipo() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"hasOwnProperty\" in {};")))
    }

    @Test
    fun objHasOwnPropertyArray() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1]; a.hasOwnProperty(\"length\");")))
    }

    @Test
    fun strCharAt() {
        assertEquals("\"b\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".charAt(1);")))
    }

    @Test
    fun strCharAtFuera() {
        assertEquals("\"\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".charAt(9);")))
    }

    @Test
    fun strCharCodeAt() {
        assertEquals("97", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".charCodeAt(0);")))
    }

    @Test
    fun strCharCodeAtFuera() {
        assertEquals("NaN", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".charCodeAt(9);")))
    }

    @Test
    fun strIndexOf() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"hello\".indexOf(\"l\");")))
    }

    @Test
    fun strIndexOfDesde() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"aa\".indexOf(\"a\", 1);")))
    }

    @Test
    fun strLastIndexOf() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcabc\".lastIndexOf(\"c\");")))
    }

    @Test
    fun strIncludes() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".includes(\"b\");")))
    }

    @Test
    fun strIncludesFalse() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".includes(\"z\");")))
    }

    @Test
    fun strStartsWith() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".startsWith(\"ab\");")))
    }

    @Test
    fun strEndsWith() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".endsWith(\"bc\");")))
    }

    @Test
    fun strSlice() {
        assertEquals("\"cdef\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcdef\".slice(2);")))
    }

    @Test
    fun strSliceNegativo() {
        assertEquals("\"ef\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcdef\".slice(-2);")))
    }

    @Test
    fun strSliceRango() {
        assertEquals("\"bc\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcdef\".slice(1, 3);")))
    }

    @Test
    fun strSubstring() {
        assertEquals("\"bc\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcdef\".substring(1, 3);")))
    }

    @Test
    fun strSubstringIntercambiado() {
        assertEquals("\"bc\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abcdef\".substring(3, 1);")))
    }

    @Test
    fun strToUpperCase() {
        assertEquals("\"ABC\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"aBc\".toUpperCase();")))
    }

    @Test
    fun strToLowerCase() {
        assertEquals("\"abc\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"AbC\".toLowerCase();")))
    }

    @Test
    fun strTrim() {
        assertEquals("\"a\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"  a  \".trim();")))
    }

    @Test
    fun strSplit() {
        assertEquals("(arr \"a\" \"b\" \"c\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"a,b,c\".split(\",\");")))
    }

    @Test
    fun strSplitVacio() {
        assertEquals("(arr \" \")", JsInterpreter.dumpResult(JsInterpreter.evaluate("\" \".split(\",\");")))
    }

    @Test
    fun strSplitSinSep() {
        assertEquals("(arr \"abc\")", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"abc\".split();")))
    }

    @Test
    fun strReplace() {
        assertEquals("\"heLlo\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"hello\".replace(\"l\", \"L\");")))
    }

    @Test
    fun strReplaceNoEncontrado() {
        assertEquals("\"hi\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"hi\".replace(\"x\", \"y\");")))
    }

    @Test
    fun strRepeat() {
        assertEquals("\"ababab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"ab\".repeat(3);")))
    }

    @Test
    fun strRepeatCero() {
        assertEquals("\"\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"ab\".repeat(0);")))
    }

    @Test
    fun strPadStart() {
        assertEquals("\"005\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"5\".padStart(3, \"0\");")))
    }

    @Test
    fun strPadEnd() {
        assertEquals("\"9..\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"9\".padEnd(3, \".\");")))
    }

    @Test
    fun strConcat() {
        assertEquals("\"hi!?\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("\"hi\".concat(\"!\", \"?\");")))
    }

    @Test
    fun strFromCharCode() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("String.fromCharCode(97, 98);")))
    }

    @Test
    fun arrPush() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1, 2]; a.push(3, 4);")))
    }

    @Test
    fun arrPushContenido() {
        assertEquals("\"1,2,3,4\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1, 2]; a.push(3, 4); a.join(\",\");")))
    }

    @Test
    fun arrPop() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1]; a.pop();")))
    }

    @Test
    fun arrPopVacio() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = []; a.pop();")))
    }

    @Test
    fun arrShift() {
        assertEquals("\"2:1\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1, 2]; var r = a.shift(); a.join(\",\") + \":\" + r;")))
    }

    @Test
    fun arrUnshift() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [2]; a.unshift(0, 1);")))
    }

    @Test
    fun arrSlice() {
        assertEquals("\"2,3\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,4].slice(1, 3).join(\",\");")))
    }

    @Test
    fun arrSliceNegativo() {
        assertEquals("\"3,4\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,4].slice(-2).join(\",\");")))
    }

    @Test
    fun arrSliceCopia() {
        assertEquals("\"2:3\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1,2]; var b = a.slice(); b.push(9); a.length + \":\" + b.length;")))
    }

    @Test
    fun arrSplice() {
        assertEquals("\"23145\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var c = [1,2,3,4,5]; var sp = c.splice(1, 2); sp.join(\"\") + c.join(\"\");")))
    }

    @Test
    fun arrIndexOf() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3].indexOf(2);")))
    }

    @Test
    fun arrIndexOfDesde() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,2].indexOf(2, 2);")))
    }

    @Test
    fun arrLastIndexOf() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,2].lastIndexOf(2);")))
    }

    @Test
    fun arrIncludes() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].includes(2);")))
    }

    @Test
    fun arrIncludesFalse() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].includes(5);")))
    }

    @Test
    fun arrJoin() {
        assertEquals("\"1-2-3\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3].join(\"-\");")))
    }

    @Test
    fun arrJoinVacio() {
        assertEquals("\"\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[].join(\",\");")))
    }

    @Test
    fun arrConcat() {
        assertEquals("\"12345\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].concat([3], [4,5]).join(\"\");")))
    }

    @Test
    fun arrReverse() {
        assertEquals("\"321\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [1,2,3]; a.reverse(); a.join(\"\");")))
    }

    @Test
    fun arrSort() {
        assertEquals("\"10,2,33\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[10, 2, 33].sort().join(\",\");")))
    }

    @Test
    fun arrSortNumerico() {
        assertEquals("\"2,10,33\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[10, 2, 33].sort(function(a, b) { return a - b; }).join(\",\");")))
    }

    @Test
    fun arrSortInPlace() {
        assertEquals("\"123\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = [3,1,2]; a.sort(function(a, b) { return a - b; }); a.join(\"\");")))
    }

    @Test
    fun arrForEach() {
        assertEquals("\"123\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var s = \"\"; [1,2,3].forEach(function(v) { s += v; }); s;")))
    }

    @Test
    fun arrMap() {
        assertEquals("\"246\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3].map(function(v) { return v * 2; }).join(\"\");")))
    }

    @Test
    fun arrFilter() {
        assertEquals("\"24\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,4].filter(function(v) { return v % 2 === 0; }).join(\"\");")))
    }

    @Test
    fun arrFind() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3].find(function(v) { return v > 1; });")))
    }

    @Test
    fun arrFindVacio() {
        assertEquals("undefined", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].find(function(v) { return v > 9; });")))
    }

    @Test
    fun arrFindIndex() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3].findIndex(function(v) { return v > 1; });")))
    }

    @Test
    fun arrSome() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].some(function(v) { return v > 1; });")))
    }

    @Test
    fun arrSomeFalse() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1].some(function(v) { return v > 9; });")))
    }

    @Test
    fun arrEvery() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("[2,4].every(function(v) { return v % 2 === 0; });")))
    }

    @Test
    fun arrEveryFalse() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("[2,5].every(function(v) { return v % 2 === 0; });")))
    }

    @Test
    fun arrReduce() {
        assertEquals("24", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2,3,4].reduce(function(a, b) { return a * b; });")))
    }

    @Test
    fun arrReduceInicial() {
        assertEquals("13", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].reduce(function(a, b) { return a + b; }, 10);")))
    }

    @Test
    fun arrForEachThis() {
        assertEquals("23", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = 0; var t = {n: 10}; [1, 2].forEach(function(v) { r = r + this.n + v; }, t); r;")))
    }

    @Test
    fun arrToString() {
        assertEquals("\"1,2\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1,2].toString();")))
    }

    @Test
    fun arrIndexOfNaN() {
        assertEquals("-1", JsInterpreter.dumpResult(JsInterpreter.evaluate("[1, NaN].indexOf(NaN);")))
    }

    @Test
    fun numToFixed() {
        assertEquals("\"3.14\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(3.14159).toFixed(2);")))
    }

    @Test
    fun numToFixedRedondeo() {
        assertEquals("\"2.67\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(2.675).toFixed(2);")))
    }

    @Test
    fun numToFixedCero() {
        assertEquals("\"-4\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(-3.7).toFixed(0);")))
    }

    @Test
    fun numToFixedNaN() {
        assertEquals("\"NaN\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(NaN).toFixed(2);")))
    }

    @Test
    fun numToFixedInf() {
        assertEquals("\"Infinity\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(Infinity).toFixed(2);")))
    }

    @Test
    fun numToStringRadix() {
        assertEquals("\"ff\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(255).toString(16);")))
    }

    @Test
    fun numToString() {
        assertEquals("\"5\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(5).toString();")))
    }

    @Test
    fun numToStringRadix2() {
        assertEquals("\"101\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(5).toString(2);")))
    }

    @Test
    fun boolToString() {
        assertEquals("\"true\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("(true).toString();")))
    }

    @Test
    fun funCall() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {x: 1, m: function() { return this.x; }}; o.m.call({x: 9});")))
    }

    @Test
    fun funApply() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {x: 1, m: function() { return this.x; }}; o.m.apply({x: 7}, []);")))
    }

    @Test
    fun funBind() {
        assertEquals("42", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f() { return this.v; } var g = f.bind({v: 42}); g();")))
    }

    @Test
    fun funCallArgs() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f(a, b) { return a + b; } f.call(null, 1, 2);")))
    }

    @Test
    fun funApplyArgs() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f(a, b) { return a + b; } f.apply(null, [1, 2]);")))
    }

    @Test
    fun evType() {
        assertEquals("\"click\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("new Event(\"click\", {bubbles: true}).type;")))
    }

    @Test
    fun evBubbles() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("new Event(\"x\", {bubbles: true}).bubbles;")))
    }

    @Test
    fun evBubblesDefault() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("new Event(\"x\").bubbles;")))
    }

    @Test
    fun evCancelableDefault() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("new Event(\"x\").cancelable;")))
    }

    @Test
    fun evDefaultPrevented() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var e = new Event(\"x\", {cancelable: true}); e.preventDefault(); e.defaultPrevented;")))
    }

    @Test
    fun evDefaultPreventedNoCancelable() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("var e = new Event(\"x\"); e.preventDefault(); e.defaultPrevented;")))
    }

    @Test
    fun etDispatchVacio() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); t.dispatchEvent(new Event(\"e\"));")))
    }

    @Test
    fun etListenerOrden() {
        assertEquals("\"12\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); var s = \"\"; t.addEventListener(\"e\", function() { s += \"1\"; }); t.addEventListener(\"e\", function() { s += \"2\"; }); t.dispatchEvent(new Event(\"e\")); s;")))
    }

    @Test
    fun etListenerThis() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); var r; t.addEventListener(\"e\", function(ev) { r = (this === t); }); t.dispatchEvent(new Event(\"e\")); r;")))
    }

    @Test
    fun etEvTarget() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); var r; t.addEventListener(\"e\", function(ev) { r = (ev.target === t) && (ev.currentTarget === t); }); t.dispatchEvent(new Event(\"e\")); r;")))
    }

    @Test
    fun etRemoveListener() {
        assertEquals("\"a\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); var s = \"a\"; function h() { s = \"b\"; } t.addEventListener(\"e\", h); t.removeEventListener(\"e\", h); t.dispatchEvent(new Event(\"e\")); s;")))
    }

    @Test
    fun etDispatchCancel() {
        assertEquals("false", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); t.addEventListener(\"e\", function(ev) { ev.preventDefault(); }); t.dispatchEvent(new Event(\"e\", {cancelable: true}));")))
    }

    @Test
    fun etDuplicado() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("var t = new EventTarget(); var n = 0; function h() { n = n + 1; } t.addEventListener(\"e\", h); t.addEventListener(\"e\", h); t.dispatchEvent(new Event(\"e\")); n;")))
    }

    @Test
    fun declArrayBasico() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, b] = [1, 2]; a + b;")))
    }

    @Test
    fun declArrayHueco() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, , b] = [1, 2, 3]; a + b;")))
    }

    @Test
    fun declArrayRest() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, ...r] = [1, 2, 3]; r[1];")))
    }

    @Test
    fun declArrayRestCadena() {
        assertEquals("\"ab,c\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, ...r] = 'abc'; a + r;")))
    }

    @Test
    fun declObjBasico() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a, b} = {a: 1, b: 2}; a + b;")))
    }

    @Test
    fun declObjRenombra() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a: x} = {a: 5}; x;")))
    }

    @Test
    fun declObjStringKey() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {'s': x} = {s: 7}; x;")))
    }

    @Test
    fun declObjDefault() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a = 3} = {}; a;")))
    }

    @Test
    fun declObjDefaultPresente() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a = 3} = {a: 9}; a;")))
    }

    @Test
    fun declArrayDefault() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a = 9] = []; a;")))
    }

    @Test
    fun declArrayDefaultCero() {
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a = 9] = [0]; a;")))
    }

    @Test
    fun declAnidado() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, [b, c]] = [1, [2, 3]]; a + b + c;")))
    }

    @Test
    fun declObjAnidado() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a: {b}} = {a: {b: 4}}; b;")))
    }

    @Test
    fun declMixto() {
        assertEquals("17", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a: [x, y]} = {a: [8, 9]}; x + y;")))
    }

    @Test
    fun declComputed() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("var k = 'q'; var {[k]: v} = {q: 6}; v;")))
    }

    @Test
    fun declObjRest() {
        assertEquals("\"true 5\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var {a, ...r} = {a: 1, b: 2, c: 3}; (r.a === undefined) + ' ' + (r.b + r.c);")))
    }

    @Test
    fun declCadena() {
        assertEquals("\"hi\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a, b] = 'hi'; a + b;")))
    }

    @Test
    fun declDefaultsSecuenciales() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var [a = 1, b = a + 1] = []; b;")))
    }

    @Test
    fun declLetPatron() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("let [a, b] = [1, 2]; a + b;")))
    }

    @Test
    fun declNullishError() {
        assertEquals("\"Cannot destructure null\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = ''; try { var [x] = null; } catch (e) { m = e.message; } m;")))
    }

    @Test
    fun asignaArray() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a, b; [a, b] = [1, 2]; a + b;")))
    }

    @Test
    fun asignaSwap() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a = 1, b = 2; [a, b] = [b, a]; a;")))
    }

    @Test
    fun asignaMiembro() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {}; [o.x] = [5]; o.x;")))
    }

    @Test
    fun asignaObjetoParen() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a; ({a} = {a: 2}); a;")))
    }

    @Test
    fun asignaObjetoDefault() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a; ({a = 7} = {}); a;")))
    }

    @Test
    fun asignaObjetoMiembro() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = {}; ({x: o.y} = {x: 3}); o.y;")))
    }

    @Test
    fun asignaAnidado() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a, b; [a, [b]] = [1, [2]]; a + b;")))
    }

    @Test
    fun asignaRest() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a, r; [a, ...r] = [1, 2, 3]; r.length;")))
    }

    @Test
    fun asignaValorExpr() {
        assertEquals("2", JsInterpreter.dumpResult(JsInterpreter.evaluate("var a; [a] = [1 + 1]; a;")))
    }

    @Test
    fun paramArray() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f([a, b]) { return a + b; } f([1, 2]);")))
    }

    @Test
    fun paramObjeto() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f({x, y}) { return x * y; } f({x: 2, y: 3});")))
    }

    @Test
    fun paramDefaultPatron() {
        assertEquals("7", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f([a] = [7]) { return a; } f();")))
    }

    @Test
    fun paramDefaultDentro() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f({x = 4}) { return x; } f({});")))
    }

    @Test
    fun paramArrow() {
        assertEquals("10", JsInterpreter.dumpResult(JsInterpreter.evaluate("var f = ([a, b]) => a + b; f([4, 6]);")))
    }

    @Test
    fun paramAnidado() {
        assertEquals("12", JsInterpreter.dumpResult(JsInterpreter.evaluate("function f([{n}]) { return n; } f([{n: 12}]);")))
    }

    @Test
    fun paramMetodo() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate("var o = { m([a]) { return a; } }; o.m([9]);")))
    }

    @Test
    fun fechaEpoch() {
        assertEquals("1234567890", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date(1234567890); d.getTime();")))
    }

    @Test
    fun fechaValueOf() {
        assertEquals("0", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date(0); d.valueOf();")))
    }

    @Test
    fun fechaTipo() {
        assertEquals("\"object\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date(0); typeof d;")))
    }

    @Test
    fun fechaUTCFecha() {
        assertEquals("\"1970,0,2\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date(86400000); d.getUTCFullYear() + ',' + d.getUTCMonth() + ',' + d.getUTCDate();")))
    }

    @Test
    fun fechaUTCDia() {
        assertEquals("4", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(0).getUTCDay();")))
    }

    @Test
    fun fechaUTCHora() {
        assertEquals("\"6,56,7\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date(1234567890); d.getUTCHours() + ',' + d.getUTCMinutes() + ',' + d.getUTCSeconds();")))
    }

    @Test
    fun fechaISO() {
        assertEquals("\"1970-01-01T00:00:00.000Z\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(0).toISOString();")))
    }

    @Test
    fun fechaISO2() {
        assertEquals("\"1970-01-15T06:56:07.890Z\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(1234567890).toISOString();")))
    }

    @Test
    fun fechaISONegativo() {
        assertEquals("\"1969-12-31T23:59:59.999Z\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(-1).toISOString();")))
    }

    @Test
    fun fechaUTCMillis() {
        assertEquals("890", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(1234567890).getUTCMilliseconds();")))
    }

    @Test
    fun fechaUtcStatic() {
        assertEquals("1767312000000", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Date.UTC(2026, 0, 2);")))
    }

    @Test
    fun fechaUtcCompuesto() {
        assertEquals("\"2026-01-02T03:04:05.000Z\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Date(Date.UTC(2026, 0, 2, 3, 4, 5)).toISOString();")))
    }

    @Test
    fun fechaParseDateOnly() {
        assertEquals("1767312000000", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Date.parse('2026-01-02');")))
    }

    @Test
    fun fechaParseZ() {
        assertEquals("1767323045000", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Date.parse('2026-01-02T03:04:05Z');")))
    }

    @Test
    fun fechaParseISO() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate("var d = new Date('2026-01-02T03:04:05Z'); d.getUTCHours();")))
    }

    @Test
    fun fechaNow() {
        assertEquals("\"number,true\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" (typeof Date.now()) + ',' + (Date.now() > 0);")))
    }

    @Test
    fun fechaLlamada() {
        assertEquals("\"string\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" typeof Date();")))
    }

    @Test
    fun fechaInvalida() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate(" isNaN(new Date('no').getTime());")))
    }

    @Test
    fun promResolveThen() {
        assertEquals("3", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.resolve(2).then(function(v) { return v + 1; });")))
    }

    @Test
    fun promConstructor() {
        assertEquals("10", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new Promise(function(res) { res(10); }).then(function(v) { return v; });")))
    }

    @Test
    fun promCadena() {
        assertEquals("20", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.resolve(1).then(function(v) { return v + 1; }).then(function(v) { return v * 10; });")))
    }

    @Test
    fun promCatch() {
        assertEquals("\"x\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.reject(new Error('x')).catch(function(e) { return e.message; });")))
    }

    @Test
    fun promThrowEnHandler() {
        assertEquals("\"y!\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.resolve(1).then(function() { throw new Error('y'); }).catch(function(e) { return e.message + '!'; });")))
    }

    @Test
    fun promAdopcion() {
        assertEquals("9", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.resolve(1).then(function() { return Promise.resolve(9); }).then(function(v) { return v; });")))
    }

    @Test
    fun promOrden() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var r = ''; Promise.resolve().then(function() { r += 'a'; }).then(function() { r += 'b'; return r; });")))
    }

    @Test
    fun promResolveExterno() {
        assertEquals("5", JsInterpreter.dumpResult(JsInterpreter.evaluate("var res; var p = new Promise(function(r) { res = r; }); res(5); p.then(function(v) { return v; });")))
    }

    @Test
    fun promCatchRechazoValor() {
        assertEquals("8", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.reject(7).catch(function(v) { return v + 1; });")))
    }

    @Test
    fun promThenRechazoPasa() {
        assertEquals("6", JsInterpreter.dumpResult(JsInterpreter.evaluate(" Promise.reject(3).then(function(v) { return v + 100; }, function(v) { return v * 2; });")))
    }

    @Test
    fun promTipo() {
        assertEquals("\"object\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" typeof Promise.resolve(1);")))
    }

    @Test
    fun reExecBasico() {
        assertEquals("\"b\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = /a(b)c/.exec('xabc'); m[1];")))
    }

    @Test
    fun reExecIndice() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate(" /bc/.exec('abcd').index;")))
    }

    @Test
    fun reExecNulo() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate(" (/q/.exec('abc') === null);")))
    }

    @Test
    fun reTest() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate(" (/a.c/.test('abc'));")))
    }

    @Test
    fun reGrupos() {
        assertEquals("\"12,34\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = /(\\d+)-(\\d+)/.exec('ab 12-34 cd'); m[1] + ',' + m[2];")))
    }

    @Test
    fun reFlagsI() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate(" (/AB/i.test('ab'));")))
    }

    @Test
    fun reGlobalSerie() {
        assertEquals("\"ootrue\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var re = /o/g; var s = 'foo'; re.exec(s)[0] + re.exec(s)[0] + (re.exec(s) === null);")))
    }

    @Test
    fun reLastIndex() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("var re = /a/g; re.exec('aa'); re.lastIndex;")))
    }

    @Test
    fun reLastIndexSet() {
        assertEquals("1", JsInterpreter.dumpResult(JsInterpreter.evaluate("var re = /a/g; re.lastIndex = 1; re.exec('aa').index;")))
    }

    @Test
    fun reConstructor() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate(" (new RegExp('a+', 'i').test('A'));")))
    }

    @Test
    fun reConstructorFlags() {
        assertEquals("\"g\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" new RegExp('b', 'g').flags;")))
    }

    @Test
    fun reSource() {
        assertEquals("\"ab\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" /ab/.source;")))
    }

    @Test
    fun reInput() {
        assertEquals("\"abc\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" /b/.exec('abc').input;")))
    }

    @Test
    fun reGrupoOpcional() {
        assertEquals("true", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = /a(x)?b/.exec('ab'); (m[1] === undefined);")))
    }

    @Test
    fun reInvalido() {
        assertEquals("\"SyntaxError\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = ''; try { new RegExp('('); } catch (e) { m = e.name; } m;")))
    }

    @Test
    fun reFlagsInvalida() {
        assertEquals("\"SyntaxError\"", JsInterpreter.dumpResult(JsInterpreter.evaluate("var m = ''; try { new RegExp('a', 'q'); } catch (e) { m = e.name; } m;")))
    }

    @Test
    fun reTipo() {
        assertEquals("\"object\"", JsInterpreter.dumpResult(JsInterpreter.evaluate(" typeof /a/;")))
    }

}
