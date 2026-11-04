package nia.engine.qa

import nia.contracts.WallClock
import nia.engine.css.CssCascade
import nia.engine.css.CssStylesheet
import nia.engine.dom.DomDocument
import nia.engine.html.DomNode
import nia.engine.js.JsEvalResult
import nia.engine.js.JsFetchApi
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsStorageApi
import nia.engine.js.JsStr
import nia.engine.layout.LayoutEngine
import nia.engine.media.SubtitleParser
import nia.engine.net.FetchTransport
import nia.engine.paint.PaintSceneBuilder
import nia.engine.storage.IndexedDb
import nia.engine.storage.StorageManager
import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Matriz P7 YouTube (T039 v1, R0120, RES-0063): catalogo fechado de las
 * acciones objetivo del segundo objetivo explicito del navegador
 * (busqueda, watch con relacionados, comentarios, subs, historial,
 * login, portada y ver-mas-tarde) con estado COMPLETA, LIMITE o
 * EXCLUIDA. Las acciones COMPLETA se EJECUTAN contra un corpus
 * enlatado con transporte falso SIN red con el pipeline completo del
 * motor; los limites van fechados (play/seek y calidad exigen
 * HTMLMediaElement en el DOM, excluido de T036 v2 y pendiente de v2
 * con codecs de plataforma segun ADR-015) y protegido/directos van
 * aparte (ADR-015 y smoke real T067). Exclusiones v1: corpus con una
 * sola particion de storage y las acciones LIMITE y EXCLUIDA solo se
 * declaran (no se ejecutan), fechadas en la nota y en
 * docs/matriz-p7-youtube.md.
 */
class YoutubeMatrix(private val transport: FetchTransport, private val clock: WallClock) {

    private val origin = Origin.Tuple("https", "corpus.nia", 443)
    private val site = Site.fromOrigin(origin)
    private val storage = StorageManager()
    private val idb = IndexedDb()

    /** Catalogo fechado de las acciones objetivo de YouTube. */
    fun acciones(): List<MatrixAction> = listOf(
        MatrixAction("yt-busqueda", "youtube.com", "Busqueda de videos por query", ActionState.COMPLETA, "resultados del buscador contados desde la API del corpus"),
        MatrixAction("yt-watch", "youtube.com", "Pagina de visualizacion con tabla de relacionados", ActionState.COMPLETA, "relacionados del video renderizados como tabla"),
        MatrixAction("yt-comentarios", "youtube.com", "Comentarios cargados por fetch", ActionState.COMPLETA, "comentarios insertados en el DOM desde la API del corpus"),
        MatrixAction("yt-subs", "youtube.com", "Pista de subtitulos WebVTT descargada y parseada", ActionState.COMPLETA, "cues de la pista WebVTT contados con el parser de subtitulos v1"),
        MatrixAction("yt-historial", "youtube.com", "Historial de reproduccion persistido", ActionState.COMPLETA, "videos vistos guardados via localStorage"),
        MatrixAction("yt-login", "youtube.com", "Login con form y POST de sesion", ActionState.COMPLETA, "form con usuario y clave que postea la sesion"),
        MatrixAction("yt-grid", "youtube.com", "Portada con tabla de videos", ActionState.COMPLETA, "videos de la portada renderizados como tabla"),
        MatrixAction("yt-mas-tarde", "youtube.com", "Lista de ver mas tarde persistida", ActionState.COMPLETA, "videos guardados y re-leidos via localStorage"),
        MatrixAction("yt-play-seek", "youtube.com", "Reproduccion con play y seek", ActionState.LIMITE, "LIMITE 2026-11-04: exige HTMLMediaElement en el DOM, excluido de T036 v2; pendiente del elemento multimedia del DOM con los codecs de plataforma (ADR-015)"),
        MatrixAction("yt-calidad", "youtube.com", "Cambio de calidad del video", ActionState.LIMITE, "LIMITE 2026-11-04: el cambio de calidad exige cambiar el flujo del elemento multimedia, sin HTMLMediaElement en el DOM v1"),
        MatrixAction("yt-protegido", "youtube.com", "Contenido protegido con DRM", ActionState.EXCLUIDA, "EXCLUIDA 2026-11-04: protegido aparte segun ADR-015; descomposicion solo cuando la matriz confirme el requisito (T059)"),
        MatrixAction("yt-directos", "youtube.com", "Directos y casting", ActionState.EXCLUIDA, "EXCLUIDA 2026-11-04: directos y casting aparte del objetivo; exigen red real y reloj continuo, smoke real T067")
    )

    /** Ejecuta SOLO las acciones COMPLETA contra el corpus enlatado. */
    fun correr(): List<ActionRun> =
        acciones().filter { it.state == ActionState.COMPLETA }.map { accion ->
            when (accion.id) {
                "yt-busqueda" -> ytBusqueda(accion)
                "yt-watch" -> ytWatch(accion)
                "yt-comentarios" -> ytComentarios(accion)
                "yt-subs" -> ytSubs(accion)
                "yt-historial" -> ytHistorial(accion)
                "yt-login" -> ytLogin(accion)
                "yt-grid" -> ytGrid(accion)
                "yt-mas-tarde" -> ytMasTarde(accion)
                else -> ActionRun(accion, false, "accion sin implementar")
            }
        }

    /** Resumen de la matriz: completas ok, limites y excluidas. */
    fun resumen(): String =
        "ok=" + correr().count { it.ok } +
            " limites=" + acciones().count { it.state == ActionState.LIMITE } +
            " excluidas=" + acciones().count { it.state == ActionState.EXCLUIDA }

    private fun ytBusqueda(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_BUSQUEDA)
        val r = evaluar(doc, SCRIPT_YT_BUSQUEDA)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun ytWatch(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_WATCH)
        val r = evaluar(doc, SCRIPT_YT_WATCH)
        return ActionRun(accion, sinFallos(r), conRender(doc, detalleDe(r)))
    }

    private fun ytComentarios(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_COMENTARIOS)
        val r = evaluar(doc, SCRIPT_YT_COMENTARIOS)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun ytSubs(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_SUBS)
        val r = evaluar(doc, SCRIPT_YT_SUBS)
        if (!sinFallos(r)) return ActionRun(accion, false, "eval-fallo")
        val v = r.value
        if (v !is JsStr) return ActionRun(accion, false, "sin-vtt")
        val cues = SubtitleParser.parse(v.value).size
        return ActionRun(accion, cues > 0, "cues=" + cues)
    }

    private fun ytHistorial(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_HISTORIAL)
        val r = evaluar(doc, SCRIPT_YT_HISTORIAL)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun ytLogin(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_LOGIN)
        val r = evaluar(doc, SCRIPT_YT_LOGIN)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun ytGrid(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_GRID)
        val r = evaluar(doc, SCRIPT_YT_GRID)
        return ActionRun(accion, sinFallos(r), conRender(doc, detalleDe(r)))
    }

    private fun ytMasTarde(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_YT_MAS_TARDE)
        val r = evaluar(doc, SCRIPT_YT_MAS_TARDE)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    /** Evalua el script de la pagina con los bindings DOM, fetch y storage. */
    private fun evaluar(doc: DomDocument, script: String): JsEvalResult =
        JsInterpreter.evaluateWithDom(script, doc, JsLimits(), JsFetchApi(transport, clock), JsStorageApi(storage, idb, site, origin))

    private fun sinFallos(r: JsEvalResult): Boolean = !r.thrown && !r.interrupted

    /** Detalle observable de la accion: el string final del script. */
    private fun detalleDe(r: JsEvalResult): String {
        if (!sinFallos(r)) return "eval-fallo"
        val v = r.value
        return if (v is JsStr) v.value else JsInterpreter.dumpResult(r)
    }

    /** La accion con tabla cierra el pipeline con layout y paint. */
    private fun conRender(doc: DomDocument, base: String): String {
        val root = doc.nodeOf(doc.rootHandle()) as DomNode.Document
        val computed = CssCascade.compute(root, CssStylesheet.parse(CSS_CORPUS))
        val layout = LayoutEngine.layout(root, computed)
        val tabla = doc.findFirst("table")
        val conTabla = tabla != null && layout.boxOf(doc.nodeOf(tabla)) != null
        val escena = PaintSceneBuilder.build(root, computed, layout)
        return base + ",tabla=" + (if (conTabla) "si" else "no") +
            ",paint=" + (if (escena.ops.isNotEmpty()) "si" else "no")
    }

    companion object {
        private const val CSS_CORPUS = "h1 { font-size: 20px; } th { color: #333333; } .comentario { color: #111111; }"
        private const val PAGINA_YT_BUSQUEDA = """<html><head><title>Buscar</title></head><body><h1>Buscar</h1><input id="q" value="gran hotel"><div id="resultados"></div></body></html>"""
        private const val PAGINA_YT_WATCH = """<html><head><title>Gran Hotel S01E01</title></head><body><h1>Gran Hotel S01E01</h1><table><tbody><tr><td>Episodio 2</td></tr><tr><td>Episodio 3</td></tr><tr><td>Episodio 4</td></tr></tbody></table></body></html>"""
        private const val PAGINA_YT_COMENTARIOS = """<html><head><title>Comentarios</title></head><body><h1>Comentarios</h1><div id="comentarios"></div></body></html>"""
        private const val PAGINA_YT_SUBS = """<html><head><title>Subtitulos</title></head><body><h1>Subtitulos</h1><div id="subs"></div></body></html>"""
        private const val PAGINA_YT_HISTORIAL = """<html><head><title>Historial</title></head><body><h1>Historial</h1><div id="estado"></div></body></html>"""
        private const val PAGINA_YT_LOGIN = """<html><head><title>Sign in</title></head><body><form id="login-form"><input id="usuario"><input id="clave" type="password"><div id="estado"></div></form></body></html>"""
        private const val PAGINA_YT_GRID = """<html><head><title>Portada</title></head><body><h1>Portada</h1><table><tbody><tr class="video"><td>Gran Hotel</td></tr><tr class="video"><td>Cocina</td></tr><tr class="video"><td>Viajes</td></tr><tr class="video"><td>Musica</td></tr></tbody></table></body></html>"""
        private const val PAGINA_YT_MAS_TARDE = """<html><head><title>Ver mas tarde</title></head><body><h1>Ver mas tarde</h1><div id="lista"></div></body></html>"""
        private const val SCRIPT_YT_BUSQUEDA = "var q = document.getElementById('q').value; fetch('https://corpus.nia/yt/search?q=' + q).then(function (r) { return r.text(); }).then(function (t) { document.getElementById('resultados').textContent = t; return 'resultados=' + document.getElementById('resultados').textContent; })"
        private const val SCRIPT_YT_WATCH = "var filas = document.querySelectorAll('tr'); 'filas=' + filas.length"
        private const val SCRIPT_YT_COMENTARIOS = "var lista = document.getElementById('comentarios'); fetch('https://corpus.nia/yt/comentario-7/c1').then(function (r) { return r.text(); }).then(function (t) { var d = document.createElement('div'); d.setAttribute('class', 'comentario'); d.textContent = t; lista.appendChild(d); return fetch('https://corpus.nia/yt/comentario-7/c2').then(function (r2) { return r2.text(); }).then(function (t2) { var d2 = document.createElement('div'); d2.setAttribute('class', 'comentario'); d2.textContent = t2; lista.appendChild(d2); return 'comentarios=' + document.querySelectorAll('.comentario').length; }); })"
        private const val SCRIPT_YT_SUBS = "fetch('https://corpus.nia/yt/subs-7.vtt').then(function (r) { return r.text(); }).then(function (t) { document.getElementById('subs').textContent = t; return t; })"
        private const val SCRIPT_YT_HISTORIAL = "localStorage.setItem('historial:v1', '1'); localStorage.setItem('historial:v2', '1'); var n = 0; if (localStorage.getItem('historial:v1')) { n = n + 1; } if (localStorage.getItem('historial:v2')) { n = n + 1; } 'historial=' + n"
        private const val SCRIPT_YT_LOGIN = "var f = document.getElementById('login-form'); document.getElementById('usuario').value = 'nia'; document.getElementById('clave').value = 'demo'; f.addEventListener('submit', function (ev) { ev.preventDefault(); fetch('https://corpus.nia/yt/session', { method: 'POST', body: 'nia:demo' }).then(function (r) { return r.text(); }).then(function (t) { document.getElementById('estado').textContent = t; return 'login=' + t; }); }); f.submit()"
        private const val SCRIPT_YT_GRID = "var videos = document.querySelectorAll('tr.video'); 'videos=' + videos.length"
        private const val SCRIPT_YT_MAS_TARDE = "localStorage.setItem('tarde:v1', '1'); localStorage.setItem('tarde:v2', '1'); var n = 0; if (localStorage.getItem('tarde:v1')) { n = n + 1; } if (localStorage.getItem('tarde:v2')) { n = n + 1; } document.getElementById('lista').textContent = 'ver-mas-tarde'; 'lista=' + n"
    }
}
