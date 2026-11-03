package nia.engine.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Ipc
import nia.contracts.IpcEnvelope
import nia.contracts.IpcMessages
import nia.contracts.Outcome
import nia.contracts.Retryability

class BrokerRendererTest {

    private fun ok(o: Outcome<IpcEnvelope>): IpcEnvelope {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value
    }

    private fun fallo(o: Outcome<IpcEnvelope>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    private fun texto(e: IpcEnvelope): String = String(e.payload ?: ByteArray(0), Charsets.UTF_8)

    @Test fun handshakeConecta() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE, BrokerOps.RENDER))
        assertEquals("connected", texto(ok(r.handshake())))
    }

    @Test fun requestSinHandshakeDenegado() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.RENDER))
        assertEquals(ErrorCode.DENIED, fallo(r.request(BrokerOps.RENDER)).code)
    }

    @Test fun navigateAutorizado() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE))
        ok(r.handshake())
        val res = ok(r.request(BrokerOps.NAVIGATE, "tab-1".toByteArray()))
        assertEquals("navigation-started", texto(res))
    }

    @Test fun renderRespondeAck() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.RENDER))
        ok(r.handshake())
        assertEquals("ack", texto(ok(r.request(BrokerOps.RENDER))))
    }

    @Test fun operacionNoConcedidaDenegada() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE))
        ok(r.handshake())
        val e = fallo(r.request(BrokerOps.STORAGE_READ))
        assertEquals(ErrorCode.DENIED, e.code)
        assertEquals(Retryability.NONE, e.retryability)
    }

    @Test fun operacionDesconocidaRechazada() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf("tab.cosas"))
        ok(r.handshake())
        assertEquals(ErrorCode.UNSUPPORTED, fallo(r.request("tab.cosas")).code)
    }

    @Test fun payloadDeOtraPestanaDenegado() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE, BrokerOps.SNAPSHOT))
        ok(r.handshake())
        val e = fallo(r.request(BrokerOps.NAVIGATE, "tab-2".toByteArray()))
        assertEquals(ErrorCode.DENIED, e.code)
    }

    @Test fun contextoDesconocidoDenegado() {
        val broker = Broker()
        val env = IpcMessages.request("ctx-noexiste", 1, BrokerOps.RENDER)
        assertEquals(ErrorCode.DENIED, fallo(broker.dispatch(env)).code)
    }

    @Test fun crashResuelveProcessGone() {
        val eventos = ArrayList<BrokerEvent>()
        var brokerRef: Broker? = null
        var rendererRef: Renderer? = null
        val broker = Broker(
            onEvent = { ev -> eventos.add(ev) },
            navigateHook = { _ ->
                val b = brokerRef
                val v = rendererRef
                if (b != null && v != null) b.crash(v.contextId)
                Outcome.success(Unit)
            }
        )
        brokerRef = broker
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE))
        rendererRef = r
        ok(r.handshake())
        val e = fallo(r.request(BrokerOps.NAVIGATE, "tab-1".toByteArray()))
        assertEquals(ErrorCode.PROCESS_GONE, e.code)
        assertEquals(Retryability.RETRY_LATER, e.retryability)
        val crashes = eventos.filterIsInstance<BrokerEvent.RendererCrashed>()
        assertEquals(1, crashes.size)
        assertEquals(1, crashes[0].pendingRequests)
    }

    @Test fun contextoMuertoRespondeProcessGone() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.RENDER))
        ok(r.handshake())
        assertTrue(broker.crash(r.contextId))
        assertEquals(ErrorCode.PROCESS_GONE, fallo(r.request(BrokerOps.RENDER)).code)
    }

    @Test fun respawnRecuperable() {
        val eventos = ArrayList<BrokerEvent>()
        val broker = Broker(onEvent = { ev -> eventos.add(ev) })
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.NAVIGATE, BrokerOps.RENDER))
        ok(r.handshake())
        assertTrue(broker.crash(r.contextId))
        val nuevo = broker.respawn(r)
        assertTrue(nuevo.contextId != r.contextId)
        ok(nuevo.handshake())
        val res = ok(nuevo.request(BrokerOps.NAVIGATE, "tab-1".toByteArray()))
        assertEquals("navigation-started", texto(res))
        assertEquals(1, eventos.filterIsInstance<BrokerEvent.RendererRespawned>().size)
    }

    @Test fun generacionCaducadaStaleHandle() {
        val broker = Broker()
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.RENDER))
        ok(r.handshake())
        val nueva = broker.invalidate(r)
        assertEquals(2L, nueva)
        assertEquals(ErrorCode.STALE_HANDLE, fallo(r.request(BrokerOps.RENDER)).code)
    }

    @Test fun cerradoDenegado() {
        val eventos = ArrayList<BrokerEvent>()
        val broker = Broker(onEvent = { ev -> eventos.add(ev) })
        val r = broker.attachRenderer("tab-1", setOf(BrokerOps.RENDER))
        ok(r.handshake())
        assertEquals("closed", texto(ok(r.close())))
        assertEquals(ErrorCode.DENIED, fallo(r.request(BrokerOps.RENDER)).code)
        assertEquals(1, eventos.filterIsInstance<BrokerEvent.RendererClosed>().size)
    }

    @Test fun payloadGrandeRechazadoPorElSobre() {
        assertFailsWith<IllegalArgumentException> {
            IpcMessages.request("ctx-x", 1, BrokerOps.RENDER, ByteArray(Ipc.MAX_INLINED_PAYLOAD_BYTES + 1))
        }
    }
}
