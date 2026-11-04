package nia.engine.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * T036 v2 (R0102, RES-0055): espejo del gemelo JS validado offline.
 * Parser WebVTT/SRT puro y pista con desfase, sin Android.
 */
class SubtitlesTest {

    @Test
    fun webvttBasico() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHola\n\n00:00:05.000 --> 00:00:06.500\nAdios\n")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Hola", cues[0].text)
        assertEquals(5000L, cues[1].startMs)
        assertEquals(6500L, cues[1].endMs)
        assertEquals("Adios", cues[1].text)
        val track = SubtitleTrack(cues)
        assertNull(track.textAt(0L))
        assertEquals("Hola", track.textAt(1000L))
        assertEquals("Hola", track.textAt(3999L))
        assertNull(track.textAt(4000L))
        assertEquals("Adios", track.textAt(6000L))
        assertNull(track.textAt(6500L))
    }

    @Test
    fun webvttConIdsDeCue() {
        val cues = SubtitleParser.parse("WEBVTT\n\nuno\n00:00:01.000 --> 00:00:02.000\nA\n\ndos\n00:00:03.000 --> 00:00:04.000\nB\n")
        assertEquals(2, cues.size)
        assertEquals("A", cues[0].text)
        assertEquals("B", cues[1].text)
        assertEquals(3000L, cues[1].startMs)
    }

    @Test
    fun webvttCabeceraConMetadatos() {
        val cues = SubtitleParser.parse("WEBVTT - demo\nX-Ttl: 1\n\n00:00:01.000 --> 00:00:02.000\nA\n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun bomUtf8() {
        val cues = SubtitleParser.parse("\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun crlf() {
        val cues = SubtitleParser.parse("WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nA\r\n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun timestampsCortos() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:05.500 --> 01:02.000\nA\n")
        assertEquals(5500L, cues[0].startMs)
        assertEquals(62000L, cues[0].endMs)
    }

    @Test
    fun ajustesDeCueIgnorados() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start line:0\nA\n")
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].endMs)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun bloqueNoteSaltado() {
        val cues = SubtitleParser.parse("WEBVTT\n\nNOTE un comentario\n\n00:00:01.000 --> 00:00:02.000\nA\n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun textoMultilinea() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nlinea uno\nlinea dos\n")
        assertEquals("linea uno\nlinea dos", cues[0].text)
    }

    @Test
    fun srtBasico() {
        val cues = SubtitleParser.parse("1\n00:00:01,000 --> 00:00:04,000\nHola\n\n2\n00:00:05,000 --> 00:00:06,500\nAdios\n")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals("Hola", cues[0].text)
        assertEquals(6500L, cues[1].endMs)
    }

    @Test
    fun srtMultilinea() {
        val cues = SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\nuna\ndos\n")
        assertEquals("una\ndos", cues[0].text)
    }

    @Test
    fun solapamientoGanaPrimero() {
        val track = SubtitleTrack(
            listOf(SubtitleCue(0L, 2000L, "A"), SubtitleCue(1000L, 3000L, "B"))
        )
        assertEquals("A", track.textAt(1500L))
        assertEquals("B", track.textAt(2500L))
    }

    @Test
    fun ventanaVaciaNuncaActiva() {
        val track = SubtitleTrack(listOf(SubtitleCue(1000L, 1000L, "X")))
        assertNull(track.textAt(999L))
        assertNull(track.textAt(1000L))
        assertNull(track.textAt(1001L))
    }

    @Test
    fun finMenorQueInicioLanza() {
        assertFailsWith<SubtitleFormatException> { SubtitleParser.parse("00:00:05.000 --> 00:00:01.000\nA\n") }
    }

    @Test
    fun timestampConLetrasLanza() {
        assertFailsWith<SubtitleFormatException> { SubtitleParser.parse("00:00:0a.000 --> 00:00:02.000\nA\n") }
    }

    @Test
    fun flechaSinFinLanza() {
        assertFailsWith<SubtitleFormatException> { SubtitleParser.parse("00:00:01.000 -->\nA\n") }
    }

    @Test
    fun contenidoVacioListaVacia() {
        assertEquals(0, SubtitleParser.parse("").size)
        assertEquals(0, SubtitleParser.parse("\n\n\n").size)
    }

    @Test
    fun soloCabeceraWebvtt() {
        assertEquals(0, SubtitleParser.parse("WEBVTT\n").size)
        assertEquals(0, SubtitleParser.parse("WEBVTT").size)
    }

    @Test
    fun srtSinIndice() {
        val cues = SubtitleParser.parse("00:00:01,000 --> 00:00:02.000\nA\n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
        assertEquals(2000L, cues[0].endMs)
    }

    @Test
    fun horasConMilisegundos() {
        val cues = SubtitleParser.parse("WEBVTT\n\n01:02:03.500 --> 01:02:04.000\nA\n")
        assertEquals(3723500L, cues[0].startMs)
        assertEquals(3724000L, cues[0].endMs)
    }

    @Test
    fun espaciosAlrededorDeFlecha() {
        val cues = SubtitleParser.parse("WEBVTT\n\n  00:00:01.000 --> 00:00:02.000  \nA\n")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2000L, cues[0].endMs)
    }

    @Test
    fun offsetPositivoRetrasa() {
        val track = SubtitleTrack(listOf(SubtitleCue(1000L, 2000L, "A")))
        track.setOffset(500L)
        assertNull(track.textAt(1200L))
        assertEquals("A", track.textAt(1500L))
        assertNull(track.textAt(2500L))
    }

    @Test
    fun offsetNegativoAdelanta() {
        val track = SubtitleTrack(listOf(SubtitleCue(1000L, 2000L, "A")))
        track.setOffset(-1000L)
        assertEquals("A", track.textAt(0L))
        assertNull(track.textAt(2500L))
    }

    @Test
    fun setOffsetAbsoluto() {
        val track = SubtitleTrack(listOf(SubtitleCue(0L, 1000L, "A")))
        track.setOffset(1000L)
        assertEquals(1000L, track.offset())
    }

    @Test
    fun shiftAcumula() {
        val track = SubtitleTrack(listOf(SubtitleCue(0L, 1000L, "A")))
        track.shift(500L)
        track.shift(250L)
        assertEquals(750L, track.offset())
    }

    @Test
    fun offsetCeroPorDefecto() {
        val track = SubtitleTrack(listOf(SubtitleCue(0L, 1000L, "A")))
        assertEquals(0L, track.offset())
        assertEquals("A", track.textAt(500L))
    }

    @Test
    fun msDeUnoYDosDigitos() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:00:01.5 --> 00:00:02.05\nA\n")
        assertEquals(1500L, cues[0].startMs)
        assertEquals(2050L, cues[0].endMs)
    }

    @Test
    fun srtBloqueSinFlechaLanza() {
        assertFailsWith<SubtitleFormatException> { SubtitleParser.parse("1\n2\n") }
    }

    @Test
    fun pistaSinCuesNulo() {
        val track = SubtitleTrack(emptyList())
        assertNull(track.textAt(5000L))
    }

    @Test
    fun lineasVaciasDelCueRecortadas() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nA\n \n")
        assertEquals(1, cues.size)
        assertEquals("A", cues[0].text)
    }

    @Test
    fun dobleFlechaLanza() {
        assertFailsWith<SubtitleFormatException> { SubtitleParser.parse("00:00:01.000 --> 00:00:02.000 --> 00:00:03.000\nA\n") }
    }
}
