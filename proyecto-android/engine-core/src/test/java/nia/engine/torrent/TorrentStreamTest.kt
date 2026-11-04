package nia.engine.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Espejo Kotlin del gemelo TS validado offline (19 escenarios en 2
 * corridas): cada escenario del gemelo tiene su prueba aqui contra las
 * clases reales. T048 v1 (R0110, RES-0059): streaming del archivo
 * parcial reproducible sobre las piezas verificadas de la sesion.
 */
class TorrentStreamTest {

    private fun dataT048(n: Int): ByteArray = ByteArray(n) { (it + 1).toByte() }

    private fun metaT048(
        files: List<TorrentFile>,
        pieceLength: Long,
        contenido: ByteArray
    ): TorrentMeta {
        val hashes = ArrayList<ByteArray>()
        var i = 0
        while (i < contenido.size) {
            val len = minOf(pieceLength.toInt(), contenido.size - i)
            hashes.add(sha1(contenido.copyOfRange(i, i + len)))
            i += len
        }
        return TorrentMeta(
            "demo", pieceLength, hashes, files, contenido.size.toLong(),
            ByteArray(20), "", emptyList()
        )
    }

    private class FuenteT048(
        private val data: ByteArray,
        private val pieceLength: Int,
        private val hasta: Int = Int.MAX_VALUE
    ) : PieceSource {
        override fun piece(index: Int, length: Int): ByteArray? {
            if (index >= hasta) return null
            return data.copyOfRange(index * pieceLength, index * pieceLength + length)
        }
    }

    private class FuenteMalaT048(
        private val data: ByteArray,
        private val pieceLength: Int
    ) : PieceSource {
        override fun piece(index: Int, length: Int): ByteArray? {
            if (index == 1) return data.copyOfRange(0, length)
            return data.copyOfRange(index * pieceLength, index * pieceLength + length)
        }
    }

    @Test
    fun streamSinPiezasDevuelveNada() {
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, dataT048(40))
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        assertEquals(0L, stream.readableBytes())
        assertNull(stream.read(0L, 10))
        assertFalse(stream.isRangeReady(0L, 1L))
    }

    @Test
    fun streamPumpProgresivoAvanzaReadable() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        assertEquals(20L, stream.readableBytes())
        assertTrue(stream.isRangeReady(0L, 20L))
        assertFalse(stream.isRangeReady(0L, 21L))
    }

    @Test
    fun streamLecturaDentroDePieza() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        val chunk = stream.read(5L, 10) ?: throw AssertionError("chunk null")
        assertEquals(5L, chunk.offset)
        assertEquals(10, chunk.bytes.size)
        assertEquals(6, chunk.bytes[0].toInt())
        assertEquals(15, chunk.bytes[9].toInt())
    }

    @Test
    fun streamSeCortaEnPrimerHueco() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        val chunk = stream.read(10L, 30) ?: throw AssertionError("chunk null")
        assertEquals(10, chunk.bytes.size)
    }

    @Test
    fun streamDesdeHuecoDevuelveNull() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        assertNull(stream.read(25L, 5))
    }

    @Test
    fun streamOffsetFueraDeRangoNull() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 2))
        assertNull(stream.read(40L, 5))
        assertNull(stream.read(-1L, 5))
    }

    @Test
    fun streamUltimaLecturaParcialAlFin() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 2))
        sesion.pump(FuenteT048(contenido, 20, 2))
        val chunk = stream.read(30L, 30) ?: throw AssertionError("chunk null")
        assertEquals(10, chunk.bytes.size)
        assertEquals(31, chunk.bytes[0].toInt())
        assertEquals(40, chunk.bytes[9].toInt())
    }

    @Test
    fun streamMaxLengthMenorQueDisponible() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 2))
        val chunk = stream.read(0L, 5) ?: throw AssertionError("chunk null")
        assertEquals(5, chunk.bytes.size)
    }

    @Test
    fun streamSesionCompletaLeeTodo() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 2))
        sesion.pump(FuenteT048(contenido, 20, 2))
        val chunk = stream.read(0L, 100) ?: throw AssertionError("chunk null")
        assertEquals(40, chunk.bytes.size)
        assertArrayEquals(contenido, chunk.bytes)
    }

    @Test
    fun streamRequiresInvalidos() {
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, dataT048(40))
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        try {
            stream.read(0L, 0)
            fail("maxLength 0 debe fallar")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun streamCruzaFronteraDePiezaCortandoEnHueco() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        val chunk = stream.read(15L, 20) ?: throw AssertionError("chunk null")
        assertEquals(5, chunk.bytes.size)
        assertEquals(16, chunk.bytes[0].toInt())
        assertEquals(20, chunk.bytes[4].toInt())
    }

    @Test
    fun streamLecturaCruzaFronteraDePieza() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 1))
        val chunk = stream.read(15L, 4) ?: throw AssertionError("chunk null")
        assertEquals(4, chunk.bytes.size)
        assertEquals(19, chunk.bytes[3].toInt())
        assertEquals(20L, stream.readableBytes())
    }

    @Test
    fun streamHashRechazadoMantienePrefijo() {
        val contenido = dataT048(60)
        val meta = metaT048(listOf(TorrentFile("v.bin", 60L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val fuente = FuenteMalaT048(contenido, 20)
        assertEquals(PumpResult.COMPLETED, sesion.pump(fuente))
        assertEquals(PumpResult.REJECTED, sesion.pump(fuente))
        assertEquals(20L, TorrentStream(sesion, 0).readableBytes())
    }

    @Test
    fun streamArchivoConOffsetCompartePieza() {
        val contenido = dataT048(40)
        val meta = metaT048(
            listOf(TorrentFile("a.txt", 25L), TorrentFile("b.bin", 15L)),
            20L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        val streamA = TorrentStream(sesion, 0)
        val streamB = TorrentStream(sesion, 1)
        sesion.pump(FuenteT048(contenido, 20, 1))
        assertEquals(20L, streamA.readableBytes())
        assertEquals(0L, streamB.readableBytes())
    }

    @Test
    fun streamSegundoArchivoLeeDesdeSuOffset() {
        val contenido = dataT048(40)
        val meta = metaT048(
            listOf(TorrentFile("a.txt", 25L), TorrentFile("b.bin", 15L)),
            20L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        val streamA = TorrentStream(sesion, 0)
        val streamB = TorrentStream(sesion, 1)
        sesion.pump(FuenteT048(contenido, 20, 2))
        sesion.pump(FuenteT048(contenido, 20, 2))
        val chunkA = streamA.read(0L, 100) ?: throw AssertionError("chunkA null")
        val chunkB = streamB.read(0L, 100) ?: throw AssertionError("chunkB null")
        assertEquals(25, chunkA.bytes.size)
        assertEquals(15, chunkB.bytes.size)
        assertEquals(26, chunkB.bytes[0].toInt())
        assertEquals(40, chunkB.bytes[14].toInt())
        assertNull(streamB.read(15L, 5))
    }

    @Test
    fun streamPiezaFinalCortaNoDesborda() {
        val contenido = dataT048(45)
        val meta = metaT048(listOf(TorrentFile("c.bin", 45L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        val stream = TorrentStream(sesion, 0)
        sesion.pump(FuenteT048(contenido, 20, 3))
        sesion.pump(FuenteT048(contenido, 20, 3))
        sesion.pump(FuenteT048(contenido, 20, 3))
        val chunk = stream.read(40L, 10) ?: throw AssertionError("chunk null")
        assertEquals(5, chunk.bytes.size)
        assertEquals(41, chunk.bytes[0].toInt())
        assertEquals(45, chunk.bytes[4].toInt())
    }

    @Test
    fun streamArchivoVacio() {
        val contenido = dataT048(40)
        val meta = metaT048(
            listOf(
                TorrentFile("a.txt", 25L),
                TorrentFile("vacio", 0L),
                TorrentFile("b.bin", 15L)
            ),
            20L, contenido
        )
        val sesion = TorrentSession(meta, setOf(1))
        val stream = TorrentStream(sesion, 1)
        assertEquals(0L, stream.length)
        assertEquals(0L, stream.readableBytes())
        assertNull(stream.read(0L, 5))
        assertTrue(stream.isRangeReady(0L, 0L))
    }

    @Test
    fun streamSesionPausadaMantienePiezas() {
        val contenido = dataT048(40)
        val meta = metaT048(listOf(TorrentFile("video.bin", 40L)), 20L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        sesion.pump(FuenteT048(contenido, 20, 1))
        sesion.pause()
        val stream = TorrentStream(sesion, 0)
        assertEquals(20L, stream.readableBytes())
        assertTrue(stream.read(0L, 10) != null)
    }

    @Test
    fun streamArchivoFueraDeRangoRequire() {
        val meta = metaT048(listOf(TorrentFile("a.txt", 25L)), 20L, dataT048(40))
        val sesion = TorrentSession(meta, emptySet())
        try {
            TorrentStream(sesion, 5)
            fail("archivo fuera de rango debe fallar")
        } catch (e: IllegalArgumentException) {
        }
    }
}
