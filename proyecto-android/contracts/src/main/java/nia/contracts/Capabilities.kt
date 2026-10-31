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
        Capability("engine.html", TestStatus.NOT_RUN, "P2 sin iniciar"),
        Capability("engine.css", TestStatus.NOT_RUN, "P2 sin iniciar"),
        Capability("engine.js", TestStatus.NOT_RUN, "P3 sin iniciar"),
        Capability("engine.network", TestStatus.NOT_RUN, "P2 sin iniciar")
    )
}
