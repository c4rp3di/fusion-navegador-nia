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

}
