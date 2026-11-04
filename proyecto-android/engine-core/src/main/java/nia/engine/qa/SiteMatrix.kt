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
import nia.engine.net.FetchTransport
import nia.engine.paint.PaintSceneBuilder
import nia.engine.storage.IndexedDb
import nia.engine.storage.StorageManager
import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Matriz P7 GitHub/Gmail (T038 v1, R0118, RES-0062): catalogo fechado de
 * las acciones objetivo de ADR-012 (GitHub: repo, archivos, issues y
 * diffs; Gmail: bandeja, hilos, busqueda y redaccion) con estado
 * COMPLETA, LIMITE o EXCLUIDA. Las acciones COMPLETA se EJECUTAN contra
 * un corpus enlatado con transporte falso SIN red: cada pagina se
 * parsea con el motor, su script corre con los bindings DOM, fetch y
 * storage de las tareas ya verificadas, y el pipeline termina en
 * layout y paint cuando la accion lo exige (tablas de archivos y de
 * hilos). El informe es determinista y reproducible; la red real y las
 * sesiones del usuario quedan para el smoke real (T067). Exclusiones
 * v1: corpus con una sola particion de storage, las acciones LIMITE y
 * EXCLUIDA solo se declaran (no se ejecutan) y los limites van
 * fechados en la nota de cada accion y en docs/matriz-p7-github-gmail.md.
 */
enum class ActionState { COMPLETA, LIMITE, EXCLUIDA }

data class MatrixAction(
    val id: String,
    val site: String,
    val title: String,
    val state: ActionState,
    val note: String
)

data class ActionRun(
    val action: MatrixAction,
    val ok: Boolean,
    val detail: String
)

class GithubGmailMatrix(private val transport: FetchTransport, private val clock: WallClock) {

    private val origin = Origin.Tuple("https", "corpus.nia", 443)
    private val site = Site.fromOrigin(origin)
    private val storage = StorageManager()
    private val idb = IndexedDb()

    /** Catalogo fechado de las acciones objetivo de ADR-012. */
    fun acciones(): List<MatrixAction> = listOf(
        MatrixAction("gh-repo", "github.com", "Repo publico con tabla de archivos", ActionState.COMPLETA, "lista de archivos del repo renderizada como tabla"),
        MatrixAction("gh-issue", "github.com", "Issue con comentarios cargados por fetch", ActionState.COMPLETA, "comentarios insertados en el DOM desde la API del corpus"),
        MatrixAction("gh-login", "github.com", "Login con form y POST de session", ActionState.COMPLETA, "form con usuario y clave que postea la sesion"),
        MatrixAction("gm-bandeja", "mail.google.com", "Bandeja con tabla de hilos", ActionState.COMPLETA, "hilos de la bandeja renderizados como tabla"),
        MatrixAction("gm-hilo", "mail.google.com", "Hilo abierto con mensaje por fetch", ActionState.COMPLETA, "mensaje del hilo insertado desde la API del corpus"),
        MatrixAction("gm-busqueda", "mail.google.com", "Busqueda de hilos por query", ActionState.COMPLETA, "resultados del buscador contados desde la API del corpus"),
        MatrixAction("gm-redaccion", "mail.google.com", "Redaccion con borrador en localStorage", ActionState.COMPLETA, "borrador del cuerpo guardado y re-leido"),
        MatrixAction("gm-leidos", "mail.google.com", "Estado leido persistido", ActionState.COMPLETA, "hilos marcados leidos via localStorage"),
        MatrixAction("gh-diff", "github.com", "Diff plano sin resaltado de sintaxis", ActionState.LIMITE, "LIMITE 2026-11-04: el diff se muestra como texto plano, sin resaltado de sintaxis"),
        MatrixAction("gm-composicion-rica", "mail.google.com", "Composicion rich-text", ActionState.LIMITE, "LIMITE 2026-11-04: la composicion rich-text exige contenteditable, no soportado por el DOM v1"),
        MatrixAction("gh-notificaciones", "github.com", "Notificaciones con sesion real", ActionState.EXCLUIDA, "EXCLUIDA 2026-11-04: exige sesion real y canal de notificaciones; fuera del corpus enlatado (smoke real T067)"),
        MatrixAction("gm-oauth", "mail.google.com", "Login real de Google (OAuth)", ActionState.EXCLUIDA, "EXCLUIDA 2026-11-04: el login real exige red y credenciales del usuario; smoke real T067")
    )

    /** Ejecuta SOLO las acciones COMPLETA contra el corpus enlatado. */
    fun correr(): List<ActionRun> =
        acciones().filter { it.state == ActionState.COMPLETA }.map { accion ->
            when (accion.id) {
                "gh-repo" -> ghRepo(accion)
                "gh-issue" -> ghIssue(accion)
                "gh-login" -> ghLogin(accion)
                "gm-bandeja" -> gmBandeja(accion)
                "gm-hilo" -> gmHilo(accion)
                "gm-busqueda" -> gmBusqueda(accion)
                "gm-redaccion" -> gmRedaccion(accion)
                "gm-leidos" -> gmLeidos(accion)
                else -> ActionRun(accion, false, "accion sin implementar")
            }
        }

    /** Resumen de la matriz: completas ok, limites y excluidas. */
    fun resumen(): String =
        "ok=" + correr().count { it.ok } +
            " limites=" + acciones().count { it.state == ActionState.LIMITE } +
            " excluidas=" + acciones().count { it.state == ActionState.EXCLUIDA }

    private fun ghRepo(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GH_REPO)
        val r = evaluar(doc, SCRIPT_GH_REPO)
        return ActionRun(accion, sinFallos(r), conRender(doc, detalleDe(r)))
    }

    private fun ghIssue(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GH_ISSUE)
        val r = evaluar(doc, SCRIPT_GH_ISSUE)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun ghLogin(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GH_LOGIN)
        val r = evaluar(doc, SCRIPT_GH_LOGIN)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun gmBandeja(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GM_BANDEJA)
        val r = evaluar(doc, SCRIPT_GM_BANDEJA)
        return ActionRun(accion, sinFallos(r), conRender(doc, detalleDe(r)))
    }

    private fun gmHilo(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GM_HILO)
        val r = evaluar(doc, SCRIPT_GM_HILO)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun gmBusqueda(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GM_BUSQUEDA)
        val r = evaluar(doc, SCRIPT_GM_BUSQUEDA)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun gmRedaccion(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GM_REDACCION)
        val r = evaluar(doc, SCRIPT_GM_REDACCION)
        return ActionRun(accion, sinFallos(r), detalleDe(r))
    }

    private fun gmLeidos(accion: MatrixAction): ActionRun {
        val doc = DomDocument.parse(PAGINA_GM_LEIDOS)
        val r = evaluar(doc, SCRIPT_GM_LEIDOS)
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
        private const val PAGINA_GH_REPO = """<html><head><title>nia / demo</title></head><body><h1>nia / demo</h1><table><thead><tr><th>archivo</th><th>bytes</th></tr></thead><tbody><tr><td>README.md</td><td>1204</td></tr><tr><td>Main.kt</td><td>3480</td></tr></tbody></table></body></html>"""
        private const val PAGINA_GH_ISSUE = """<html><head><title>Issue 42</title></head><body><h1>Issue 42</h1><div id="comentarios"></div></body></html>"""
        private const val PAGINA_GH_LOGIN = """<html><head><title>Sign in</title></head><body><form id="login-form"><input id="usuario"><input id="clave" type="password"><div id="estado"></div></form></body></html>"""
        private const val PAGINA_GM_BANDEJA = """<html><head><title>Bandeja</title></head><body><h1>Bandeja</h1><table><tbody><tr class="hilo"><td>Ana</td><td>Presupuesto Q4</td></tr><tr class="hilo"><td>Luis</td><td>Vacaciones</td></tr></tbody></table></body></html>"""
        private const val PAGINA_GM_HILO = """<html><head><title>Hilo</title></head><body><h1>Hilo</h1><div id="mensaje"></div></body></html>"""
        private const val PAGINA_GM_BUSQUEDA = """<html><head><title>Buscar</title></head><body><h1>Buscar</h1><input id="q" value="presupuesto"><div id="resultados"></div></body></html>"""
        private const val PAGINA_GM_REDACCION = """<html><head><title>Redaccion</title></head><body><form id="redaccion"><input id="para"><textarea id="cuerpo"></textarea></form></body></html>"""
        private const val PAGINA_GM_LEIDOS = """<html><head><title>Leidos</title></head><body><div class="hilo" id="hilo-1">Ana</div><div class="hilo" id="hilo-2">Luis</div></body></html>"""
        private const val SCRIPT_GH_REPO = "var filas = document.querySelectorAll('tr'); 'filas=' + filas.length"
        private const val SCRIPT_GH_ISSUE = "var lista = document.getElementById('comentarios'); fetch('https://corpus.nia/gh/issue-42/c1').then(function (r) { return r.text(); }).then(function (t) { var d = document.createElement('div'); d.setAttribute('class', 'comentario'); d.textContent = t; lista.appendChild(d); return fetch('https://corpus.nia/gh/issue-42/c2').then(function (r2) { return r2.text(); }).then(function (t2) { var d2 = document.createElement('div'); d2.setAttribute('class', 'comentario'); d2.textContent = t2; lista.appendChild(d2); return 'comentarios=' + document.querySelectorAll('.comentario').length; }); })"
        private const val SCRIPT_GH_LOGIN = "var f = document.getElementById('login-form'); document.getElementById('usuario').value = 'nia'; document.getElementById('clave').value = 'demo'; f.addEventListener('submit', function (ev) { ev.preventDefault(); fetch('https://corpus.nia/gh/session', { method: 'POST', body: 'nia:demo' }).then(function (r) { return r.text(); }).then(function (t) { document.getElementById('estado').textContent = t; return 'login=' + t; }); }); f.submit()"
        private const val SCRIPT_GM_BANDEJA = "var hilos = document.querySelectorAll('tr.hilo'); 'hilos=' + hilos.length"
        private const val SCRIPT_GM_HILO = "fetch('https://corpus.nia/gm/hilo-7').then(function (r) { return r.text(); }).then(function (t) { document.getElementById('mensaje').textContent = t; return 'hilo=' + document.getElementById('mensaje').textContent; })"
        private const val SCRIPT_GM_BUSQUEDA = "var q = document.getElementById('q').value; fetch('https://corpus.nia/gm/search?q=' + q).then(function (r) { return r.text(); }).then(function (t) { document.getElementById('resultados').textContent = t; return 'resultados=' + document.getElementById('resultados').textContent; })"
        private const val SCRIPT_GM_REDACCION = "document.getElementById('para').value = 'ana@example.org'; document.getElementById('cuerpo').value = 'Ana, adjunto el presupuesto.'; var borrador = document.getElementById('cuerpo').value; localStorage.setItem('borrador', borrador); 'borrador=' + (localStorage.getItem('borrador') == borrador ? 'guardado' : 'perdido')"
        private const val SCRIPT_GM_LEIDOS = "localStorage.setItem('leido:hilo-1', '1'); localStorage.setItem('leido:hilo-2', '1'); var n = 0; if (localStorage.getItem('leido:hilo-1')) { n = n + 1; } if (localStorage.getItem('leido:hilo-2')) { n = n + 1; } 'leidos=' + n"
    }
}
