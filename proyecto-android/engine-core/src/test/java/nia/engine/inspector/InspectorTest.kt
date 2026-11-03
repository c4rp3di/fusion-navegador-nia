package nia.engine.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import nia.contracts.WallClock

class InspectorTest {

    private class FakeClock : WallClock {
        var t = 1000L
        override fun nowMillis(): Long {
            val r = t
            t += 1
            return r
        }
    }

    private fun ctx(generation: Long = 1): EventContext =
        EventContext("session-1", "tab-1", "frame-1", "doc-" + generation, generation)

    @Test fun contextoRequerido() {
        assertFailsWith<IllegalArgumentException> {
            EventContext("session-1", "", "frame-1", "doc-1", 1)
        }
    }

    @Test fun secuenciaMonotonaYTimestamps() {
        val s = InspectorSession(FakeClock())
        val e1 = s.console(ctx(), EventLevel.INFO, "hola")
        val e2 = s.console(ctx(), EventLevel.INFO, "chao")
        assertEquals(1L, e1.seq)
        assertEquals(2L, e2.seq)
        assertEquals(1000L, e1.timestampMillis)
        assertEquals(1001L, e2.timestampMillis)
        assertEquals(2, s.all().size)
    }

    @Test fun consolaRedactaBearer() {
        val s = InspectorSession()
        val e = s.console(ctx(), EventLevel.ERROR, "Authorization: Bearer abc123")
        assertEquals("Authorization: Bearer [redacted]", e.payload["text"])
    }

    @Test fun urlConCredencialesRedactada() {
        val s = InspectorSession()
        val e = s.navigation(ctx(), "https://u:p@ejemplo.com/x?token=secreto")
        assertEquals("https://[redacted]@ejemplo.com/x?token=[redacted]", e.payload["url"])
    }

    @Test fun cabecerasSensiblesRedactadas() {
        val s = InspectorSession()
        val e = s.custom(
            ctx(), "network", EventLevel.INFO,
            mapOf("authorization" to "Bearer x", "content-type" to "text/html")
        )
        assertEquals("[redacted]", e.payload["authorization"])
        assertEquals("text/html", e.payload["content-type"])
    }

    @Test fun crudoNoSePersiste() {
        val s = InspectorSession()
        s.console(ctx(), EventLevel.INFO, "password=hunter2")
        val export = s.export()
        assertTrue(export.contains("password=[redacted]"))
        assertTrue(!export.contains("hunter2"))
    }

    @Test fun replayDesdeSeq() {
        val s = InspectorSession()
        s.console(ctx(), EventLevel.INFO, "a")
        s.console(ctx(), EventLevel.INFO, "b")
        s.console(ctx(), EventLevel.INFO, "c")
        val ultimos = s.eventsSince(1)
        assertEquals(2, ultimos.size)
        assertEquals(2L, ultimos[0].seq)
        assertEquals(3L, ultimos[1].seq)
    }

    @Test fun exportSanitizado() {
        val s = InspectorSession()
        s.navigation(ctx(), "https://u:p@ejemplo.com/")
        val export = s.export()
        assertTrue(export.contains("navigation"))
        assertTrue(export.contains("INFO"))
        assertTrue(export.contains("tab-1"))
        assertTrue(!export.contains("u:p"))
    }

    @Test fun kindYNivelPresentes() {
        val s = InspectorSession()
        val c = s.console(ctx(), EventLevel.WARNING, "cuidado")
        val n = s.navigation(ctx(), "https://ejemplo.com/")
        assertEquals("console", c.kind)
        assertEquals(EventLevel.WARNING, c.level)
        assertEquals("navigation", n.kind)
        assertEquals(EventLevel.INFO, n.level)
    }

    @Test fun exportReferenciaProtocoloVersionado() {
        val s = InspectorSession()
        val export = s.export()
        assertTrue(export.contains("inspector NIA v" + InspectorProtocol.PROTOCOL_VERSION))
    }
}
