package nia.engine.media

import java.io.ByteArrayOutputStream
import nia.engine.torrent.TorrentMeta
import nia.engine.torrent.TorrentSession
import nia.engine.torrent.TorrentStream

/**
 * T049 v1 (R0114, RES-0060): subtitulos INCLUIDOS en un torrent. Los
 * archivos con extension srt/vtt/ass/ssa del metainfo se leen enteros
 * con TorrentStream SOLO si el prefijo legible de la sesion los cubre
 * (readFully devuelve null en caso contrario) y alimentan un
 * SubtitleManager con el nombre de pista = ruta del archivo; la
 * decodificacion es UTF-8 (la deteccion de codificacion sigue
 * excluida, la decide quien lee). Los subs del CONTENEDOR de un medio
 * remoto (Matroska, mp4) son v2: aqui el contenedor ES el torrent.
 */
object TorrentSubtitles {

    /** Indices de los archivos de subtitulos del metainfo, en orden. */
    fun indexes(meta: TorrentMeta): List<Int> =
        meta.files.indices.filter { i ->
            val ruta = meta.files[i].path.lowercase()
            ruta.endsWith(".srt") || ruta.endsWith(".vtt") ||
                ruta.endsWith(".ass") || ruta.endsWith(".ssa")
        }

    /** Contenido completo del archivo del stream como String UTF-8, o
     * null si el prefijo legible no lo cubre; lee por maxChunk bytes. */
    fun readFully(stream: TorrentStream, maxChunk: Int = 8192): String? {
        if (stream.readableBytes() < stream.length) return null
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (offset < stream.length) {
            val chunk = stream.read(offset, maxChunk) ?: return null
            out.write(chunk.bytes)
            offset += chunk.bytes.size
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Gestor con las pistas de subtitulos YA legibles de la sesion
     * (los archivos incompletos se omiten); sin seleccion. */
    fun load(session: TorrentSession): SubtitleManager {
        val gestor = SubtitleManager()
        for (i in indexes(session.meta)) {
            val contenido = readFully(TorrentStream(session, i)) ?: continue
            gestor.add(session.meta.files[i].path, contenido)
        }
        return gestor
    }
}
