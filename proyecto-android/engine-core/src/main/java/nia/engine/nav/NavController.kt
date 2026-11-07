package nia.engine.nav

import nia.contracts.ErrorCode
import nia.contracts.Outcome
import nia.contracts.WallClock
import nia.engine.dom.DomDocument
import nia.engine.html.DomNode
import nia.engine.js.JsFetchApi
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsStorageApi
import nia.engine.net.FetchManager
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.PoliticaHttpsOnly
import nia.engine.net.RedirectPolicy
import nia.engine.net.SiteContext
import nia.engine.net.TlsInfo
import nia.engine.profile.BrowserProfile
import nia.engine.url.Origin
import nia.engine.url.Site
import nia.engine.url.Url
import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser

/**
 * NavController puro JVM (T071 v1, R0149, RES-0074): orquesta una
 * navegacion completa sobre los motores ya verificados del nucleo, sin
 * tocar Android ni la UI (el shell de T072, ADR-022, lo cablea fuera del
 * hilo de UI: navigate es bloqueante y corre en el hilo del llamador).
 *
 * Fases por navegacion, en orden: NAVIGATING (una por navegacion),
 * RESPONSE_RECEIVED (una por salto de redireccion), PARSING, COMMITTED
 * (identidad y origen fijos, entrada en la historia) y LOADED (subrecursos
 * y scripts). Un NavOutcome.Failed lleva la fase donde fallo: page nulo
 * significa fallo ANTES del commit (la historia queda intacta); page no
 * nulo, fallo DESPUES del commit (pagina comprometida en la historia).
 *
 * Piezas integradas: FetchManager inyectable (T011) con generaciones para
 * cancelar por cooperacion (una navegacion nueva invalida la anterior y
 * las respuestas en vuelo se descartan como STALE_HANDLE); RedirectPolicy
 * (T026) con cookies por salto via CookieJar (contexto
 * TOP_LEVEL_CROSS_SITE en la navegacion, SAME_SITE o EMBEDDED_CROSS_SITE
 * en subrecursos, sin degradar https a http); DomDocument.parse (T013)
 * como documento vivo; commit de identidad con Origin y Site (T010) y
 * storage particionado por perfil (T033, expuesto a los scripts via
 * JsStorageApi); subrecursos img y stylesheet contables por tipo y
 * destino con mixed-content bloqueado; scripts inline y src ejecutados
 * con JsInterpreter.evaluateWithDom sobre JsFetchApi y JsStorageApi; y
 * back/forward sobre la historia de la pestana. Las mutaciones del
 * documento vivo tras el commit invalidan el layout: needsLayout compara
 * la revision del documento contra la revision capturada al commit.
 *
 * La cancelacion nunca interrumpe a la fuerza: el shell invalida la
 * generacion (invalidateGeneration o una navegacion nueva) y las
 * respuestas en vuelo se descartan; el deadline de navigate (absoluto
 * contra el reloj inyectable) vence sin llamar al transporte.
 *
 * Politica HTTPS-only y TLS (T084 v1, R0160, RES-0077): con una
 * PoliticaHttpsOnly inyectada, cada salto se decide ANTES de llamar al
 * transporte (http sin excepcion -> DENIED; con excepcion -> evento
 * visible "excepcion-http:host"); NavPage.tls lleva el TlsInfo real de
 * la respuesta final (null si el transporte no lo expone o sirvio de
 * cache: NUNCA se fabrica).
 *
 * Exclusiones v1 (API propia del motor): sin frame tree ni CSP (X02,
 * subtareas futuras de T071); un solo Set-Cookie por respuesta (el
 * transporte entrega cabeceras como mapa); Location relativa compleja
 * sin resolver (RedirectPolicy la entrega como Stop); subrecursos solo
 * absolutos o relativos al origen; un script src con un unico hijo de
 * texto; scripts bloqueantes en LOADED sin event loop (T025, T072) ni
 * service workers (T034); back/forward que re-navega, sin snapshots. El
 * gemelo TS validado offline (2 corridas identicas, huella
 * f0bd98c8.2740b7a8.76) espeja estos 14 escenarios en NavControllerTest.
 */
enum class NavState { NAVIGATING, RESPONSE_RECEIVED, PARSING, COMMITTED, LOADED }

/** Evento observable de una navegacion: fase y URL implicada. */
data class NavEvent(val state: NavState, val url: String)

/** Subrecurso contable: tipo (image, stylesheet), destino y resultado. */
data class NavSubresource(val kind: String, val url: String, val outcome: String)

/** Script ejecutado en LOADED: referencia inline:N o src:url, y salida. */
data class NavScript(val ref: String, val output: String)

/**
 * Pagina comprometida: URL final tras redirecciones, estado, identidad,
 * documento vivo, revision capturada al commit y contadores de carga.
 *
 * T072 v2 (R0168, RES-0078): html lleva el crudo de la respuesta final
 * para que el shell pinte la pagina con el motor sin re-fetch; null si
 * el llamador no lo conoce.
 */
data class NavPage(
    val url: String,
    val status: Int,
    val origin: Origin,
    val site: Site,
    val document: DomDocument,
    val layoutRevision: Long,
    val subresources: List<NavSubresource>,
    val scripts: List<NavScript>,
    val events: List<NavEvent>,
    val tls: TlsInfo? = null,
    val html: String? = null
)

/**
 * Resultado tipificado de una navegacion. Failed.phase es la fase donde
 * fallo; Failed.page no nulo marca un fallo DESPUES del commit.
 */
sealed class NavOutcome {
    data class Loaded(val page: NavPage) : NavOutcome()
    data class Failed(
        val phase: NavState,
        val code: ErrorCode,
        val message: String,
        val page: NavPage?
    ) : NavOutcome()
}

/**
 * Controlador de navegacion por pestana: compone el fetch con la
 * politica de redirecciones, el perfil (cookies, cache, storage) y el
 * documento vivo de la pagina actual.
 */
class NavController(
    private val transport: FetchTransport,
    private val clock: WallClock,
    private val profile: BrowserProfile,
    private val httpsOnly: PoliticaHttpsOnly? = null
) {
    private val manager = FetchManager(transport, clock, profile.cache)
    private val history = ArrayList<String>()
    private var index = -1
    private var page: NavPage? = null
    private var lastState: NavState? = null

    /** Cuotas de los scripts de la pagina (T100, RES-0104): el shell
     * fija el JsLimits resuelto ANTES de navegar; por defecto el del
     * motor (100M pasos y 10M allocs, T021). */
    var cuotasScript: JsLimits = JsLimits()

    /** Nueva generacion: las respuestas en vuelo quedan stale. */
    fun invalidateGeneration(): Long = manager.invalidate()

    /** Navega a una URL; deadlineMillis absoluto contra el reloj. */
    fun navigate(url: String, deadlineMillis: Long = -1): NavOutcome =
        navigateInternal(url, deadlineMillis, true)

    /** Re-navega a la entrada anterior; null si no hay entrada previa. */
    fun back(): NavOutcome? {
        if (index <= 0) return null
        val saved = index
        index -= 1
        val out = navigateInternal(history[index], -1, false)
        if (out is NavOutcome.Failed && out.page == null) index = saved
        return out
    }

    /** Re-navega a la entrada siguiente; null si no hay entrada futura. */
    fun forward(): NavOutcome? {
        if (index < 0 || index >= history.size - 1) return null
        val saved = index
        index += 1
        val out = navigateInternal(history[index], -1, false)
        if (out is NavOutcome.Failed && out.page == null) index = saved
        return out
    }

    fun canGoBack(): Boolean = index > 0

    fun canGoForward(): Boolean = index >= 0 && index < history.size - 1

    fun historyUrls(): List<String> = ArrayList(history)

    fun currentPage(): NavPage? = page

    fun state(): NavState? = lastState

    /** True si el documento vivo muto desde el commit: hay que renderizar. */
    fun needsLayout(): Boolean {
        val p = page ?: return false
        return p.document.snapshot().revision != p.layoutRevision
    }

    private fun navigateInternal(url: String, deadlineMillis: Long, push: Boolean): NavOutcome {
        manager.invalidate()
        val events = ArrayList<NavEvent>()
        events.add(NavEvent(NavState.NAVIGATING, url))
        lastState = NavState.NAVIGATING
        var current = url
        var method = "GET"
        var hops = 0
        var response: FetchResponse? = null
        while (true) {
            val u: Url = try {
                UrlParser.parse(current)
            } catch (e: UrlParseException) {
                return NavOutcome.Failed(NavState.NAVIGATING, ErrorCode.INVALID_INPUT, "URL invalida: " + e.reason, null)
            }
            if (u.scheme != "http" && u.scheme != "https") {
                return NavOutcome.Failed(NavState.NAVIGATING, ErrorCode.UNSUPPORTED, "esquema no soportado: " + u.scheme, null)
            }
            if (httpsOnly != null) {
                when (val decision = httpsOnly.decidir(u.scheme, u.host)) {
                    is PoliticaHttpsOnly.Decision.Bloquear -> {
                        return NavOutcome.Failed(NavState.NAVIGATING, ErrorCode.DENIED, "https-only: sin excepcion para " + decision.host, null)
                    }
                    is PoliticaHttpsOnly.Decision.PermitirConAdvertencia -> {
                        events.add(NavEvent(NavState.NAVIGATING, "excepcion-http:" + decision.host))
                    }
                    PoliticaHttpsOnly.Decision.Navegar -> {
                    }
                }
            }
            val headers = HashMap<String, String>()
            val cookie = profile.cookies.cookieHeader(u.host, u.path, u.scheme == "https", SiteContext.TOP_LEVEL_CROSS_SITE)
            if (cookie.isNotEmpty()) headers["Cookie"] = cookie
            val request = manager.newRequest(method, current, headers)
            val outcome = manager.fetch(request, null, deadlineMillis)
            if (outcome is Outcome.Failure) {
                return NavOutcome.Failed(NavState.NAVIGATING, outcome.error.code, outcome.error.safeMessage, null)
            }
            val fetched = (outcome as Outcome.Success<FetchResponse>).value
            response = fetched
            events.add(NavEvent(NavState.RESPONSE_RECEIVED, current))
            val setCookie = headerValue(fetched.headers, "set-cookie")
            if (setCookie != null) {
                profile.cookies.storeFromResponse(u.host, u.scheme == "https", setCookie)
            }
            if (RedirectPolicy.redirectable(fetched.status)) {
                val plan = RedirectPolicy.plan(fetched.status, method, headerValue(fetched.headers, "location"), current, hops)
                if (plan is RedirectPolicy.Plan.Stop) {
                    return NavOutcome.Failed(NavState.NAVIGATING, ErrorCode.UNSUPPORTED, "redireccion no seguida: " + plan.reason, null)
                }
                val follow = plan as RedirectPolicy.Plan.Follow
                method = follow.method
                current = follow.url
                hops += 1
                continue
            }
            break
        }
        val body = response ?: return NavOutcome.Failed(NavState.NAVIGATING, ErrorCode.UNSUPPORTED, "sin respuesta", null)
        lastState = NavState.PARSING
        events.add(NavEvent(NavState.PARSING, current))
        val fuente = String(body.body, Charsets.UTF_8)
        val document = DomDocument.parse(fuente)
        val finalUrl: Url = try {
            UrlParser.parse(current)
        } catch (e: UrlParseException) {
            return NavOutcome.Failed(NavState.PARSING, ErrorCode.INVALID_INPUT, "URL invalida: " + e.reason, null)
        }
        val origin = Origin.of(finalUrl)
        val tuple = origin as? Origin.Tuple
        if (tuple == null) {
            return NavOutcome.Failed(NavState.PARSING, ErrorCode.UNSUPPORTED, "origen opaco", null)
        }
        val site = Site.fromOrigin(tuple)
        lastState = NavState.COMMITTED
        events.add(NavEvent(NavState.COMMITTED, current))
        if (push) {
            while (history.size > index + 1) history.removeAt(history.size - 1)
            history.add(current)
            index = history.size - 1
        }
        val committed = NavPage(
            current, body.status, origin, site, document, document.snapshot().revision,
            ArrayList(), ArrayList(), ArrayList(events), body.seguridad, fuente
        )
        page = committed
        lastState = NavState.LOADED
        val items = ArrayList<LoadItem>()
        walkLoadItems(document.nodeOf(document.rootHandle()), finalUrl, items)
        val subresources = ArrayList<NavSubresource>()
        val scripts = ArrayList<NavScript>()
        val storageApi = JsStorageApi(profile.storage, profile.idb, site, origin)
        val fetchApi = JsFetchApi(transport, clock)
        var inlineCount = 0
        for (item in items) {
            when (item) {
                is LoadItem.Subresource -> {
                    val r = fetchSide(item.url, finalUrl, site)
                    if (r is SideResult.NavFailure) {
                        return NavOutcome.Failed(NavState.LOADED, r.code, r.message, committed)
                    }
                    subresources.add(NavSubresource(item.kind, item.url, describe(r)))
                }
                is LoadItem.InlineScript -> {
                    inlineCount += 1
                    val result = JsInterpreter.evaluateWithDom(item.source, document, cuotasScript, fetchApi, storageApi)
                    scripts.add(NavScript("inline:" + inlineCount, JsInterpreter.dumpResult(result)))
                }
                is LoadItem.ScriptSrc -> {
                    val ref = "src:" + item.url
                    val r = fetchSide(item.url, finalUrl, site)
                    if (r is SideResult.NavFailure) {
                        return NavOutcome.Failed(NavState.LOADED, r.code, r.message, committed)
                    }
                    if (r is SideResult.Ok) {
                        val result = JsInterpreter.evaluateWithDom(r.source, document, cuotasScript, fetchApi, storageApi)
                        scripts.add(NavScript(ref, JsInterpreter.dumpResult(result)))
                    } else {
                        scripts.add(NavScript(ref, describe(r)))
                    }
                }
            }
        }
        events.add(NavEvent(NavState.LOADED, current))
        val loaded = committed.copy(
            subresources = subresources,
            scripts = scripts,
            events = ArrayList(events)
        )
        page = loaded
        return NavOutcome.Loaded(loaded)
    }

    private sealed class LoadItem {
        data class Subresource(val kind: String, val url: String) : LoadItem()
        data class InlineScript(val source: String) : LoadItem()
        data class ScriptSrc(val url: String) : LoadItem()
    }

    /** Resultado de un fetch lateral: subrecurso o script con src. */
    private sealed class SideResult {
        data class Ok(val source: String) : SideResult()
        object Blocked : SideResult()
        data class NavFailure(val code: ErrorCode, val message: String) : SideResult()
        data class Failed(val code: ErrorCode) : SideResult()
    }

    private fun describe(r: SideResult): String = when (r) {
        is SideResult.Ok -> "ok"
        SideResult.Blocked -> "blocked-mixed-content"
        is SideResult.Failed -> "failed:" + r.code.name
        is SideResult.NavFailure -> "failed:" + r.code.name
    }

    /**
     * Fetch lateral con mixed-content y cookies por contexto de sitio; un
     * CANCELLED o STALE_HANDLE aborta la navegacion (fallo tras el commit).
     */
    private fun fetchSide(url: String, page: Url, pageSite: Site): SideResult {
        if (page.scheme == "https" && url.startsWith("http://")) return SideResult.Blocked
        val u: Url = try {
            UrlParser.parse(url)
        } catch (e: UrlParseException) {
            return SideResult.Failed(ErrorCode.UNSUPPORTED)
        }
        val sideOrigin = Origin.of(u)
        val sideTuple = sideOrigin as? Origin.Tuple
            ?: return SideResult.Failed(ErrorCode.UNSUPPORTED)
        val sideSite = Site.fromOrigin(sideTuple)
        val context = if (sideSite == pageSite) SiteContext.SAME_SITE else SiteContext.EMBEDDED_CROSS_SITE
        val headers = HashMap<String, String>()
        val cookie = profile.cookies.cookieHeader(u.host, u.path, u.scheme == "https", context)
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val request = manager.newRequest("GET", url, headers)
        val outcome = manager.fetch(request)
        if (outcome is Outcome.Failure) {
            val code = outcome.error.code
            if (code == ErrorCode.CANCELLED || code == ErrorCode.STALE_HANDLE) {
                return SideResult.NavFailure(code, outcome.error.safeMessage)
            }
            return SideResult.Failed(code)
        }
        val resp = (outcome as Outcome.Success<FetchResponse>).value
        return SideResult.Ok(String(resp.body, Charsets.UTF_8))
    }

    /** Recorrido en orden del documento que declara la fase LOADED. */
    private fun walkLoadItems(node: DomNode, page: Url, out: MutableList<LoadItem>) {
        if (node is DomNode.Element) {
            when (node.name) {
                "script" -> {
                    val src = node.attributes["src"]
                    if (src == null) {
                        val text = node.children.firstOrNull() as? DomNode.Text
                        if (text != null && text.data.isNotBlank()) {
                            out.add(LoadItem.InlineScript(text.data))
                        }
                    } else {
                        val full = resolveUrl(page, src)
                        if (full != null) out.add(LoadItem.ScriptSrc(full))
                    }
                }
                "img" -> {
                    val src = node.attributes["src"]
                    if (src != null) {
                        val full = resolveUrl(page, src)
                        if (full != null) out.add(LoadItem.Subresource("image", full))
                    }
                }
                "link" -> {
                    val rel = node.attributes["rel"]
                    if (rel != null && rel.lowercase() == "stylesheet") {
                        val href = node.attributes["href"]
                        if (href != null) {
                            val full = resolveUrl(page, href)
                            if (full != null) out.add(LoadItem.Subresource("stylesheet", full))
                        }
                    }
                }
            }
        }
        val kids = when (node) {
            is DomNode.Document -> node.children
            is DomNode.Element -> node.children
            else -> null
        }
        if (kids != null) {
            for (c in kids) walkLoadItems(c, page, out)
        }
    }

    /** URL absoluta del subrecurso: absoluta o relativa al origen. */
    private fun resolveUrl(page: Url, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.startsWith("/")) {
            val port = if (page.port != null) ":" + page.port else ""
            return page.scheme + "://" + page.host + port + t
        }
        return null
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? {
        val lower = name.lowercase()
        for ((k, v) in headers) {
            if (k.lowercase() == lower) return v
        }
        return null
    }
}
