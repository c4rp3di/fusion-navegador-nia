package nia.engine.css

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests del parser CSS v2 (R0023): espejo validado del gemelo TS.
 * v1 (T014) + combinadores de hermanos +/~ y pseudo-clases estaticas
 * (:first-child, :last-child, :only-child, :nth-child(an+b)).
 * v3 (R0090, RES-0050): @media soportado y guardado por regla; el skip de
 * @-rules sin soporte se prueba con @import.
 */
class CssParserTest {

    @Test
    fun reglaSimple() {
        val s = CssStylesheet.parse("p{color:red}")
        assertEquals(1, s.rules.size, "reglas")
        assertEquals(listOf(CssSimpleSelector.Type("p")), s.rules[0].selectors[0].compounds[0].simples, "sel")
        assertEquals(listOf("color" to "red"), s.rules[0].declarations, "decls")
    }

    @Test
    fun agrupacion() {
        val s = CssStylesheet.parse("h1,h2{margin:0}")
        assertEquals(2, s.rules[0].selectors.size, "sels")
        assertEquals(listOf("margin" to "0"), s.rules[0].declarations, "decls")
    }

    @Test
    fun compoundCompleto() {
        val s = CssStylesheet.parse("div.a#b[x=y]{c:v}")
        assertEquals(listOf(
            CssSimpleSelector.Type("div"),
            CssSimpleSelector.Cls("a"),
            CssSimpleSelector.Id("b"),
            CssSimpleSelector.Attr("x", "=", "y")
        ), s.rules[0].selectors[0].compounds[0].simples, "compound")
    }

    @Test
    fun combinadores() {
        assertEquals(listOf("child"), CssStylesheet.parse("a > b{c:v}").rules[0].selectors[0].combinators, "child")
        assertEquals(listOf("desc"), CssStylesheet.parse("a b{c:v}").rules[0].selectors[0].combinators, "desc")
    }

    @Test
    fun especificidad() {
        assertEquals(CssSpecificity(1, 0, 0), CssCascade.specificityOf(CssStylesheet.parse("#x{c:v}").rules[0].selectors[0]), "id")
        assertEquals(CssSpecificity(0, 2, 0), CssCascade.specificityOf(CssStylesheet.parse(".a.b{c:v}").rules[0].selectors[0]), "clases")
        assertEquals(CssSpecificity(0, 0, 2), CssCascade.specificityOf(CssStylesheet.parse("div span{c:v}").rules[0].selectors[0]), "tipos")
        assertEquals(CssSpecificity(1, 1, 2), CssCascade.specificityOf(CssStylesheet.parse("a.b#z i{c:v}").rules[0].selectors[0]), "mixto")
    }

    @Test
    fun pseudoNoSoportado() {
        val s = CssStylesheet.parse("p:hover{color:red}")
        assertEquals(listOf("unsupported-pseudo"), s.errors, "errores")
        assertEquals(0, s.rules.size, "reglas")
    }

    @Test
    fun atRuleSeSalta() {
        val s = CssStylesheet.parse("@import x;p{color:blue}")
        assertEquals(listOf("unsupported-at-rule"), s.errors, "errores")
        assertEquals(1, s.rules.size, "reglas")
        assertEquals(listOf("color" to "blue"), s.rules[0].declarations, "decls")
        val m = CssStylesheet.parse("@media screen{p{color:red}}p{color:blue}")
        assertEquals(emptyList<String>(), m.errors, "errores media")
        assertEquals(2, m.rules.size, "reglas media")
        assertEquals("screen", m.rules[0].media, "query")
        assertEquals(listOf("color" to "red"), m.rules[0].declarations, "decls media")
        assertEquals(null, m.rules[1].media, "sin query")
    }

    @Test
    fun bloqueSinCerrar() {
        val s = CssStylesheet.parse("p{color:red")
        assertEquals(listOf("unterminated-block"), s.errors, "errores")
        assertEquals(0, s.rules.size, "reglas")
    }

    @Test
    fun declaracionInvalidaSeSalta() {
        val s = CssStylesheet.parse("p{color red;font-size:2px}")
        assertEquals(listOf("invalid-declaration"), s.errors, "errores")
        assertEquals(listOf("font-size" to "2px"), s.rules[0].declarations, "decls")
    }

    @Test
    fun ordenDeReglas() {
        val s = CssStylesheet.parse("p,q{color:red}.a{color:blue}")
        assertEquals(2, s.rules.size, "reglas")
        assertEquals(listOf(0, 1), s.rules.map { it.order }, "orden")
    }

    @Test
    fun attrConSimbolo() {
        val s = CssStylesheet.parse("[lang~=en]{c:v}")
        assertEquals(listOf(CssSimpleSelector.Attr("lang", "~=", "en")), s.rules[0].selectors[0].compounds[0].simples, "attr")
    }

    @Test
    fun selectorInvalidoTiraRegla() {
        val s = CssStylesheet.parse("p,{color:red}")
        assertEquals(listOf("invalid-selector"), s.errors, "errores")
        assertEquals(0, s.rules.size, "reglas")
    }

    @Test
    fun comentarioInicial() {
        val s = CssStylesheet.parse("/*c*/p{color:red}")
        assertEquals(1, s.rules.size, "reglas")
        assertEquals(listOf("color" to "red"), s.rules[0].declarations, "decls")
    }

    @Test
    fun puntoYComaFinal() {
        val s = CssStylesheet.parse("p{color:red;}")
        assertEquals(listOf("color" to "red"), s.rules[0].declarations, "decls")
    }

    @Test
    fun propiedadEnMinusculas() {
        val s = CssStylesheet.parse("p{COLOR:RED}")
        assertEquals(listOf("color" to "RED"), s.rules[0].declarations, "decls")
    }

    @Test
    fun comentarioEnDeclaraciones() {
        val s = CssStylesheet.parse("p{color:red;/*x*/}")
        assertEquals(listOf("color" to "red"), s.rules[0].declarations, "decls")
        assertEquals(emptyList<String>(), s.errors, "errores")
    }

    @Test
    fun combinadoresHermanos() {
        assertEquals(listOf("adj"), CssStylesheet.parse("a+b{c:v}").rules[0].selectors[0].combinators, "adj")
        assertEquals(listOf("gen"), CssStylesheet.parse("a~b{c:v}").rules[0].selectors[0].combinators, "gen")
    }

    @Test
    fun pseudoEstatico() {
        val s = CssStylesheet.parse("p:first-child{c:v}")
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("first-child", null)
        ), s.rules[0].selectors[0].compounds[0].simples, "first-child")
    }

    @Test
    fun pseudoConAntecesor() {
        val s = CssStylesheet.parse("div p:last-child{c:v}")
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("last-child", null)
        ), s.rules[0].selectors[0].compounds[1].simples, "last-child")
    }

    @Test
    fun nthChildAn() {
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(2, 0))
        ), CssStylesheet.parse("p:nth-child(2n){c:v}").rules[0].selectors[0].compounds[0].simples, "2n")
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(2, 1))
        ), CssStylesheet.parse("p:nth-child(2n+1){c:v}").rules[0].selectors[0].compounds[0].simples, "2n+1")
    }

    @Test
    fun nthChildOddEven() {
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(2, 1))
        ), CssStylesheet.parse("p:nth-child(odd){c:v}").rules[0].selectors[0].compounds[0].simples, "odd")
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(2, 0))
        ), CssStylesheet.parse("p:nth-child(even){c:v}").rules[0].selectors[0].compounds[0].simples, "even")
    }

    @Test
    fun nthChildEntero() {
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(0, 3))
        ), CssStylesheet.parse("p:nth-child(3){c:v}").rules[0].selectors[0].compounds[0].simples, "3")
    }

    @Test
    fun nthChildNegativo() {
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(-1, 3))
        ), CssStylesheet.parse("p:nth-child(-n+3){c:v}").rules[0].selectors[0].compounds[0].simples, "-n+3")
    }

    @Test
    fun nthChildConEspacios() {
        assertEquals(listOf(
            CssSimpleSelector.Type("p"),
            CssSimpleSelector.Pseudo("nth-child", CssNth(2, 1))
        ), CssStylesheet.parse("p:nth-child( 2n + 1 ){c:v}").rules[0].selectors[0].compounds[0].simples, "espacios")
    }

    @Test
    fun nthChildInvalido() {
        val s = CssStylesheet.parse("p:nth-child(foo){c:v}")
        assertEquals(listOf("invalid-selector"), s.errors, "errores")
        assertEquals(0, s.rules.size, "reglas")
    }

    @Test
    fun pseudoEstaticoConArgumento() {
        val s = CssStylesheet.parse("p:first-child(x){c:v}")
        assertEquals(listOf("invalid-selector"), s.errors, "errores")
        assertEquals(0, s.rules.size, "reglas")
    }

    @Test
    fun especificidadPseudoClase() {
        assertEquals(
            CssSpecificity(0, 1, 1),
            CssCascade.specificityOf(CssStylesheet.parse("p:first-child{c:v}").rules[0].selectors[0]),
            "pseudo como clase"
        )
    }
}
