package nia.engine.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas): cada escenario del gemelo tiene su prueba aqui contra
 * las clases reales. T047 v3 (R0129, RES-0067, ADR-016): metadatos
 * magnet (BEP-9/10) con peer guionado y torrentDesdeInfo, SIN red.
 */
class TorrentMagnetTest {

    private val infoHash = byteArrayOf(
        0x00, 0x01, 0x41, 0x5a, 0x61, 0x7a, 0x30, 0x39, 0x2e, 0x2d,
        0x5f, 0x7e, 0x20, 0xff.toByte(), 0x80.toByte(), 0x10, 0x11, 0x12, 0x13, 0x14
    )
    private val peerId = "-NIA-0001-0000000000".toByteArray(Charsets.US_ASCII)

    private fun bytesAHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun piezasSha1(data: ByteArray, largo: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val fin = minOf(offset + largo, data.size)
            val bloque = data.copyOfRange(offset, fin)
            val sha = java.security.MessageDigest.getInstance("SHA-1").digest(bloque)
            out.write(sha)
            offset = fin
        }
        return out.toByteArray()
    }

    private fun infoSintetico(): ByteArray {
        val data = ByteArray(40) { (it + 1).toByte() }
        val info = Benc.BDict(LinkedHashMap())
        info.entries["name"] = Benc.BStr("red.txt".toByteArray(Charsets.UTF_8))
        info.entries["piece length"] = Benc.BInt(16)
        info.entries["pieces"] = Benc.BStr(piezasSha1(data, 16))
        info.entries["length"] = Benc.BInt(40)
        return Bencode.encode(info)
    }

    private class ConexionGuion(guion: ByteArray) : PeerConnection {
        val escritas = ByteArrayOutputStream()
        override val input: InputStream = ByteArrayInputStream(guion)
        override val output: OutputStream = escritas
        override fun close() {}
    }

    private class TransporteGuionado(private val guion: ByteArray) : PeerTransport {
        var conexion: ConexionGuion? = null
        override fun connect(peer: TrackerPeer): PeerConnection {
            val c = ConexionGuion(guion)
            conexion = c
            return c
        }
    }

    private fun handshakeDelPeer(ih: ByteArray): ByteArray =
        byteArrayOf(19) + "BitTorrent protocol".toByteArray(Charsets.US_ASCII) +
            ByteArray(8) + ih + peerId

    private fun wire32(out: ByteArrayOutputStream, valor: Int) {
        out.write((valor shr 24) and 0xff)
        out.write((valor shr 16) and 0xff)
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun guionMensaje(out: ByteArrayOutputStream, id: Int, payload: ByteArray) {
        wire32(out, 1 + payload.size)
        out.write(id)
        out.write(payload)
    }

    private fun extDelPeer(ut: Long): ByteArray {
        val m = Benc.BDict(LinkedHashMap())
        m.entries["ut_metadata"] = Benc.BInt(ut)
        val raiz = Benc.BDict(LinkedHashMap())
        raiz.entries["m"] = m
        return Bencode.encode(raiz)
    }

    private fun respuestaMetadata(ut: Int, piece: Int, total: Int, raw: ByteArray): ByteArray {
        val guion = ByteArrayOutputStream()
        val dict = Benc.BDict(LinkedHashMap())
        dict.entries["msg_type"] = Benc.BInt(1)
        dict.entries["piece"] = Benc.BInt(piece.toLong())
        dict.entries["total_size"] = Benc.BInt(total.toLong())
        val payload = ByteArrayOutputStream()
        payload.write(Bencode.encode(dict))
        payload.write(raw)
        guionMensaje(guion, ut, payload.toByteArray())
        return guion.toByteArray()
    }

    private fun fetch(guion: ByteArray, pieceSize: Int): Pair<ByteArray?, ByteArray> {
        val t = TransporteGuionado(guion)
        val r = PeerMetadataClient(t, infoHash, peerId, pieceSize).fetchMetadata(TrackerPeer("10.0.0.1", 1))
        val escritas = t.conexion?.escritas?.toByteArray() ?: ByteArray(0)
        return Pair(r, escritas)
    }

    @Test
    fun handshakeDeExtensionEscribeElMensaje20() {
        val (r, escritas) = fetch(handshakeDelPeer(infoHash), 16384)
        assertNull(r)
        assertEquals(97, escritas.size)
        assertEquals(
            "000000191464313a6d6431313a75745f6d657461646174616931656565",
            bytesAHex(escritas.copyOfRange(68, 97))
        )
    }

    @Test
    fun fetchMetadataDeUnaPiezaDevuelveElInfoDict() {
        val info = infoSintetico()
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        guionMensaje(guion, 20, extDelPeer(2))
        guion.write(respuestaMetadata(2, 0, info.size, info))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertArrayEquals(info, r)
        assertEquals(127, escritas.size)
        assertEquals(
            "0000001a0264383a6d73675f74797065693065353a706965636569306565",
            bytesAHex(escritas.copyOfRange(97, 127))
        )
    }

    @Test
    fun fetchMetadataEnsamblaVariasPiezasEnOrden() {
        val info = infoSintetico()
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        guionMensaje(guion, 20, extDelPeer(3))
        guion.write(respuestaMetadata(3, 0, info.size, info.copyOfRange(0, 48)))
        guion.write(respuestaMetadata(3, 1, info.size, info.copyOfRange(48, 96)))
        guion.write(respuestaMetadata(3, 2, info.size, info.copyOfRange(96, info.size)))
        val (r, escritas) = fetch(guion.toByteArray(), 48)
        assertArrayEquals(info, r)
        assertEquals(187, escritas.size)
    }

    @Test
    fun fetchMetadataRechazaPeerSinUtMetadata() {
        val m = Benc.BDict(LinkedHashMap())
        m.entries["otra_ext"] = Benc.BInt(7)
        val raiz = Benc.BDict(LinkedHashMap())
        raiz.entries["m"] = m
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        guionMensaje(guion, 20, Bencode.encode(raiz))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertNull(r)
        assertEquals(97, escritas.size)
    }

    @Test
    fun fetchMetadataSinRespuestaDevuelveNull() {
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        guionMensaje(guion, 20, extDelPeer(2))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertNull(r)
        assertEquals(127, escritas.size)
    }

    @Test
    fun fetchMetadataConMsgTypeDosDevuelveNull() {
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        guionMensaje(guion, 20, extDelPeer(2))
        val dict = Benc.BDict(LinkedHashMap())
        dict.entries["msg_type"] = Benc.BInt(2)
        dict.entries["piece"] = Benc.BInt(0)
        guionMensaje(guion, 2, Bencode.encode(dict))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertNull(r)
        assertEquals(127, escritas.size)
    }

    @Test
    fun fetchMetadataRechazaHandshakeConOtroInfoHash() {
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(ByteArray(20) { 0x77.toByte() }))
        guionMensaje(guion, 20, extDelPeer(2))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertNull(r)
        assertEquals(68, escritas.size)
    }

    @Test
    fun fetchMetadataIgnoraMensajesPreviosYPiezasAjenas() {
        val info = infoSintetico()
        val guion = ByteArrayOutputStream()
        guion.write(handshakeDelPeer(infoHash))
        wire32(guion, 0)
        guionMensaje(guion, 5, byteArrayOf(0xe0.toByte()))
        guionMensaje(guion, 20, extDelPeer(3))
        guion.write(respuestaMetadata(3, 5, info.size, ByteArray(48) { 9 }))
        guion.write(respuestaMetadata(3, 0, info.size, info))
        val (r, escritas) = fetch(guion.toByteArray(), 16384)
        assertArrayEquals(info, r)
        assertEquals(127, escritas.size)
    }

    @Test
    fun metadataEnsambladaMontaElTorrentYVerificaElInfohash() {
        val meta = torrentDesdeInfo("udp://tracker.example:6969/announce", infoSintetico())
        assertEquals("red.txt", meta.name)
        assertEquals(3, meta.pieceCount())
        assertEquals(40L, meta.totalLength)
        assertEquals("22862e930ba002ea69b3a0b3977f90b5a5f2d405", meta.infoHashHex)
        assertEquals(listOf("udp://tracker.example:6969/announce"), meta.trackers)
    }
}
