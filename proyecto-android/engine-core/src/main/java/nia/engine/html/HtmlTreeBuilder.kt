package nia.engine.html

/**
 * Tree builder HTML (T012 v3): subconjunto practico del tree construction
 * WHATWG (seccion 13.2.6). Construye un DOM propio (DomNode) a partir de
 * los tokens de HtmlTokenizer usando los modos de insercion INITIAL,
 * BEFORE_HTML, BEFORE_HEAD, IN_HEAD, AFTER_HEAD, IN_BODY, AFTER_BODY,
 * AFTER_AFTER_BODY y los modos de tabla IN_TABLE, IN_CAPTION,
 * IN_COLUMN_GROUP, IN_TABLE_BODY, IN_ROW, IN_CELL, mas IN_TEMPLATE (con
 * pila de modos de template), con reprocess y errores de parseo
 * tipificados (recuperacion en entrada malformada): tbody implicito,
 * cierre implicito de celdas/filas y foster parenting (lo ajeno a la
 * tabla se inserta antes de la tabla mas reciente).
 *
 * DomPretty serializa el arbol a una forma legible (snapshots de tests).
 *
 * Fuera de alcance: frameset y adoption agency. Desviacion documentada:
 * el contenido de template vive como hijos directos del elemento (sin
 * DocumentFragment separado); hr cierra p.
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
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "listing", "form", "li",
        "xmp", "plaintext"
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

    private val TABLE_SCOPE_STOP = setOf("html", "table", "template")

    private val TABLE_FOSTER = setOf("table", "tbody", "tfoot", "thead", "tr")

    private val STRAY_IN_BODY = setOf(
        "caption", "col", "colgroup", "frame", "tbody", "td", "tfoot", "th", "thead", "tr"
    )

    fun parse(input: String): Result {
        val tk = HtmlTokenizer.tokenize(input)
        return Machine(tk.tokens, tk.parseErrors).run()
    }

    private class Machine(private val tokens: List<HtmlToken>, initErrors: List<String>) {

        private enum class Mode {
            INITIAL, BEFORE_HTML, BEFORE_HEAD, IN_HEAD, AFTER_HEAD,
            IN_BODY, AFTER_BODY, AFTER_AFTER_BODY,
            IN_TABLE, IN_CAPTION, IN_COLUMN_GROUP, IN_TABLE_BODY,
            IN_ROW, IN_CELL, IN_TEMPLATE
        }

        private val errors = ArrayList<String>(initErrors)
        private val document = DomNode.Document()
        private val stack = ArrayList<DomNode.Element>()
        private var mode = Mode.INITIAL
        private val templateModes = ArrayList<Mode>()
        private var foster = false
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

        private fun insertBeforeTable(node: DomNode) {
            var ti = -1
            for (k in stack.size - 1 downTo 0) {
                if (stack[k].name == "table") {
                    ti = k
                    break
                }
            }
            if (ti >= 0) {
                val tbl = stack[ti]
                for (k in ti - 1 downTo 0) {
                    val parent = stack[k]
                    val idx = parent.children.indexOf(tbl)
                    if (idx >= 0) {
                        if (node is DomNode.Text && idx > 0) {
                            val prev = parent.children[idx - 1]
                            if (prev is DomNode.Text) {
                                prev.data += node.data
                                return
                            }
                        }
                        parent.children.add(idx, node)
                        return
                    }
                }
            }
            cur().children.add(node)
        }

        private fun insertNode(node: DomNode) {
            if (foster && cur().name in TABLE_FOSTER) {
                insertBeforeTable(node)
            } else {
                cur().children.add(node)
            }
        }

        private fun insertEl(name: String, attrs: Map<String, String>) {
            val el = DomNode.Element(name, attrs)
            insertNode(el)
            stack.add(el)
        }

        private fun insertVoidEl(name: String, attrs: Map<String, String>) {
            insertNode(DomNode.Element(name, attrs))
        }

        private fun appendComment(data: String) {
            cur().children.add(DomNode.Comment(data))
        }

        private fun insertText(text: String) {
            if (foster && cur().name in TABLE_FOSTER) {
                insertBeforeTable(DomNode.Text(text))
                return
            }
            insertTextIn(text)
        }

        private fun insertTextIn(text: String) {
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

        private fun inTableScope(target: String): Boolean {
            for (k in stack.size - 1 downTo 0) {
                val nm = stack[k].name
                if (nm == target) return true
                if (nm in TABLE_SCOPE_STOP) return false
            }
            return false
        }

        private fun inTableScopeAny(targets: Set<String>): Boolean {
            for (k in stack.size - 1 downTo 0) {
                val nm = stack[k].name
                if (nm in targets) return true
                if (nm in TABLE_SCOPE_STOP) return false
            }
            return false
        }

        private fun popUntilAny(names: Set<String>) {
            while (stack.isNotEmpty()) {
                val el = stack.removeAt(stack.size - 1)
                if (el.name in names) break
            }
        }

        private fun clearToTableBodyContext() {
            while (stack.isNotEmpty()) {
                val nm = stack.last().name
                if (nm == "tbody" || nm == "tfoot" || nm == "thead" ||
                    nm == "table" || nm == "template" || nm == "html"
                ) break
                stack.removeAt(stack.size - 1)
            }
        }

        private fun clearToRowContext() {
            while (stack.isNotEmpty()) {
                val nm = stack.last().name
                if (nm == "tr" || nm == "table" || nm == "template" || nm == "html") break
                stack.removeAt(stack.size - 1)
            }
        }

        private fun popRow() {
            if (inTableScope("tr")) {
                clearToRowContext()
                if (stack.isNotEmpty() && stack.last().name == "tr") {
                    stack.removeAt(stack.size - 1)
                }
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

        private fun resetMode() {
            if (templateModes.isNotEmpty()) {
                mode = templateModes.last()
                return
            }
            for (k in stack.size - 1 downTo 0) {
                when (stack[k].name) {
                    "template" -> {
                        mode = Mode.IN_TEMPLATE
                        return
                    }
                    "caption" -> {
                        mode = Mode.IN_CAPTION
                        return
                    }
                    "colgroup" -> {
                        mode = Mode.IN_COLUMN_GROUP
                        return
                    }
                    "table" -> {
                        mode = Mode.IN_TABLE
                        return
                    }
                    "tbody", "thead", "tfoot" -> {
                        mode = Mode.IN_TABLE_BODY
                        return
                    }
                    "tr" -> {
                        mode = Mode.IN_ROW
                        return
                    }
                    "td", "th" -> {
                        mode = Mode.IN_CELL
                        return
                    }
                    "body" -> {
                        mode = Mode.IN_BODY
                        return
                    }
                    "head" -> {
                        mode = Mode.IN_HEAD
                        return
                    }
                }
            }
            mode = Mode.IN_BODY
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

        private fun processInBody(tok: HtmlToken) {
            when (tok) {
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
                    "table" -> {
                        closeP()
                        insertEl("table", tok.attributes)
                        mode = Mode.IN_TABLE
                    }
                    "hr" -> {
                        closeP()
                        insertVoidEl("hr", tok.attributes)
                    }
                    in STRAY_IN_BODY -> err("unexpected-table-tag-in-body")
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
        }

        private fun handle(tok: HtmlToken) {
            if (mode == Mode.IN_HEAD || mode == Mode.IN_BODY || mode == Mode.IN_TABLE ||
                mode == Mode.IN_CAPTION || mode == Mode.IN_COLUMN_GROUP ||
                mode == Mode.IN_TABLE_BODY || mode == Mode.IN_ROW || mode == Mode.IN_CELL ||
                mode == Mode.IN_TEMPLATE
            ) {
                if (tok is HtmlToken.StartTag && tok.name == "template") {
                    insertEl("template", tok.attributes)
                    templateModes.add(Mode.IN_TEMPLATE)
                    mode = Mode.IN_TEMPLATE
                    return
                }
                if (tok is HtmlToken.EndTag && tok.name == "template") {
                    if (findEl("template") == null) {
                        err("unexpected-end-template")
                    } else {
                        popUntil("template")
                        if (templateModes.isNotEmpty()) {
                            templateModes.removeAt(templateModes.size - 1)
                        }
                        resetMode()
                    }
                    return
                }
            }
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
                Mode.IN_BODY -> processInBody(tok)
                Mode.IN_TABLE -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> if (allWs(tok.text)) {
                        insertTextIn(tok.text)
                    } else {
                        err("foster-parenting")
                        foster = true
                        insertText(tok.text)
                        foster = false
                    }
                    is HtmlToken.Eof -> processInBody(tok)
                    is HtmlToken.StartTag -> when (tok.name) {
                        "caption" -> {
                            insertEl("caption", tok.attributes)
                            mode = Mode.IN_CAPTION
                        }
                        "colgroup" -> {
                            insertEl("colgroup", tok.attributes)
                            mode = Mode.IN_COLUMN_GROUP
                        }
                        "col" -> {
                            insertEl("colgroup", emptyMap())
                            mode = Mode.IN_COLUMN_GROUP
                            reprocess = true
                        }
                        "tbody", "tfoot", "thead" -> {
                            insertEl(tok.name, tok.attributes)
                            mode = Mode.IN_TABLE_BODY
                        }
                        "td", "th", "tr" -> {
                            insertEl("tbody", emptyMap())
                            mode = Mode.IN_TABLE_BODY
                            reprocess = true
                        }
                        "table" -> {
                            err("unexpected-start-table")
                            if (inTableScope("table")) {
                                popUntil("table")
                                resetMode()
                                reprocess = true
                            }
                        }
                        "style", "script", "title" -> insertEl(tok.name, tok.attributes)
                        "input" -> if (tok.attributes["type"]?.lowercase() == "hidden") {
                            err("unexpected-input-in-table")
                            insertVoidEl("input", tok.attributes)
                        } else {
                            err("foster-parenting")
                            foster = true
                            processInBody(tok)
                            foster = false
                        }
                        "form" -> {
                            err("unexpected-form-in-table")
                            insertVoidEl("form", tok.attributes)
                        }
                        else -> {
                            err("foster-parenting")
                            foster = true
                            processInBody(tok)
                            foster = false
                        }
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "table" -> if (inTableScope("table")) {
                            popUntil("table")
                            resetMode()
                        } else {
                            err("unexpected-end-table")
                        }
                        "body", "caption", "col", "colgroup", "html", "tbody", "td",
                        "tfoot", "th", "thead", "tr" -> err("unexpected-end-tag-in-table")
                        else -> {
                            err("foster-parenting")
                            foster = true
                            processInBody(tok)
                            foster = false
                        }
                    }
                    else -> {
                        // Sin mas tokens aplicables en IN_TABLE.
                    }
                }
                Mode.IN_CAPTION -> when (tok) {
                    is HtmlToken.EndTag -> when (tok.name) {
                        "caption" -> if (inTableScope("caption")) {
                            popUntil("caption")
                            resetMode()
                        } else {
                            err("unexpected-end-caption")
                        }
                        "table" -> if (inTableScope("table")) {
                            popUntil("table")
                            resetMode()
                        } else {
                            err("unexpected-end-table")
                        }
                        "body", "col", "colgroup", "html", "tbody", "td", "tfoot",
                        "th", "thead", "tr" -> {
                            err("unexpected-end-in-caption")
                            processInBody(tok)
                        }
                        else -> processInBody(tok)
                    }
                    else -> processInBody(tok)
                }
                Mode.IN_COLUMN_GROUP -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> if (allWs(tok.text)) {
                        insertTextIn(tok.text)
                    } else {
                        if (cur().name == "colgroup") {
                            stack.removeAt(stack.size - 1)
                            mode = Mode.IN_TABLE
                        } else {
                            err("unexpected-token-in-colgroup")
                            mode = Mode.IN_TABLE
                        }
                        reprocess = true
                    }
                    is HtmlToken.Eof -> {
                        if (cur().name == "colgroup") {
                            stack.removeAt(stack.size - 1)
                            mode = Mode.IN_TABLE
                        } else {
                            err("unexpected-token-in-colgroup")
                            mode = Mode.IN_TABLE
                        }
                        reprocess = true
                    }
                    is HtmlToken.StartTag -> when (tok.name) {
                        "html" -> {
                            err("unexpected-html")
                            mergeAttrs(stack[0], tok.attributes)
                        }
                        "colgroup" -> err("unexpected-colgroup")
                        "col" -> insertVoidEl("col", tok.attributes)
                        else -> {
                            if (cur().name == "colgroup") {
                                stack.removeAt(stack.size - 1)
                                mode = Mode.IN_TABLE
                            } else {
                                err("unexpected-token-in-colgroup")
                                mode = Mode.IN_TABLE
                            }
                            reprocess = true
                        }
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "colgroup" -> if (cur().name == "colgroup") {
                            stack.removeAt(stack.size - 1)
                            mode = Mode.IN_TABLE
                        } else {
                            err("unexpected-end-colgroup")
                        }
                        "col" -> err("unexpected-end-col")
                        else -> {
                            if (cur().name == "colgroup") {
                                stack.removeAt(stack.size - 1)
                                mode = Mode.IN_TABLE
                            } else {
                                err("unexpected-token-in-colgroup")
                                mode = Mode.IN_TABLE
                            }
                            reprocess = true
                        }
                    }
                }
                Mode.IN_TABLE_BODY -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.StartTag -> when (tok.name) {
                        "tr" -> {
                            clearToTableBodyContext()
                            insertEl("tr", tok.attributes)
                            mode = Mode.IN_ROW
                        }
                        "td", "th" -> {
                            err("unexpected-cell-in-table-body")
                            clearToTableBodyContext()
                            insertEl("tr", emptyMap())
                            mode = Mode.IN_ROW
                            reprocess = true
                        }
                        "caption", "col", "colgroup", "tbody", "tfoot", "thead" -> {
                            if (inTableScopeAny(setOf("tbody", "tfoot", "thead"))) {
                                clearToTableBodyContext()
                                stack.removeAt(stack.size - 1)
                                mode = Mode.IN_TABLE
                                reprocess = true
                            } else {
                                err("unexpected-table-tag")
                            }
                        }
                        else -> {
                            mode = Mode.IN_TABLE
                            reprocess = true
                        }
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "tbody", "tfoot", "thead" -> if (inTableScopeAny(setOf("tbody", "tfoot", "thead"))) {
                            clearToTableBodyContext()
                            if (stack.last().name == tok.name) {
                                stack.removeAt(stack.size - 1)
                            }
                            mode = Mode.IN_TABLE
                        } else {
                            err("unexpected-end-section")
                        }
                        "body", "caption", "col", "colgroup", "html", "td", "th", "tr" ->
                            err("unexpected-end-tag-in-table")
                        else -> {
                            mode = Mode.IN_TABLE
                            reprocess = true
                        }
                    }
                    else -> {
                        mode = Mode.IN_TABLE
                        reprocess = true
                    }
                }
                Mode.IN_ROW -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.StartTag -> when (tok.name) {
                        "td", "th" -> {
                            clearToRowContext()
                            insertEl(tok.name, tok.attributes)
                            mode = Mode.IN_CELL
                        }
                        "tr" -> {
                            err("unexpected-tr-in-row")
                            popRow()
                            mode = Mode.IN_TABLE_BODY
                            reprocess = true
                        }
                        "caption", "col", "colgroup", "tbody", "tfoot", "thead" -> {
                            if (inTableScope("tr")) {
                                popRow()
                                mode = Mode.IN_TABLE_BODY
                                reprocess = true
                            } else {
                                err("unexpected-table-tag")
                            }
                        }
                        else -> {
                            mode = Mode.IN_TABLE_BODY
                            reprocess = true
                        }
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "tr" -> if (inTableScope("tr")) {
                            popRow()
                            mode = Mode.IN_TABLE_BODY
                        } else {
                            err("unexpected-end-tr")
                        }
                        "tbody", "tfoot", "thead" -> if (inTableScopeAny(setOf("tbody", "tfoot", "thead"))) {
                            popRow()
                            mode = Mode.IN_TABLE_BODY
                            reprocess = true
                        } else {
                            err("unexpected-end-section")
                        }
                        "body", "caption", "col", "colgroup", "html", "td", "th" ->
                            err("unexpected-end-tag-in-row")
                        else -> {
                            mode = Mode.IN_TABLE_BODY
                            reprocess = true
                        }
                    }
                    else -> {
                        mode = Mode.IN_TABLE_BODY
                        reprocess = true
                    }
                }
                Mode.IN_CELL -> when (tok) {
                    is HtmlToken.StartTag -> if (
                        tok.name == "caption" || tok.name == "col" || tok.name == "colgroup" ||
                        tok.name == "tbody" || tok.name == "td" || tok.name == "tfoot" ||
                        tok.name == "th" || tok.name == "thead" || tok.name == "tr"
                    ) {
                        if (inTableScopeAny(setOf("td", "th"))) {
                            popUntilAny(setOf("td", "th"))
                            mode = Mode.IN_ROW
                            reprocess = true
                        } else {
                            err("unexpected-table-tag-in-cell")
                        }
                    } else {
                        processInBody(tok)
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "td", "th" -> if (inTableScopeAny(setOf("td", "th"))) {
                            popUntilAny(setOf("td", "th"))
                            mode = Mode.IN_ROW
                        } else {
                            err("unexpected-end-cell")
                        }
                        "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr", "table" -> {
                            if (inTableScopeAny(setOf("td", "th"))) {
                                popUntilAny(setOf("td", "th"))
                                mode = if (tok.name == "table") Mode.IN_TABLE else Mode.IN_ROW
                                reprocess = true
                            } else {
                                err("unexpected-end-table-tag-in-cell")
                            }
                        }
                        else -> processInBody(tok)
                    }
                    else -> processInBody(tok)
                }
                Mode.IN_TEMPLATE -> when (tok) {
                    is HtmlToken.Comment -> appendComment(tok.data)
                    is HtmlToken.Doctype -> err("unexpected-doctype")
                    is HtmlToken.Data -> insertText(tok.text)
                    is HtmlToken.Eof -> {
                        if (findEl("template") == null) {
                            stopped = true
                        } else {
                            err("eof-in-template")
                            popUntil("template")
                            if (templateModes.isNotEmpty()) {
                                templateModes.removeAt(templateModes.size - 1)
                            }
                            resetMode()
                            reprocess = true
                        }
                    }
                    is HtmlToken.StartTag -> {
                        val target: Mode = when (tok.name) {
                            "caption", "colgroup", "tbody", "tfoot", "thead" -> Mode.IN_TABLE
                            "col" -> Mode.IN_COLUMN_GROUP
                            "tr" -> Mode.IN_TABLE_BODY
                            "td", "th" -> Mode.IN_ROW
                            else -> Mode.IN_BODY
                        }
                        if (templateModes.isNotEmpty()) {
                            templateModes[templateModes.size - 1] = target
                        } else {
                            templateModes.add(target)
                        }
                        mode = target
                        reprocess = true
                    }
                    is HtmlToken.EndTag -> when (tok.name) {
                        "body", "br", "html" -> processInBody(tok)
                        else -> err("unexpected-end-in-template")
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
