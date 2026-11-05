package nia.engine.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas): cada escenario del gemelo tiene su prueba aqui contra
 * las clases reales. T047 v3 (R0129, RES-0067, ADR-016): DHT (BEP-5)
 * con transporte inyectable, tabla de k-buckets y get_peers, SIN red.
 */
class TorrentDhtTest {

    private val selfId = "-NIA-DHT-NODE-000001".toByteArray(Charsets.US_ASCII)
    private val infoHash = byteArrayOf(
        0x00, 0x01, 0x41, 0x5a, 0x61, 0x7a, 0x30, 0x39, 0x2e, 0x2d,
        0x5f, 0x7e, 0x20, 0xff.toByte(), 0x80.toByte(), 0x10, 0x11, 0x12, 0x13, 0x14
    )

    private fun bytesAHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun dict(vararg pares: Pair<String, Benc>): Benc.BDict {
        val m = LinkedHashMap<String, Benc>()
        for (p in pares) m[p.first] = p.second
        return Benc.BDict(m)
    }

    private class DhtFalso(private val respuestas: Map<String, ByteArray?>) : DhtTransport {
        val peticiones = ArrayList<ByteArray>()
        override fun exchange(ip: String, port: Int, request: ByteArray): ByteArray? {
            peticiones.add(request.copyOf())
            return respuestas[ip]
        }
    }

    private fun respuestaR(tx: Int, r: Benc.BDict): ByteArray = Bencode.encode(
        dict(
            "t" to Benc.BStr(byteArrayOf(((tx shr 8) and 0xff).toByte(), (tx and 0xff).toByte())),
            "y" to Benc.BStr("r".toByteArray(Charsets.UTF_8)),
            "r" to r
        )
    )

    @Test
    fun consultaPingCodificaTyQyA() {
        val cliente = DhtClient(DhtFalso(emptyMap()), selfId)
        val consulta = cliente.construirConsulta(1, "ping", dict("id" to Benc.BStr(selfId)))
        assertEquals(
            "64313a6164323a696432303a2d4e49412d4448542d4e4f44452d30303030303165313a71343a70696e67313a74323a0001313a79313a7165",
            bytesAHex(consulta)
        )
    }

    @Test
    fun consultaGetPeersLlevaInfoHashYTxDeDosBytes() {
        val cliente = DhtClient(DhtFalso(emptyMap()), selfId)
        val consulta = cliente.construirConsulta(
            258, "get_peers", dict("id" to Benc.BStr(selfId), "info_hash" to Benc.BStr(infoHash))
        )
        assertEquals(
            "64313a6164323a696432303a2d4e49412d4448542d4e4f44452d303030303031393a696e666f5f6861736832303a0001415a617a30392e2d5f7e20ff80101112131465313a71393a6765745f7065657273313a74323a0102313a79313a7165",
            bytesAHex(consulta)
        )
    }

    @Test
    fun respuestaRSeDecodificaConToken() {
        val body = respuestaR(1, dict("id" to Benc.BStr(selfId), "token" to Benc.BStr("aa".toByteArray(Charsets.UTF_8))))
        val r = DhtClient(DhtFalso(emptyMap()), selfId).parsearRespuesta(body, 1)
        val token = (r?.entries?.get("token") as? Benc.BStr)?.text()
        assertEquals("aa", token)
    }

    @Test
    fun respuestaErrorSeIgnora() {
        val body = Bencode.encode(
            dict(
                "t" to Benc.BStr(byteArrayOf(0, 1)),
                "y" to Benc.BStr("e".toByteArray(Charsets.UTF_8)),
                "e" to dict("204" to Benc.BStr("Server Error".toByteArray(Charsets.UTF_8)))
            )
        )
        assertNull(DhtClient(DhtFalso(emptyMap()), selfId).parsearRespuesta(body, 1))
    }

    @Test
    fun respuestaNoDiccionarioSeIgnora() {
        assertNull(DhtClient(DhtFalso(emptyMap()), selfId).parsearRespuesta("i1e".toByteArray(Charsets.UTF_8), 1))
    }

    @Test
    fun nodosCompactosSeDesempaquetan() {
        val nodoA = ByteArray(20)
        nodoA[0] = 0x02
        val nodoB = ByteArray(20)
        nodoB[0] = 0x03
        val crudo = nodoA +
            byteArrayOf(10, 0, 0, 1, 0x1a, 0xe1.toByte()) +
            nodoB +
            byteArrayOf(0xc0.toByte(), 0xa8.toByte(), 1, 2, 0xc8.toByte(), 0xd5.toByte())
        val peers = DhtClient(DhtFalso(emptyMap()), selfId).desempaquetarNodos(crudo)
        assertEquals(2, peers.size)
        assertEquals("10.0.0.1", peers[0].ip)
        assertEquals(6881, peers[0].port)
        assertEquals("192.168.1.2", peers[1].ip)
        assertEquals(51413, peers[1].port)
    }

    @Test
    fun nodosCompactosInvalidosSeDescartan() {
        val nodoA = ByteArray(20)
        nodoA[0] = 0x02
        val crudo = nodoA + byteArrayOf(10, 0, 0, 1, 0x1a)
        assertEquals(0, DhtClient(DhtFalso(emptyMap()), selfId).desempaquetarNodos(crudo).size)
    }

    @Test
    fun indiceDeBucketPorPrimerBitDistinto() {
        val routing = DhtRouting(ByteArray(20), 8)
        val id80 = ByteArray(20)
        id80[0] = 0x80.toByte()
        val id01 = ByteArray(20)
        id01[0] = 0x01
        val idCola = ByteArray(20)
        idCola[19] = 0x01
        assertEquals(0, routing.indiceDe(id80))
        assertEquals(7, routing.indiceDe(id01))
        assertEquals(159, routing.indiceDe(idCola))
        assertEquals(-1, routing.indiceDe(ByteArray(20)))
    }

    @Test
    fun bucketLlenoIgnoraYCercanosOrdenaPorXor() {
        val routing = DhtRouting(ByteArray(20), 2)
        val nA = ByteArray(20)
        nA[0] = 0x80.toByte()
        val nB = ByteArray(20)
        nB[0] = 0x40.toByte()
        val nC = ByteArray(20)
        nC[0] = 0x80.toByte()
        for (i in 1 until 20) nC[i] = 0xff.toByte()
        val nD = ByteArray(20)
        nD[0] = 0xc0.toByte()
        assertTrue(routing.add(DhtNode(nA, "10.0.0.1", 1)))
        assertTrue(routing.add(DhtNode(nC, "10.0.0.2", 2)))
        assertTrue(routing.add(DhtNode(nB, "10.0.0.3", 3)))
        assertFalse(routing.add(DhtNode(nD, "10.0.0.4", 4)))
        assertFalse(routing.add(DhtNode(nA, "10.0.0.1", 1)))
        val cercanos = routing.cercanos(ByteArray(20), 2)
        assertEquals(2, cercanos.size)
        assertEquals("4000000000000000000000000000000000000000", bytesAHex(cercanos[0].id))
        assertEquals("8000000000000000000000000000000000000000", bytesAHex(cercanos[1].id))
    }

    @Test
    fun buscarPeersRecopilaDeduplicaYRecorreNodos() {
        val nodoA = ByteArray(20)
        nodoA[0] = 0x02
        val nodoB = ByteArray(20)
        nodoB[0] = 0x03
        val nodosCrudo = nodoA +
            byteArrayOf(10, 0, 0, 2, 0x1b, 0x59) +
            nodoB +
            byteArrayOf(10, 0, 0, 3, 0x1b, 0x5a)
        val respNodos = respuestaR(
            1,
            dict(
                "id" to Benc.BStr(selfId),
                "token" to Benc.BStr("tk".toByteArray(Charsets.UTF_8)),
                "nodes" to Benc.BStr(nodosCrudo)
            )
        )
        val resp2 = respuestaR(
            2,
            dict(
                "id" to Benc.BStr(selfId),
                "values" to Benc.BList(listOf(Benc.BStr(byteArrayOf(10, 0, 0, 9, 0x1b, 0x39))))
            )
        )
        val resp3 = respuestaR(
            3,
            dict(
                "id" to Benc.BStr(selfId),
                "values" to Benc.BList(
                    listOf(
                        Benc.BStr(byteArrayOf(10, 0, 0, 9, 0x1b, 0x39)),
                        Benc.BStr(byteArrayOf(10, 0, 1, 1, 0x04, 0xd2.toByte()))
                    )
                )
            )
        )
        val falso = DhtFalso(mapOf("10.0.0.1" to respNodos, "10.0.0.2" to resp2, "10.0.0.3" to resp3))
        val peers = DhtClient(falso, selfId).buscarPeers(infoHash, listOf(TrackerPeer("10.0.0.1", 1)))
        assertEquals(2, peers.size)
        assertEquals("10.0.0.9", peers[0].ip)
        assertEquals(6969, peers[0].port)
        assertEquals("10.0.1.1", peers[1].ip)
        assertEquals(1234, peers[1].port)
        assertEquals(3, falso.peticiones.size)
        assertEquals(
            "64313a6164323a696432303a2d4e49412d4448542d4e4f44452d303030303031393a696e666f5f6861736832303a0001415a617a30392e2d5f7e20ff80101112131465313a71393a6765745f7065657273313a74323a0001313a79313a7165",
            bytesAHex(falso.peticiones[0])
        )
    }

    @Test
    fun buscarPeersSinRespuestaDevuelveVacia() {
        val falso = DhtFalso(emptyMap())
        val peers = DhtClient(falso, selfId).buscarPeers(infoHash, listOf(TrackerPeer("10.0.0.1", 1)))
        assertEquals(0, peers.size)
        assertEquals(1, falso.peticiones.size)
    }
}
