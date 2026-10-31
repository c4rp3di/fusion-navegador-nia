package nia.engine.html

/**
 * Tokenizer HTML (T012 v1): subconjunto practico del algoritmo WHATWG
 * (tokenization, seccion 13.2.5 de la spec HTML).
 *
 * Cubre: data, etiquetas con atributos (comillas dobles/simples o sin
 * comillas, nombres en minusculas ASCII, duplicados: gana el primero),
 * self-closing, comentarios (incluidos '--!>' y cierres abruptos),
 * doctype (nombre + forceQuirks) y RAWTEXT/RCDATA aproximado para
 * script/style/textarea/title/xmp/iframe/noembed/noframes.
 *
 * Fuera de alcance v1: character references ('&' pasa literal), CDATA
 * (se degrada a comentario con error, como en HTML), atributos en
 * etiquetas de cierre (se descartan) e ids PUBLIC/SYSTEM del doctype.
 * Los errores de parseo se recogen por nombre en parseErrors.
 */
object HtmlTokenizer {

    /** Resultado del tokenizado: tokens (siempre con Eof final) y errores de parseo. */
    data class Result(val tokens: List<HtmlToken>, val parseErrors: List<String>)

    fun tokenize(input: String): Result = Machine(input).run()

    private class Machine(private val s: String) {

        private enum class State {
            DATA, TAG_OPEN, END_TAG_OPEN, TAG_NAME,
            BEFORE_ATTR_NAME, ATTR_NAME, AFTER_ATTR_NAME, BEFORE_ATTR_VALUE,
            ATTR_VALUE_DQ, ATTR_VALUE_SQ, ATTR_VALUE_UQ, AFTER_ATTR_VALUE_QUOTED,
            SELF_CLOSING, MARKUP_DECL,
            COMMENT_START, COMMENT_START_DASH, COMMENT, COMMENT_END_DASH,
            COMMENT_END, COMMENT_END_BANG,
            DOCTYPE, DOCTYPE_NAME, DOCTYPE_SKIP, BOGUS_COMMENT, RAWTEXT
        }

        private val RAWTEXT_ELEMENTS = setOf(
            "script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes"
        )

        private val tokens = ArrayList<HtmlToken>()
        private val errors = ArrayList<String>()
        private var state = State.DATA
        private var i = 0

        private var dataBuf = StringBuilder()
        private var tagName = StringBuilder()
        private var isEndTag = false
        private var selfClosing = false
        private var attrs = LinkedHashMap<String, String>()
        private var attrName = StringBuilder()
        private var attrValue = StringBuilder()
        private var commentBuf = StringBuilder()
        private var dtName: String? = null
        private var rawName = ""

        private fun err(name: String) {
            errors.add(name)
        }

        private fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

        private fun isAlpha(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z'

        private fun lowerChar(c: Char): Char =
            if (c in 'A'..'Z') (c.code + 32).toChar() else c

        private fun matchesAsciiIgnoreCase(start: Int, ref: String): Boolean {
            if (s.length - start < ref.length) return false
            for (k in ref.indices) {
                if (lowerChar(s[start + k]) != ref[k]) return false
            }
            return true
        }

        private fun flushData() {
            if (dataBuf.isNotEmpty()) {
                tokens.add(HtmlToken.Data(dataBuf.toString()))
                dataBuf = StringBuilder()
            }
        }

        private fun finalizeAttr() {
            if (attrName.isEmpty()) return
            val key = attrName.toString()
            if (attrs.containsKey(key)) {
                err("duplicate-attribute")
            } else {
                attrs[key] = attrValue.toString()
            }
            attrName = StringBuilder()
            attrValue = StringBuilder()
        }

        private fun emitTag() {
            finalizeAttr()
            val name = tagName.toString()
            if (isEndTag) {
                tokens.add(HtmlToken.EndTag(name))
                state = State.DATA
            } else {
                tokens.add(HtmlToken.StartTag(name, LinkedHashMap(attrs), selfClosing))
                if (RAWTEXT_ELEMENTS.contains(name)) {
                    rawName = name
                    state = State.RAWTEXT
                } else {
                    state = State.DATA
                }
            }
            tagName = StringBuilder()
            isEndTag = false
            selfClosing = false
            attrs = LinkedHashMap()
        }

        private fun emitCommentAndReset() {
            tokens.add(HtmlToken.Comment(commentBuf.toString()))
            commentBuf = StringBuilder()
        }

        private fun emitDoctype(forceQuirks: Boolean) {
            tokens.add(HtmlToken.Doctype(dtName, forceQuirks))
        }

        fun run(): Result {
            val n = s.length
            while (true) {
                when (state) {
                    State.DATA -> {
                        if (i >= n) {
                            flushData()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '<') {
                            state = State.TAG_OPEN
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            dataBuf.append('\uFFFD')
                        } else {
                            dataBuf.append(c)
                        }
                    }
                    State.RAWTEXT -> {
                        if (i >= n) {
                            flushData()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        if (c == '<' && i + 1 < n && s[i + 1] == '/') {
                            var j = i + 2
                            var k = 0
                            while (j < n && k < rawName.length && lowerChar(s[j]) == rawName[k]) {
                                j++
                                k++
                            }
                            if (k == rawName.length && j < n) {
                                val t = s[j]
                                if (t == '>' || t == '/' || isWs(t)) {
                                    flushData()
                                    i += 2
                                    state = State.END_TAG_OPEN
                                    continue
                                }
                            }
                            dataBuf.append("</")
                            i += 2
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            dataBuf.append('\uFFFD')
                            i++
                        } else {
                            dataBuf.append(c)
                            i++
                        }
                    }
                    State.TAG_OPEN -> {
                        if (i >= n) {
                            err("eof-before-tag-name")
                            dataBuf.append('<')
                            state = State.DATA
                        } else {
                            val c = s[i]
                            i++
                            if (c == '!') {
                                flushData()
                                state = State.MARKUP_DECL
                            } else if (c == '/') {
                                flushData()
                                state = State.END_TAG_OPEN
                            } else if (isAlpha(c)) {
                                flushData()
                                isEndTag = false
                                tagName = StringBuilder()
                                i--
                                state = State.TAG_NAME
                            } else if (c == '?') {
                                flushData()
                                err("unexpected-question-mark-instead-of-tag-name")
                                commentBuf = StringBuilder()
                                i--
                                state = State.BOGUS_COMMENT
                            } else if (c.code == 0) {
                                err("unexpected-null-character")
                                dataBuf.append('<')
                                i--
                                state = State.DATA
                            } else {
                                err("invalid-first-character-in-tag-name")
                                dataBuf.append('<')
                                i--
                                state = State.DATA
                            }
                        }
                    }
                    State.END_TAG_OPEN -> {
                        if (i >= n) {
                            err("eof-before-tag-name")
                            dataBuf.append("</")
                            state = State.DATA
                        } else {
                            val c = s[i]
                            i++
                            if (isAlpha(c)) {
                                isEndTag = true
                                tagName = StringBuilder()
                                i--
                                state = State.TAG_NAME
                            } else if (c == '>') {
                                err("missing-end-tag-name")
                                state = State.DATA
                            } else if (c.code == 0) {
                                err("unexpected-null-character")
                                commentBuf = StringBuilder()
                                i--
                                state = State.BOGUS_COMMENT
                            } else {
                                err("invalid-first-character-in-tag-name")
                                commentBuf = StringBuilder()
                                i--
                                state = State.BOGUS_COMMENT
                            }
                        }
                    }
                    State.TAG_NAME -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            state = State.BEFORE_ATTR_NAME
                        } else if (c == '/') {
                            state = State.SELF_CLOSING
                        } else if (c == '>') {
                            emitTag()
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            tagName.append('\uFFFD')
                        } else {
                            tagName.append(lowerChar(c))
                        }
                    }
                    State.BEFORE_ATTR_NAME -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            // ignorar
                        } else if (c == '/' || c == '>') {
                            i--
                            state = State.AFTER_ATTR_NAME
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            finalizeAttr()
                            attrName = StringBuilder()
                            attrName.append('\uFFFD')
                            state = State.ATTR_NAME
                        } else if (c == '=') {
                            err("unexpected-equals-sign-before-attribute-name")
                            finalizeAttr()
                            attrName = StringBuilder()
                            attrName.append('=')
                            state = State.ATTR_NAME
                        } else {
                            finalizeAttr()
                            i--
                            state = State.ATTR_NAME
                        }
                    }
                    State.ATTR_NAME -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c) || c == '/' || c == '>') {
                            i--
                            state = State.AFTER_ATTR_NAME
                        } else if (c == '=') {
                            state = State.BEFORE_ATTR_VALUE
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            attrName.append('\uFFFD')
                        } else if (c == '"' || c == '\'' || c == '<') {
                            err("unexpected-character-in-attribute-name")
                            attrName.append(lowerChar(c))
                        } else {
                            attrName.append(lowerChar(c))
                        }
                    }
                    State.AFTER_ATTR_NAME -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            // ignorar
                        } else if (c == '/') {
                            state = State.SELF_CLOSING
                        } else if (c == '>') {
                            emitTag()
                        } else {
                            i--
                            state = State.BEFORE_ATTR_NAME
                        }
                    }
                    State.BEFORE_ATTR_VALUE -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            // ignorar
                        } else if (c == '"') {
                            state = State.ATTR_VALUE_DQ
                        } else if (c == '\'') {
                            state = State.ATTR_VALUE_SQ
                        } else if (c == '>') {
                            err("missing-attribute-value")
                            emitTag()
                        } else {
                            i--
                            state = State.ATTR_VALUE_UQ
                        }
                    }
                    State.ATTR_VALUE_DQ, State.ATTR_VALUE_SQ -> {
                        val quote = if (state == State.ATTR_VALUE_DQ) '"' else '\''
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == quote) {
                            state = State.AFTER_ATTR_VALUE_QUOTED
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            attrValue.append('\uFFFD')
                        } else {
                            attrValue.append(c)
                        }
                    }
                    State.ATTR_VALUE_UQ -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            state = State.BEFORE_ATTR_NAME
                        } else if (c == '>') {
                            emitTag()
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            attrValue.append('\uFFFD')
                        } else if (c == '"' || c == '\'' || c == '<' || c == '=' || c == '\u0060') {
                            err("unexpected-character-in-unquoted-attribute-value")
                            attrValue.append(c)
                        } else {
                            attrValue.append(c)
                        }
                    }
                    State.AFTER_ATTR_VALUE_QUOTED -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            state = State.BEFORE_ATTR_NAME
                        } else if (c == '/') {
                            state = State.SELF_CLOSING
                        } else if (c == '>') {
                            emitTag()
                        } else {
                            err("missing-whitespace-between-attributes")
                            i--
                            state = State.BEFORE_ATTR_NAME
                        }
                    }
                    State.SELF_CLOSING -> {
                        if (i >= n) {
                            err("eof-in-tag")
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            if (isEndTag) err("unexpected-solidus-in-tag")
                            selfClosing = true
                            emitTag()
                        } else {
                            err("unexpected-solidus-in-tag")
                            i--
                            state = State.BEFORE_ATTR_NAME
                        }
                    }
                    State.MARKUP_DECL -> {
                        if (i + 1 < n && s[i] == '-' && s[i + 1] == '-') {
                            i += 2
                            commentBuf = StringBuilder()
                            state = State.COMMENT_START
                        } else if (matchesAsciiIgnoreCase(i, "doctype")) {
                            i += 7
                            dtName = null
                            state = State.DOCTYPE
                        } else if (i + 6 < n && s.substring(i, i + 7) == "[CDATA[") {
                            err("cdata-in-html")
                            i += 7
                            commentBuf = StringBuilder()
                            state = State.BOGUS_COMMENT
                        } else {
                            err("incorrectly-opened-comment")
                            commentBuf = StringBuilder()
                            state = State.BOGUS_COMMENT
                        }
                    }
                    State.COMMENT_START -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            err("abrupt-closing-of-empty-comment")
                            emitCommentAndReset()
                            state = State.DATA
                        } else if (c == '-') {
                            state = State.COMMENT_START_DASH
                        } else {
                            i--
                            state = State.COMMENT
                        }
                    }
                    State.COMMENT_START_DASH -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            err("abrupt-closing-of-comment")
                            emitCommentAndReset()
                            state = State.DATA
                        } else {
                            commentBuf.append('-')
                            if (c != '-') i--
                            state = State.COMMENT
                        }
                    }
                    State.COMMENT -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '-') {
                            state = State.COMMENT_END_DASH
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            commentBuf.append('\uFFFD')
                        } else {
                            commentBuf.append(c)
                        }
                    }
                    State.COMMENT_END_DASH -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '-') {
                            state = State.COMMENT_END
                        } else if (c == '>') {
                            err("abrupt-closing-of-comment")
                            emitCommentAndReset()
                            state = State.DATA
                        } else {
                            commentBuf.append('-')
                            i--
                            state = State.COMMENT
                        }
                    }
                    State.COMMENT_END -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            emitCommentAndReset()
                            state = State.DATA
                        } else if (c == '!') {
                            state = State.COMMENT_END_BANG
                        } else if (c == '-') {
                            commentBuf.append('-')
                            state = State.COMMENT_END_DASH
                        } else {
                            commentBuf.append('-')
                            i--
                            state = State.COMMENT
                        }
                    }
                    State.COMMENT_END_BANG -> {
                        if (i >= n) {
                            err("eof-in-comment")
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            err("incorrectly-closed-comment")
                            emitCommentAndReset()
                            state = State.DATA
                        } else if (c == '-') {
                            commentBuf.append("--!")
                            state = State.COMMENT_END_DASH
                        } else {
                            commentBuf.append("--!")
                            i--
                            state = State.COMMENT
                        }
                    }
                    State.DOCTYPE -> {
                        if (i >= n) {
                            err("eof-in-doctype")
                            emitDoctype(true)
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            // ignorar
                        } else if (c == '>') {
                            err("missing-doctype-name")
                            emitDoctype(true)
                            state = State.DATA
                        } else {
                            i--
                            state = State.DOCTYPE_NAME
                        }
                    }
                    State.DOCTYPE_NAME -> {
                        if (i >= n) {
                            err("eof-in-doctype")
                            emitDoctype(true)
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (isWs(c)) {
                            state = State.DOCTYPE_SKIP
                        } else if (c == '>') {
                            emitDoctype(false)
                            state = State.DATA
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            dtName = (dtName ?: "") + '\uFFFD'
                        } else {
                            dtName = (dtName ?: "") + lowerChar(c)
                        }
                    }
                    State.DOCTYPE_SKIP -> {
                        if (i >= n) {
                            err("eof-in-doctype")
                            emitDoctype(true)
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            emitDoctype(false)
                            state = State.DATA
                        }
                    }
                    State.BOGUS_COMMENT -> {
                        if (i >= n) {
                            emitCommentAndReset()
                            tokens.add(HtmlToken.Eof)
                            break
                        }
                        val c = s[i]
                        i++
                        if (c == '>') {
                            emitCommentAndReset()
                            state = State.DATA
                        } else if (c.code == 0) {
                            err("unexpected-null-character")
                            commentBuf.append('\uFFFD')
                        } else {
                            commentBuf.append(c)
                        }
                    }
                }
            }
            return Result(tokens, errors)
        }
    }
}
