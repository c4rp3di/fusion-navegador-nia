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
import nia.engine.media.MediaPuente
import nia.engine.net.FetchManager
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.PoliticaHttpsOnly
import nia.engine.net.RedirectPolicy
import nia.engine.net.RequestContext
import nia.engine.net.RequestFilter
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
 * Exclusiones v1 (API propia del motor): frame tree minimo desde T071
 * v2 (R0416, RES-0165: iframes con src cargados 1 nivel, sin scripts
 * de subframe); CSP sigue fuera (X02); un solo Set-Cookie por respuesta (el
 * transporte entrega cabeceras como mapa); Location relativa compleja
 * sin resolver (RedirectPolicy la entrega como Stop); subrecursos
 * resueltos contra la base efectiva desde T111 v2 (R0404, RES-0161:
 * relativas de ruta, ?query y //host, y <base href>), sin iframe ni
 * font ni media contables; un script src con un unico hijo de
 * texto; scripts bloqueantes en LOADED sin event loop (T025, T072) ni
 * service workers (T034); back/forward que re-navega, sin snapshots. El
 * gemelo TS validado offline (2 corridas identicas, huella
 * f0bd98c8.2740b7a8.76) espeja estos 14 escenarios en NavControllerTest.
 *
 * T052 v1 (R0242): filtro de red opcional (RequestFilter) inyectado en
 * su FetchManager y en el JsFetchApi de los scripts de la pagina: el
 * documento lleva contexto tipo "document" y cada subrecurso
 * (image, stylesheet, script) viaja con su tipo e iniciador; el
 * tercero lo calcula el host contra Site v1. Sin filtro, el
 * comportamiento es exactamente el de antes.
 *
 * T071 v2 / X02 frame tree minimo (R0416, RES-0165): los iframes con
 * src del documento se cargan en LOADED como subframes de 1 nivel:
 * resolveUrl contra la base efectiva, fetchSide tipo "document" con
 * mixed-content y cookies EMBEDDED reales, subdocumento parseado (sus
 * scripts ejecutan desde v2d; sin subframes anidados). NavPage.subframes
 * expone cada uno
 * (src crudo, url resuelta, origin --null/opaco con sandbox sin
 * allow-same-origin--, flags sandbox medidos, estado y subdocumento).
 *
 * T071 v2c / TK-071-7 (R0423, RES-0167): los iframes SIN src (o con src
 * vacio) tambien entran al frame tree como about:blank SINTETICO:
 * subframe loaded con url "about:blank", origin null (opaco, NO hereda
 * el del padre) y subdocumento vacio, SIN fetch de red.
 *
 * T071 v2d / TK-071-1 (R0426, RES-0168): los scripts del subdocumento
 * corren en LOADED con las cuotas del nav, fetch con la url del subframe
 * como initiator y storage particionado (top-site del documento, origin
 * del subframe); sandbox sin allow-scripts los BLOQUEA; subframe opaco
 * corre SIN storage.
 *
 * T071 v2e / TK-071-2 (R0429, RES-0169): frame tree RECURSIVO con tope
 * de profundidad 3: los iframes dentro de un subframe cargan como
 * subframes de ese subframe (mismo fetch/particion/sandbox/scripts; el
 * fallo de red de cualquier nivel tumba la navegacion tras el commit
 * como los de nivel 1); a profundidad 3 no se cargan mas.
 *
 * T071 v2f / TK-071-4 (R0432, RES-0170): CSP por directiva: la cabecera
 * Content-Security-Policy del documento (si viene) se aplica con
 * script-src, style-src, img-src y frame-src con fallback a default-src
 * sobre scripts inline (solo 'unsafe-inline'), scripts src, subrecursos
 * image/stylesheet y frames de nivel 1; lo bloqueado se registra como
 * "blocked-csp" SIN fetch ni ejecucion; la CSP del subdocumento propio
 * queda para version siguiente.
 *
 * T111 v3 / TK-111-2 (R0434, RES-0171): async/defer/module: los script
 * src con defer (o type=module sin async) se ejecutan DESPUES del resto
 * de la carga, en orden de documento; async (y module con async) se
 * ejecutan al cargar como los classic; type=module inline tambien
 * defiere. Los module corren SIN resolucion de import/export
 * (exclusion): si fallan, la salida lo muestra.
 */
enum class NavState { NAVIGATING, RESPONSE_RECEIVED, PARSING, COMMITTED, LOADED }

/** Evento observable de una navegacion: fase y URL implicada. */
data class NavEvent(val state: NavState, val url: String)

/** Subrecurso contable: tipo (image, stylesheet), destino y resultado. */
data class NavSubresource(val kind: String, val url: String, val outcome: String)

/** Script ejecutado en LOADED: referencia inline:N o src:url, y salida. */
data class NavScript(val ref: String, val output: String)

/**
 * Subframe de 1 nivel (T071 v2, X02): src crudo del iframe, url resuelta
 * (null si no resuelve), origin del subframe (null/opaco cuando el
 * sandbox esta presente sin allow-same-origin), flags sandbox medidas,
 * estado (loaded, blocked-mixed-content, failed:CODE, unresolved), el
 * subdocumento parseado (null si no cargo), los scripts ejecutados
 * DENTRO desde T071 v2d (R0426, RES-0168) (inline:N o src:url con su
 * salida; vacio si el sandbox lo prohibe o no cargo) y, desde T071 v2e
 * (R0429, RES-0169), los subframes ANIDADOS dentro (arbol con tope de
 * profundidad 3; vacio en los niveles mas profundos).
 */
data class NavSubframe(
    val src: String,
    val url: String?,
    val origin: Origin?,
    val sandbox: List<String>,
    val estado: String,
    val document: DomDocument?,
    val scripts: List<NavScript> = emptyList(),
    val subframes: List<NavSubframe> = emptyList()
)

/**
 * Pagina comprometida: URL final tras redirecciones, estado, identidad,
 * documento vivo, revision capturada al commit y contadores de carga.
 *
 * T072 v2 (R0168, RES-0078): html lleva el crudo de la respuesta final
 * para que el shell pinte la pagina con el motor sin re-fetch; null si
 * el llamador no lo conoce.
 *
 * T071 v2 (R0416, RES-0165): subframes lista los iframes con src del
 * documento (frame tree de 1 nivel, X02); vacia en paginas sin iframe.
 *
 * T071 v2f (R0432, RES-0170): csp lleva el crudo de la cabecera
 * Content-Security-Policy de la respuesta del documento (null si no
 * viene); la politica se aplica por directiva sobre los subrecursos,
 * scripts y frames de ESTE documento.
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
    val html: String? = null,
    val subframes: List<NavSubframe> = emptyList(),
    val csp: String? = null
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
    private val httpsOnly: PoliticaHttpsOnly? = null,
    private val filter: RequestFilter? = null
) {
    private val manager = FetchManager(transport, clock, profile.cache, filter)
    private val history = ArrayList<String>()
    private var index = -1
    private var page: NavPage? = null
    private var lastState: NavState? = null

    /** Cuotas de los scripts de la pagina (T100, RES-0104): el shell
     * fija el JsLimits resuelto ANTES de navegar; por defecto el del
     * motor (100M pasos y 10M allocs, T021). */
    var cuotasScript: JsLimits = JsLimits()

    /** T092 v2 (R0358, RES-0145): puente HTMLMediaElement (TK-111-1)
     * inyectable: el shell lo implementa sobre el backend REAL de
     * plataforma; con null los video/audio de la pagina NO exponen API
     * de media (honestidad: nada se finge). */
    var puenteMedia: MediaPuente? = null

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
            val outcome = manager.fetch(request, null, deadlineMillis, RequestContext.of(current, "document", ""))
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
        val fuente = decodificarDocumento(body, headerValue(body.headers, "content-type"))
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
        val cspCrudo = headerValue(body.headers, "content-security-policy")
        val csp = parseCsp(cspCrudo)
        lastState = NavState.COMMITTED
        events.add(NavEvent(NavState.COMMITTED, current))
        if (push) {
            while (history.size > index + 1) history.removeAt(history.size - 1)
            history.add(current)
            index = history.size - 1
        }
        val committed = NavPage(
            current, body.status, origin, site, document, document.snapshot().revision,
            ArrayList(), ArrayList(), ArrayList(events), body.seguridad, fuente,
            csp = cspCrudo
        )
        page = committed
        lastState = NavState.LOADED
        val items = ArrayList<LoadItem>()
        walkLoadItems(document.nodeOf(document.rootHandle()), baseDeResolucion(document, finalUrl), items)
        val subresources = ArrayList<NavSubresource>()
        val scripts = ArrayList<NavScript>()
        val subframes = ArrayList<NavSubframe>()
        val storageApi = JsStorageApi(profile.storage, profile.idb, site, origin)
        val fetchApi = JsFetchApi(transport, clock, null, filter, current)
        val baseMedia = finalUrl.scheme + "://" + finalUrl.host +
            (if (finalUrl.port != null) ":" + finalUrl.port else "")
        var inlineCount = 0
        val deferPendientes = ArrayList<DeferPendiente>()
        var deferInline = 0
        for (item in items) {
            when (item) {
                is LoadItem.Subresource -> {
                    val dir = if (item.kind == "stylesheet") "style-src" else "img-src"
                    if (!cspPermite(csp, dir, item.url, origin, false)) {
                        subresources.add(NavSubresource(item.kind, item.url, "blocked-csp"))
                    } else {
                        val r = fetchSide(item.url, finalUrl, site, item.kind, current)
                        if (r is SideResult.NavFailure) {
                            return NavOutcome.Failed(NavState.LOADED, r.code, r.message, committed)
                        }
                        subresources.add(NavSubresource(item.kind, item.url, describe(r)))
                    }
                }
                is LoadItem.InlineScript -> {
                    if (item.defer) {
                        deferInline += 1
                        deferPendientes.add(DeferPendiente("defer:inline:" + deferInline, null, item.source))
                    } else {
                        inlineCount += 1
                        if (!cspPermite(csp, "script-src", null, origin, true)) {
                            scripts.add(NavScript("inline:" + inlineCount, "blocked-csp"))
                        } else {
                            val result = JsInterpreter.evaluateWithDom(item.source, document, cuotasScript, fetchApi, storageApi, puenteMedia, baseMedia)
                            scripts.add(NavScript("inline:" + inlineCount, JsInterpreter.dumpResult(result)))
                        }
                    }
                }
                is LoadItem.ScriptSrc -> {
                    if (item.defer) {
                        deferPendientes.add(DeferPendiente("defer:src:" + item.url, item.url, null))
                    } else {
                        val ref = if (item.async) "async:src:" + item.url else "src:" + item.url
                        if (!cspPermite(csp, "script-src", item.url, origin, false)) {
                            scripts.add(NavScript(ref, "blocked-csp"))
                        } else {
                            val r = fetchSide(item.url, finalUrl, site, "script", current)
                            if (r is SideResult.NavFailure) {
                                return NavOutcome.Failed(NavState.LOADED, r.code, r.message, committed)
                            }
                            if (r is SideResult.Ok) {
                                val result = JsInterpreter.evaluateWithDom(r.source, document, cuotasScript, fetchApi, storageApi, puenteMedia, baseMedia)
                                scripts.add(NavScript(ref, JsInterpreter.dumpResult(result)))
                            } else {
                                scripts.add(NavScript(ref, describe(r)))
                            }
                        }
                    }
                }
                is LoadItem.Frame -> {
                    val frameUrl = resolveUrl(finalUrl, item.src)
                    if (frameUrl != null && !cspPermite(csp, "frame-src", frameUrl, origin, false)) {
                        subframes.add(NavSubframe(item.src, frameUrl, null, item.sandbox, "blocked-csp", null))
                    } else {
                        val c = cargarSubframe(item.src, item.sandbox, finalUrl, site, current, 1)
                        if (c is CargaSubframe.Fallo) {
                            return NavOutcome.Failed(NavState.LOADED, c.code, c.message, committed)
                        }
                        subframes.add((c as CargaSubframe.Ok).subframe)
                    }
                }
            }
        }
        for (d in deferPendientes) {
            if (d.src != null) {
                val ds = d.src
                if (!cspPermite(csp, "script-src", ds, origin, false)) {
                    scripts.add(NavScript(d.ref, "blocked-csp"))
                } else {
                    val r = fetchSide(ds, finalUrl, site, "script", current)
                    if (r is SideResult.NavFailure) {
                        return NavOutcome.Failed(NavState.LOADED, r.code, r.message, committed)
                    }
                    if (r is SideResult.Ok) {
                        val result = JsInterpreter.evaluateWithDom(r.source, document, cuotasScript, fetchApi, storageApi, puenteMedia, baseMedia)
                        scripts.add(NavScript(d.ref, JsInterpreter.dumpResult(result)))
                    } else {
                        scripts.add(NavScript(d.ref, describe(r)))
                    }
                }
            } else {
                if (!cspPermite(csp, "script-src", null, origin, true)) {
                    scripts.add(NavScript(d.ref, "blocked-csp"))
                } else {
                    val result = JsInterpreter.evaluateWithDom(d.source!!, document, cuotasScript, fetchApi, storageApi, puenteMedia, baseMedia)
                    scripts.add(NavScript(d.ref, JsInterpreter.dumpResult(result)))
                }
            }
        }
        events.add(NavEvent(NavState.LOADED, current))
        val loaded = committed.copy(
            subresources = subresources,
            scripts = scripts,
            events = ArrayList(events),
            subframes = subframes
        )
        page = loaded
        return NavOutcome.Loaded(loaded)
    }

    /**
     * T111 v3 / TK-111-2 (R0434, RES-0171): script defer encolado durante
     * el recorrido, con su ref ya numerada y el src o el fuente crudo
     * (nunca ambos); se ejecuta al final de la carga, en orden.
     */
    private class DeferPendiente(val ref: String, val src: String?, val source: String?)

    private sealed class LoadItem {
        data class Subresource(val kind: String, val url: String) : LoadItem()
        data class InlineScript(
            val source: String,
            val defer: Boolean = false,
            val module: Boolean = false
        ) : LoadItem()
        data class ScriptSrc(
            val url: String,
            val async: Boolean = false,
            val defer: Boolean = false,
            val module: Boolean = false
        ) : LoadItem()
        data class Frame(val src: String, val sandbox: List<String>) : LoadItem()
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
    private fun fetchSide(url: String, page: Url, pageSite: Site, kind: String, initiatorUrl: String): SideResult {
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
        val outcome = manager.fetch(request, null, -1, RequestContext.of(url, kind, initiatorUrl))
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

    /**
     * T071 v2 (R0416, RES-0165): origin del subframe; fetchSide ya
     * rechaza urls con origen no-Tuple, asi que aqui el parse no falla.
     */
    private sealed class CargaSubframe {
        data class Ok(val subframe: NavSubframe) : CargaSubframe()
        data class Fallo(val code: ErrorCode, val message: String) : CargaSubframe()
    }

    /**
     * T071 v2e / TK-071-2 (R0429, RES-0169): carga un subframe del frame
     * tree RECURSIVO (tope de profundidad 3): about:blank sintetico sin
     * src (v2c), fetchSide tipo document, scripts del subdocumento (v2d)
     * y los iframes DENTRO cargados un nivel mas abajo hasta el tope; el
     * fallo de red de cualquier nivel se propaga como Fallo y la rama del
     * nivel 1 decide el return de la navegacion.
     */
    private fun cargarSubframe(
        src: String,
        sandbox: List<String>,
        base: Url,
        topSite: Site,
        currentUrl: String,
        profundidad: Int
    ): CargaSubframe {
        if (src.isEmpty()) {
            return CargaSubframe.Ok(NavSubframe(src, "about:blank", null, sandbox, "loaded", DomDocument.parse("")))
        }
        val full = resolveUrl(base, src)
        if (full == null) {
            return CargaSubframe.Ok(NavSubframe(src, null, null, sandbox, "unresolved", null))
        }
        val r = fetchSide(full, base, topSite, "document", currentUrl)
        if (r is SideResult.NavFailure) {
            return CargaSubframe.Fallo(r.code, r.message)
        }
        if (r !is SideResult.Ok) {
            return CargaSubframe.Ok(NavSubframe(src, full, null, sandbox, describe(r), null))
        }
        val opaco = sandbox.isNotEmpty() && "allow-same-origin" !in sandbox
        val subOrigin = if (opaco) null else subframeOrigin(full)
        val subDoc = DomDocument.parse(r.source)
        val subScripts = ArrayList<NavScript>()
        val subHijos = ArrayList<NavSubframe>()
        if (sandbox.isEmpty() || "allow-scripts" in sandbox) {
            val subUrl = try {
                UrlParser.parse(full)
            } catch (e: UrlParseException) {
                null
            }
            if (subUrl != null) {
                val subFetchApi = JsFetchApi(transport, clock, null, filter, full)
                val subTuple = subOrigin as? Origin.Tuple
                val subStorageApi = if (subTuple == null) null else JsStorageApi(profile.storage, profile.idb, topSite, subTuple)
                val subBaseMedia = subUrl.scheme + "://" + subUrl.host +
                    (if (subUrl.port != null) ":" + subUrl.port else "")
                val subItems = ArrayList<LoadItem>()
                walkLoadItems(subDoc.nodeOf(subDoc.rootHandle()), subUrl, subItems)
                val subDefer = ArrayList<DeferPendiente>()
                var subInline = 0
                for (sub in subItems) {
                    when (sub) {
                        is LoadItem.InlineScript -> {
                            if (sub.defer) {
                                subInline += 1
                                subDefer.add(DeferPendiente("defer:inline:" + subInline, null, sub.source))
                            } else {
                                subInline += 1
                                val result = JsInterpreter.evaluateWithDom(sub.source, subDoc, cuotasScript, subFetchApi, subStorageApi, puenteMedia, subBaseMedia)
                                subScripts.add(NavScript("inline:" + subInline, JsInterpreter.dumpResult(result)))
                            }
                        }
                        is LoadItem.ScriptSrc -> {
                            if (sub.defer) {
                                subDefer.add(DeferPendiente("defer:src:" + sub.url, sub.url, null))
                            } else {
                                val ref = if (sub.async) "async:src:" + sub.url else "src:" + sub.url
                                val sr = fetchSide(sub.url, subUrl, topSite, "script", currentUrl)
                                if (sr is SideResult.NavFailure) {
                                    return CargaSubframe.Fallo(sr.code, sr.message)
                                }
                                if (sr is SideResult.Ok) {
                                    val result = JsInterpreter.evaluateWithDom(sr.source, subDoc, cuotasScript, subFetchApi, subStorageApi, puenteMedia, subBaseMedia)
                                    subScripts.add(NavScript(ref, JsInterpreter.dumpResult(result)))
                                } else {
                                    subScripts.add(NavScript(ref, describe(sr)))
                                }
                            }
                        }
                        is LoadItem.Frame -> {
                            if (profundidad < 3) {
                                val cr = cargarSubframe(sub.src, sub.sandbox, subUrl, topSite, currentUrl, profundidad + 1)
                                if (cr is CargaSubframe.Fallo) {
                                    return cr
                                }
                                subHijos.add((cr as CargaSubframe.Ok).subframe)
                            }
                        }
                        else -> {}
                    }
                }
                for (d in subDefer) {
                    if (d.src != null) {
                        val ds = d.src
                        val r2 = fetchSide(ds, subUrl, topSite, "script", currentUrl)
                        if (r2 is SideResult.NavFailure) {
                            return CargaSubframe.Fallo(r2.code, r2.message)
                        }
                        if (r2 is SideResult.Ok) {
                            val result = JsInterpreter.evaluateWithDom(r2.source, subDoc, cuotasScript, subFetchApi, subStorageApi, puenteMedia, subBaseMedia)
                            subScripts.add(NavScript(d.ref, JsInterpreter.dumpResult(result)))
                        } else {
                            subScripts.add(NavScript(d.ref, describe(r2)))
                        }
                    } else {
                        val result = JsInterpreter.evaluateWithDom(d.source!!, subDoc, cuotasScript, subFetchApi, subStorageApi, puenteMedia, subBaseMedia)
                        subScripts.add(NavScript(d.ref, JsInterpreter.dumpResult(result)))
                    }
                }
            }
        }
        return CargaSubframe.Ok(
            NavSubframe(src, full, subOrigin, sandbox, "loaded", subDoc, subScripts, subHijos)
        )
    }

    /**
     * T071 v2f / TK-071-4 (R0432, RES-0170): parseo de la cabecera
     * Content-Security-Policy: directivas separadas por ';', nombre en
     * minusculas y fuentes en crudo (vacias = 'none'); null si la
     * cabecera no viene. Las directivas desconocidas se guardan y se
     * ignoran al aplicar.
     */
    private fun parseCsp(crudo: String?): Map<String, List<String>>? {
        if (crudo == null) return null
        val out = LinkedHashMap<String, List<String>>()
        for (parte in crudo.split(';')) {
            val tokens = parte.trim().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            out[tokens[0].lowercase()] = tokens.drop(1)
        }
        return out
    }

    /**
     * T071 v2f / TK-071-4: aplicacion POR DIRECTIVA con fallback a
     * default-src SOLO si la directiva especifica falta (la especifica
     * gana entera): sin CSP pasa todo; fuentes vacias o 'none' bloquean;
     * inline pasa solo con 'unsafe-inline'; una url pasa con '*', con
     * 'self' (mismo origen del documento) o si su origen coincide
     * exacto con una fuente. Sin hashes, nonces, wildcards parciales ni
     * report-only (v1).
     */
    private fun cspPermite(
        csp: Map<String, List<String>>?,
        directiva: String,
        url: String?,
        pageOrigin: Origin,
        inline: Boolean
    ): Boolean {
        if (csp == null) return true
        val fuentes = csp[directiva] ?: csp["default-src"] ?: return true
        if (fuentes.isEmpty() || "'none'" in fuentes) return false
        if (inline) return "'unsafe-inline'" in fuentes
        if (url == null) return false
        if ("*" in fuentes) return true
        val origenUrl = try {
            Origin.of(UrlParser.parse(url))
        } catch (e: UrlParseException) {
            null
        } ?: return false
        for (f in fuentes) {
            if (f == "'self'") {
                if (origenUrl == pageOrigin) return true
            } else {
                val origenFuente = try {
                    Origin.of(UrlParser.parse(f))
                } catch (e: UrlParseException) {
                    null
                }
                if (origenFuente != null && origenFuente == origenUrl) return true
            }
        }
        return false
    }

    private fun subframeOrigin(url: String): Origin? = try {
        Origin.of(UrlParser.parse(url))
    } catch (e: UrlParseException) {
        null
    }

    /** Flags del atributo sandbox en minusculas, sin vacios; sin atributo, vacia. */
    private fun sandboxFlags(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return raw.split(' ', '\t', '\n', '\r', '\u000C')
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Recorrido en orden del documento que declara la fase LOADED.
     *
     * T111 v3 (R0434, RES-0171): los script se recogen con async/defer
     * (por presencia) y type=module medidos: async gana a defer, defer o
     * module sin async difieren, el inline classic ignora defer y solo
     * el module inline defiere.
     */
    private fun walkLoadItems(node: DomNode, page: Url, out: MutableList<LoadItem>) {
        if (node is DomNode.Element) {
            when (node.name) {
                "script" -> {
                    val src = node.attributes["src"]
                    val tipo = node.attributes["type"]
                    val module = tipo != null && tipo.trim().lowercase() == "module"
                    val esAsync = node.attributes.containsKey("async")
                    val esDefer = (node.attributes.containsKey("defer") || module) && !esAsync
                    if (src == null) {
                        val text = node.children.firstOrNull() as? DomNode.Text
                        if (text != null && text.data.isNotBlank()) {
                            out.add(LoadItem.InlineScript(text.data, module, module))
                        }
                    } else {
                        val full = resolveUrl(page, src)
                        if (full != null) out.add(LoadItem.ScriptSrc(full, esAsync, esDefer, module))
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
                "iframe" -> {
                    val src = node.attributes["src"]
                    out.add(LoadItem.Frame(src ?: "", sandboxFlags(node.attributes["sandbox"])))
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

    /**
     * T111 v2 (R0404, RES-0161): URL absoluta de un subrecurso contra la
     * base efectiva: absolutas, //host, /ruta, ?query y rutas relativas
     * con segmentos . y .. (los normaliza UrlParser al parsear la
     * candidata). null si la referencia queda vacia, es solo #fragmento
     * o la candidata no parsea.
     */
    private fun resolveUrl(page: Url, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.startsWith("#")) return null
        val origen = page.scheme + "://" + page.host +
            (if (page.port != null) ":" + page.port else "")
        if (t.startsWith("//")) return page.scheme + ":" + t
        if (t.startsWith("?")) return origen + page.path + t
        if (t.startsWith("/")) return origen + t
        val corte = page.path.lastIndexOf('/')
        val dir = if (corte <= 0) "/" else page.path.substring(0, corte + 1)
        return try {
            UrlParser.parse(origen + dir + t).toString()
        } catch (e: UrlParseException) {
            null
        }
    }

    /**
     * T111 v2 (R0404, RES-0161): base efectiva de resolucion: el href del
     * primer <base> del documento si existe y parsea (absoluto o relativo
     * a la pagina); si no, la URL de la pagina.
     */
    private fun baseDeResolucion(document: DomDocument, page: Url): Url {
        val handle = document.findFirst("base") ?: return page
        val href = document.attributeOf(handle, "href")?.trim() ?: return page
        if (href.isEmpty()) return page
        val resuelta = resolveUrl(page, href) ?: return page
        return try {
            UrlParser.parse(resuelta)
        } catch (e: UrlParseException) {
            page
        }
    }

    /**
     * T111 v2 (R0404, RES-0161, TK-111-4): decodifica el documento con el
     * charset de la cabecera Content-Type y, en su ausencia, con el del
     * primer meta charset del crudo (escaneado como latin-1 para leer los
     * bytes intactos en los primeros 2048 caracteres); si no hay ninguno,
     * o el nombre es desconocido, UTF-8 como antes.
     */
    private fun decodificarDocumento(respuesta: FetchResponse, contentType: String?): String {
        val porCabecera = charsetDeCabecera(contentType)
        if (porCabecera != null) {
            return decodificar(respuesta.body, porCabecera)
        }
        val vista = String(respuesta.body, Charsets.ISO_8859_1)
        val limite = minOf(vista.length, 2048)
        val m = Regex("(?i)<meta[^>]{0,200}charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._\\-]+)").find(vista.substring(0, limite))
        if (m != null) {
            return decodificar(respuesta.body, m.groupValues[1])
        }
        return String(respuesta.body, Charsets.UTF_8)
    }

    /** charset declarado en una cabecera Content-Type; null si no esta. */
    private fun charsetDeCabecera(contentType: String?): String? {
        if (contentType == null) return null
        val m = Regex("(?i)charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._\\-]+)").find(contentType)
        return m?.groupValues?.get(1)
    }

    private fun decodificar(crudo: ByteArray, nombre: String): String =
        try {
            String(crudo, java.nio.charset.Charset.forName(nombre))
        } catch (e: Exception) {
            String(crudo, Charsets.UTF_8)
        }

    private fun headerValue(headers: Map<String, String>, name: String): String? {
        val lower = name.lowercase()
        for ((k, v) in headers) {
            if (k.lowercase() == lower) return v
        }
        return null
    }
}
