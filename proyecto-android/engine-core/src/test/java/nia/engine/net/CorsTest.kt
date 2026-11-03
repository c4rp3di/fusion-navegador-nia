package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests T026 v1 (R0052): CORS. Espejo 1:1 del gemelo TS validado
 * offline: mismas fuentes, mismas expectativas (pruebas multi-origen).
 * v2 (R0060): credenciales CORS.
 */
class CorsTest {

    @Test fun corsGetEsSimple() {
        assertTrue(Cors.isSimple("GET", emptyMap()))
    }

    @Test fun corsPutNoEsSimple() {
        assertFalse(Cors.isSimple("PUT", emptyMap()))
    }

    @Test fun corsDeleteNoEsSimple() {
        assertFalse(Cors.isSimple("DELETE", emptyMap()))
    }

    @Test fun corsPostTextPlainEsSimple() {
        assertTrue(Cors.isSimple("POST", mapOf("Content-Type" to "text/plain")))
    }

    @Test fun corsPostJsonNoEsSimple() {
        assertFalse(Cors.isSimple("POST", mapOf("Content-Type" to "application/json")))
    }

    @Test fun corsCustomHeaderNoEsSimple() {
        assertFalse(Cors.isSimple("GET", mapOf("X-Nia" to "1")))
    }

    @Test fun corsSameOriginSiempreOk() {
        assertEquals(Cors.Decision(true, "same-origin"), Cors.evaluate("", emptyMap()))
    }

    @Test fun corsSinAcaoDenegado() {
        assertEquals(
            Cors.Decision(false, "sin-access-control-allow-origin"),
            Cors.evaluate("https://a.example", mapOf("content-type" to "text/html"))
        )
    }

    @Test fun corsAcaoEstrella() {
        assertEquals(
            Cors.Decision(true, "allow-origin-*"),
            Cors.evaluate("https://a.example", mapOf("access-control-allow-origin" to "*"))
        )
    }

    @Test fun corsAcaoCoincide() {
        assertEquals(
            Cors.Decision(true, "allow-origin"),
            Cors.evaluate("https://a.example", mapOf("access-control-allow-origin" to "https://a.example"))
        )
    }

    @Test fun corsAcaoNoCoincide() {
        assertEquals(
            Cors.Decision(false, "allow-origin-no-coincide"),
            Cors.evaluate("https://a.example", mapOf("access-control-allow-origin" to "https://b.example"))
        )
    }

    @Test fun corsPreflightOk() {
        assertEquals(
            Cors.Decision(true, "preflight-ok"),
            Cors.evaluatePreflight(
                "https://a.example", "DELETE", listOf("x-nia"),
                mapOf(
                    "access-control-allow-origin" to "https://a.example",
                    "access-control-allow-methods" to "GET, POST, DELETE",
                    "access-control-allow-headers" to "X-Nia"
                )
            )
        )
    }

    @Test fun corsPreflightMetodoDenegado() {
        assertEquals(
            "metodo-no-permitido",
            Cors.evaluatePreflight(
                "https://a.example", "DELETE", emptyList(),
                mapOf("access-control-allow-origin" to "*", "access-control-allow-methods" to "GET")
            ).reason
        )
    }

    @Test fun corsPreflightCabeceraDenegada() {
        assertEquals(
            "sin-access-control-allow-headers",
            Cors.evaluatePreflight(
                "https://a.example", "POST", listOf("x-nia"),
                mapOf("access-control-allow-origin" to "*", "access-control-allow-methods" to "POST")
            ).reason
        )
    }

    @Test fun corsPreflightCaseInsensitive() {
        assertTrue(
            Cors.evaluatePreflight(
                "https://a.example", "delete", listOf("x-nia"),
                mapOf(
                    "access-control-allow-origin" to "*",
                    "access-control-allow-methods" to "DELETE",
                    "access-control-allow-headers" to "X-NIA"
                )
            ).ok
        )
    }

    @Test fun corsMaxAge() {
        assertEquals(600L, Cors.preflightMaxAge(mapOf("access-control-max-age" to "600")))
    }

    @Test fun corsMaxAgeCap() {
        assertEquals(86400L, Cors.preflightMaxAge(mapOf("access-control-max-age" to "999999")))
    }

    @Test fun corsMaxAgeInvalido() {
        assertNull(Cors.preflightMaxAge(mapOf("access-control-max-age" to "abc")))
    }

    @Test fun corsCabecerasANecesarias() {
        assertEquals(
            listOf("x-nia"),
            Cors.requestHeadersNeedingPreflight(
                mapOf("X-Nia" to "1", "Accept" to "x", "Content-Type" to "text/plain")
            )
        )
    }

    @Test fun corsCabeceraProhibida() {
        assertTrue(Cors.isForbiddenHeader("Cookie"))
    }

    @Test fun corsCredencialesEstrellaDenegada() {
        assertEquals(
            Cors.Decision(false, "allow-origin-*-con-credenciales"),
            Cors.evaluate("https://a.example", mapOf("access-control-allow-origin" to "*"), true)
        )
    }

    @Test fun corsCredencialesSinAcacDenegadas() {
        assertEquals(
            Cors.Decision(false, "sin-allow-credentials"),
            Cors.evaluate("https://a.example", mapOf("access-control-allow-origin" to "https://a.example"), true)
        )
    }

    @Test fun corsCredencialesConAcacOk() {
        assertEquals(
            Cors.Decision(true, "allow-origin"),
            Cors.evaluate(
                "https://a.example",
                mapOf(
                    "access-control-allow-origin" to "https://a.example",
                    "access-control-allow-credentials" to "true"
                ),
                true
            )
        )
    }

    @Test fun corsCredencialesAcacFalseDenegadas() {
        assertEquals(
            Cors.Decision(false, "sin-allow-credentials"),
            Cors.evaluate(
                "https://a.example",
                mapOf(
                    "access-control-allow-origin" to "https://a.example",
                    "access-control-allow-credentials" to "false"
                ),
                true
            )
        )
    }

    @Test fun corsPreflightCredencialesEstrellaDenegada() {
        assertEquals(
            Cors.Decision(false, "allow-origin-*-con-credenciales"),
            Cors.evaluatePreflight(
                "https://a.example", "GET", emptyList(),
                mapOf("access-control-allow-origin" to "*", "access-control-allow-methods" to "GET"),
                true
            )
        )
    }

    @Test fun corsPreflightCredencialesOk() {
        assertEquals(
            Cors.Decision(true, "preflight-ok"),
            Cors.evaluatePreflight(
                "https://a.example", "DELETE", emptyList(),
                mapOf(
                    "access-control-allow-origin" to "https://a.example",
                    "access-control-allow-methods" to "DELETE",
                    "access-control-allow-credentials" to "true"
                ),
                true
            )
        )
    }
}
