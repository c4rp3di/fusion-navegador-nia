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
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import nia.contracts.ContractsVersion
import nia.engine.css.CssCascade
import nia.engine.css.CssComputedStyle
import nia.engine.css.CssStylesheet
import nia.engine.devtools.DevToolsModel
import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.gestures.AccionBorde
import nia.engine.gestures.ConfigGestos
import nia.engine.gestures.RastreadorBorde
import nia.engine.gestures.ZoomToque
import nia.engine.html.DomNode
import nia.engine.layout.LayoutEngine
import nia.engine.layout.LayoutResult
import nia.engine.modules.EstilosModulos
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene
import nia.engine.paint.PaintSceneBuilder
import nia.engine.seleccion.FragmentoTexto
import nia.engine.seleccion.Fragmentos
import nia.engine.seleccion.PosSeleccion
import nia.engine.seleccion.Seleccion
import nia.engine.busqueda.Busqueda

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
 *
 * T072 v2 (R0168, RES-0078): modo navegador con loadPagina(html, css,
 * scroll): CSS de base propio (sin el de la demo), sin notas al pie y
 * con alto de documento para que el ScrollView del shell desplace la
 * pagina real pintada por el motor.
 */
class PaintView(context: Context) : View(context) {

    /** T077 v1 (R0195, RES-0093): zoom REAL del documento — el viewport
     * logico baja de 360 a 360/zoom y el motor RE-MAQUETA el documento
     * (reflow del layout propio), nunca se escala un bitmap. 1.0 = ancho
     * de lectura por defecto. */
    var factorZoom = 1.0
        private set

    /** T078 v1 (R0199, RES-0094): configuracion VIVA de gestos; se
     * aplica EN CALIENTE desde Ajustes (actualizarGestos). */
    private var cfgGestos = ConfigGestos()

    /** T080 v1 (RES-0097): viewport logico de BASE por sitio — 360
     *  (movil) o 720 (escritorio); el zoom sigue aplicando encima. */
    var baseViewport: Double = 360.0

    /** Viewport logico efectivo tras el zoom (medida, dibujo y toque). */
    private val viewport: Double get() = baseViewport / factorZoom

    private var document: DomDocument = DomDocument.parse(DemoPage.HTML)
    private var devtoolsModel: DevToolsModel = DevToolsModel(document, DemoPage.CSS)
    private var root: DomNode = document.nodeOf(document.rootHandle())
    private var rootDoc: DomNode.Document = root as DomNode.Document
    private lateinit var scene: PaintScene
    private var layout: LayoutResult? = null
    private var loadedHtml: String = DemoPage.HTML
    private var baseCss: String = DemoPage.CSS
    private var modoDemo: Boolean = true
    private var scrollDocumento: Boolean = false

    /** C6 v1 (R0276, RES-0120): hoja de estilos del modulo ganador del
     *  documento (null = sin hoja); el overlay display:none se aplica
     *  en el MISMO pase de layout (sin flash, sin re-layout). */
    private var hojaModulo: String? = null

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

    /** T079 v1 (RES-0095): seleccion de texto del shell: ancla/foco,
     *  handle arrastrado (1 ancla, 2 foco) y menu via callback. */
    private var seleccionTexto: Pair<PosSeleccion, PosSeleccion>? = null
    private var arrastrandoHandle = 0
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 214, 10)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 10)
        style = Paint.Style.FILL
    }
    private var onSeleccionMenu: ((String) -> Unit)? = null

    /** T081 v1 (RES-0096): busqueda en la pagina del shell: matches
     *  como pares de anclas del motor y match activo (0-based, -1 = sin). */
    private var matchesBusqueda: List<Busqueda.Coincidencia> = emptyList()
    private var matchActual = -1
    private val matchFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 80, 200, 255)
        style = Paint.Style.FILL
    }
    private val matchActualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 140, 0)
        style = Paint.Style.FILL
    }

    /** T077 v1 (R0195, RES-0093): gestos base sobre la pagina pintada.
     * El borde (atras/adelante) y el doble tap se deciden con la logica
     * PURA del motor (RastreadorBorde/ZoomToque, tests en CI); el
     * long-press usa el detector del sistema (se cancela solo con el
     * scroll, sin robar input); la pinza acumula el factor y lo aplica
     * al SOLTAR para re-maquetear una sola vez por gesto completo. */
    private var onGestoBorde: ((AccionBorde) -> Unit)? = null
    private var onToqueLargo: ((InfoToqueLargo) -> Unit)? = null
    private var rastreadorBorde: RastreadorBorde? = null
    private var zoomToque = ZoomToque(cfgGestos) { System.currentTimeMillis() }
    private var pinzaActiva = false
    private var pinzaBase = 1.0
    private var pinzaFactor = 1.0

    private val detectorToque = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // T079: sobre texto plano abre la seleccion propia; los
            // enlaces e imagenes conservan el menu T077 intacto.
            val info = infoToqueLargo(e.x, e.y)
            if (info != null && info.tipo == "texto") {
                iniciarSeleccion(e.x, e.y)
                return
            }
            if (info != null) onToqueLargo?.invoke(info)
        }
    })

    private val detectorPinza = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!cfgGestos.pinza) {
                return false
            }
            pinzaActiva = true
            pinzaBase = factorZoom
            pinzaFactor = 1.0
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinzaFactor *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinzaActiva = false
            fijarZoom(pinzaBase * pinzaFactor)
        }
    })

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
        baseCss = DemoPage.CSS
        document = DomDocument.parse(html)
        devtoolsModel = DevToolsModel(document, DemoPage.CSS)
        root = document.nodeOf(document.rootHandle())
        rootDoc = root as DomNode.Document
        DevToolsState.selectedNodeId = null
        focusedInput = null
        editor.clear()
        seleccionTexto = null
        matchesBusqueda = emptyList()
        matchActual = -1
        rebuild()
    }

    /** T072 v2 (R0168): pagina REAL del navegador: CSS de base propio,
     * sin notas de demo y con alto de documento cuando se pide scroll. */
    fun loadPagina(html: String, css: String, scroll: Boolean) {
        loadedHtml = html
        baseCss = css
        modoDemo = false
        scrollDocumento = scroll
        document = DomDocument.parse(html)
        devtoolsModel = DevToolsModel(document, css)
        root = document.nodeOf(document.rootHandle())
        rootDoc = root as DomNode.Document
        DevToolsState.selectedNodeId = null
        focusedInput = null
        editor.clear()
        seleccionTexto = null
        matchesBusqueda = emptyList()
        matchActual = -1
        rebuild()
        requestLayout()
        invalidate()
    }

    /** C6 v1 (R0276, RES-0120): fija la hoja de estilos del modulo
     *  para el PROXIMO documento (null = sin hoja); la fija el shell
     *  tras el disparo de navegacion y vive con el documento. */
    fun cargarHojaModulo(css: String?) {
        hojaModulo = css
    }

    /** HTML fuente cargado (SAF: Guardar escribe esto, no el DOM mutado). */
    fun currentHtml(): String = loadedHtml

    /** R0172 (diagnostico smoke): ops de la escena pintada. */
    fun opsCount(): Int = scene.ops.size

    /** T098 (RES-0108): escena re-maquetada al ancho util de impresion
     *  (puntos como px logicos del motor) SIN tocar la vista: el mismo
     *  pipeline del rebuild() con viewport de impresion; la pagina
     *  impresa es el MISMO render del motor, jamas una captura. */
    fun escenaParaImprimir(anchoUtil: Double): PaintScene {
        var css = baseCss + styleTextOf()
        if (DevToolsState.userCss.isNotEmpty()) css = css + "\n" + DevToolsState.userCss
        val computed = CssCascade.compute(root, CssStylesheet.parse(css))
        val laid = LayoutEngine.layout(rootDoc, styleOfDe(computed), anchoUtil)
        return PaintSceneBuilder.build(rootDoc, computed, laid)
    }

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
                // R0171 (crash del smoke T072 v2): el CSS vive en los hijos
                // Text del <style>; textOf exige un nodo Text y con el handle
                // del ELEMENTO reventaba en paginas reales (la demo no trae
                // <style> y no lo ejecutaba nunca). Comentarios y <style>
                // vacio se ignoran; varios hijos Text se concatenan.
                for (k in childrenOrEmpty(h)) {
                    val hijo = document.nodeOf(k)
                    if (hijo is DomNode.Text) {
                        sb.append('\n').append(hijo.data)
                    }
                }
                return
            }
            for (k in childrenOrEmpty(h)) go(k)
        }
        go(document.rootHandle())
        return sb.toString()
    }

    /** C6 v1 (R0276, RES-0120): styleOf con el overlay de la hoja del
     *  modulo (fail-closed: hoja invalida o sin matches = computed tal
     *  cual); el display:none casado viaja al layout en el MISMO pase. */
    private fun styleOfDe(computed: CssComputedStyle): (DomNode, String) -> String? {
        val hoja = hojaModulo ?: return { node, name -> computed.propertyOf(node, name) }
        val ocultos = EstilosModulos.ocultos(rootDoc, hoja)
        if (ocultos.isEmpty()) return { node, name -> computed.propertyOf(node, name) }
        return { node, name ->
            if (name == "display" && node in ocultos) "none" else computed.propertyOf(node, name)
        }
    }

    /** Recalcula cascada+layout+escena con las reglas del usuario (v4). */
    private fun rebuild() {
        var css = baseCss + styleTextOf()
        if (DevToolsState.userCss.isNotEmpty()) css = css + "\n" + DevToolsState.userCss
        val computed = CssCascade.compute(root, CssStylesheet.parse(css))
        val laid = LayoutEngine.layout(rootDoc, styleOfDe(computed), viewport)
        layout = laid
        scene = PaintSceneBuilder.build(rootDoc, computed, laid)
        builtRevision = DevToolsState.revision
    }

    /** T072 v2 (R0168): con scrollDocumento el alto pedido es el del
     * contenido (escena al ancho real); sin el, la demo queda intacta. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!scrollDocumento) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val ancho = View.MeasureSpec.getSize(widthMeasureSpec)
        val escala = ancho / viewport
        var maximo = 0.0
        for (op in scene.ops) {
            val fin = op.y + op.height
            if (fin > maximo) maximo = fin
        }
        val alto = (maximo * escala).toInt() + 128
        setMeasuredDimension(ancho, maxOf(alto, View.MeasureSpec.getSize(heightMeasureSpec)))
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
        if (modoDemo && sel != null) {
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
        dibujarSeleccion(canvas, scale)
        dibujarBusqueda(canvas, scale)
        if (modoDemo) {
            canvas.drawText("dibujo del motor propio · sin WebView", 24f, height - 48f, footnote)
            canvas.drawText("contracts v$ContractsVersion", 24f, height - 16f, footnote)
        }
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
        // T077 v1 (R0195, RES-0093): los detectores del sistema se
        // alimentan SIEMPRE (long-press y pinza viven aqui); el borde y
        // el doble tap se deciden con la logica del motor. La vista
        // NUNCA roba el scroll: el ScrollView padre intercepta lo
        // vertical (no se toca el gesto del sistema).
        detectorToque.onTouchEvent(event)
        detectorPinza.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (manejarDownSeleccion(event)) return true
                rastreadorBorde = if (cfgGestos.swipe) RastreadorBorde(width.toDouble(), cfgGestos) else null
                rastreadorBorde?.down(event.x.toDouble(), event.y.toDouble())
                if (!pinzaActiva && cfgGestos.dobleTap) {
                    val nuevoZoom = zoomToque.toque(event.x.toDouble(), event.y.toDouble(), factorZoom)
                    if (nuevoZoom != null) fijarZoom(nuevoZoom)
                }
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
            MotionEvent.ACTION_MOVE -> {
                if (arrastrandoHandle != 0) {
                    moverHandleSeleccion(event)
                    return true
                }
                if (!pinzaActiva) {
                    val accion = rastreadorBorde?.move(event.x.toDouble(), event.y.toDouble())
                        ?: AccionBorde.NINGUNA
                    if (accion != AccionBorde.NINGUNA) onGestoBorde?.invoke(accion)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                arrastrandoHandle = 0
                rastreadorBorde?.up()
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    /** T077 v1: registra el ejecutor del gesto de borde del shell. */
    fun setOnGestoBorde(accion: (AccionBorde) -> Unit) {
        onGestoBorde = accion
    }

    /** T077 v1: registra el menu propio del toque largo (lo resuelto
     * via hitTest del motor viaja al shell, nunca al reves). */
    fun setOnToqueLargo(accion: (InfoToqueLargo) -> Unit) {
        onToqueLargo = accion
    }

    /** T077 v1: zoom actual del documento (1.0 = ancho de lectura). */
    fun zoomActual(): Double = factorZoom

    /** T078 v1 (R0199, RES-0094): configura los gestos EN CALIENTE
     * desde Ajustes (toggle por gesto, banda y umbral del borde): el
     * rastreador nace por toque con la cfg nueva, el ZoomToque se
     * reconstruye limpio y los limites de zoom aplican al proximo
     * fijarZoom. Sin re-maquetar la pagina ni tocar el scroll. */
    fun actualizarGestos(cfg: ConfigGestos) {
        cfgGestos = cfg
        zoomToque = ZoomToque(cfg) { System.currentTimeMillis() }
    }

    /** T077 v1: aplica el zoom REAL del motor — RE-MAQUETEO con viewport
     * 360/zoom (reflow del layout propio), limitado por ConfigGestos;
     * sin cambios no re-maquetea. Ruta equivalente por menu: Zoom +,
     * Zoom - y Zoom 100% del BrowserActivity. */
    fun fijarZoom(factor: Double) {
        val nuevo = ZoomToque.limitar(factor, cfgGestos)
        if (nuevo == factorZoom) return
        factorZoom = nuevo
        rebuild()
        requestLayout()
        invalidate()
    }

    /** T080 v1 (RES-0097): fija el viewport logico de base (720 en
     *  escritorio, 360 en movil); re-maqueta como el zoom, NUNCA un
     *  bitmap. Ruta del shell: aplicarSitio y menu Escritorio. */
    fun fijarBaseViewport(px: Double) {
        val nuevo = if (px <= 0.0) 360.0 else px
        if (nuevo == baseViewport) return
        baseViewport = nuevo
        rebuild()
        requestLayout()
        invalidate()
    }

    /** T079 v1 (RES-0095): registra el menu propio de la seleccion
     * de texto (el texto resuelto viaja al shell, nunca al reves). */
    fun setOnSeleccionMenu(accion: (String) -> Unit) {
        onSeleccionMenu = accion
    }

    /** T079 v1: hay seleccion de texto activa (para el shell). */
    fun haySeleccion(): Boolean = seleccionTexto != null

    /** T079 v1: texto de la seleccion activa, "" si no la hay. */
    fun textoSeleccionado(): String {
        val sel = seleccionTexto ?: return ""
        return Seleccion.texto(Fragmentos.de(scene), sel.first, sel.second)
    }

    /** T079 v1: limpia la seleccion y repinta. */
    fun limpiarSeleccion() {
        seleccionTexto = null
        invalidate()
    }

    /** Fragmentos de texto de la escena, en coordenadas de documento. */
    private fun fragmentosSeleccion(): List<FragmentoTexto> = Fragmentos.de(scene)

    /** T079 v1: abre la seleccion con la palabra completa bajo el
     *  toque largo; sin texto bajo el punto no hace nada. */
    private fun iniciarSeleccion(x: Float, y: Float) {
        val scale = width / viewport.toFloat()
        val frags = fragmentosSeleccion()
        val sel = Seleccion.palabraBajo(frags, (x / scale).toDouble(), (y / scale).toDouble())
        if (sel == null) return
        seleccionTexto = sel
        arrastrandoHandle = 0
        invalidate()
        onSeleccionMenu?.invoke(textoSeleccionado())
    }

    /** T079 v1: DOWN con seleccion activa: arranca el arrastre del
     *  handle mas cercano o limpia con un tap fuera de los handles. */
    private fun manejarDownSeleccion(event: MotionEvent): Boolean {
        val sel = seleccionTexto ?: return false
        val scale = width / viewport.toFloat()
        val frags = fragmentosSeleccion()
        val radioPx = 32.0
        var mejorHandle = 0
        var mejorD = Double.MAX_VALUE
        for (h in 1..2) {
            val pos = if (h == 1) sel.first else sel.second
            val p = Seleccion.punto(frags, pos)
            val dx = (event.x - (p.x * scale).toFloat()).toDouble()
            val dy = (event.y - (p.y * scale).toFloat()).toDouble()
            val d = Math.hypot(dx, dy)
            if (d < mejorD) {
                mejorD = d
                mejorHandle = h
            }
        }
        if (mejorD <= radioPx) {
            arrastrandoHandle = mejorHandle
            return true
        }
        seleccionTexto = null
        invalidate()
        return false
    }

    /** T079 v1: mueve el handle arrastrado anclando al fragmento mas
     *  cercano en radio; sin ancla en radio el handle se queda. */
    private fun moverHandleSeleccion(event: MotionEvent) {
        val sel = seleccionTexto ?: return
        val scale = width / viewport.toFloat()
        val frags = fragmentosSeleccion()
        val ancla = Seleccion.anclarMasCercano(
            frags,
            (event.x / scale).toDouble(),
            (event.y / scale).toDouble(),
            64.0
        ) ?: return
        seleccionTexto = if (arrastrandoHandle == 1) Pair(ancla, sel.second) else Pair(sel.first, ancla)
        invalidate()
    }

    /** T079 v1: dibuja las cajas de resaltado y los dos handles. */
    private fun dibujarSeleccion(canvas: Canvas, scale: Float) {
        val sel = seleccionTexto ?: return
        val frags = fragmentosSeleccion()
        for (c in Seleccion.cajas(frags, sel.first, sel.second)) {
            canvas.drawRect(
                (c.x * scale).toFloat(),
                (c.y * scale).toFloat(),
                ((c.x + c.width) * scale).toFloat(),
                ((c.y + c.height) * scale).toFloat(),
                selectionFill
            )
        }
        for (pos in listOf(sel.first, sel.second)) {
            val p = Seleccion.punto(frags, pos)
            canvas.drawCircle((p.x * scale).toFloat(), (p.y * scale).toFloat(), 10f, handlePaint)
        }
    }

    /** T081 v1 (RES-0096): busca la consulta sobre los fragmentos
     *  del documento y resalta; devuelve el total de coincidencias. */
    fun buscarEnPagina(consulta: String): Int {
        matchesBusqueda = Busqueda.buscar(Fragmentos.de(scene), consulta)
        matchActual = if (matchesBusqueda.isEmpty()) -1 else 0
        invalidate()
        return matchesBusqueda.size
    }

    /** T081 v1: siguiente match con vuelta circular definida. */
    fun siguienteMatch() {
        if (matchesBusqueda.isEmpty()) return
        matchActual = (matchActual + 1) % matchesBusqueda.size
        invalidate()
    }

    /** T081 v1: anterior match con vuelta circular definida. */
    fun anteriorMatch() {
        if (matchesBusqueda.isEmpty()) return
        matchActual = (matchActual - 1 + matchesBusqueda.size) % matchesBusqueda.size
        invalidate()
    }

    /** T081 v1: total de coincidencias de la busqueda activa. */
    fun totalMatches(): Int = matchesBusqueda.size

    /** T081 v1: match activo en base 1 para el N de M del shell. */
    fun matchActual(): Int = matchActual + 1

    /** T081 v1: limpia la busqueda (carga o cierre de la barra). */
    fun limpiarBusqueda() {
        matchesBusqueda = emptyList()
        matchActual = -1
        invalidate()
    }

    /** T081 v1: Y en px de la vista del match activo para que el
     *  scroll del shell lo deje visible. */
    fun yMatchActualPx(): Float? {
        if (matchActual < 0 || matchActual >= matchesBusqueda.size) return null
        val scale = width / viewport.toFloat()
        val p = Seleccion.punto(Fragmentos.de(scene), matchesBusqueda[matchActual].inicio)
        return (p.y * scale).toFloat()
    }

    /** T081 v1: dibuja las cajas de las coincidencias; la activa con
     *  color propio para distinguir el N de M. */
    private fun dibujarBusqueda(canvas: Canvas, scale: Float) {
        if (matchesBusqueda.isEmpty()) return
        val frags = Fragmentos.de(scene)
        for ((idx, m) in matchesBusqueda.withIndex()) {
            val pintura = if (idx == matchActual) matchActualPaint else matchFill
            for (caja in Seleccion.cajas(frags, m.inicio, m.fin)) {
                canvas.drawRect(
                    (caja.x * scale).toFloat(),
                    (caja.y * scale).toFloat(),
                    ((caja.x + caja.width) * scale).toFloat(),
                    ((caja.y + caja.height) * scale).toFloat(),
                    pintura
                )
            }
        }
    }

    /** T077 v1: contenido resuelto bajo el toque largo (enlace, imagen
     * o texto), anclado al documento real del motor. */
    class InfoToqueLargo(val tipo: String, val url: String, val texto: String)

    /** Resuelve el contenido bajo (x, y): hitTest del motor y subida por
     * los padres hasta <a href> o <img src>; si no, el texto del nodo. */
    private fun infoToqueLargo(x: Float, y: Float): InfoToqueLargo? {
        val scale = width / viewport.toFloat()
        val hit = scene.hitTest((x / scale).toDouble(), (y / scale).toDouble()) ?: return null
        var enlace: String? = null
        var imagen: String? = null
        var h = devtoolsModel.handleOf(hit.node)
        var vueltas = 0
        while (h != null && vueltas < 64) {
            val n = document.nodeOf(h)
            if (n is DomNode.Element) {
                val nombre = n.name.lowercase()
                if (enlace == null && nombre == "a") {
                    enlace = try {
                        document.attributeOf(h, "href")
                    } catch (e: DomException) {
                        null
                    }
                }
                if (imagen == null && nombre == "img") {
                    imagen = try {
                        document.attributeOf(h, "src")
                    } catch (e: DomException) {
                        null
                    }
                }
                if (enlace != null) break
            }
            h = parentHandleOf(h)
            vueltas += 1
        }
        val texto = (hit.node as? DomNode.Text)?.data ?: ""
        return when {
            enlace != null -> InfoToqueLargo("enlace", enlace ?: "", texto.trim())
            imagen != null -> InfoToqueLargo("imagen", imagen ?: "", texto.trim())
            texto.isNotBlank() -> InfoToqueLargo("texto", "", texto.trim())
            else -> null
        }
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
            // R0172: fallback CLARO: un color que Android no
            // parsea no puede quedar negro invisible.
            Color.rgb(176, 176, 192)
        }
}
