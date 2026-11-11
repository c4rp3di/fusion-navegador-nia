package nia.engine.js

/**
 * Bindings JS de historia (T111 v4, R0436, RES-0172, TK-111-3): expone
 * el global history de la pagina sobre la historia de la pestana que
 * posee el host (NavController en la navegacion real). pushState y
 * replaceState escriben entradas same-origin: la url del tercer
 * argumento se compone sobre la URL VIVA de la entrada actual (un
 * #fragmento reemplaza al anterior; vacia o ausente duplica la actual)
 * y el host resuelve las relativas contra la URL del DOCUMENTO
 * (exclusion: no contra la ultima entrada), verifica el origen y
 * devuelve la url final; null (cross-origin o invalida) se traduce
 * aqui a SecurityError del spec. length y state son accessores reales
 * sobre la entrada actual (patron __lenGet de JsStorageApi); el estado
 * se conserva como el VALOR JS entregado (sin clonado estructural: los
 * closures dentro no son invocables tras la sesion que lo creo).
 *
 * Exclusiones v1: SIN popstate/hashchange (las sesiones de script
 * mueren con cada evaluacion: no hay event loop de documento vivo
 * donde dispararlos), SIN history.back/forward/go desde JS (sin
 * re-entrancia en la navegacion), el titulo se ignora (como en el
 * spec) y el subframe no recibe history propio (exclusion).
 */
interface JsHistoryHost {
    /** pushState: nueva entrada tras la actual (trunca el futuro);
     * devuelve la url resuelta o null si es cross-origin/invalida. */
    fun spaPush(raw: String, state: JsValue?): String?

    /** replaceState: reemplaza la entrada actual; null como spaPush. */
    fun spaReplace(raw: String, state: JsValue?): String?

    /** Tamano de la historia de la pestana (entradas y futuro). */
    fun historyLength(): Int

    /** Estado JS de la entrada actual (null si no lo lleva). */
    fun historyState(): JsValue?
}

/**
 * API de history para una navegacion: el host posee la historia y la
 * url viva parte de la URL final del documento; cada push/replace la
 * actualiza y el NavController la aplica a la pagina comprometida.
 */
class JsHistoryApi(private val host: JsHistoryHost, urlViva: String) {

    /** URL viva de la entrada actual (tras sus push/replace). */
    private var viva = urlViva

    /** URL final de la entrada actual tras los push/replace. */
    fun urlActual(): String = viva

    /** Accessores parseados una vez (patron de JsStorageApi). */
    private val lenGet = parseAccessorFun("(function () { return this.__lenGet(); })")
    private val stateGet = parseAccessorFun("(function () { return this.__stateGet(); })")

    private fun parseAccessorFun(src: String): JsFun {
        val r = JsInterpreter.evaluate(src)
        val v = r.value
        if (v is JsFun) return v
        throw JsRuntime.throwNative("TypeError", "accessor-no-compilable")
    }

    /** Instala el global history en la sesion JS. */
    fun install(genv: Env) {
        val h = JsRuntime.newObj()
        h.props["__lenGet"] = JsProp.Value(JsBuiltins.fn("__lenGet") { _, _ ->
            JsNum(host.historyLength().toDouble())
        })
        h.props["__stateGet"] = JsProp.Value(JsBuiltins.fn("__stateGet") { _, _ ->
            host.historyState() ?: JsNull
        })
        h.props["length"] = JsProp.Accessor(lenGet, null)
        h.props["state"] = JsProp.Accessor(stateGet, null)
        h.props["pushState"] = JsProp.Value(JsBuiltins.fn("pushState") { args, _ ->
            val resuelta = host.spaPush(urlDe(args), estadoDe(args))
            if (resuelta == null) {
                throw JsRuntime.throwNative("SecurityError", "pushState cross-origin o url invalida")
            }
            viva = resuelta
            JsUndefined
        })
        h.props["replaceState"] = JsProp.Value(JsBuiltins.fn("replaceState") { args, _ ->
            val resuelta = host.spaReplace(urlDe(args), estadoDe(args))
            if (resuelta == null) {
                throw JsRuntime.throwNative("SecurityError", "replaceState cross-origin o url invalida")
            }
            viva = resuelta
            JsUndefined
        })
        genv.declare("history", h)
    }

    /** URL efectiva del tercer argumento: vacia, ausente o null duplica
     * la viva; un #fragmento reemplaza al de la viva. */
    private fun urlDe(args: List<JsValue>): String {
        if (args.size < 3) return viva
        val u = args[2]
        if (u is JsUndefined || u is JsNull) return viva
        val s = JsRuntime.toStringJs(u)
        if (s.isEmpty()) return viva
        if (s.startsWith("#")) return viva.substringBefore('#') + s
        return s
    }

    /** Estado del primer argumento (undefined sin argumentos). */
    private fun estadoDe(args: List<JsValue>): JsValue =
        if (args.isEmpty()) JsUndefined else args[0]
}
