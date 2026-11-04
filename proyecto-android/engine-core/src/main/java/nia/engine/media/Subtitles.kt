package nia.engine.media

/** Cue de subtitulos v1 (ADR-017): ventana en ms y texto multi-linea. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Contenido de subtitulos ilegal: bloque sin flecha valida o timestamps fuera de orden. */
class SubtitleFormatException(message: String) : Exception(message)

/**
 * Parser de subtitulos v1 (T036 v2, R0102, RES-0055, ADR-017 + T049 v1,
 * R0114, RES-0060): WebVTT, SRT y ASS/SSA puros sobre un String ya
 * decodificado. Acepta BOM UTF-8, CRLF, cabeceras y bloques
 * NOTE/STYLE/REGION de WebVTT, ids de cue e indices de SRT (lineas sin
 * flecha antes del timing) e ignora ajustes tras el timestamp (align,
 * line). Lanza SubtitleFormatException si un bloque con flecha tiene
 * timestamps mal formados o fin menor que inicio. ASS/SSA entra por la
 * seccion [Events]: Format fija el orden de los campos, Dialogue trae
 * los cues con Text obligatoriamente el ULTIMO campo (absorbe comas),
 * los centesimos caben en parseTimestamp, las etiquetas entre llaves
 * se quitan y \N es salto de linea.
 * Exclusiones v1: estilos de cue y deteccion de codificacion (la
 * decodifica quien lee el archivo; CP1252/Latin-1 mal decodificado se
 * muestra con caracteres corruptos, avisado en UI).
 */
object SubtitleParser {

    fun parse(content: String): List<SubtitleCue> {
        val text = if (content.startsWith("\uFEFF")) content.substring(1) else content
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        return when {
            normalized.startsWith("WEBVTT") -> parseWebVtt(normalized)
            normalized.startsWith("[Script Info]") -> parseAss(normalized)
            else -> parseSrt(normalized)
        }
    }

    private fun parseWebVtt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (block in content.split("\n\n")) {
            if (block.isBlank()) continue
            val lines = block.split("\n")
            val head = lines[0].trim()
            if (head.startsWith("WEBVTT") || head.startsWith("NOTE") ||
                head.startsWith("STYLE") || head.startsWith("REGION")) continue
            cues.add(parseBlock(lines))
        }
        return cues
    }

    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (block in content.split("\n\n")) {
            if (block.isBlank()) continue
            cues.add(parseBlock(block.split("\n")))
        }
        return cues
    }

    /** Seccion [Events] de ASS/SSA: Format fija el orden de los campos
     * y Dialogue trae los cues; Text debe ser el ULTIMO campo (absorbe
     * comas), los centesimos caben en parseTimestamp, las etiquetas
     * entre llaves se quitan y \N es salto de linea. */
    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var enEvents = false
        var formato: List<String> = emptyList()
        for (raw in content.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                enEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!enEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                formato = line.substring(7).split(",").map { it.trim() }
                continue
            }
            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                if (formato.size < 3) throw SubtitleFormatException("ass-format")
                val campos = line.substring(9).split(",", limit = formato.size)
                if (campos.size != formato.size) throw SubtitleFormatException("ass-dialogue")
                val iStart = formato.indexOf("Start")
                val iEnd = formato.indexOf("End")
                val iText = formato.indexOf("Text")
                if (iStart < 0 || iEnd < 0 || iText != formato.size - 1) {
                    throw SubtitleFormatException("ass-format")
                }
                val start = parseTimestamp(campos[iStart].trim())
                val end = parseTimestamp(campos[iEnd].trim())
                if (end < start) throw SubtitleFormatException("order")
                cues.add(SubtitleCue(start, end, limpiarTextoAss(campos[iText])))
            }
        }
        return cues
    }

    /** Texto de Dialogue sin etiquetas de estilo entre llaves y con \N
     * como salto de linea. */
    private fun limpiarTextoAss(raw: String): String {
        val sinLlaves = StringBuilder()
        var enLlave = false
        for (c in raw) {
            when {
                c == '{' -> enLlave = true
                c == '}' -> enLlave = false
                !enLlave -> sinLlaves.append(c)
            }
        }
        return sinLlaves.toString()
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .trim()
    }

    /** Bloque: lineas opcionales (id o indice) + timing con flecha + texto. */
    private fun parseBlock(lines: List<String>): SubtitleCue {
        var timingIndex = -1
        for (i in lines.indices) {
            if (lines[i].contains("-->")) {
                timingIndex = i
                break
            }
        }
        if (timingIndex == -1) throw SubtitleFormatException("no-timing")
        val parts = lines[timingIndex].split("-->")
        if (parts.size != 2) throw SubtitleFormatException("timing")
        val start = parseTimestamp(parts[0].trim())
        val end = parseTimestamp(parts[1].trim().split(" ").first())
        if (end < start) throw SubtitleFormatException("order")
        val body = lines.subList(timingIndex + 1, lines.size)
            .map { it.trim() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        return SubtitleCue(start, end, body.joinToString("\n"))
    }

    /** h:mm:ss.mmm | mm:ss.mmm | mm:ss,mmm; milisegundos de 1 a 3 digitos. */
    private fun parseTimestamp(raw: String): Long {
        val separated = raw.replace(',', '.')
        val dot = separated.lastIndexOf('.')
        val msText = if (dot >= 0) separated.substring(dot + 1) else ""
        val clockText = if (dot >= 0) separated.substring(0, dot) else separated
        val ms = if (msText.isEmpty()) {
            0L
        } else {
            if (msText.length > 3 || msText.any { it < '0' || it > '9' }) {
                throw SubtitleFormatException("timestamp")
            }
            msText.padEnd(3, '0').toLong()
        }
        val units = clockText.split(':')
        if (units.size > 3) throw SubtitleFormatException("timestamp")
        var seconds = 0L
        for (unit in units) {
            if (unit.isEmpty() || unit.any { it < '0' || it > '9' }) {
                throw SubtitleFormatException("timestamp")
            }
            seconds = seconds * 60 + unit.toLong()
        }
        return seconds * 1000 + ms
    }
}

/**
 * Pista de subtitulos v1 (T036 v2, R0102, RES-0055): seleccion del cue
 * activo contra la posicion del nucleo con desfase manual persistente en
 * la pista. Desfase positivo retrasa (el cue aparece mas tarde) y
 * negativo adelanta; con solapamiento gana el primer cue.
 */
class SubtitleTrack(private val cues: List<SubtitleCue>) {

    private var offsetMs: Long = 0

    fun offset(): Long = offsetMs

    /** Desfase absoluto en ms. */
    fun setOffset(ms: Long) {
        offsetMs = ms
    }

    /** Mueve el desfase (shift(500) retrasa medio segundo). */
    fun shift(deltaMs: Long) {
        offsetMs += deltaMs
    }

    /** Texto del cue activo en positionMs (null si no hay ninguno). */
    fun textAt(positionMs: Long): String? {
        val shifted = positionMs - offsetMs
        for (cue in cues) {
            if (cue.startMs <= shifted && shifted < cue.endMs) {
                return cue.text
            }
        }
        return null
    }
}

/**
 * Gestor de pistas multi-pista (T049 v1, R0114, RES-0060): pistas con
 * nombre (p. ej. idiomas o rutas de archivo) construidas con el parser,
 * seleccion de la activa y texto/desfase delegados a la pista
 * seleccionada (el desfase vive en cada pista). textAt sin seleccion es
 * null; add sobre un nombre repetido reemplaza la pista.
 */
class SubtitleManager {

    private val pistas = LinkedHashMap<String, SubtitleTrack>()
    private var seleccionada: String? = null

    /** Pista nueva (o reemplazo si el nombre ya existe). */
    fun add(nombre: String, contenido: String) {
        pistas[nombre] = SubtitleTrack(SubtitleParser.parse(contenido))
    }

    /** Nombres en orden de insercion. */
    fun nombres(): List<String> = pistas.keys.toList()

    /** Selecciona la pista (false si el nombre no existe). */
    fun select(nombre: String): Boolean {
        if (!pistas.containsKey(nombre)) return false
        seleccionada = nombre
        return true
    }

    /** Nombre de la pista seleccionada o null. */
    fun selected(): String? = seleccionada

    private fun pistaActiva(): SubtitleTrack? = seleccionada?.let { pistas[it] }

    /** Pista seleccionada o null (para el overlay y el desfase). */
    fun selectedTrack(): SubtitleTrack? = pistaActiva()

    /** Texto del cue activo de la seleccionada (null sin seleccion). */
    fun textAt(positionMs: Long): String? = pistaActiva()?.textAt(positionMs)

    /** Desfase de la seleccionada (0 sin seleccion). */
    fun offset(): Long = pistaActiva()?.offset() ?: 0L

    /** Desfase absoluto sobre la seleccionada (no-op sin seleccion). */
    fun setOffset(ms: Long) {
        pistaActiva()?.setOffset(ms)
    }

    /** Mueve el desfase de la seleccionada (no-op sin seleccion). */
    fun shift(deltaMs: Long) {
        pistaActiva()?.shift(deltaMs)
    }
}
