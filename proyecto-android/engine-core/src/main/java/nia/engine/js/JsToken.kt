package nia.engine.js

/**
 * Tokens del lexer ECMAScript (T018 v1). Cada token cubre el rango de fuente
 * [start, end). Los literales numericos llevan el raw y el valor Double; los
 * BigInt solo el raw; los RegExp llevan cuerpo y flags por separado.
 */
sealed class JsToken(val start: Int, val end: Int) {

    class Punctuator(val value: String, start: Int, end: Int) : JsToken(start, end)

    class Keyword(val value: String, start: Int, end: Int) : JsToken(start, end)

    class Identifier(val value: String, start: Int, end: Int) : JsToken(start, end)

    class NumberLiteral(val raw: String, val value: Double, start: Int, end: Int) : JsToken(start, end)

    class BigIntLiteral(val raw: String, start: Int, end: Int) : JsToken(start, end)

    class StringLiteral(val value: String, val quote: Char, start: Int, end: Int) : JsToken(start, end)

    class TemplateLiteral(val value: String, start: Int, end: Int) : JsToken(start, end)

    class TemplateHead(val value: String, start: Int, end: Int) : JsToken(start, end)

    class TemplateMiddle(val value: String, start: Int, end: Int) : JsToken(start, end)

    class TemplateTail(val value: String, start: Int, end: Int) : JsToken(start, end)

    class RegExpLiteral(val body: String, val flags: String, start: Int, end: Int) : JsToken(start, end)

    class Eof(start: Int, end: Int) : JsToken(start, end)
}

/**
 * Error de lexico tipificado con posicion de fuente y linea/columna 1-based.
 * El lexer se recupera y continua: los errores se acumulan junto a los tokens.
 */
class JsLexError(val code: String, val start: Int, val line: Int, val column: Int)

class JsLexResult(val tokens: List<JsToken>, val errors: List<JsLexError>)
