package nia.engine.html

/**
 * Tokens del tokenizer HTML (T012 v1), subconjunto del algoritmo WHATWG.
 *
 * Los tokens de datos se agrupan en runs contiguos; toda secuencia termina
 * con Eof. Los errores de parseo se recogen aparte (parseErrors del
 * Resultado), no inline en los tokens.
 */
sealed class HtmlToken {

    /** Caracteres de datos (agrupados en runs contiguos). */
    data class Data(val text: String) : HtmlToken()

    /**
     * Etiqueta de apertura. Los nombres van en minusculas ASCII y los
     * atributos conservan el orden de aparicion; si un nombre se repite
     * gana el primero (parse error tipificado).
     */
    data class StartTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean
    ) : HtmlToken()

    /** Etiqueta de cierre. Los atributos que llevara, si habia, se descartan (v1). */
    data class EndTag(val name: String) : HtmlToken()

    /** Comentario. */
    data class Comment(val data: String) : HtmlToken()

    /**
     * Doctype. name nulo = nombre ausente (forceQuirks true); los ids
     * PUBLIC/SYSTEM se consumen pero no se guardan (v1).
     */
    data class Doctype(val name: String?, val forceQuirks: Boolean) : HtmlToken()

    /** Fin de la entrada. */
    object Eof : HtmlToken()
}
