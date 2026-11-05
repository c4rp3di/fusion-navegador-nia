package nia.engine.torrent

import java.io.ByteArrayOutputStream

/**
 * T047 v3 (R0129, RES-0067, ADR-016): tracker UDP (BEP-15) con
 * transporte inyectable (UdpTransport) y sin dependencias: connect
 * con el magic 0x41727101980 y connection id verificado por action y
 * transaction id, y announce de 98 bytes con los codigos de evento del
 * BEP-15 (none 0, completed 1, started 2, stopped 3), contadores de 64
 * bits y peers compactos de 6 bytes IPv4+puerto en la respuesta. La
 * respuesta reutiliza TrackerAnnounceResponse del announce HTTP de v2.
 *
 * Exclusiones v3 (documentadas): sin scrape, sin re-announce
 * programado por interval (el llamador decide) y solo IPv4.
 */

/** Transporte del tracker UDP inyectable: datagrama ida y vuelta. */
interface UdpTransport {
    fun exchange(ip: String, port: Int, packet: ByteArray): ByteArray?
}

/** Cliente del tracker UDP (BEP-15): connect + announce. */
class UdpTrackerClient(private val transport: UdpTransport) {

    /** Datagrama de connect: magic, action 0 y transaction id. */
    internal fun construirConnect(tx: Int): ByteArray {
        val out = ByteArrayOutputStream(16)
        escribir64(out, 0x41727101980L)
        escribir32(out, 0)
        escribir32(out, tx)
        return out.toByteArray()
    }

    /** Respuesta de connect: connection id de 8 bytes o null. */
    internal fun parsearConnect(body: ByteArray, tx: Int): ByteArray? {
        if (body.size < 16) return null
        if (entero32(body, 0) != 0) return null
        if (entero32(body, 4) != tx) return null
        return body.copyOfRange(8, 16)
    }

    /** Datagrama de announce de 98 bytes (BEP-15). */
    internal fun construirAnnounce(connId: ByteArray, tx: Int, req: TrackerAnnounceRequest): ByteArray {
        val out = ByteArrayOutputStream(98)
        out.write(connId)
        escribir32(out, 1)
        escribir32(out, tx)
        out.write(req.infoHash)
        out.write(req.peerId)
        escribir64(out, req.downloaded)
        escribir64(out, req.left)
        escribir64(out, req.uploaded)
        escribir32(out, eventoCodigo(req.event))
        escribir32(out, 0)
        escribir32(out, 0)
        escribir32(out, -1)
        escribir16(out, req.port)
        return out.toByteArray()
    }

    /** Respuesta de announce: interval y peers compactos o null. */
    internal fun parsearAnnounce(body: ByteArray, tx: Int): TrackerAnnounceResponse? {
        if (body.size < 20) return null
        if (entero32(body, 0) != 1) return null
        if (entero32(body, 4) != tx) return null
        val interval = entero32(body, 8).toLong() and 0xffffffffL
        val resto = body.size - 20
        if (resto % 6 != 0) return null
        val peers = ArrayList<TrackerPeer>()
        for (i in 0 until resto / 6) {
            val base = 20 + i * 6
            val ip = (body[base].toInt() and 0xff).toString() + "." +
                (body[base + 1].toInt() and 0xff) + "." +
                (body[base + 2].toInt() and 0xff) + "." +
                (body[base + 3].toInt() and 0xff)
            val puerto = (body[base + 4].toInt() and 0xff) * 256 +
                (body[base + 5].toInt() and 0xff)
            peers.add(TrackerPeer(ip, puerto))
        }
        return TrackerAnnounceResponse(interval, peers)
    }

    /** Connect + announce contra el tracker UDP; fallo tipificado. */
    fun announce(ip: String, port: Int, req: TrackerAnnounceRequest): TrackerAnnounceResponse {
        val respuestaConnect = transport.exchange(ip, port, construirConnect(1))
            ?: throw TorrentFormatException("tracker UDP sin respuesta")
        val connId = parsearConnect(respuestaConnect, 1)
            ?: throw TorrentFormatException("tracker UDP sin conexion")
        val respuestaAnnounce = transport.exchange(ip, port, construirAnnounce(connId, 2, req))
            ?: throw TorrentFormatException("tracker UDP sin respuesta")
        return parsearAnnounce(respuestaAnnounce, 2)
            ?: throw TorrentFormatException("tracker UDP announce fallido")
    }

    /** Codigo de evento del BEP-15 (distinto del HTTP). */
    internal fun eventoCodigo(evento: AnnounceEvent): Int = when (evento) {
        AnnounceEvent.NONE -> 0
        AnnounceEvent.COMPLETED -> 1
        AnnounceEvent.STARTED -> 2
        AnnounceEvent.STOPPED -> 3
    }

    private fun escribir64(out: ByteArrayOutputStream, valor: Long) {
        escribir32(out, (valor shr 32).toInt())
        escribir32(out, (valor and 0xffffffffL).toInt())
    }

    private fun escribir32(out: ByteArrayOutputStream, valor: Int) {
        out.write((valor shr 24) and 0xff)
        out.write((valor shr 16) and 0xff)
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun escribir16(out: ByteArrayOutputStream, valor: Int) {
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun entero32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
