package nia.engine.html

/**
 * Tree builder HTML (T012 v2): subconjunto practico del tree construction
 * WHATWG (seccion 13.2.6). Construye un DOM propio (DomNode) a partir de
 * los tokens de HtmlTokenizer usando los modos de insercion INITIAL,
 * BEFORE_HTML, BEFORE_HEAD, IN_HEAD, AFTER_HEAD, IN_BODY, AFTER_BODY y
 * AFTER_AFTER_BODY, con reprocess y errores de parseo tipificados
 * (recuperacion en entrada malformada).
 *
 * DomPretty serializa el arbol a una forma legible (snapshots de tests).
 *
 * Fuera de alcance v1: modos de tabla (in-table y afines), template,
 * frameset y adoption agency; hr se trata como void simple (no cierra p).
 */
sealed class DomNode {
    class Document : DomNode() {
        val children = ArrayList<DomNode>()
    }

    class Doctype(val name: String?) : DomNode()

    class Comment(val data: String) : DomNode()

    class Text(var data: String) : DomNode()

    class Element(val name: String, attrs: Map<String, String>) : DomNode() {
        val attributes = LinkedHashMap<String, String>(attrs)
        val children = ArrayList<DomNode>()
    }
}

object DomPretty {

    fun stringify(node: DomNode): String = pretty(node)

    private fun pretty(node: DomNode): String = when (node) {
        is DomNode.Document ->
            if (node.children.isEmpty()) "(document)"
            else "(document " + node.children.joinToString(" ") { pretty(it) } + ")"
        is DomNode.Doctype -> if (node.name == null) "(doctype)" else "(doctype " + node.name + ")"
        is DomNode.Comment -> "(comment " + quote(node.data) + ")"
        is DomNode.Text -> quote(node.data)
        is DomNode.Element -> {
            val sb = StringBuilder("(").append(node.name)
            for ((k, v) in node.attributes) {
                sb.append(' ').append(k).append("=\"").append(v).append('"')
            }
            for (child in node.children) {
                sb.append(' ').append(pretty(child))
            }
            sb.append(')').toString()
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\t' -> sb.append("\\t")
                ch == '\r' -> sb.append("\\r")
                ch.code < 32 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}

object HtmlTreeBuilder {

    /** Resultado del parseo: arbol (document) y errores de parseo acumulados. */
    data class Result(val document: DomNode.Document, val parseErrors: List<String>)

    private val VOID = setOf(
        "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img",
        "input", "keygen", "link", "meta", "param", "source", "track", "wbr"
    )

    private val P_CLOSERS = setOf(
        "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir",
        "div", "dl", "dd", "dt", "fieldset", "figcaption", "figure", "footer", "header",
        "hgroup", "main", "menu", "nav", "ol", "p", "section", "summary", "ul",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "listing", "form", "li", "table",
        "hr", "xmp", "plaintext"
    )

    private val RAWN = setOf(
        "title", "style", "script", "textarea", "xmp", "iframe", "noembed", "noframes", "noscript"
    )

    private val HEAD_VOID = setOf("base", "basefont", "bgsound", "link", "meta")

    private val HEADING = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private val IMPLIED = setOf(
        "dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc",
        "tbody", "td", "tfoot", "th", "thead", "tr"
    )

    private val SCOPE_STOP = setOf(
        "applet", "caption", "html", "table", "td", "th", "marquee", "object", "template"
    )

    fun parse(input: String): Result {
        val tk = HtmlTokenizer.tokenize(input)
        return Machine(tk.tokens, tk.parseErrors).run()
    }

    private class Machine(private val tokens: List<HtmlToken>, initErrors: List<String>) {

        private enum class Mode {
            INITIAL, BEFORE_HTML, BEFORE_HEAD, IN_HEAD, AFTER_HEAD,
            IN_BODY, AFTER_BODY, AFTER_AFTER_BODY
        }

        private val errors = ArrayList<String>(initErrors)
        private val document = DomNode.Document()
        private val stack = ArrayList<DomNode.Element>()
        private var mode = Mode.INITIAL
        private var pos = 0
        private var reprocess = false
        private var stopped = false

        fun run(): Result {
            while (pos < tokens.size && !stopped) {
                val tok = tokens[pos]
                pos++
                reprocess = true
                while (reprocess && !stopped) {
                    reprocess = false
                    handle(tok)
                }
            }
            return Result(document, errors)
        }

        private fun cur(): DomNode.Element = stack.last()

        private fun err(message: String) {
            errors.add(message)
        }

        private fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

        private fun allWs(s: String): Boolean = s.all { isWs(it) }

        private fun insertEl(name: String, attrs: Map<String, String>) {
            val el = DomNode.Element(name, attrs)
            cur().children.add(el)
            stack.add(el)
        }

        private fun insertVoidEl(name: String, attrs: Map<String, String>) {
            cur().children.add(DomNode.Element(name, attrs))
        }

        private fun appendComment(data: String) {
            cur().children.add(DomNode.Comment(data))
        }

        private fun insertText(text: String) {
            val parent = cur()
            val last = parent.children.lastOrNull()
            if (last is DomNode.Text) {
                last.data += text
            } else {
                parent.children.add(DomNode.Text(text))
            }
        }

        private fun inScope(target: String, extra: Set<String> = emptySet()): Boolean {
            for (k in stack.size - 1 downTo 0) {
                val nm = stack[k].name
                if (nm == target) return true
                if (nm in SCOPE_STOP || nm in extra) return false
            }
            return false
        }

        private fun popUntil(name: String) {
            while (stack.isNotEmpty()) {
                val el = stack.removeAt(stack.size - 1)
                if (el.name == name) break
            }
        }

        private fun popUntilDdDt() {
            while (stack.isNotEmpty()) {
                val el = stack.removeAt(stack.size - 1)
                if (el.name == "dd" || el.name == "dt") break
            }
        }

        private fun closeP() {
            if (inScope("p", setOf("button"))) popUntil("p")
        }

        private fun popHead() {
            while (stack.isNotEmpty()) {
                val el = stack.removeAt(stack.size - 1)
                if (el.name == "head") break
            }
        }

        private fun findEl(name: String): DomNode.Element? {
            for (k in stack.size - 1 downTo 0) {
                if (stack[k].name == name) return stack[k]
            }
            return null
        }

        private fun mergeAttrs(el: DomNode.Element, attrs: Map<String, String>) {
            for ((k, v) in attrs) {
                if (k !in el.attributes) el.attributes[k] = v
            }
        }

        private fun genericEnd(name: String) {
            for (k in stack.size - 1 downTo 0) {
                if (stack[k].name == name) {
                    if (cur().name != name) {
                        for (j in stack.size - 1 downTo k + 1) {
                            if (stack[j].name !in IMPLIED) {
                                err("mismatched-end-tag")
                                break
                            }
                        }
                    }
                    popUntil(name)
                    return
                }
            }
            err("unexpected-end-tag")
        }

        private fun createHtml(attrs: Map<String, String>) {
            val el = DomNode.Element("html", attrs)
            document.children.add(el)
            stack.add(el)
        }

        private fun createHead() {
            val el = DomNode.Element("head", emptyMap())
            cur().children.add(el)
            stack.add(el)
        }

        private fun createBody(attrs: Map<String, String>) {
            val el = DomNode.Element("body", attrs)
            cur().children.add(el)
            stack.add(el)
        }

        private fun handle(tok: HtmlToken) {
            when (mode) {
                Mode.INITIAL -> when (tok) {
                    is HtmlToken.Comment -> document.children.add(DomNode.Comment(tok.data))
                    is HtmlToken.Doctype -> document.children.add(DomNode.Doctype(tok.name))
                    is HtmlToken.Data -> if (!allWs(tok.text)) {
                        mode = Mode.BEFORE_HTML
                        reprocess = true
                    }
                    else -> {
                        mode = Mode.BEFORE_HTML
                        reprocess = true
                    }
                }
                Mode.BEFORE_HTML -> when (tok) {
                    is HtmlToken.Comment -> document.children.add(DomNode.Comment(tok.data))
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> if (!allWs(tok.text)) {
                        createHtml(emptyMap())
                        mode = Mode.BEFORE_HEAD
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> if (tok.name == "html") {
                        createHtml(tok.attributes)
                        mode = Mode.BEFORE_HEAD
                    } else {
                        createHtml(emptyMap())
                        mode = Mode.BEFORE_HEAD
                        reprocess = true
                    }
                    else -> {
                        createHtml(emptyMap())
                        mode = Mode.BEFORE_HEAD
                        reprocess = true
                    }
                }
                Mode.BEFORE_HEAD -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> if (!allWs(tok.text)) {
                        createHead()
                        mode = Mode.IN_HEAD
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> when {
                        tok.name == "html" -> {
                            err("unexpected-html")
                            mergeAttrs(stack[0], tok.attributes)
                        }
                        tok.name == "head" -> {
                            createHead()
                            mode = Mode.IN_HEAD
                        }
                        else -> {
                            createHead()
                            mode = Mode.IN_HEAD
                            reprocess = true
                        }
                    }
                    else -> {
                        createHead()
                        mode = Mode.IN_HEAD
                        reprocess = true
                    }
                }
                Mode.IN_HEAD -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> when {
                        cur().name != "head" -> insertText(tok.text)
                        allWs(tok.text) -> insertText(tok.text)
                        else -> {
                            popHead()
                            mode = Mode.AFTER_HEAD
                            reprocess = true
                        }
                    }
                    is HtmlToken.StartTag -> when {
                        tok.name == "html" -> {
                            err("unexpected-html")
                            mergeAttrs(stack[0], tok.attributes)
                        }
                        tok.name in HEAD_VOID -> insertVoidEl(tok.name, tok.attributes)
                        tok.name == "head" -> err("unexpected-start-head")
                        tok.name == "template" -> err("v1-template-no-soportado")
                        tok.name in RAWN -> insertEl(tok.name, tok.attributes)
                        else -> {
                            popHead()
                            mode = Mode.AFTER_HEAD
                            reprocess = true
                        }
                    }
                    is HtmlToken.EndTag -> when {
                        tok.name == "head" -> {
                            popHead()
                            mode = Mode.AFTER_HEAD
                        }
                        tok.name == "body" || tok.name == "html" || tok.name == "br" -> {
                            popHead()
                            mode = Mode.AFTER_HEAD
                            reprocess = true
                        }
                        else -> genericEnd(tok.name)
                    }
                    else -> {
                        popHead()
                        mode = Mode.AFTER_HEAD
                        reprocess = true
                    }
                }
                Mode.AFTER_HEAD -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> if (allWs(tok.text)) insertText(tok.text) else {
                        createBody(emptyMap())
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> when {
                        tok.name == "html" -> {
                            err("unexpected-html")
                            mergeAttrs(stack[0], tok.attributes)
                        }
                        tok.name == "body" -> {
                            createBody(tok.attributes)
                            mode = Mode.IN_BODY
                        }
                        tok.name == "frameset" -> err("v1-frameset-no-soportado")
                        else -> {
                            createBody(emptyMap())
                            mode = Mode.IN_BODY
                            reprocess = true
                        }
                    }
                    else -> {
                        createBody(emptyMap())
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                }
                Mode.IN_BODY -> when (tok) {
                    is HtmlToken.Data -> insertText(tok.text)
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Eof -> {
                        if (stack.size > 2) err("eof-in-body")
                        stopped = true
                    }
                    is HtmlToken.StartTag -> when (tok.name) {
                        "html" -> {
                            err("unexpected-html")
                            mergeAttrs(stack[0], tok.attributes)
                        }
                        "head" -> err("unexpected-start-head")
                        "body" -> {
                            err("unexpected-start-body")
                            findEl("body")?.let { mergeAttrs(it, tok.attributes) }
                        }
                        in VOID -> insertVoidEl(tok.name, tok.attributes)
                        else -> {
                            if (tok.selfClosing) err("non-void-html-element-start-tag-with-self-closing-flag")
                            when {
                                tok.name == "p" -> {
                                    closeP()
                                    insertEl(tok.name, tok.attributes)
                                }
                                tok.name == "li" -> {
                                    closeP()
                                    if (inScope("li", setOf("ol", "ul"))) popUntil("li")
                                    insertEl(tok.name, tok.attributes)
                                }
                                tok.name == "dd" || tok.name == "dt" -> {
                                    closeP()
                                    if (inScope("dd") || inScope("dt")) popUntilDdDt()
                                    insertEl(tok.name, tok.attributes)
                                }
                                tok.name in HEADING -> {
                                    closeP()
                                    if (cur().name in HEADING) {
                                        err("unexpected-heading-nesting")
                                        stack.removeAt(stack.size - 1)
                                    }
                                    insertEl(tok.name, tok.attributes)
                                }
                                tok.name in P_CLOSERS -> {
                                    closeP()
                                    insertEl(tok.name, tok.attributes)
                                }
                                else -> insertEl(tok.name, tok.attributes)
                            }
                        }
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "body" -> {
                            if (inScope("body")) {
                                for (j in 2 until stack.size) {
                                    if (stack[j].name !in IMPLIED) {
                                        err("unexpected-content-open")
                                        break
                                    }
                                }
                                mode = Mode.AFTER_BODY
                            } else {
                                err("unexpected-end-body")
                            }
                        }
                        "html" -> if (inScope("body")) {
                            mode = Mode.AFTER_BODY
                            reprocess = true
                        } else {
                            err("unexpected-end-html")
                        }
                        "p" -> if (inScope("p", setOf("button"))) popUntil("p") else err("unexpected-end-p")
                        in HEADING -> {
                            var found = -1
                            for (k in stack.size - 1 downTo 0) {
                                if (stack[k].name in HEADING) {
                                    found = k
                                    break
                                }
                            }
                            if (found >= 0) {
                                for (j in stack.size - 1 downTo found + 1) {
                                    if (stack[j].name !in IMPLIED) {
                                        err("mismatched-end-tag")
                                        break
                                    }
                                }
                                while (stack.size > found) stack.removeAt(stack.size - 1)
                            } else {
                                err("unexpected-end-heading")
                            }
                        }
                        "li" -> if (inScope("li", setOf("ol", "ul"))) popUntil("li") else err("unexpected-end-li")
                        "dd", "dt" -> if (inScope(tok.name)) popUntil(tok.name) else err("unexpected-end-" + tok.name)
                        else -> genericEnd(tok.name)
                    }
                    else -> {
                        // El resto de tokens no aplica en IN_BODY.
                    }
                }
                Mode.AFTER_BODY -> when (tok) {
                    is HtmlToken.Comment -> stack[0].children.add(DomNode.Comment(tok.data))
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> {
                        err("unexpected-char-after-body")
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> if (tok.name == "html") {
                        err("unexpected-html")
                        mergeAttrs(stack[0], tok.attributes)
                    } else {
                        err("unexpected-token-after-body")
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    is HtmlToken.EndTag -> if (tok.name == "html") {
                        mode = Mode.AFTER_AFTER_BODY
                    } else {
                        err("unexpected-token-after-body")
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    else -> stopped = true
                }
                Mode.AFTER_AFTER_BODY -> when (tok) {
                    is HtmlToken.Comment -> document.children.add(DomNode.Comment(tok.data))
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> {
                        if (!allWs(tok.text)) err("unexpected-char-after-after-body")
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> if (tok.name == "html") {
                        err("unexpected-html")
                        mergeAttrs(stack[0], tok.attributes)
                    } else {
                        err("unexpected-token-after-after-body")
                        mode = Mode.IN_BODY
                        reprocess = true
                    }
                    else -> stopped = true
                }
            }
        }
    }
}
