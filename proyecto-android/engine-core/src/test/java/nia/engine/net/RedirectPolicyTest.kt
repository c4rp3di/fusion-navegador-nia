package nia.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests T026 v1 (R0052): politica de redirecciones 3xx. Espejo 1:1 del
 * gemelo TS validado offline: mismas fuentes, mismas expectativas.
 */
class RedirectPolicyTest {

    @Test fun redir303ConvierteAGet() {
        assertEquals(
            RedirectPolicy.Plan.Follow("GET", "https://a.example/nuevo", true),
            RedirectPolicy.plan(303, "POST", "/nuevo", "https://a.example/viejo", 0)
        )
    }

    @Test fun redir307ConservaMetodoYCuerpo() {
        assertEquals(
            RedirectPolicy.Plan.Follow("POST", "https://a.example/x", false),
            RedirectPolicy.plan(307, "POST", "https://a.example/x", "https://a.example/viejo", 0)
        )
    }

    @Test fun redir301PostConvierteAGet() {
        assertEquals(
            RedirectPolicy.Plan.Follow("GET", "https://a.example/x", true),
            RedirectPolicy.plan(301, "POST", "https://a.example/x", "https://a.example/viejo", 0)
        )
    }

    @Test fun redir308ConservaMetodo() {
        assertEquals(
            "PUT",
            RedirectPolicy.plan(308, "PUT", "https://a.example/x", "https://a.example/viejo", 0).let {
                (it as RedirectPolicy.Plan.Follow).method
            }
        )
    }

    @Test fun redirGetNoCambiaMetodo() {
        val p = RedirectPolicy.plan(302, "GET", "https://a.example/x", "https://a.example/viejo", 0)
        assertEquals("GET", (p as RedirectPolicy.Plan.Follow).method)
    }

    @Test fun redirSinLocation() {
        assertEquals(
            "sin-location",
            (RedirectPolicy.plan(302, "GET", null, "https://a.example/", 0) as RedirectPolicy.Plan.Stop).reason
        )
    }

    @Test fun redirLimite() {
        assertEquals(
            "limite-de-redirecciones",
            (RedirectPolicy.plan(302, "GET", "https://a.example/x", "https://a.example/", 20) as RedirectPolicy.Plan.Stop).reason
        )
    }

    @Test fun redirUltimoSaltoPermitido() {
        assertTrue(RedirectPolicy.plan(302, "GET", "https://a.example/x", "https://a.example/", 19) is RedirectPolicy.Plan.Follow)
    }

    @Test fun redirNoDegradaHttps() {
        assertEquals(
            "degradacion-https-prohibida",
            (RedirectPolicy.plan(302, "GET", "http://a.example/x", "https://a.example/", 0) as RedirectPolicy.Plan.Stop).reason
        )
    }

    @Test fun redirHttpAHttpsPermitido() {
        assertTrue(RedirectPolicy.plan(302, "GET", "https://a.example/x", "http://a.example/", 0) is RedirectPolicy.Plan.Follow)
    }

    @Test fun redirRelativaComplejaRechazada() {
        assertEquals(
            "location-relativa-compleja-no-soportada",
            (RedirectPolicy.plan(302, "GET", "pagina", "https://a.example/a/b", 0) as RedirectPolicy.Plan.Stop).reason
        )
    }

    @Test fun redirNoRedireccionable() {
        assertFalse(RedirectPolicy.redirectable(200))
    }

    @Test fun redirRedireccionable() {
        assertTrue(RedirectPolicy.redirectable(302))
    }
}
