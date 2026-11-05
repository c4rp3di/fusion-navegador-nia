package app.nia.navegador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import nia.contracts.ContractsVersion
import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.devtools.DevToolsModel
import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import nia.engine.layout.LayoutResult
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder

/**
 * Adaptador Android del motor (v2, R0023): ejecuta el pipeline completo
 * (HTML -> CSS -> layout -> display list) en el init y dibuja la escena
 * escalando el viewport logico de 360px al ancho de pantalla. La demo usa
 * las novedades v2: padding de body, bordes de h1 y de la tarjeta con
 * border-color, pseudo-clase :first-child y selector de hermano p+p. El
 * toque se convierte a coordenadas de documento y se resuelve con hitTest
 * del motor; el op topmost queda resaltado. Sin WebView y sin layout XML.
 *
 * T017 v3 (R0072): el toque tambien SELECCIONA el nodo del op (resuelto a
 * handle con DevToolsModel.handleOf sobre el documento registrado) y el
 * nodeId queda en DevToolsState para el panel DevTools.
 *
 * T017 v4 (R0074, RES-0042): la pagina re-renderiza con las reglas CSS del
 * usuario escritas en el panel (rebuild por revision de DevToolsState) y
 * el nodo seleccionado queda con resaltado persistente en cian, distinto
 * del resaltado amarillo transitorio del toque.
 *
 * T035 v1 (R0088, RES-0049): IME, lector de pantalla y carga de HTML.
 * El input del documento tocado se enfoca y edita con un IME real: el
 * editor (SpannableStringBuilder via BaseInputConnection) es la verdad
 * mientras hay foco y cada mutacion se vuelca al atributo value del DOM
 * del motor (el mismo que JS lee como input.value, T024/T027); el value se
 * pinta dentro de la caja del input (todo cromo: el layout no fija heights)
 * con caret al final en el enfocado. El lector de pantalla ve el arbol del
 * documento: AccessibilityNodeProvider con un nodo virtual por nodeId del
 * DOM (estables en pre-orden), texto de los Text, value y rol de los
 * inputs, bounds de la caja del layout y acciones de foco/edicion. load()
 * re-parsea un HTML (SAF) sumando los <style> del documento al CSS de la
 * demo. Exclusiones v1: caret fijo (sin parpadeo), cursor siempre al final
 * y una sola linea; los inputs abiertos por SAF necesitan display:block
 * para ser tocables.
 *
 * R0167 (fix smoke T067-A): BaseInputConnection con fullEditor=true
 * para que el IME entregue commitText; con false el IME era dummy y las
 * letras llegaban como KeyEvent que PaintView no procesaba.
 */
class PaintView(context: Context) : View(context) {

    private val viewport = 360.0

    private var document: DomDocument = DomDocument.parse(DemoPage.HTML)
    private var devtoolsModel: DevToolsModel = DevToolsModel(document, DemoPage.CSS)
    private var root: DomNode = document.nodeOf(document.rootHandle())
    private var rootDoc: DomNode.Document = root as DomNode.Document
    private lateinit var scene: PaintScene
    private var layout: LayoutResult? = null
    private var loadedHtml: String = DemoPage.HTML

    private var builtRevision = -1

    private val fillPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val footnote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(127, 224, 188)
        textSize = 26f
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val selected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 255, 255)
        style = Paint.Style.FILL
    }

    private var lastHit: PaintOp? = null

    /** T035 v1 (IME): input del documento enfocado; el editor es la
     * verdad mientras el IME esta activo y se vuelca al value del DOM. */
    private var focusedInput: DomNode.Element? = null
    private val editor = SpannableStringBuilder()
    private var imm: InputMethodManager? = null
    private val screenLoc = IntArray(2)

    init {
        rebuild()
    }

    /** T035 v1 (SAF): parsea el HTML dado y re-renderiza; los <style> del
     * propio documento se suman al CSS de la demo. */
    fun load(html: String) {
        loadedHtml = html
        document = DomDocument.parse(html)
        devtoolsModel = DevToolsModel(document, DemoPage.CSS)
        root = document.nodeOf(document.rootHandle())
        rootDoc = root as DomNode.Document
        DevToolsState.selectedNodeId = null
        focusedInput = null
        editor.clear()
        rebuild()
    }

    /** HTML fuente cargado (SAF: Guardar escribe esto, no el DOM mutado). */
    fun currentHtml(): String = loadedHtml

    /** Hijos del handle o vacio si el nodo no es contenedor (patron de
     * JsDomBindings para no depender del tipo del nodo). */
    private fun childrenOrEmpty(h: DomHandle): List<DomHandle> =
        try {
            document.childrenOf(h)
        } catch (e: DomException) {
            emptyList()
        }

    /** Inputs del documento en orden documental (T035). */
    private fun inputs(): List<DomNode.Element> {
        val out = ArrayList<DomNode.Element>()
        fun go(h: DomHandle) {
            val n = document.nodeOf(h)
            if (n is DomNode.Element && n.name.equals("input", ignoreCase = true)) out.add(n)
            for (k in childrenOrEmpty(h)) go(k)
        }
        go(document.rootHandle())
        return out
    }

    /** Value del input del DOM (patron de __valueGet de JsDomBindings). */
    private fun inputValue(input: DomNode.Element): String {
        val h = devtoolsModel.handleOf(input) ?: return ""
        return try {
            document.attributeOf(h, "value") ?: ""
        } catch (e: DomException) {
            ""
        }
    }

    /** Concatena el texto de los <style> del documento (v1: el CSS de la
     * demo sigue de base y el del documento se suma). */
    private fun styleTextOf(): String {
        val sb = StringBuilder()
        fun go(h: DomHandle) {
            val n = document.nodeOf(h)
            if (n is DomNode.Element && n.name.equals("style", ignoreCase = true)) {
                sb.append('\n').append(document.textOf(h))
            }
            for (k in childrenOrEmpty(h)) go(k)
        }
        go(document.rootHandle())
        return sb.toString()
    }

    /** Recalcula cascada+layout+escena con las reglas del usuario (v4). */
    private fun rebuild() {
        var css = DemoPage.CSS + styleTextOf()
        if (DevToolsState.userCss.isNotEmpty()) css = css + "\n" + DevToolsState.userCss
        val computed = CssCascade.compute(root, CssStylesheet.parse(css))
        val laid = LayoutEngine.layout(rootDoc, computed, viewport)
        layout = laid
        scene = PaintSceneBuilder.build(rootDoc, computed, laid)
        builtRevision = DevToolsState.revision
    }

    override fun onDraw(canvas: Canvas) {
        if (DevToolsState.revision != builtRevision) rebuild()
        canvas.drawColor(Color.rgb(20, 20, 34))
        val scale = width / viewport.toFloat()
        for (op in scene.ops) {
            when (op) {
                is PaintOp.DrawRect -> {
                    fillPaint.color = parseColor(op.color)
                    canvas.drawRect(
                        (op.x * scale).toFloat(),
                        (op.y * scale).toFloat(),
                        ((op.x + op.width) * scale).toFloat(),
                        ((op.y + op.height) * scale).toFloat(),
                        fillPaint
                    )
                }
                is PaintOp.DrawText -> {
                    textPaint.color = parseColor(op.color)
                    textPaint.textSize = (op.fontSize * scale).toFloat()
                    canvas.drawText(
                        op.text,
                        (op.x * scale).toFloat(),
                        ((op.y + op.fontSize) * scale).toFloat(),
                        textPaint
                    )
                }
            }
        }
        paintInputs(canvas, scale)
        val hit = lastHit
        if (hit != null) {
            canvas.drawRect(
                (hit.x * scale).toFloat(),
                (hit.y * scale).toFloat(),
                ((hit.x + hit.width) * scale).toFloat(),
                ((hit.y + hit.height) * scale).toFloat(),
                highlight
            )
        }
        val sel = DevToolsState.selectedNodeId
        if (sel != null) {
            val sh = devtoolsModel.handleWithNodeId(sel)
            if (sh != null) {
                val selNode = document.nodeOf(sh)
                for (op in scene.ops) {
                    if (op.node === selNode) {
                        canvas.drawRect(
                            (op.x * scale).toFloat(),
                            (op.y * scale).toFloat(),
                            ((op.x + op.width) * scale).toFloat(),
                            ((op.y + op.height) * scale).toFloat(),
                            selected
                        )
                    }
                }
            }
            canvas.drawText("seleccion #" + sel, 24f, height - 80f, footnote)
        }
        canvas.drawText("dibujo del motor propio · sin WebView", 24f, height - 48f, footnote)
        canvas.drawText("contracts v$ContractsVersion", 24f, height - 16f, footnote)
    }

    /** T035 v1 (IME): pinta el value de cada input dentro de su caja del
     * layout (recortado) con caret al final en el enfocado. */
    private fun paintInputs(canvas: Canvas, scale: Float) {
        val l = layout ?: return
        for (input in inputs()) {
            val box = l.boxOf(input) ?: continue
            val text = inputValue(input)
            val left = (box.x * scale).toFloat()
            val top = (box.y * scale).toFloat()
            val right = ((box.x + box.width) * scale).toFloat()
            val bottom = ((box.y + box.height) * scale).toFloat()
            if (right <= left || bottom <= top) continue
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            textPaint.color = parseColor("#f0eef8")
            textPaint.textSize = (14.0 * scale).toFloat()
            val pad = (4.0 * scale).toFloat()
            val baseline = top + (bottom - top) * 0.72f
            canvas.drawText(text, left + pad, baseline, textPaint)
            if (input === focusedInput) {
                val caretX = left + pad + textPaint.measureText(text)
                canvas.drawRect(caretX, top + pad, caretX + (3.0 * scale).toFloat(), bottom - pad, caretPaint)
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val scale = width / viewport.toFloat()
            val docX = (event.x / scale).toDouble()
            val docY = (event.y / scale).toDouble()
            val hit = scene.hitTest(docX, docY)
            lastHit = hit
            if (hit != null) {
                val h = devtoolsModel.handleOf(hit.node)
                if (h != null) DevToolsState.selectedNodeId = h.nodeId
            }
            focusInput(hit?.node as? DomNode.Element)
            performClick()
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    /** T035 v1 (IME): enfoca el input tocado y muestra el teclado; tocar
     * fuera limpia el foco y lo esconde. */
    private fun focusInput(el: DomNode.Element?) {
        val target = if (el != null && el.name.equals("input", ignoreCase = true)) el else null
        if (target == null) {
            focusedInput = null
            imm?.hideSoftInputFromWindow(windowToken, 0)
            invalidate()
            return
        }
        focusedInput = target
        editor.clear()
        editor.append(inputValue(target))
        Selection.setSelection(editor, editor.length)
        if (imm == null) {
            imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        }
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm?.showSoftInput(this, 0)
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean = focusedInput != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val input = focusedInput ?: return null
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        editor.clear()
        editor.append(inputValue(input))
        Selection.setSelection(editor, editor.length)
        // R0167: fullEditor=true: con false el IME era dummy y las letras
        // llegaban como KeyEvent que nadie procesaba.
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = editor
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val ok = super.commitText(text, newCursorPosition)
                syncEditorToDom()
                return ok
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                val ok = super.setComposingText(text, newCursorPosition)
                syncEditorToDom()
                return ok
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val ok = super.deleteSurroundingText(beforeLength, afterLength)
                syncEditorToDom()
                return ok
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val ok = super.sendKeyEvent(event)
                syncEditorToDom()
                return ok
            }
        }
    }

    /** El editor del IME es la verdad mientras el input esta enfocado:
     * cada mutacion se vuelca al value del DOM del motor y se anuncia. */
    private fun syncEditorToDom() {
        val input = focusedInput ?: return
        val h = devtoolsModel.handleOf(input) ?: return
        try {
            document.setAttribute(h, "value", editor.toString())
        } catch (e: DomException) {
            return
        }
        invalidate()
        announceForAccessibility("valor: " + editor.toString())
    }

    /** T035 v1 (a11y): arbol del documento como nodos virtuales por
     * nodeId (estables en pre-orden); el provider resuelve texto, value,
     * roles y bounds contra el DOM y el layout vivos. */
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11yProvider

    private val a11yProvider = object : AccessibilityNodeProvider() {

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) {
                return AccessibilityNodeInfo.obtain(this@PaintView)
            }
            val h = handleById(virtualViewId) ?: return null
            val n = document.nodeOf(h)
            val info = AccessibilityNodeInfo.obtain()
            info.packageName = "app.nia.navegador"
            info.className = classNameOf(n)
            info.viewIdResourceName = "nia/node/" + h.nodeId
            info.isEnabled = true
            info.isFocusable = true
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (n is DomNode.Text) {
                info.text = n.data
            }
            if (n is DomNode.Element && n.name.equals("input", ignoreCase = true)) {
                info.text = inputValue(n)
                info.isEditable = true
                info.isClickable = true
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
                info.addAction(AccessibilityNodeInfo.ACTION_FOCUS)
                info.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
            }
            val scale = width / viewport.toFloat()
            getLocationOnScreen(screenLoc)
            val l = layout
            val rect = Rect()
            val box = if (l != null) l.boxOf(n) else null
            if (box != null) {
                rect.set(
                    screenLoc[0] + (box.x * scale).toInt(),
                    screenLoc[1] + (box.y * scale).toInt(),
                    screenLoc[0] + ((box.x + box.width) * scale).toInt(),
                    screenLoc[1] + ((box.y + box.height) * scale).toInt()
                )
            }
            info.setBoundsInScreen(rect)
            val parentH = parentHandleOf(h)
            if (parentH != null) {
                info.setParent(this@PaintView, parentH.nodeId)
            } else {
                info.setParent(this@PaintView)
            }
            for (k in childrenOrEmpty(h)) info.addChild(this@PaintView, k.nodeId)
            return info
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == View.NO_ID) return false
            val h = handleById(virtualViewId) ?: return false
            val n = document.nodeOf(h)
            val input = n as? DomNode.Element
            if (input != null && input.name.equals("input", ignoreCase = true)) {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    val cs = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    val text = cs?.toString() ?: ""
                    try {
                        document.setAttribute(h, "value", text)
                    } catch (e: DomException) {
                        return false
                    }
                    if (input === focusedInput) {
                        editor.clear()
                        editor.append(text)
                        Selection.setSelection(editor, editor.length)
                    }
                    invalidate()
                    return true
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_FOCUS) {
                    focusInput(input)
                    return true
                }
            }
            return action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
        }
    }

    /** Handle del nodo con el nodeId dado (busqueda en pre-orden). */
    private fun handleById(nodeId: Int): DomHandle? {
        fun go(h: DomHandle): DomHandle? {
            if (h.nodeId == nodeId) return h
            for (k in childrenOrEmpty(h)) {
                val f = go(k)
                if (f != null) return f
            }
            return null
        }
        return go(document.rootHandle())
    }

    /** Handle padre por recorrido (sin depender de la API de parentOf). */
    private fun parentHandleOf(h: DomHandle): DomHandle? {
        fun go(cur: DomHandle): DomHandle? {
            for (k in childrenOrEmpty(cur)) {
                if (k.nodeId == h.nodeId) return cur
                val f = go(k)
                if (f != null) return f
            }
            return null
        }
        return go(document.rootHandle())
    }

    /** Rol aproximado del nodo para el lector de pantalla (v1). */
    private fun classNameOf(n: DomNode): String = when {
        n is DomNode.Element && n.name.equals("input", ignoreCase = true) -> "android.widget.EditText"
        n is DomNode.Text -> "android.widget.TextView"
        n is DomNode.Element && n.name.length == 2 && (n.name[0] == 'h' || n.name[0] == 'H') -> "android.widget.TextView"
        else -> "android.widget.View"
    }

    private fun parseColor(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.BLACK
        }
}
