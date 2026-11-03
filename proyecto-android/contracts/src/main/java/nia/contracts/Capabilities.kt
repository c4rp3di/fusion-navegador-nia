package nia.contracts

/**
 * Capacidad declarada del núcleo (T004): lo soportado y lo no probado, con
 * estado de prueba real. Nunca declarar PASS sin evidencia (sección 1.5).
 */
data class Capability(
    val id: String,
    val status: TestStatus,
    val detail: String? = null
) {
    init {
        require(id.isNotBlank()) { "id de capacidad obligatorio" }
    }
}

object Capabilities {

    /** Capacidades de la baseline P0: solo PASS lo que tiene tests en verde. */
    fun baseline(): List<Capability> = listOf(
        Capability("contracts.ids", TestStatus.PASS, "IdsTest en verde (workflow contracts-tests)"),
        Capability("contracts.errors", TestStatus.PASS, "ErrorsTest en verde"),
        Capability("contracts.handles", TestStatus.PASS, "HandlesTest en verde"),
        Capability("contracts.cancellation", TestStatus.PASS, "CancellationTest en verde"),
        Capability("contracts.ipc", TestStatus.PASS, "IpcTest en verde"),
        Capability("contracts.harness", TestStatus.PASS, "HarnessTest en verde"),
        Capability("app.first-draw", TestStatus.PASS, "APK instalada con dibujo propio, sin WebView (2026-10-30)"),
        Capability("engine.url", TestStatus.PASS, "T010 (R0014): parser URL/Origin/Site con IDNA/punycode (RFC 3492); UrlParserTest y OriginSiteTest en el workflow contracts-tests"),
        Capability("engine.html", TestStatus.PASS, "T012 (R0017): tokenizer HTML + tree builder v1 (subconjunto WHATWG, modos de insercion con recovery tipificado) en :engine-core; HtmlTokenizerTest y HtmlTreeBuilderTest en el workflow contracts-tests"),
        Capability("engine.dom", TestStatus.PASS, "T013 (R0018): DOM propio con handles/generacion, mutaciones con errores tipificados y snapshots con revision monotona en :engine-core; DomDocumentTest en el workflow contracts-tests"),
        Capability("engine.css", TestStatus.PASS, "T014 (R0019): CSS v1 con parser de selectores (tipo/universal/clase/id/atributo, combinadores descendente e hijo), especificidad, cascada con orden, herencia y estilos computados en :engine-core; CssParserTest y CssCascadeTest en el workflow contracts-tests"),
        Capability("engine.layout", TestStatus.PASS, "T015 (R0020): layout v1 en :engine-core con formato de bloques (apilado vertical, ancho completo), inline con envoltura greedy de palabras, bloques anonimos, alturas de linea (normal 1.2x o px), alineacion left/center/right heredada y cajas por nodo (boxOf/fragmentsOf); LayoutTest (28 tests) en el workflow contracts-tests"),
        Capability("engine.paint", TestStatus.PASS, "T016 (R0021): pintura v1 en :engine-core (nia/engine/paint): display list de ops DrawRect/DrawText en orden documental sobre el layout real y hitTest topmost por punto; PaintView (app) renderiza la escena del pipeline completo y resuelve toques con hitTest del motor; PaintTest (20 tests) en el workflow contracts-tests"),
        Capability("engine.js", TestStatus.NOT_RUN, "P3 sin iniciar"),
        Capability("engine.network", TestStatus.NOT_RUN, "P2 sin iniciar")
    )
}
