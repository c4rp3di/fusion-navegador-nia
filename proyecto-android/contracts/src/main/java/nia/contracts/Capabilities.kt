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
        Capability("engine.css", TestStatus.PASS, "T014 (R0019) + v2 (R0023): CSS en :engine-core con parser de selectores (tipo/universal/clase/id/atributo, combinadores descendente, hijo y hermanos adyacente/general, pseudo-clases estaticas :first-child/:last-child/:only-child/:nth-child(an+b)), especificidad, cascada con orden y herencia, shorthands margin/padding/border-width (1-4 valores TRBL) y unidades relativas em/rem/% resueltas a px; CssParserTest y CssCascadeTest en el workflow contracts-tests"),
        Capability("engine.layout", TestStatus.PASS, "T015 (R0020) + v2 (R0023): layout en :engine-core con formato de bloques, inline con envoltura greedy, bloques anonimos, alturas de linea y alineacion heredada; v2 anade margin/padding/border por bloque (modelo border-box, sin margin collapsing), unidades em/rem contra la fuente y % contra el ancho del contenedor; LayoutTest en el workflow contracts-tests"),
        Capability("engine.paint", TestStatus.PASS, "T016 (R0021) + v2 (R0023): pintura en :engine-core (nia/engine/paint): display list de ops DrawRect/DrawText en orden documental sobre el layout real, hitTest topmost por punto; v2 anade hasta 4 tiras de borde por elemento (border-color o color heredado) tras el fondo; PaintView (app) renderiza la escena del pipeline completo y resuelve toques con hitTest del motor; PaintTest en el workflow contracts-tests"),
        Capability("engine.js", TestStatus.NOT_RUN, "P3 sin iniciar"),
        Capability("engine.network", TestStatus.NOT_RUN, "P2 sin iniciar")
    )
}
