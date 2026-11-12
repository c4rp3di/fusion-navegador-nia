package nia.engine.js

import nia.engine.dom.DomDocument
import nia.engine.media.MediaPuente

/**
 * Event loop de documento (T111 v7 / TK-111-3 v2, R0446, RES-0175): la
 * sesion JS VIVA de la pagina. Todos los scripts del documento corren
 * en la MISMA sesion (mismo genv): los globals y los listeners de
 * window persisten entre scripts como en una pagina real (la
 * re-declaracion entre scripts SOBRESCRIBE: Env.declare no falla).
 * El host (NavController) la abre al comprometer la navegacion, corre
 * cada script con eval(source) y la cierra con end() ANTES de abrir la
 * de la navegacion siguiente.
 *
 * Cuotas (T021): cada entrada de JS (script o despacho de evento)
 * abre su PROPIA sesion de cuotas, como hoy por script con
 * evaluateWithDom: un script agotado no envenena al siguiente. Los
 * runners vivos (keepAliveRunners) se reafirman antes de cada entrada
 * porque una evaluacion ANIDADA (scripts de un subframe) los apaga
 * al entrar.
 *
 * window: EventTarget minimo con addEventListener/removeEventListener
 * y registro en el lado Kotlin; el host dispara popstate/hashchange
 * con despacharWindow. Un listener que revienta JAMAS rompe la
 * navegacion (el fallo se traga). Exclusiones v1: sin timers (nadie
 * bombea el reloj), sin dispatchEvent propio, sin self/parent/top ni
 * handlers on*, y el subframe NO recibe event loop (sus scripts
 * siguen con sesiones propias por evaluacion).
 */
class DocumentLoop(
    private val cuotas: JsLimits,
    doc: DomDocument,
    puente: MediaPuente?,
    baseUrl: String,
    history: JsHistoryApi?
) {
    private val genv: Env = JsInterpreter.startLoopSession(cuotas)
    private val listeners = LinkedHashMap<String, MutableList<JsValue>>()
    private val window: JsObj = JsRuntime.newObj()

    init {
        JsDomBindings.install(genv, doc, puente, baseUrl)
        if (history != null) history.install(genv)
        installWindow()
    }

    /** Corre un script de la pagina en la sesion viva (mismo genv) con
     * su propia sesion de cuotas, como hoy por script. */
    fun eval(source: String): JsEvalResult {
        JsInterpreter.reanudarSesionLoop()
        JsQuotas.begin(cuotas)
        try {
            return JsInterpreter.runInSession(source, genv)
        } finally {
            JsQuotas.end()
        }
    }

    /** Dispara un evento de window sobre los listeners del tipo,
     * sincrono; ev lleva type y las propiedades del host (null se
     * traduce a null de JS). Sin listeners es no-op. */
    fun despacharWindow(tipo: String, propiedades: Map<String, JsValue?>) {
        val lista = listeners[tipo] ?: return
        JsInterpreter.reanudarSesionLoop()
        JsQuotas.begin(cuotas)
        try {
            val ev = JsRuntime.newObj()
            for ((k, v) in propiedades) {
                ev.props[k] = JsProp.Value(v ?: JsNull)
            }
            ev.props["type"] = JsProp.Value(JsStr(tipo))
            for (l in lista.toList()) {
                try {
                    JsInterpreter.callFunction(l, window, listOf(ev))
                } catch (ex: Exception) {
                    // un listener que revienta JAMAS rompe la navegacion
                }
            }
        } finally {
            JsQuotas.end()
        }
    }

    /** Cierra la sesion del documento (runners y cuotas del loop). */
    fun end() {
        JsInterpreter.endLoopSession()
    }

    private fun installWindow() {
        window.props["addEventListener"] = JsProp.Value(
            JsBuiltins.fn("addEventListener") { args, _ ->
                if (args.size >= 2) {
                    val tipo = JsRuntime.toStringJs(args[0])
                    listeners.getOrPut(tipo) { mutableListOf() }.add(args[1])
                }
                JsUndefined
            }
        )
        window.props["removeEventListener"] = JsProp.Value(
            JsBuiltins.fn("removeEventListener") { args, _ ->
                if (args.size >= 2) {
                    val tipo = JsRuntime.toStringJs(args[0])
                    listeners[tipo]?.remove(args[1])
                }
                JsUndefined
            }
        )
        genv.declare("window", window)
    }
}
