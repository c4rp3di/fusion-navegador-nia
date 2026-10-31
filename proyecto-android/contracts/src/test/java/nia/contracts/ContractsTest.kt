package nia.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun nuevosIdsSonUnicosYLlevanPrefijo() {
        val a = Ids.newNavigationId()
        val b = Ids.newNavigationId()
        assertTrue(a.value.startsWith("nav-"))
        assertNotEquals(a, b)
    }

    @Test
    fun parseRechazaVaciosYPrefijosCruzados() {
        // Un RequestId colado donde va un NavigationId debe fallar aquí.
        assertFailsWith<IllegalArgumentException> { Ids.parseNavigationId("req-1") }
        assertFailsWith<IllegalArgumentException> { Ids.parseNavigationId("   ") }
        assertEquals("doc-1", Ids.parseDocumentId("doc-1").value)
    }

    @Test
    fun idsDemasiadoLargosSeRechazan() {
        val largo = "doc-" + "x".repeat(Ids.MAX_VALUE_LENGTH)
        assertFailsWith<IllegalArgumentException> { Ids.parseDocumentId(largo) }
    }
}

class ErrorsTest {

    @Test
    fun retryabilidadPorDefectoEsConsistente() {
        assertEquals(Retryability.RETRY_LATER, ErrorCode.NETWORK.defaultRetryability())
        assertEquals(Retryability.RETRY_LATER, ErrorCode.DEADLINE.defaultRetryability())
        assertEquals(Retryability.NONE, ErrorCode.DENIED.defaultRetryability())
        assertEquals(Retryability.NONE, ErrorCode.INVALID_INPUT.defaultRetryability())
        assertEquals(Retryability.NONE, ErrorCode.TLS.defaultRetryability())
    }

    @Test
    fun errorInfoExigeMensajeSeguro() {
        assertFailsWith<IllegalArgumentException> { ErrorInfo(ErrorCode.INTERNAL, " ") }
        val info = ErrorInfo(ErrorCode.NETWORK, "sin conexión")
        assertTrue(info.traceId.startsWith("trace-"))
        assertEquals(Retryability.RETRY_LATER, info.retryability)
    }

    @Test
    fun outcomeDistingueSuccessDeFailure() {
        val ok: Outcome<Int> = Outcome.Success(7)
        val ko: Outcome<Int> = Outcome.Failure(ErrorInfo(ErrorCode.QUOTA, "cuota agotada"))
        assertEquals(7, ok.valueOrNull)
        assertNull(ko.valueOrNull)
        assertFalse(ok.isFailure)
        assertTrue(ko.isFailure)
        var visto = 0
        ok.onSuccess { visto = it }.onFailure { visto = -1 }
        assertEquals(7, visto)
        visto = 0
        ko.onSuccess { visto = it }.onFailure { visto = -1 }
        assertEquals(-1, visto)
    }
}

class HandlesTest {

    @Test
    fun handleValidoHastaQueSeInvalida() {
        val registry = HandleRegistry()
        val handle: Handle<String> = registry.issue(HandleOwner.BROWSER, "tab-1")
        val antes = registry.validate(handle)
        assertTrue(antes is Outcome.Success)
        registry.invalidateAll()
        val despues = registry.validate(handle)
        assertTrue(despues is Outcome.Failure)
        assertEquals(ErrorCode.STALE_HANDLE, (despues as Outcome.Failure).error.code)
    }

    @Test
    fun handleConGeneracionCaducadaSeDetecta() {
        val handle: Handle<String> = Handle(HandleOwner.NETWORK, generation = 3, id = "req-1")
        assertTrue(handle.isStale(4))
        assertFalse(handle.isStale(3))
    }

    @Test
    fun handleInvalidoSeRechazaAlConstruir() {
        assertFailsWith<IllegalArgumentException> {
            Handle<String>(HandleOwner.BROWSER, generation = 0, id = "tab-1")
        }
        assertFailsWith<IllegalArgumentException> {
            Handle<String>(HandleOwner.BROWSER, generation = 1, id = " ")
        }
    }
}

class CancellationTest {

    private class FakeClock(var now: Long) : WallClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun cancelarUnaVezDisparaListenersUnaSolaVez() {
        val source = CancellationTokenSource(FakeClock(0))
        var llamadas = 0
        source.token.onCancel { llamadas++ }
        assertTrue(source.cancel())
        // La segunda cancelación no gana ni vuelve a notificar.
        assertFalse(source.cancel(CancelReason.Replaced("otra")))
        assertEquals(1, llamadas)
        assertTrue(source.token.isCancelled)
        assertEquals(CancelReason.Explicit, source.token.reason())
    }

    @Test
    fun bajaDeListenerDetieneNotificaciones() {
        val source = CancellationTokenSource(FakeClock(0))
        var llamadas = 0
        val off = source.token.onCancel { llamadas++ }
        off()
        source.cancel()
        assertEquals(0, llamadas)
    }

    @Test
    fun listenerTardioRecibeInmediatoLaMismaRazon() {
        val source = CancellationTokenSource(FakeClock(0))
        source.cancel()
        var razon: CancelReason? = null
        source.token.onCancel { razon = it }
        assertEquals(CancelReason.Explicit, razon)
    }

    @Test
    fun deadlineVencidoCancelaConRazonDeadline() {
        val clock = FakeClock(1_000)
        val source = CancellationTokenSource(clock)
        val token = source.token
        // Aún no vence: la operación sigue viva.
        assertNull(token.checkDeadline(2_000))
        // El reloj avanza (controlado, no sleep): ahora vence.
        clock.now = 2_000
        val razon = token.checkDeadline(2_000)
        assertEquals(2_000L, (razon as CancelReason.Deadline).deadlineMillis)
        // Ya cancelada: cualquier comprobación devuelve la misma razón.
        clock.now = 9_999
        assertSame(razon, token.checkDeadline(9_999))
        assertTrue(token.isCancelled)
    }

    @Test
    fun throwIfCancelledLanzaConLaRazon() {
        val source = CancellationTokenSource(FakeClock(0))
        // Sin cancelar: no lanza.
        source.token.throwIfCancelled()
        source.cancel()
        val ex = assertFailsWith<OperationCancelledException> { source.token.throwIfCancelled() }
        assertEquals(CancelReason.Explicit, ex.reason)
    }
}

class IpcTest {

    @Test
    fun requestSinCorrelationYResultConCorrelation() {
        val req = IpcMessages.request("ctx-1", generation = 7, operation = "navigate")
        assertNull(req.correlationId)
        assertEquals(IpcKind.REQUEST, req.kind)
        val res = IpcMessages.result(req, "navigate.result")
        assertEquals(req.messageId, res.correlationId)
        assertEquals("ctx-1", res.contextId)
        assertEquals(7L, res.generation)
    }

    @Test
    fun resultSinCorrelationEsRechazado() {
        assertFailsWith<IllegalArgumentException> {
            IpcEnvelope(IpcMessages.newMessageId(), IpcKind.RESULT, "x", "ctx", 1, correlationId = null)
        }
    }

    @Test
    fun payloadGrandeSeRechazaYLosPequenosPasan() {
        val grande = ByteArray(Ipc.MAX_INLINED_PAYLOAD_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            IpcMessages.request("ctx", 1, "fetch", grande)
        }
        // Dentro del límite: ok.
        IpcMessages.request("ctx", 1, "fetch", ByteArray(16))
    }

    @Test
    fun versionDeProtocoloEstaCongelada() {
        assertEquals(1, Ipc.PROTOCOL_VERSION)
        assertFailsWith<IllegalArgumentException> {
            IpcEnvelope(IpcMessages.newMessageId(), IpcKind.EVENT, "e", "ctx", 1, protocolVersion = 99)
        }
    }

    @Test
    fun cancelYCloseRespetanContextoYGeneracion() {
        val req = IpcMessages.request("ctx-9", generation = 4, operation = "fetch")
        val cancel = IpcMessages.cancel(req)
        assertEquals(IpcKind.CANCEL, cancel.kind)
        assertEquals("ctx-9", cancel.contextId)
        assertEquals(4L, cancel.generation)
        val close = IpcMessages.close("ctx-9", 4)
        assertEquals(IpcKind.CLOSE, close.kind)
    }
}
