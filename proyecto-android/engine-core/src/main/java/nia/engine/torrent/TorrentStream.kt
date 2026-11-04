package nia.engine.torrent

/**
 * T048 v1 (R0110, RES-0059): streaming de UN archivo del torrent desde
 * las piezas COMPLETAS de la sesion (verificadas SHA-1, expuestas por
 * TorrentSession.pieceData desde T048): el archivo parcial reproducible
 * del titulo de T048. read sirve por rangos y se corta en el primer
 * hueco, readableBytes da el prefijo contiguo e isRangeReady consulta
 * un rango; la integracion con el reproductor (v1: prefijo a un archivo
 * en cache y MediaSource.Local) consume estos bytes. La fuente continua
 * de red (v2 torrent) se monta sobre la misma frontera.
 *
 * Exclusiones v1: sin lectura en segundo plano continua (espera la red
 * de T047 v2) y sin montar el stream como fuente directa del backend.
 */

/** Trozo servido por TorrentStream: offset del archivo y bytes. */
class StreamChunk(val offset: Long, val bytes: ByteArray) {
    val length: Int
        get() = bytes.size
}

class TorrentStream(
    private val session: TorrentSession,
    val fileIndex: Int
) {
    val file: TorrentFile
    val fileOffset: Long
    val length: Long

    init {
        require(fileIndex in session.meta.files.indices) { "archivo fuera de rango" }
        file = session.meta.files[fileIndex]
        fileOffset = session.meta.fileOffset(fileIndex)
        length = file.length
    }

    /** Fin del tramo contiguo disponible desde offset, exclusive, con
     * offset clamped a 0..length del archivo. */
    fun availableUntil(offset: Long): Long {
        var cur = offset.coerceIn(0L, length)
        while (cur < length) {
            val global = fileOffset + cur
            val p = (global / session.meta.pieceLength).toInt()
            val data = session.pieceData(p) ?: break
            val dentro = (global % session.meta.pieceLength).toInt()
            cur += data.size - dentro
        }
        return minOf(cur, length)
    }

    /** Lee hasta maxLength bytes del archivo desde offset, PARANDO en
     * el primer hueco: null si offset esta fuera del archivo o no hay
     * nada disponible en el. */
    fun read(offset: Long, maxLength: Int): StreamChunk? {
        require(maxLength > 0) { "maxLength invalido" }
        if (offset < 0L || offset >= length) return null
        val disp = availableUntil(offset)
        if (disp <= offset) return null
        val n = minOf(maxLength.toLong(), disp - offset).toInt()
        val out = ByteArray(n)
        var cur = offset
        var escritos = 0
        while (escritos < n) {
            val global = fileOffset + cur
            val p = (global / session.meta.pieceLength).toInt()
            val data = session.pieceData(p)
            if (data == null) throw IllegalStateException("pieza " + p + " no disponible")
            val dentro = (global % session.meta.pieceLength).toInt()
            val tomar = minOf(n - escritos, data.size - dentro)
            System.arraycopy(data, dentro, out, escritos, tomar)
            escritos += tomar
            cur += tomar
        }
        return StreamChunk(offset, out)
    }

    /** Esta disponible entero el rango desde offset hasta el offset
     * mas lengthBytes pedido? */
    fun isRangeReady(offset: Long, lengthBytes: Long): Boolean =
        availableUntil(offset) >= offset + lengthBytes

    /** Prefijo contiguo legible del archivo desde el byte 0. */
    fun readableBytes(): Long = availableUntil(0L)
}
