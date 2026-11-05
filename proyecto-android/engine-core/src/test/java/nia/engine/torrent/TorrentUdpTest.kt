package nia.engine.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas): cada escenario del gemelo tiene su prueba aqui contra
 * las clases reales. T047 v3 (R0129, RES-0067, ADR-016): tracker UDP
 * (BEP-15) con connect magic y announce de 98 bytes, SIN red.
 */
class TorrentUdpTest {

    private val infoHash = byteArrayOf(
        0x00, 0x01, 0x41, 0x5a, 0x61, 0x7a, 0x30, 0x39, 0x2e, 0x2d,
        0x5f, 0x7e, 0x20, 0xff.toByte(), 0x80.toByte(), 0x10, 0x11, 0x12, 0x13, 0x14
    )
    private val peerId = "-NIA-0001-0000000000".toByteArray(Charsets.US_ASCII)
    private val connResp = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0x11, 0x11, 0x11, 0x11, 0x22, 0x22, 0x22, 0x22)

    private fun bytesAHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class UdpFalso(private val respuestas: List<ByteArray?>) : UdpTransport {
        var llamadas = 0
        val paquetes = ArrayList<ByteArray>()
        override fun exchange(ip: String, port: Int, packet: ByteArray): ByteArray? {
            val respuesta = if (llamadas < respuestas.size) respuestas[llamadas] else null
            llamadas++
            paquetes.add(packet.copyOf())
            return respuesta
        }
    }

    private fun annResp(): ByteArray =
        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 120, 0, 0, 0, 1, 0, 0, 0, 2) +
            byteArrayOf(10, 0, 0, 1, 0x1a, 0xe1.toByte()) +
            byteArrayOf(0xc0.toByte(), 0xa8.toByte(), 1, 2, 0xc8.toByte(), 0xd5.toByte())

    @Test
    fun connectEscribeElMagicConActionCero() {
        val paquete = UdpTrackerClient(UdpFalso(emptyList())).construirConnect(1)
        assertEquals(16, paquete.size)
        assertEquals("00000417271019800000000000000001", bytesAHex(paquete))
    }

    @Test
    fun connectParseaElConnectionId() {
        val connId = UdpTrackerClient(UdpFalso(emptyList())).parsearConnect(connResp, 1)
        assertEquals("1111111122222222", bytesAHex(connId ?: throw AssertionError("connection id nulo")))
    }

    @Test
    fun connectConTxDistintoOActionDistintoFalla() {
        val cliente = UdpTrackerClient(UdpFalso(emptyList()))
        assertNull(cliente.parsearConnect(connResp, 2))
        val txDos = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2) + connResp.copyOfRange(8, 16)
        assertEquals(
            "1111111122222222",
            bytesAHex(cliente.parsearConnect(txDos, 2) ?: throw AssertionError("connection id nulo"))
        )
        val actionTres = byteArrayOf(0, 0, 0, 3) + connResp.copyOfRange(4, 16)
        assertNull(cliente.parsearConnect(actionTres, 1))
    }

    @Test
    fun announceEscribeLos98BytesCompletos() {
        val connId = byteArrayOf(0x22, 0x22, 0x22, 0x22, 0x33, 0x33, 0x33, 0x33)
        val req = TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40, AnnounceEvent.STARTED)
        val paquete = UdpTrackerClient(UdpFalso(emptyList())).construirAnnounce(connId, 2, req)
        assertEquals(98, paquete.size)
        assertEquals(
            "222222223333333300000001000000020001415a617a30392e2d5f7e20ff8010111213142d4e49412d303030312d30303030303030303030000000000000000000000000000000280000000000000000000000020000000000000000ffffffff1ae1",
            bytesAHex(paquete)
        )
    }

    @Test
    fun announceUsaLosCodigosDeEventoDeBep15() {
        val cliente = UdpTrackerClient(UdpFalso(emptyList()))
        val connId = byteArrayOf(0x22, 0x22, 0x22, 0x22, 0x33, 0x33, 0x33, 0x33)
        fun con(evento: AnnounceEvent) =
            cliente.construirAnnounce(connId, 2, TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40, evento))
        assertEquals("00000001", bytesAHex(con(AnnounceEvent.COMPLETED).copyOfRange(80, 84)))
        assertEquals("00000003", bytesAHex(con(AnnounceEvent.STOPPED).copyOfRange(80, 84)))
        assertEquals("00000000", bytesAHex(con(AnnounceEvent.NONE).copyOfRange(80, 84)))
        assertEquals("00000002", bytesAHex(con(AnnounceEvent.STARTED).copyOfRange(80, 84)))
    }

    @Test
    fun announceParseaIntervaloYPeers() {
        val r = UdpTrackerClient(UdpFalso(emptyList())).parsearAnnounce(annResp(), 2)
            ?: throw AssertionError("respuesta de announce nula")
        assertEquals(120L, r.interval)
        assertEquals(2, r.peers.size)
        assertEquals("10.0.0.1", r.peers[0].ip)
        assertEquals(6881, r.peers[0].port)
        assertEquals("192.168.1.2", r.peers[1].ip)
        assertEquals(51413, r.peers[1].port)
    }

    @Test
    fun announceConActionErrorOPeersInvalidosFalla() {
        val cliente = UdpTrackerClient(UdpFalso(emptyList()))
        assertNull(cliente.parsearAnnounce(byteArrayOf(0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 120), 2))
        assertNull(
            cliente.parsearAnnounce(
                byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 60, 0, 0, 0, 1, 0, 0, 0, 2, 10, 0, 0, 1),
                2
            )
        )
    }

    @Test
    fun announceCompletoConTransporteFalsoFunciona() {
        val falso = UdpFalso(listOf(connResp, annResp()))
        val r = UdpTrackerClient(falso).announce(
            "10.0.0.5", 6969, TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40, AnnounceEvent.STARTED)
        )
        assertEquals(120L, r.interval)
        assertEquals(2, r.peers.size)
        assertEquals(2, falso.llamadas)
        assertEquals(98, falso.paquetes[1].size)
    }

    @Test
    fun announceSinRespuestaLanzaTorrentFormatException() {
        try {
            UdpTrackerClient(UdpFalso(emptyList())).announce(
                "10.0.0.5", 6969, TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40)
            )
            fail("announce sin respuesta debe lanzar TorrentFormatException")
        } catch (e: TorrentFormatException) {
            assertEquals("tracker UDP sin respuesta", e.message)
        }
        try {
            UdpTrackerClient(UdpFalso(listOf(connResp))).announce(
                "10.0.0.5", 6969, TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40)
            )
            fail("announce sin respuesta debe lanzar TorrentFormatException")
        } catch (e: TorrentFormatException) {
            assertEquals("tracker UDP sin respuesta", e.message)
        }
    }
}
