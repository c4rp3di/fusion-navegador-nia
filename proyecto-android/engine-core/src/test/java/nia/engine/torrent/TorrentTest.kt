package nia.engine.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (26 escenarios): cada
 * escenario del gemelo tiene su prueba aqui contra las clases reales.
 * T047 v1 (R0108, RES-0058, ADR-016): torrent con cliente propio.
 */
class TorrentTest {

    private fun dataT047(n: Int): ByteArray = ByteArray(n) { (it + 1).toByte() }

    private fun dictT047(vararg pares: Pair<String, Benc>): Benc.BDict {
        val m = LinkedHashMap<String, Benc>()
        for (p in pares) m[p.first] = p.second
        return Benc.BDict(m)
    }

    private fun bstrT047(s: String): Benc.BStr = Benc.BStr(s.toByteArray(Charsets.UTF_8))

    private fun piezasT047(data: ByteArray, largo: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(largo, data.size - i)
            out.write(sha1(data.copyOfRange(i, i + len)))
            i += len
        }
        return out.toByteArray()
    }

    private fun torrentT047(
        archivos: List<Pair<String, Long>>,
        data: ByteArray,
        trackers: List<String>
    ): ByteArray {
        val info: Benc = if (archivos.size == 1) {
            dictT047(
                "name" to bstrT047(archivos[0].first),
                "piece length" to Benc.BInt(16),
                "pieces" to Benc.BStr(piezasT047(data, 16)),
                "length" to Benc.BInt(archivos[0].second)
            )
        } else {
            dictT047(
                "name" to bstrT047("demo"),
                "piece length" to Benc.BInt(16),
                "pieces" to Benc.BStr(piezasT047(data, 16)),
                "files" to Benc.BList(archivos.map { a ->
                    dictT047(
                        "length" to Benc.BInt(a.second),
                        "path" to Benc.BList(listOf(bstrT047(a.first)))
                    )
                })
            )
        }
        val raiz = dictT047("info" to info)
        if (trackers.isNotEmpty()) raiz.entries["announce"] = bstrT047(trackers[0])
        return Bencode.encode(raiz)
    }

    private fun base32T047(bytes: ByteArray): String {
        val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var valor = 0
        val sb = StringBuilder()
        for (b in bytes) {
            valor = (valor shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alfabeto[(valor shr bits) and 31])
            }
        }
        if (bits > 0) sb.append(alfabeto[(valor shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Fuente con los datos reales: piezas correctas de la demo. */
    private class DatosT047(private val data: ByteArray) : PieceSource {
        var pedidas = 0
        override fun piece(index: Int, length: Int): ByteArray {
            pedidas++
            return data.copyOfRange(index * 16, index * 16 + length)
        }
    }

    /** Fuente que nunca tiene la pieza. */
    private class NulaT047 : PieceSource {
        var pedidas = 0
        override fun piece(index: Int, length: Int): ByteArray? {
            pedidas++
            return null
        }
    }

    /** Fuente con piezas de tamano correcto pero hash falso (ceros). */
    private class BasuraT047 : PieceSource {
        override fun piece(index: Int, length: Int): ByteArray = ByteArray(length)
    }

    @Test
    fun bencInt() {
        assertEquals(Benc.BInt(42), Bencode.decode("i42e".toByteArray()))
        assertEquals(Benc.BInt(-7), Bencode.decode("i-7e".toByteArray()))
    }

    @Test
    fun bencString() {
        assertEquals("hola", (Bencode.decode("4:hola".toByteArray()) as Benc.BStr).text())
        assertEquals(0, (Bencode.decode("0:".toByteArray()) as Benc.BStr).bytes.size)
    }

    @Test
    fun bencList() {
        val v = Bencode.decode("l4:holai7ee".toByteArray()) as Benc.BList
        assertEquals(2, v.items.size)
        assertEquals("hola", (v.items[0] as Benc.BStr).text())
        assertEquals(Benc.BInt(7), v.items[1])
    }

    @Test
    fun bencDict() {
        val v = Bencode.decode("d3:fooi5ee".toByteArray()) as Benc.BDict
        assertEquals(1, v.entries.size)
        assertEquals(Benc.BInt(5), v.entries["foo"])
    }

    @Test
    fun bencRoundTrip() {
        val v = dictT047(
            "lista" to Benc.BList(listOf(bstrT047("hola"), Benc.BInt(7))),
            "num" to Benc.BInt(-3)
        )
        val bytes = Bencode.encode(v)
        assertEquals(v, Bencode.decode(bytes))
        assertTrue(bytes.contentEquals(Bencode.encode(Bencode.decode(bytes))))
    }

    @Test
    fun bencOrdenaClaves() {
        val v = dictT047("b" to Benc.BInt(1), "a" to Benc.BInt(2))
        val texto = String(Bencode.encode(v), Charsets.UTF_8)
        assertTrue(texto.startsWith("d1:ai2e1:bi1ee"))
    }

    @Test
    fun bencRechazos() {
        try {
            Bencode.decode("i4".toByteArray())
            fail("truncado debe fallar")
        } catch (e: BencodeException) {
        }
        try {
            Bencode.decode("i4eX".toByteArray())
            fail("cola basura debe fallar")
        } catch (e: BencodeException) {
        }
    }

    @Test
    fun torrentMonoArchivo() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf("http://tracker/ann"))
        )
        assertEquals("demo.txt", meta.name)
        assertEquals(16L, meta.pieceLength)
        assertEquals(3, meta.pieceCount())
        assertEquals(1, meta.files.size)
        assertEquals("demo.txt", meta.files[0].path)
        assertEquals(40L, meta.files[0].length)
        assertEquals(40L, meta.totalLength)
        assertEquals(listOf("http://tracker/ann"), meta.trackers)
    }

    @Test
    fun torrentMultiArchivo() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("a.bin" to 20L, "b.bin" to 12L), dataT047(32), listOf("http://t/ann"))
        )
        assertEquals(32L, meta.totalLength)
        assertEquals(2, meta.pieceCount())
        assertEquals(0, meta.piecesForFile(0).first)
        assertEquals(1, meta.piecesForFile(0).last)
        assertEquals(1, meta.piecesForFile(1).first)
        assertEquals(1, meta.piecesForFile(1).last)
        assertEquals(20L, meta.fileOffset(1))
        assertEquals(2, meta.files.size)
        assertEquals("a.bin", meta.files[0].path)
    }

    @Test
    fun torrentInfoHashEsSha1Canonico() {
        val data = dataT047(40)
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), data, listOf("http://t/ann"))
        )
        val info = dictT047(
            "name" to bstrT047("demo.txt"),
            "piece length" to Benc.BInt(16),
            "pieces" to Benc.BStr(piezasT047(data, 16)),
            "length" to Benc.BInt(40)
        )
        assertEquals(hex(sha1(Bencode.encode(info))), meta.infoHashHex)
    }

    @Test
    fun torrentTrackersAnnounceList() {
        val data = dataT047(40)
        val info = dictT047(
            "name" to bstrT047("demo.txt"),
            "piece length" to Benc.BInt(16),
            "pieces" to Benc.BStr(piezasT047(data, 16)),
            "length" to Benc.BInt(40)
        )
        val raiz = dictT047(
            "announce" to bstrT047("http://ann/x"),
            "announce-list" to Benc.BList(
                listOf(
                    Benc.BList(listOf(bstrT047("http://t1/x"))),
                    Benc.BList(listOf(bstrT047("http://t2/y"), bstrT047("http://t3/z")))
                )
            ),
            "info" to info
        )
        val meta = TorrentParser.parse(Bencode.encode(raiz))
        assertEquals(
            listOf("http://ann/x", "http://t1/x", "http://t2/y", "http://t3/z"),
            meta.trackers
        )
    }

    @Test
    fun torrentSinAnnounceOk() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf())
        )
        assertTrue(meta.trackers.isEmpty())
    }

    @Test
    fun torrentPiecesMalTamanoRechazado() {
        val info = dictT047(
            "name" to bstrT047("demo.txt"),
            "piece length" to Benc.BInt(16),
            "pieces" to Benc.BStr(ByteArray(30)),
            "length" to Benc.BInt(40)
        )
        val raiz = dictT047("info" to info)
        try {
            TorrentParser.parse(Bencode.encode(raiz))
            fail("pieces mal debe fallar")
        } catch (e: TorrentFormatException) {
            assertEquals("pieces no es multiplo de 20", e.message)
        }
    }

    @Test
    fun magnetHex() {
        val m = MagnetParser.parse(
            "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567" +
                "&dn=Demo&tr=http%3A%2F%2Ftracker%2Fann"
        )
        assertEquals("0123456789abcdef0123456789abcdef01234567", m.infoHashHex)
        assertEquals("Demo", m.name)
        assertEquals(listOf("http%3A%2F%2Ftracker%2Fann"), m.trackers)
    }

    @Test
    fun magnetBase32() {
        val h = sha1("demo".toByteArray(Charsets.UTF_8))
        val b32 = base32T047(h)
        assertEquals(32, b32.length)
        val m = MagnetParser.parse("magnet:?xt=urn:btih:" + b32)
        assertEquals(hex(h), m.infoHashHex)
        assertTrue(m.name == null)
        assertTrue(m.trackers.isEmpty())
    }

    @Test
    fun magnetMultiplesTrackers() {
        val m = MagnetParser.parse(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
                "&tr=http://t1/x&tr=http://t2/y"
        )
        assertEquals(listOf("http://t1/x", "http://t2/y"), m.trackers)
    }

    @Test
    fun magnetSinXtRechazado() {
        try {
            MagnetParser.parse("magnet:?dn=x")
            fail("magnet sin xt debe fallar")
        } catch (e: TorrentFormatException) {
            assertEquals("magnet sin xt", e.message)
        }
    }

    @Test
    fun sesionSeleccionVaciaEsTodo() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf())
        )
        val s = TorrentSession(meta, emptySet())
        assertEquals(listOf(0, 1, 2), s.neededPieces)
        assertEquals(3, s.progress().totalPieces)
        assertTrue(s.isFileNeeded(0))
    }

    @Test
    fun sesionSeleccionParcialSoloPiezasDelArchivo() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("a.bin" to 20L, "b.bin" to 12L), dataT047(32), listOf())
        )
        val s = TorrentSession(meta, setOf(1))
        assertEquals(listOf(1), s.neededPieces)
        assertEquals(1, s.progress().totalPieces)
        assertFalse(s.isFileNeeded(0))
        assertTrue(s.isFileNeeded(1))
    }

    @Test
    fun sesionPumpCompletaYAvanza() {
        val data = dataT047(40)
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), data, listOf())
        )
        val s = TorrentSession(meta, emptySet())
        val fuente = DatosT047(data)
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(1, s.progress().completedPieces)
        assertEquals(3, s.progress().totalPieces)
        assertEquals(16L, s.progress().downloadedBytes)
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(2, s.progress().completedPieces)
    }

    @Test
    fun sesionPumpHashIncorrectoRechazado() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf())
        )
        val s = TorrentSession(meta, emptySet())
        val fuente = BasuraT047()
        assertEquals(PumpResult.REJECTED, s.pump(fuente))
        assertEquals(PumpResult.REJECTED, s.pump(fuente))
        assertEquals(0, s.progress().completedPieces)
        assertEquals(0L, s.progress().downloadedBytes)
        assertEquals(TorrentState.QUEUED, s.state)
    }

    @Test
    fun sesionPumpNoDisponible() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf())
        )
        val s = TorrentSession(meta, emptySet())
        val fuente = NulaT047()
        assertEquals(PumpResult.UNAVAILABLE, s.pump(fuente))
        assertEquals(1, fuente.pedidas)
        assertEquals(0, s.progress().completedPieces)
    }

    @Test
    fun sesionPausaYResume() {
        val data = dataT047(40)
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), data, listOf())
        )
        val s = TorrentSession(meta, emptySet())
        s.start()
        assertEquals(TorrentState.RUNNING, s.state)
        s.pause()
        assertEquals(TorrentState.PAUSED, s.state)
        val fuente = DatosT047(data)
        assertEquals(PumpResult.PAUSED, s.pump(fuente))
        assertEquals(0, fuente.pedidas)
        s.resume()
        assertEquals(TorrentState.RUNNING, s.state)
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(1, fuente.pedidas)
    }

    @Test
    fun sesionCancelacion() {
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf())
        )
        val s = TorrentSession(meta, emptySet())
        s.cancel()
        assertEquals(TorrentState.CANCELLED, s.state)
        assertEquals(PumpResult.CANCELLED, s.pump(NulaT047()))
        assertEquals(0, s.progress().completedPieces)
    }

    @Test
    fun sesionDoneAlCompletarTodo() {
        val data = dataT047(40)
        val meta = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), data, listOf())
        )
        val s = TorrentSession(meta, emptySet())
        val fuente = DatosT047(data)
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(PumpResult.COMPLETED, s.pump(fuente))
        assertEquals(TorrentState.DONE, s.state)
        assertEquals(PumpResult.DONE, s.pump(fuente))
        assertEquals(3, s.progress().completedPieces)
        assertEquals(3, s.progress().totalPieces)
        assertEquals(40L, s.progress().downloadedBytes)
        assertEquals(40L, meta.totalLength)
    }

    @Test
    fun engineAltaBusquedaBorradoListado() {
        val motor = TorrentEngine()
        val meta1 = TorrentParser.parse(
            torrentT047(listOf("demo.txt" to 40L), dataT047(40), listOf("http://t/ann"))
        )
        val meta2 = TorrentParser.parse(
            torrentT047(listOf("a.bin" to 20L, "b.bin" to 12L), dataT047(32), listOf())
        )
        motor.add(meta1, emptySet())
        motor.add(meta2, setOf(1))
        assertEquals(2, motor.list().size)
        assertEquals(meta1.infoHashHex, motor.find(meta1.infoHashHex)?.meta?.infoHashHex)
        assertTrue(motor.delete(meta1.infoHashHex))
        assertFalse(motor.delete(meta1.infoHashHex))
        assertTrue(motor.find(meta1.infoHashHex) == null)
        assertEquals(1, motor.list().size)
        assertTrue(motor.delete(meta2.infoHashHex))
        assertTrue(motor.list().isEmpty())
    }
}
