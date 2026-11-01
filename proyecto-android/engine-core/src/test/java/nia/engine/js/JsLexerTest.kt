package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del lexer ECMAScript v1 (T018). Espejo 1:1 del gemelo TS/JS
 * validado: mismas fuentes, mismas expectativas.
 */
class JsLexerTest {

    private fun lex(src: String): JsLexResult = JsLexer.lex(src)

    private fun kindOf(t: JsToken): String = when (t) {
        is JsToken.Punctuator -> "punct"
        is JsToken.Keyword -> "kw"
        is JsToken.Identifier -> "id"
        is JsToken.NumberLiteral -> "num"
        is JsToken.BigIntLiteral -> "bigint"
        is JsToken.StringLiteral -> "str"
        is JsToken.TemplateLiteral -> "tmpl"
        is JsToken.TemplateHead -> "tmplHead"
        is JsToken.TemplateMiddle -> "tmplMiddle"
        is JsToken.TemplateTail -> "tmplTail"
        is JsToken.RegExpLiteral -> "regex"
        is JsToken.Eof -> "eof"
    }

    private fun valOf(t: JsToken): String = when (t) {
        is JsToken.Punctuator -> t.value
        is JsToken.Keyword -> t.value
        is JsToken.Identifier -> t.value
        is JsToken.NumberLiteral -> t.raw
        is JsToken.BigIntLiteral -> t.raw
        is JsToken.StringLiteral -> t.value
        is JsToken.TemplateLiteral -> t.value
        is JsToken.TemplateHead -> t.value
        is JsToken.TemplateMiddle -> t.value
        is JsToken.TemplateTail -> t.value
        is JsToken.RegExpLiteral -> t.body
        is JsToken.Eof -> ""
    }

    private fun kinds(src: String): String =
        lex(src).tokens.joinToString(",") { kindOf(it) }

    private fun vals(src: String): String =
        lex(src).tokens.joinToString(",") { valOf(it) }

    private fun errs(src: String): String =
        lex(src).errors.joinToString(",") { it.code }

    @Test
    fun fuenteVaciaProduceSoloEof() {
        val r = lex("")
        assertEquals(1, r.tokens.size)
        val t = r.tokens[0]
        assertEquals("eof", kindOf(t))
        assertEquals(0, t.start)
        assertEquals(0, t.end)
        assertEquals(0, r.errors.size)
    }

    @Test
    fun espaciosYComentariosSeSaltanConSpansCorrectos() {
        val src = "a // x\n b /* y */ c"
        assertEquals("id,id,id,eof", kinds(src))
        assertEquals(0, lex(src).tokens[0].start)
        assertEquals(1, lex(src).tokens[0].end)
        assertEquals(8, lex(src).tokens[1].start)
        assertEquals(9, lex(src).tokens[1].end)
        assertEquals(18, lex(src).tokens[2].start)
    }

    @Test
    fun comentarioSinCerrarDaErrorConLineaColumna() {
        val r = lex("a /* x")
        assertEquals("unterminated-comment", errs("a /* x"))
        assertEquals(1, r.errors[0].line)
        assertEquals(3, r.errors[0].column)
    }

    @Test
    fun puntuadoresLargosYSimples() {
        assertEquals(
            ">>>=,...,===,!==,**=,<<=,>>=,>>>,&&=,||=,??=,=>,==,!=,<=,>=,&&,||,??,++,--,+=,-=,*=,%=,&=,|=,^=,<<,>>,",
            vals(">>>= ... === !== **= <<= >>= >>> &&= ||= ??= => == != <= >= && || ?? ++ -- += -= *= %= &= |= ^= << >>")
        )
        assertEquals(
            "{,},(,),[,],;,,,<,>,+,-,*,%,&,|,^,!,~,?,:,=,.,",
            vals("{}()[];,<>+-*%&|^!~?:=.")
        )
    }

    @Test
    fun keywordsEstrictasVsContextuales() {
        assertEquals(
            "var,class,extends,super,this,null,true,false,",
            vals("var class extends super this null true false")
        )
        assertEquals(
            "id,id,id,id,id,id,id,id,id,eof",
            kinds("let static of get set from as async undefined")
        )
    }

    @Test
    fun identificadoresConEscapeUnicode() {
        val s1 = "\\" + "u0041bc"
        val r1 = lex(s1)
        assertEquals("id", kindOf(r1.tokens[0]))
        assertEquals("Abc", valOf(r1.tokens[0]))
        assertEquals(0, r1.tokens[0].start)
        assertEquals(8, r1.tokens[0].end)
        val s2 = "\\" + "u00zz"
        val r2 = lex(s2)
        assertEquals("unexpected-character", errs(s2))
        assertEquals("id:u00zz", kindOf(r2.tokens[0]) + ":" + valOf(r2.tokens[0]))
    }

    @Test
    fun numerosDecimales() {
        val r = lex("42 3.14 5. .5 1e3 2e+10 1_000")
        assertEquals(42.0, (r.tokens[0] as JsToken.NumberLiteral).value)
        assertEquals(3.14, (r.tokens[1] as JsToken.NumberLiteral).value)
        assertEquals(5.0, (r.tokens[2] as JsToken.NumberLiteral).value)
        assertEquals(0.5, (r.tokens[3] as JsToken.NumberLiteral).value)
        assertEquals(1000.0, (r.tokens[4] as JsToken.NumberLiteral).value)
        assertEquals(2.0e10, (r.tokens[5] as JsToken.NumberLiteral).value)
        assertEquals(1000.0, (r.tokens[6] as JsToken.NumberLiteral).value)
        assertEquals("5.", valOf(r.tokens[2]))
    }

    @Test
    fun hexOctalBinarioBigInt() {
        val r = lex("0xff 0o17 0b101 0xFFn 123n")
        assertEquals(255.0, (r.tokens[0] as JsToken.NumberLiteral).value)
        assertEquals(15.0, (r.tokens[1] as JsToken.NumberLiteral).value)
        assertEquals(5.0, (r.tokens[2] as JsToken.NumberLiteral).value)
        assertEquals("bigint:0xFFn", kindOf(r.tokens[3]) + ":" + valOf(r.tokens[3]))
        val t4 = r.tokens[4]
        assertEquals("bigint:123n:" + t4.end, kindOf(t4) + ":" + valOf(t4) + ":" + t4.end)
        assertEquals(26, t4.end)
    }

    @Test
    fun octalLegacyDaErrorYValorDecimal() {
        val r = lex("010")
        assertEquals("legacy-octal-literal", errs("010"))
        assertEquals(10.0, (r.tokens[0] as JsToken.NumberLiteral).value)
        assertEquals("legacy-octal-literal", errs("08"))
    }

    @Test
    fun hexSinDigitos() {
        val r = lex("0x")
        assertEquals("invalid-numeric-literal", errs("0x"))
        assertEquals("num:0x", kindOf(r.tokens[0]) + ":" + valOf(r.tokens[0]))
    }

    @Test
    fun bigintConPuntoEsError() {
        val r = lex("1.5n")
        assertEquals("invalid-bigint-literal", errs("1.5n"))
        val t = r.tokens[0] as JsToken.NumberLiteral
        assertEquals(1.5, t.value)
        assertEquals(4, t.end)
    }

    @Test
    fun stringsSimplesYEscapes() {
        assertEquals("hola", valOf(lex("'hola'").tokens[0]))
        assertEquals("x\ty", valOf(lex("\"x\\ty\"").tokens[0]))
        assertEquals("a'b", valOf(lex("'a\\'b'").tokens[0]))
        assertEquals("\\", valOf(lex("'\\\\'").tokens[0]))
    }

    @Test
    fun escapesUnicodeEnStrings() {
        val s1 = "'" + "\\" + "u0041'"
        assertEquals("A", valOf(lex(s1).tokens[0]))
        val s2 = "'" + "\\" + "u{1F600}'"
        assertEquals(String(Character.toChars(0x1F600)), valOf(lex(s2).tokens[0]))
        assertEquals("A", valOf(lex("'\\x41'").tokens[0]))
        assertEquals(0.toChar().toString(), valOf(lex("'\\0'").tokens[0]))
        assertEquals("q$", valOf(lex("'\\q\\$'").tokens[0]))
    }

    @Test
    fun continuacionDeLineaEnString() {
        val s = "'a\\" + "\nb'"
        assertEquals("ab", valOf(lex(s).tokens[0]))
    }

    @Test
    fun escapeOctalNoSoportado() {
        val s = "'\\07'"
        val r = lex(s)
        assertEquals("octal-escape-unsupported", errs(s))
        assertEquals("7", valOf(r.tokens[0]))
    }

    @Test
    fun stringsSinCerrar() {
        val r1 = lex("'abc")
        assertEquals("unterminated-string", errs("'abc"))
        assertEquals("str:abc:4", kindOf(r1.tokens[0]) + ":" + valOf(r1.tokens[0]) + ":" + r1.tokens[0].end)
        val s2 = "'ab\ncd'"
        assertEquals("unterminated-string,unterminated-string", errs(s2))
        assertEquals("str,id,str,eof", kinds(s2))
    }

    @Test
    fun escapeHexInvalidoRecuperaTexto() {
        val s = "'\\xzz'"
        val r = lex(s)
        assertEquals("invalid-hex-escape", errs(s))
        assertEquals("zz", valOf(r.tokens[0]))
    }

    @Test
    fun templateSinSustitucion() {
        val r = lex("`hola`")
        assertEquals("tmpl", kindOf(r.tokens[0]))
        assertEquals("hola", valOf(r.tokens[0]))
        assertEquals(0, r.tokens[0].start)
        assertEquals(6, r.tokens[0].end)
        val s2 = "`a" + "\\" + "u0041b\\nb`"
        assertEquals("aAb\nb", valOf(lex(s2).tokens[0]))
        assertEquals("a\nb", valOf(lex("`a\r\nb`").tokens[0]))
    }

    @Test
    fun templateConSustituciones() {
        val src = "`a\${x}b\${y}c`"
        val seq = lex(src).tokens.joinToString(" ") {
            kindOf(it) + "@" + valOf(it) + ":" + it.start + "-" + it.end
        }
        assertEquals(
            "tmplHead@a:0-4 id@x:4-5 tmplMiddle@b:5-9 id@y:9-10 tmplTail@c:10-13 eof@:13-13",
            seq
        )
    }

    @Test
    fun templatesAnidados() {
        val src = "`a\${ " + "`i\${j}k`" + " }b`"
        val r = lex(src)
        val seq = r.tokens.joinToString(" ") { kindOf(it) + "@" + valOf(it) }
        assertEquals("tmplHead@a tmplHead@i id@j tmplTail@k tmplTail@b eof@", seq)
        assertEquals(0, r.errors.size)
    }

    @Test
    fun templateSinCerrar() {
        val r1 = lex("`abc")
        assertEquals("unterminated-template", errs("`abc"))
        assertEquals("tmplHead:abc", kindOf(r1.tokens[0]) + ":" + valOf(r1.tokens[0]))
        assertEquals("unterminated-template", errs("`a\${b"))
    }

    @Test
    fun llavesDentroDeSustitucion() {
        val src = "`\${ {a:1} }`"
        val r = lex(src)
        assertEquals("tmplHead,punct,id,punct,num,punct,tmplTail,eof", kinds(src))
        assertEquals(0, r.errors.size)
    }

    @Test
    fun regexBasicoYContexto() {
        val r = lex("/ab+c/gi")
        assertEquals("regex", kindOf(r.tokens[0]))
        assertEquals("ab+c", valOf(r.tokens[0]))
        assertEquals("gi", (r.tokens[0] as JsToken.RegExpLiteral).flags)
        assertEquals(0, r.tokens[0].start)
        assertEquals(8, r.tokens[0].end)
        assertEquals("kw,regex,eof", kinds("return /x/"))
    }

    @Test
    fun regexConClaseYEscape() {
        val r = lex("/a[/x/]b\\//g")
        assertEquals("a[/x/]b\\/", valOf(r.tokens[0]))
        assertEquals("g", (r.tokens[0] as JsToken.RegExpLiteral).flags)
    }

    @Test
    fun regexSinCerrar() {
        val r = lex("/ab")
        assertEquals("unterminated-regexp", errs("/ab"))
        assertEquals("regex:ab", kindOf(r.tokens[0]) + ":" + valOf(r.tokens[0]))
        assertEquals("unterminated-regexp", errs("/a\ncd/"))
    }

    @Test
    fun regexConFlagsInvalidos() {
        val r = lex("/a/qz")
        assertEquals("invalid-regexp-flags", errs("/a/qz"))
        assertEquals("qz", (r.tokens[0] as JsToken.RegExpLiteral).flags)
    }

    @Test
    fun divisionFrenteARegex() {
        assertEquals("id,punct,id,eof", kinds("a / b"))
        assertEquals("id,punct,num,eof", kinds("x /= 2"))
        assertEquals("punct,id,punct,id,punct,punct,num,eof", kinds("(a+b)/2"))
        assertEquals("kw,regex,punct,id,punct,id,punct,eof", kinds("return /re/.test(x)"))
        assertEquals("kw,punct,id,punct,eof", kinds("this /re/"))
    }

    @Test
    fun caracterInesperadoConLineaColumnaYRecuperacion() {
        val r = lex("a\n@")
        assertEquals("unexpected-character", errs("a\n@"))
        assertEquals(2, r.errors[0].line)
        assertEquals(1, r.errors[0].column)
        assertEquals("id,id,eof", kinds("a # b"))
    }

    @Test
    fun spansYEof() {
        val seq = lex("a b").tokens.joinToString(" ") { it.start.toString() + ":" + it.end }
        assertEquals("0:1 2:3 3:3", seq)
    }

    @Test
    fun awaitYieldSonKeywords() {
        assertEquals("kw,id,punct,kw,id,eof", kinds("await x; yield y"))
    }

    @Test
    fun exponentes() {
        val r = lex("1e-2 1E+3 10e2")
        assertEquals(0.01, (r.tokens[0] as JsToken.NumberLiteral).value)
        assertEquals(1000.0, (r.tokens[1] as JsToken.NumberLiteral).value)
        assertEquals(1000.0, (r.tokens[2] as JsToken.NumberLiteral).value)
    }

    @Test
    fun programaCompleto() {
        val src = "const x = 42; if (x >= 10) { log(`x=\${x}`); }"
        val r = lex(src)
        val seq = r.tokens.joinToString(",") { kindOf(it) }
        assertEquals(
            "kw,id,punct,num,punct,kw,punct,id,punct,num,punct,punct,id,punct,tmplHead,id,tmplTail,punct,punct,punct,eof",
            seq
        )
        assertEquals(0, r.errors.size)
    }

    @Test
    fun guionBajoSueltoEsError() {
        assertEquals("invalid-numeric-literal", errs("1_"))
    }

    @Test
    fun numeroPegadoAIdentificador() {
        assertEquals("num,id,eof", kinds("5.foo"))
        assertEquals("num,id,eof", kinds("1e"))
    }

    @Test
    fun erroresMultiplesConRecuperacion() {
        val r = lex("@ ~ #")
        assertEquals("unexpected-character,unexpected-character", errs("@ ~ #"))
        assertEquals("punct,eof", kinds("@ ~ #"))
    }
}
