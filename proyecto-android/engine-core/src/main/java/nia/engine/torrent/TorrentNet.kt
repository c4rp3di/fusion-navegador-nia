package nia.engine.torrent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * T047 v2 (R0126, RES-0066, ADR-016): la red del torrent sobre la
 * frontera PieceSource de v1, sin dependencias y con transporte
 * inyectable (como FetchTransport y MediaBackend): announce HTTP al
 * tracker (BEP-3) y protocolo wire de peers (BEP-3). TrackerClient
 * construye el announce (info_hash y peer_id percent-codificados por
 * byte, compact=1 y evento started/stopped/completed) y decodifica la
 * respuesta bencode (failure reason, interval con 1800 por defecto y
 * peers compactos de 6 bytes IPv4+puerto o en lista de diccionarios).
 * PeerWireClient habla el wire tras PeerTransport: handshake verificado
 * (19 + BitTorrent protocol + 8 reservados + infohash + peer id),
 * interested, bitfield validado contra el numero de piezas, have y
 * keep-alive ignorados, choke/unchoke y request/piece por bloques de
 * 16 KiB que se ensamblan en la pieza completa; IO o protocolo roto
 * devuelven null. PeerPoolSource implementa PieceSource recorriendo
 * los peers del announce hasta servir cada pieza y alimenta el pump
 * de TorrentSession: la verificacion SHA-1 no cambia.
 *
 * Exclusiones v2 (documentadas): sin DHT (BEP-5, v3), sin tracker UDP
 * (BEP-15, v3), sin metadatos magnet por extension (BEP-9/10, v3),
 * sin PEX ni cifrado y solo peers IPv4 del announce.
 */

/** Peer del announce: IPv4 en texto y puerto. */
data class TrackerPeer(val ip: String, val port: Int)

/** Transporte del tracker inyectable: GET HTTP que devuelve el cuerpo. */
interface TrackerTransport {
    fun get(url: String): ByteArray
}

/** Evento del announce; NONE no viaja en la URL. */
enum class AnnounceEvent { NONE, STARTED, STOPPED, COMPLETED }

/** Peticion de announce (BEP-3). */
class TrackerAnnounceRequest(
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val port: Int,
    val uploaded: Long,
    val downloaded: Long,
    val left: Long,
    val event: AnnounceEvent = AnnounceEvent.NONE
)

/** Respuesta del announce: intervalo de re-announce y peers. */
class TrackerAnnounceResponse(
    val interval: Long,
    val peers: List<TrackerPeer>
)

/** Cliente del announce HTTP de un tracker (BEP-3). */
class TrackerClient(private val transport: TrackerTransport) {

    fun announce(trackerUrl: String, req: TrackerAnnounceRequest): TrackerAnnounceResponse =
        parsear(transport.get(construirUrl(trackerUrl, req)))

    /** URL del announce con info_hash y peer_id percent-codificados. */
    internal fun construirUrl(trackerUrl: String, req: TrackerAnnounceRequest): String {
        val sep = if (trackerUrl.contains('?')) "&" else "?"
        val out = StringBuilder()
        out.append(trackerUrl).append(sep)
        out.append("info_hash=").append(codificar(req.infoHash))
        out.append("&peer_id=").append(codificar(req.peerId))
        out.append("&port=").append(req.port)
        out.append("&uploaded=").append(req.uploaded)
        out.append("&downloaded=").append(req.downloaded)
        out.append("&left=").append(req.left)
        out.append("&compact=1")
        if (req.event != AnnounceEvent.NONE) {
            out.append("&event=").append(nombreEvento(req.event))
        }
        return out.toString()
    }

    /** Decodifica la respuesta bencode del tracker. */
    internal fun parsear(body: ByteArray): TrackerAnnounceResponse {
        val raiz = Bencode.decode(body)
        if (raiz !is Benc.BDict) {
            throw TorrentFormatException("respuesta del tracker no es diccionario")
        }
        (raiz.entries["failure reason"] as? Benc.BStr)?.let {
            throw TorrentFormatException("tracker: " + it.text())
        }
        val interval = (raiz.entries["interval"] as? Benc.BInt)?.value ?: 1800L
        val raw = raiz.entries["peers"]
            ?: return TrackerAnnounceResponse(interval, emptyList())
        val peers = when (raw) {
            is Benc.BStr -> compactos(raw.bytes)
            is Benc.BList -> raw.items.map { peer ->
                val d = peer as? Benc.BDict
                    ?: throw TorrentFormatException("peer sin diccionario")
                val ip = (d.entries["ip"] as? Benc.BStr)?.text()
                    ?: throw TorrentFormatException("peer sin ip")
                val puerto = (d.entries["port"] as? Benc.BInt)?.value
                    ?: throw TorrentFormatException("peer sin port")
                TrackerPeer(ip, puerto.toInt())
            }
            else -> throw TorrentFormatException("peers invalidos")
        }
        return TrackerAnnounceResponse(interval, peers)
    }

    private fun compactos(raw: ByteArray): List<TrackerPeer> {
        if (raw.size % 6 != 0) {
            throw TorrentFormatException("peers compactos invalidos")
        }
        return (0 until raw.size / 6).map {
            val ip = (raw[it * 6].toInt() and 0xff).toString() + "." +
                (raw[it * 6 + 1].toInt() and 0xff) + "." +
                (raw[it * 6 + 2].toInt() and 0xff) + "." +
                (raw[it * 6 + 3].toInt() and 0xff)
            val puerto = (raw[it * 6 + 4].toInt() and 0xff) * 256 +
                (raw[it * 6 + 5].toInt() and 0xff)
            TrackerPeer(ip, puerto)
        }
    }

    private fun nombreEvento(evento: AnnounceEvent): String = when (evento) {
        AnnounceEvent.STARTED -> "started"
        AnnounceEvent.STOPPED -> "stopped"
        AnnounceEvent.COMPLETED -> "completed"
        AnnounceEvent.NONE -> ""
    }

    /** Percent-codifica byte a byte; solo A-Z a-z 0-9 . - _ ~ van limpios. */
    private fun codificar(bytes: ByteArray): String {
        val out = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xff
            val c = v.toChar()
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '.' || c == '-' || c == '_' || c == '~') {
                out.append(c)
            } else {
                val h = v.toString(16)
                out.append('%').append(if (h.length < 2) "0" + h else h)
            }
        }
        return out.toString()
    }
}

/** Conexion a un peer inyectable (tests y demos con streams enlatados). */
interface PeerConnection {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** Transporte de peers inyectable: la frontera de sockets de v2. */
interface PeerTransport {
    fun connect(peer: TrackerPeer): PeerConnection
}

/** Mensaje del wire; id null es keep-alive. */
internal class WireMessage(val id: Int?, val payload: ByteArray)

/**
 * Cliente del protocolo wire de peers (BEP-3): una conexion por pieza.
 * Handshake verificado, interested, bitfield validado contra el numero
 * de piezas (longitud invalida corta la conexion; el contenido se
 * ignora y el have puede llegar despues), choke/unchoke con las
 * peticiones ya enviadas aceptando los bloques que lleguen, keep-alive
 * y have ignorados, request/piece por bloques y ensamblado; IO o
 * protocolo roto devuelven null y la conexion se cierra SIEMPRE.
 */
class PeerWireClient(
    private val transport: PeerTransport,
    private val infoHash: ByteArray,
    private val peerId: ByteArray,
    private val pieceCount: Int,
    private val blockLength: Int = 16384
) {

    /** Pieza completa o null (handshake rechazado, peer sin datos, IO). */
    fun fetchPiece(peer: TrackerPeer, pieceIndex: Int, pieceLength: Int): ByteArray? {
        val conexion = try {
            transport.connect(peer)
        } catch (e: IOException) {
            return null
        }
        try {
            escribirHandshake(conexion.output)
            if (!leerHandshake(conexion.input)) return null
            escribirMensaje(conexion.output, 2, ByteArray(0))
            var desbloqueado = false
            var solicitado = false
            val bloques = HashMap<Int, ByteArray>()
            val pendientes = bloquesDe(pieceLength)
            while (true) {
                if (desbloqueado && !solicitado) {
                    for ((comienzo, largo) in pendientes) {
                        escribirSolicitud(conexion.output, pieceIndex, comienzo, largo)
                    }
                    solicitado = true
                }
                if (bloques.size == pendientes.size) {
                    return ensamblar(pendientes, bloques, pieceLength)
                }
                val msg = leerMensaje(conexion.input) ?: return null
                when (msg.id) {
                    0 -> desbloqueado = false
                    1 -> desbloqueado = true
                    5 -> if (msg.payload.size != (pieceCount + 7) / 8) return null
                    7 -> agregarBloque(msg, pieceIndex, pendientes, bloques)
                    else -> {}
                }
            }
        } catch (e: IOException) {
            return null
        } finally {
            try {
                conexion.close()
            } catch (e: IOException) {
            }
        }
    }

    private fun bloquesDe(pieceLength: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var comienzo = 0
        while (comienzo < pieceLength) {
            val largo = minOf(blockLength, pieceLength - comienzo)
            out.add(Pair(comienzo, largo))
            comienzo += largo
        }
        return out
    }

    private fun ensamblar(
        pendientes: List<Pair<Int, Int>>,
        bloques: HashMap<Int, ByteArray>,
        pieceLength: Int
    ): ByteArray {
        val out = ByteArray(pieceLength)
        for ((comienzo, largo) in pendientes) {
            val data = bloques[comienzo] ?: ByteArray(0)
            System.arraycopy(data, 0, out, comienzo, largo)
        }
        return out
    }

    private fun agregarBloque(
        msg: WireMessage,
        pieceIndex: Int,
        pendientes: List<Pair<Int, Int>>,
        bloques: HashMap<Int, ByteArray>
    ) {
        if (msg.payload.size < 8) return
        val index = entero32(msg.payload, 0)
        val comienzo = entero32(msg.payload, 4)
        if (index != pieceIndex) return
        for ((c, largo) in pendientes) {
            if (c == comienzo && largo + 8 == msg.payload.size) {
                bloques[comienzo] = msg.payload.copyOfRange(8, msg.payload.size)
                return
            }
        }
    }

    private fun leerMensaje(input: InputStream): WireMessage? {
        val cabecera = leer(input, 4) ?: return null
        val largo = entero32(cabecera, 0)
        if (largo == 0) return WireMessage(null, ByteArray(0))
        if (largo < 0 || largo > 4 * 1024 * 1024) return null
        val cuerpo = leer(input, largo) ?: return null
        return WireMessage(cuerpo[0].toInt() and 0xff, cuerpo.copyOfRange(1, cuerpo.size))
    }

    private fun leerHandshake(input: InputStream): Boolean {
        val h = leer(input, 68) ?: return false
        if (h[0] != 19) return false
        val pstr = String(h, 1, 19, Charsets.US_ASCII)
        if (pstr != "BitTorrent protocol") return false
        return h.copyOfRange(28, 48).contentEquals(infoHash)
    }

    private fun leer(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var leido = 0
        while (leido < n) {
            val k = input.read(out, leido, n - leido)
            if (k < 0) return null
            leido += k
        }
        return out
    }

    private fun escribirHandshake(output: OutputStream) {
        output.write(19)
        output.write("BitTorrent protocol".toByteArray(Charsets.US_ASCII))
        output.write(ByteArray(8))
        output.write(infoHash)
        output.write(peerId)
        output.flush()
    }

    private fun escribirMensaje(output: OutputStream, id: Int, payload: ByteArray) {
        escribir32(output, 1 + payload.size)
        output.write(id)
        output.write(payload)
        output.flush()
    }

    private fun escribirSolicitud(output: OutputStream, index: Int, begin: Int, length: Int) {
        val payload = ByteArrayOutputStream(12)
        escribir32(payload, index)
        escribir32(payload, begin)
        escribir32(payload, length)
        escribir32(output, 13)
        output.write(6)
        output.write(payload.toByteArray())
        output.flush()
    }

    private fun escribir32(out: OutputStream, valor: Int) {
        out.write((valor shr 24) and 0xff)
        out.write((valor shr 16) and 0xff)
        out.write((valor shr 8) and 0xff)
        out.write(valor and 0xff)
    }

    private fun entero32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}

/**
 * Fuente de piezas del torrent (PieceSource) sobre los peers del
 * announce: recorre los peers en orden y devuelve la primera pieza
 * completa; un peer que falla pasa al siguiente y sin peers o sin
 * exito devuelve null (la sesion queda UNAVAILABLE).
 */
class PeerPoolSource(
    private val peers: List<TrackerPeer>,
    transport: PeerTransport,
    infoHash: ByteArray,
    peerId: ByteArray,
    pieceCount: Int,
    blockLength: Int = 16384
) : PieceSource {
    private val cliente = PeerWireClient(transport, infoHash, peerId, pieceCount, blockLength)

    override fun piece(index: Int, length: Int): ByteArray? {
        for (peer in peers) {
            val data = cliente.fetchPiece(peer, index, length)
            if (data != null && data.size == length) return data
        }
        return null
    }
}
