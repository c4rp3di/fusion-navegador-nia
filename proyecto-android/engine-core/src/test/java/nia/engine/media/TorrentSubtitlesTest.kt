package nia.engine.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nia.engine.torrent.PieceSource
import nia.engine.torrent.PumpResult
import nia.engine.torrent.TorrentFile
import nia.engine.torrent.TorrentMeta
import nia.engine.torrent.TorrentSession
import nia.engine.torrent.TorrentStream

/**
 * T049 v1 (R0114, RES-0060): espejo del gemelo TS validado offline.
 * Subtitulos incluidos en el torrent: indexes por extension, readFully
 * solo con el prefijo legible y load al gestor multi-pista.
 */
class TorrentSubtitlesTest {

    private fun sha1T049(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes)

    /** Meta multi-archivo del gemelo (hashes sobre el contenido). */
    private fun metaT049(
        files: List<TorrentFile>,
        pieceLength: Long,
        contenido: ByteArray
    ): TorrentMeta {
        val hashes = ArrayList<ByteArray>()
        var i = 0
        while (i < contenido.size) {
            val len = minOf(pieceLength.toInt(), contenido.size - i)
            hashes.add(sha1T049(contenido.copyOfRange(i, i + len)))
            i += len
        }
        return TorrentMeta(
            "demo", pieceLength, hashes, files,
            contenido.size.toLong(), ByteArray(20), "", emptyList()
        )
    }

    /** Fuente del gemelo: la pieza desde data o null tras hasta. */
    private class FuenteT049(
        private val data: ByteArray,
        private val pieceLength: Int,
        private val hasta: Int = Int.MAX_VALUE
    ) : PieceSource {
        override fun piece(index: Int, length: Int): ByteArray? {
            if (index >= hasta) return null
            return data.copyOfRange(index * pieceLength, index * pieceLength + length)
        }
    }

    private fun dataT049(n: Int): ByteArray = ByteArray(n) { (it + 1).toByte() }

    private fun bombear(sesion: TorrentSession, fuente: PieceSource) {
        while (sesion.pump(fuente) == PumpResult.COMPLETED) {
        }
    }

    @Test
    fun indexesDetectaExtensiones() {
        val meta = metaT049(
            listOf(
                TorrentFile("pelicula.mkv", 40L),
                TorrentFile("es.srt", 20L),
                TorrentFile("EN.VTT", 10L),
                TorrentFile("estilo.ass", 5L),
                TorrentFile("viejo.ssa", 5L),
                TorrentFile("notas.txt", 5L)
            ),
            16L, dataT049(85)
        )
        assertEquals(listOf(1, 2, 3, 4), TorrentSubtitles.indexes(meta))
    }

    @Test
    fun readFullyIncompletoNull() {
        val contenido = dataT049(40)
        val meta = metaT049(listOf(TorrentFile("subs.srt", 40L)), 16L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        sesion.pump(FuenteT049(contenido, 16, 1))
        assertNull(TorrentSubtitles.readFully(TorrentStream(sesion, 0)))
    }

    @Test
    fun readFullyCompletoLeeTodoMultiChunk() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"
        val contenido = srt.toByteArray(Charsets.UTF_8)
        val meta = metaT049(listOf(TorrentFile("subs.srt", contenido.size.toLong())), 16L, contenido)
        val sesion = TorrentSession(meta, emptySet())
        bombear(sesion, FuenteT049(contenido, 16))
        assertEquals(srt, TorrentSubtitles.readFully(TorrentStream(sesion, 0), 7))
    }

    @Test
    fun loadOmiteIncompletos() {
        val video = dataT049(16)
        val a = "0:01 --> 0:02\nA\n".toByteArray(Charsets.UTF_8)
        val b = "0:05 --> 0:06\nBBBBB\n".toByteArray(Charsets.UTF_8)
        val contenido = video + a + b
        val meta = metaT049(
            listOf(
                TorrentFile("video.bin", 16L),
                TorrentFile("a.srt", 16L),
                TorrentFile("b.srt", 20L)
            ),
            16L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        sesion.pump(FuenteT049(contenido, 16, 2))
        sesion.pump(FuenteT049(contenido, 16, 2))
        val gestor = TorrentSubtitles.load(sesion)
        assertEquals(listOf("a.srt"), gestor.nombres())
        assertTrue(gestor.select("a.srt"))
        assertEquals("A", gestor.textAt(1500L))
    }

    @Test
    fun loadMultiPistaConSeleccion() {
        val video = dataT049(10)
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray(Charsets.UTF_8)
        val ass = ("[Script Info]\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hola\n").toByteArray(Charsets.UTF_8)
        val contenido = video + srt + ass
        val meta = metaT049(
            listOf(
                TorrentFile("video.bin", video.size.toLong()),
                TorrentFile("en.srt", srt.size.toLong()),
                TorrentFile("es.ass", ass.size.toLong())
            ),
            16L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        bombear(sesion, FuenteT049(contenido, 16))
        val gestor = TorrentSubtitles.load(sesion)
        assertEquals(listOf("en.srt", "es.ass"), gestor.nombres())
        assertTrue(gestor.select("es.ass"))
        assertEquals("Hola", gestor.textAt(1500L))
        assertTrue(gestor.select("en.srt"))
        assertEquals("Hello", gestor.textAt(1500L))
        assertFalse(gestor.select("no.existe"))
        assertEquals("en.srt", gestor.selected())
    }

    @Test
    fun loadSinSubs() {
        val contenido = dataT049(50)
        val meta = metaT049(
            listOf(TorrentFile("video.bin", 40L), TorrentFile("notas.txt", 10L)),
            16L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        bombear(sesion, FuenteT049(contenido, 16))
        val gestor = TorrentSubtitles.load(sesion)
        assertTrue(gestor.nombres().isEmpty())
        assertNull(gestor.selected())
        assertNull(gestor.textAt(1000L))
    }

    @Test
    fun loadLeeDesdeOffsetCompartido() {
        val video = dataT049(30)
        val srt = "0:01 --> 0:02\nHi\n".toByteArray(Charsets.UTF_8)
        val contenido = video + srt
        val meta = metaT049(
            listOf(TorrentFile("video.bin", 30L), TorrentFile("subs.srt", srt.size.toLong())),
            16L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        bombear(sesion, FuenteT049(contenido, 16))
        val gestor = TorrentSubtitles.load(sesion)
        assertEquals(listOf("subs.srt"), gestor.nombres())
        assertTrue(gestor.select("subs.srt"))
        assertEquals("Hi", gestor.textAt(1500L))
    }

    @Test
    fun loadArchivoVacio() {
        val contenido = dataT049(16)
        val meta = metaT049(
            listOf(TorrentFile("vacio.srt", 0L), TorrentFile("x.bin", 16L)),
            16L, contenido
        )
        val sesion = TorrentSession(meta, emptySet())
        bombear(sesion, FuenteT049(contenido, 16))
        val gestor = TorrentSubtitles.load(sesion)
        assertEquals(listOf("vacio.srt"), gestor.nombres())
        assertTrue(gestor.select("vacio.srt"))
        assertNull(gestor.textAt(100L))
    }
}
