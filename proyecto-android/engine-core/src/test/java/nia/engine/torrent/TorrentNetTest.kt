package nia.engine.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (27 escenarios en 2
 * corridas): cada escenario del gemelo tiene su prueba aqui contra las
 * clases reales. T047 v2 (R0126, RES-0066, ADR-016): red del torrent
 * (announce HTTP del tracker y protocolo wire de peers) sobre la
 * frontera PieceSource, con transportes enlatados y SIN red.
 */
class TorrentNetTest {

    private val infoHash = byteArrayOf(
        0x00, 0x01, 0x41, 0x5a, 0x61, 0x7a, 0x30, 0x39, 0x2e, 0x2d,
        0x5f, 0x7e, 0x20, 0xff.toByte(), 0x80.toByte(), 0x10, 0x11, 0x12, 0x13, 0x14
    )
    private val peerId = "-NIA-0001-0000000000".toByteArray(Charsets.US_ASCII)

    private fun dataN(n: Int): ByteArray = ByteArray(n) { (it + 1).toByte() }

    private fun bytesAHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun piezaONulo(r: ByteArray?): ByteArray = r ?: throw AssertionError("pieza nula")

    private fun piezasSha1(data: ByteArray, largo: Int): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(largo, data.size - i)
            md.reset()
            out.write(md.digest(data.copyOfRange(i, i + len)))
            i += len
        }
        return out.toByteArray()
    }

    private fun dict(vararg pares: Pair<String, Benc>): Benc.BDict {
        val m = LinkedHashMap<String, Benc>()
        for (p in pares) m[p.first] = p.second
        return Benc.BDict(m)
    }

    private class TrackerFalso(private val cuerpo: ByteArray) : TrackerTransport {
        override fun get(url: String): ByteArray = cuerpo
    }

    private class ConexionGuionada(guion: ByteArray) : PeerConnection {
        val escritas = java.io.ByteArrayOutputStream()
        override val input: java.io.InputStream = java.io.ByteArrayInputStream(guion)
        override val output: java.io.OutputStream = escritas
        override fun close() {}
    }

    private class TransporteContador(private val construir: (Int) -> ByteArray) : PeerTransport {
        var conexiones = 0
        var ultima: ConexionGuionada? = null
        override fun connect(peer: TrackerPeer): PeerConnection {
            val n = conexiones
            conexiones++
            val c = ConexionGuionada(construir(n))
            ultima = c
            return c
        }
    }

    private class TransportePorPeer(private val guiones: Map<String, ByteArray>) : PeerTransport {
        var conexiones = 0
        override fun connect(peer: TrackerPeer): PeerConnection {
            conexiones++
            return ConexionGuionada(guiones.getValue(peer.ip))
        }
    }

    private class TransporteRoto : PeerTransport {
        override fun connect(peer: TrackerPeer): PeerConnection =
            throw java.io.IOException("sin ruta")
    }

    private fun guionHandshake(out: java.io.ByteArrayOutputStream, ih: ByteArray, pid: ByteArray) {
        out.write(19)
        out.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8))
        out.write(ih)
        out.write(pid)
    }

    private fun wire32(out: java.io.ByteArrayOutputStream, valor: Int) {
        out.write((valor shr 24) and 0xff)
        out.write((valor shr 16) and 0xff)
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun guionMensaje(out: java.io.ByteArrayOutputStream, id: Int, payload: ByteArray) {
        wire32(out, 1 + payload.size)
        out.write(id)
        out.write(payload)
    }

    private fun guionPieza(out: java.io.ByteArrayOutputStream, index: Int, begin: Int, block: ByteArray) {
        val payload = java.io.ByteArrayOutputStream()
        wire32(payload, index)
        wire32(payload, begin)
        payload.write(block)
        guionMensaje(out, 7, payload.toByteArray())
    }

    private fun escritasDe(t: TransporteContador): ByteArray {
        val c = t.ultima ?: throw AssertionError("sin conexion")
        return c.escritas.toByteArray()
    }

    @Test
    fun announceCodificaInfoHashYPeerIdPorByte() {
        val req = TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40)
        val url = TrackerClient(TrackerFalso(ByteArray(0)))
            .construirUrl("http://tracker.example/announce", req)
        assertEquals(
            "http://tracker.example/announce?info_hash=%00%01AZaz09.-_~%20%ff%80%10%11%12%13%14&peer_id=-NIA-0001-0000000000&port=6881&uploaded=0&downloaded=0&left=40&compact=1",
            url
        )
    }

    @Test
    fun announceUsaAmpersandCuandoElTrackerYaTieneQuery() {
        val req = TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40)
        val url = TrackerClient(TrackerFalso(ByteArray(0)))
            .construirUrl("http://t/announce?key=1", req)
        assertEquals(
            "http://t/announce?key=1&info_hash=%00%01AZaz09.-_~%20%ff%80%10%11%12%13%14&peer_id=-NIA-0001-0000000000&port=6881&uploaded=0&downloaded=0&left=40&compact=1",
            url
        )
    }

    @Test
    fun announceConEventoStartedViajaYConNoneSeOmite() {
        val cliente = TrackerClient(TrackerFalso(ByteArray(0)))
        val started = cliente.construirUrl(
            "http://t/announce",
            TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40, AnnounceEvent.STARTED)
        )
        val none = cliente.construirUrl(
            "http://t/announce",
            TrackerAnnounceRequest(infoHash, peerId, 6881, 0, 0, 40)
        )
        assertEquals(
            "http://t/announce?info_hash=%00%01AZaz09.-_~%20%ff%80%10%11%12%13%14&peer_id=-NIA-0001-0000000000&port=6881&uploaded=0&downloaded=0&left=40&compact=1&event=started",
            started
        )
        assertFalse(none.contains("event="))
    }

    @Test
    fun announceParseaPeersCompactosYIntervalo() {
        val cuerpo = Bencode.encode(
            dict(
                "interval" to Benc.BInt(120),
                "peers" to Benc.BStr(
                    byteArrayOf(
                        10, 0, 0, 1, 0x1a, 0xe1.toByte(),
                        0xc0.toByte(), 0xa8.toByte(), 1, 2, 0xc8.toByte(), 0xd5.toByte()
                    )
                )
            )
        )
        val respuesta = TrackerClient(TrackerFalso(cuerpo))
            .announce("http://t/a", TrackerAnnounceRequest(infoHash, peerId, 1, 0, 0, 0))
        assertEquals(120L, respuesta.interval)
        assertEquals(2, respuesta.peers.size)
        assertEquals("10.0.0.1", respuesta.peers[0].ip)
        assertEquals(6881, respuesta.peers[0].port)
        assertEquals("192.168.1.2", respuesta.peers[1].ip)
        assertEquals(51413, respuesta.peers[1].port)
    }

    @Test
    fun announceParseaPeersEnListaDeDiccionarios() {
        val cuerpo = Bencode.encode(
            dict(
                "interval" to Benc.BInt(60),
                "peers" to Benc.BList(
                    listOf(
                        dict(
                            "ip" to Benc.BStr("203.0.113.7".toByteArray(Charsets.UTF_8)),
                            "port" to Benc.BInt(6969)
                        )
                    )
                )
            )
        )
        val respuesta = TrackerClient(TrackerFalso(cuerpo))
            .announce("http://t/a", TrackerAnnounceRequest(infoHash, peerId, 1, 0, 0, 0))
        assertEquals(60L, respuesta.interval)
        assertEquals(1, respuesta.peers.size)
        assertEquals("203.0.113.7", respuesta.peers[0].ip)
        assertEquals(6969, respuesta.peers[0].port)
    }

    @Test
    fun announceSinPeersDevuelveVaciaEIntervaloPorDefecto() {
        val cuerpo = Bencode.encode(dict("complete" to Benc.BInt(2)))
        val respuesta = TrackerClient(TrackerFalso(cuerpo))
            .announce("http://t/a", TrackerAnnounceRequest(infoHash, peerId, 1, 0, 0, 0))
        assertEquals(1800L, respuesta.interval)
        assertTrue(respuesta.peers.isEmpty())
    }

    @Test
    fun announceConFailureReasonLanzaTorrentFormatException() {
        val cuerpo = Bencode.encode(
            dict("failure reason" to Benc.BStr("tracker saturado".toByteArray(Charsets.UTF_8)))
        )
        try {
            TrackerClient(TrackerFalso(cuerpo))
                .announce("http://t/a", TrackerAnnounceRequest(infoHash, peerId, 1, 0, 0, 0))
            fail("failure reason debe lanzar")
        } catch (e: TorrentFormatException) {
            assertEquals("tracker: tracker saturado", e.message)
        }
    }

    @Test
    fun announceConRespuestaQueNoEsDiccionarioFalla() {
        try {
            TrackerClient(TrackerFalso("i1e".toByteArray(Charsets.UTF_8)))
                .announce("http://t/a", TrackerAnnounceRequest(infoHash, peerId, 1, 0, 0, 0))
            fail("respuesta no diccionario debe lanzar")
        } catch (e: TorrentFormatException) {
            assertEquals("respuesta del tracker no es diccionario", e.message)
        }
    }

    @Test
    fun handshakeEscribeElProtocoloCompletoDe68Bytes() {
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16))
        val e = escritasDe(t)
        assertEquals(73, e.size)
        assertEquals(19, e[0].toInt())
        assertEquals("BitTorrent protocol", String(e, 1, 19, Charsets.US_ASCII))
        assertArrayEquals(ByteArray(8), e.copyOfRange(20, 28))
        assertArrayEquals(infoHash, e.copyOfRange(28, 48))
        assertArrayEquals(peerId, e.copyOfRange(48, 68))
    }

    @Test
    fun fetchPieceEnsamblaLaPiezaPorBloques() {
        val data40 = dataN(40)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 0, 0, data40.copyOfRange(0, 16))
        guionPieza(g, 0, 16, data40.copyOfRange(16, 32))
        guionPieza(g, 0, 32, data40.copyOfRange(32, 40))
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 40)
        assertArrayEquals(data40, piezaONulo(r))
        val e = escritasDe(t)
        assertEquals(124, e.size)
        assertEquals(
            "0000000d060000000000000000000000100000000d060000000000000010000000100000000d06000000000000002000000008",
            bytesAHex(e.copyOfRange(73, 124))
        )
    }

    @Test
    fun fetchPieceDeUnSoloBloquePideUnaSolaSolicitud() {
        val data16 = dataN(16)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 0, 0, data16)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16)
        assertArrayEquals(data16, piezaONulo(r))
        assertEquals(90, escritasDe(t).size)
    }

    @Test
    fun fetchPieceRechazaHandshakeConOtroInfoHash() {
        val otro = ByteArray(20) { 0x77.toByte() }
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, otro, peerId)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16))
        assertEquals(68, escritasDe(t).size)
    }

    @Test
    fun fetchPieceRechazaPstrIncorrecto() {
        val g = java.io.ByteArrayOutputStream()
        g.write(19)
        g.write("NotTorrent protocolx".toByteArray(Charsets.US_ASCII))
        g.write(ByteArray(8))
        g.write(infoHash)
        g.write(peerId)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16))
        assertEquals(68, escritasDe(t).size)
    }

    @Test
    fun fetchPieceSinRespuestaDevuelveNull() {
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 40))
        assertEquals(124, escritasDe(t).size)
    }

    @Test
    fun fetchPieceConBitfieldDeLongitudInvalidaDevuelveNull() {
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte(), 0x00))
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16))
        assertEquals(73, escritasDe(t).size)
    }

    @Test
    fun fetchPieceEsperaElDeschoque() {
        val data40 = dataN(40)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 0, ByteArray(0))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 0, 0, data40.copyOfRange(0, 16))
        guionPieza(g, 0, 16, data40.copyOfRange(16, 32))
        guionPieza(g, 0, 32, data40.copyOfRange(32, 40))
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 40)
        assertArrayEquals(data40, piezaONulo(r))
    }

    @Test
    fun fetchPieceConChokeTardioAceptaLosBloques() {
        val data40 = dataN(40)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        guionMensaje(g, 0, ByteArray(0))
        guionPieza(g, 0, 0, data40.copyOfRange(0, 16))
        guionPieza(g, 0, 16, data40.copyOfRange(16, 32))
        guionPieza(g, 0, 32, data40.copyOfRange(32, 40))
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 40)
        assertArrayEquals(data40, piezaONulo(r))
    }

    @Test
    fun fetchPieceConHaveYKeepAliveCompletaLaPieza() {
        val data16 = dataN(16)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        wire32(g, 0)
        guionMensaje(g, 5, byteArrayOf(0x00))
        val have = java.io.ByteArrayOutputStream()
        wire32(have, 2)
        guionMensaje(g, 4, have.toByteArray())
        guionMensaje(g, 3, ByteArray(0))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 0, 0, data16)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16)
        assertArrayEquals(data16, piezaONulo(r))
        assertEquals(90, escritasDe(t).size)
    }

    @Test
    fun fetchPieceIgnoraLosBloquesDeOtrasPiezas() {
        val data16 = dataN(16)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 1, 0, data16)
        guionPieza(g, 0, 0, data16)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16)
        assertArrayEquals(data16, piezaONulo(r))
    }

    @Test
    fun fetchPieceIgnoraElBloqueDeComienzoYLargoDesconocidos() {
        val data16 = dataN(16)
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        guionPieza(g, 0, 99, data16)
        guionPieza(g, 0, 0, data16.copyOfRange(0, 11))
        guionPieza(g, 0, 0, data16)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        val r = cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16)
        assertArrayEquals(data16, piezaONulo(r))
    }

    @Test
    fun fetchPieceConLargoDeMensajeInvalidoDevuelveNull() {
        val g = java.io.ByteArrayOutputStream()
        guionHandshake(g, infoHash, peerId)
        guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(g, 1, ByteArray(0))
        g.write(0xff)
        g.write(0xff)
        g.write(0xff)
        g.write(0xff)
        val t = TransporteContador { g.toByteArray() }
        val cliente = PeerWireClient(t, infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 40))
        assertEquals(124, escritasDe(t).size)
    }

    @Test
    fun connectQueLanzaIOExceptionDevuelveNull() {
        val cliente = PeerWireClient(TransporteRoto(), infoHash, peerId, 3, 16)
        assertNull(cliente.fetchPiece(TrackerPeer("10.0.0.1", 1), 0, 16))
    }

    @Test
    fun poolSirveLaPiezaDelPrimerPeerQueLaTiene() {
        val data16 = dataN(16)
        val malo = java.io.ByteArrayOutputStream()
        guionHandshake(malo, ByteArray(20) { 0x66.toByte() }, peerId)
        val bueno = java.io.ByteArrayOutputStream()
        guionHandshake(bueno, infoHash, peerId)
        guionMensaje(bueno, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(bueno, 1, ByteArray(0))
        guionPieza(bueno, 0, 0, data16)
        val t = TransportePorPeer(
            mapOf("10.0.0.1" to malo.toByteArray(), "10.0.0.2" to bueno.toByteArray())
        )
        val pool = PeerPoolSource(
            listOf(TrackerPeer("10.0.0.1", 1), TrackerPeer("10.0.0.2", 2)),
            t, infoHash, peerId, 3, 16
        )
        assertArrayEquals(data16, piezaONulo(pool.piece(0, 16)))
        assertEquals(2, t.conexiones)
    }

    @Test
    fun poolDevuelveNullSiNingunPeerLaSirve() {
        val malo = java.io.ByteArrayOutputStream()
        guionHandshake(malo, ByteArray(20) { 0x66.toByte() }, peerId)
        val t = TransportePorPeer(
            mapOf("10.0.0.1" to malo.toByteArray(), "10.0.0.2" to malo.toByteArray())
        )
        val pool = PeerPoolSource(
            listOf(TrackerPeer("10.0.0.1", 1), TrackerPeer("10.0.0.2", 2)),
            t, infoHash, peerId, 3, 16
        )
        assertNull(pool.piece(0, 16))
        assertEquals(2, t.conexiones)
    }

    @Test
    fun poolConListaVaciaDevuelveNull() {
        val t = TransportePorPeer(emptyMap())
        val pool = PeerPoolSource(emptyList(), t, infoHash, peerId, 3, 16)
        assertNull(pool.piece(0, 16))
        assertEquals(0, t.conexiones)
    }

    @Test
    fun sessionPumpConPoolCompletaHastaDone() {
        val data40 = dataN(40)
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("red.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data40, 16))
        info.entries["length"] = Benc.BInt(40)
        val root = Benc.BDict(LinkedHashMap())
        root.entries["announce"] = Benc.BStr("http://tracker.example/announce".toByteArray(Charsets.UTF_8))
        root.entries["info"] = info
        val meta = TorrentParser.parse(Bencode.encode(root))
        assertEquals("22862e930ba002ea69b3a0b3977f90b5a5f2d405", meta.infoHashHex)
        val t = TransporteContador { n ->
            val largo = minOf(16, meta.totalLength.toInt() - n * 16)
            val g = java.io.ByteArrayOutputStream()
            guionHandshake(g, meta.infoHash, peerId)
            guionMensaje(g, 5, byteArrayOf(0xe0.toByte()))
            guionMensaje(g, 1, ByteArray(0))
            guionPieza(g, n, 0, data40.copyOfRange(n * 16, n * 16 + largo))
            g.toByteArray()
        }
        val pool = PeerPoolSource(listOf(TrackerPeer("127.0.0.1", 6881)), t, meta.infoHash, peerId, meta.pieceCount(), 16)
        val sesion = TorrentSession(meta, emptySet())
        var bomba: PumpResult
        var bombeos = 0
        do {
            bomba = sesion.pump(pool)
            bombeos++
        } while (bomba == PumpResult.COMPLETED)
        assertEquals(PumpResult.DONE, bomba)
        assertEquals(TorrentState.DONE, sesion.state)
        assertEquals(3, sesion.progress().completedPieces)
        assertEquals(3, sesion.progress().totalPieces)
        assertEquals(40L, sesion.progress().downloadedBytes)
        assertEquals(4, bombeos)
        assertEquals(3, t.conexiones)
    }

    @Test
    fun sessionPumpSinPeersDevuelveUnavailable() {
        val data40 = dataN(40)
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("red.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data40, 16))
        info.entries["length"] = Benc.BInt(40)
        val root = Benc.BDict(LinkedHashMap())
        root.entries["announce"] = Benc.BStr("http://tracker.example/announce".toByteArray(Charsets.UTF_8))
        root.entries["info"] = info
        val meta = TorrentParser.parse(Bencode.encode(root))
        val pool = PeerPoolSource(emptyList(), TransportePorPeer(emptyMap()), meta.infoHash, peerId, meta.pieceCount(), 16)
        val sesion = TorrentSession(meta, emptySet())
        assertEquals(PumpResult.UNAVAILABLE, sesion.pump(pool))
        assertEquals(TorrentState.QUEUED, sesion.state)
    }
}
