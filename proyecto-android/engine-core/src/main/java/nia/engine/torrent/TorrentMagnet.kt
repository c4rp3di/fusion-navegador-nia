package nia.engine.torrent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * T047 v3 (R0129, RES-0067, ADR-016): metadatos magnet por extension
 * (BEP-9/BEP-10) con PeerTransport inyectable y sin dependencias.
 * PeerMetadataClient hace el handshake del peer (68 bytes verificados
 * contra el infohash como en v2), envia el handshake de extension
 * (mensaje 20 con m.ut_metadata = 1) y aprende el id con el que el
 * peer habla ut_metadata, pide las piezas (msg_type 0) y ensambla el
 * diccionario info con el total_size aprendido de la primera
 * respuesta; msg_type 2, peer sin ut_metadata, EOF o IO devuelven null
 * y la conexion se cierra SIEMPRE. torrentDesdeInfo monta el torrent
 * completo (announce + info) desde los metadatos ensamblados.
 *
 * Exclusiones v3 (documentadas): extensiones BEP-10 fuera de
 * ut_metadata ignoradas y sin peticiones en paralelo (una por vez).
 */

/** Diccionario ut_metadata partido: dict bencode y bytes del bloque. */
internal class ParMetadata(val dict: Benc.BDict, val resto: ByteArray)

/**
 * Cliente de metadatos magnet (BEP-9/10): una conexion, handshake de
 * extension y piezas de ut_metadata hasta el info dict completo.
 */
class PeerMetadataClient(
    private val transport: PeerTransport,
    private val infoHash: ByteArray,
    private val peerId: ByteArray,
    private val pieceSize: Int = 16384
) {

    /** Diccionario info completo (bytes bencode) o null. */
    fun fetchMetadata(peer: TrackerPeer): ByteArray? {
        val conexion = try {
            transport.connect(peer)
        } catch (e: IOException) {
            return null
        }
        try {
            escribirHandshake(conexion.output)
            if (!leerHandshake(conexion.input)) return null
            escribirMensaje(conexion.output, 20, extHandshakePayload())
            var utId = -1
            var totalSize = -1
            val piezas = HashMap<Int, ByteArray>()
            val solicitadas = HashSet<Int>()
            while (true) {
                if (utId >= 0) {
                    val necesarias = if (totalSize < 0) -1 else (totalSize + pieceSize - 1) / pieceSize
                    if (necesarias > 0 && piezas.size == necesarias) {
                        val out = ByteArrayOutputStream()
                        for (i in 0 until necesarias) {
                            out.write(piezas[i] ?: ByteArray(0))
                        }
                        val ensamblado = out.toByteArray()
                        return if (ensamblado.size == totalSize) ensamblado else null
                    }
                    if (totalSize < 0) {
                        if (!solicitadas.contains(0)) {
                            escribirMensaje(conexion.output, utId, peticionMetadata(0))
                            solicitadas.add(0)
                        }
                    } else {
                        for (i in 0 until necesarias) {
                            if (!solicitadas.contains(i)) {
                                escribirMensaje(conexion.output, utId, peticionMetadata(i))
                                solicitadas.add(i)
                            }
                        }
                    }
                }
                val msg = leerMensaje(conexion.input) ?: return null
                if (msg.id == 20) {
                    if (utId >= 0) continue
                    val d = try {
                        Bencode.decode(msg.payload)
                    } catch (e: BencodeException) {
                        return null
                    }
                    if (d !is Benc.BDict) return null
                    val m = d.entries["m"] as? Benc.BDict ?: return null
                    val ut = m.entries["ut_metadata"] as? Benc.BInt ?: return null
                    utId = ut.value.toInt()
                } else if (utId >= 0 && msg.id == utId) {
                    val par = partirDiccionario(msg.payload) ?: continue
                    val mt = par.dict.entries["msg_type"] as? Benc.BInt ?: continue
                    val piece = par.dict.entries["piece"] as? Benc.BInt ?: continue
                    if (mt.value == 2L) return null
                    val ts = par.dict.entries["total_size"] as? Benc.BInt ?: continue
                    if (mt.value == 1L && piece.value >= 0 &&
                        solicitadas.contains(piece.value.toInt()) && ts.value >= 0) {
                        if (totalSize < 0) totalSize = ts.value.toInt()
                        if (ts.value.toInt() == totalSize) {
                            piezas[piece.value.toInt()] = par.resto
                        }
                    }
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

    /** Handshake de extension (BEP-10): m.ut_metadata = 1. */
    internal fun extHandshakePayload(): ByteArray {
        val m = Benc.BDict(LinkedHashMap())
        m.entries["ut_metadata"] = Benc.BInt(1)
        val raiz = Benc.BDict(LinkedHashMap())
        raiz.entries["m"] = m
        return Bencode.encode(raiz)
    }

    /** Peticion de la pieza (msg_type 0, BEP-9). */
    internal fun peticionMetadata(piece: Int): ByteArray {
        val raiz = Benc.BDict(LinkedHashMap())
        raiz.entries["msg_type"] = Benc.BInt(0)
        raiz.entries["piece"] = Benc.BInt(piece.toLong())
        return Bencode.encode(raiz)
    }

    /** Parte el payload ut_metadata: dict + bytes del bloque (BEP-9). */
    internal fun partirDiccionario(payload: ByteArray): ParMetadata? {
        if (payload.isEmpty() || payload[0].toInt() != 'd'.code) return null
        val pos = intArrayOf(0)
        val valor = escanear(payload, pos) ?: return null
        if (valor !is Benc.BDict) return null
        return ParMetadata(valor, payload.copyOfRange(pos[0], payload.size))
    }

    /** Escanea UN valor bencode y deja la posicion tras el valor. */
    private fun escanear(bytes: ByteArray, pos: IntArray): Benc? {
        if (pos[0] >= bytes.size) return null
        val c = bytes[pos[0]].toInt() and 0xff
        if (c == 'i'.code) {
            var fin = pos[0] + 1
            while (fin < bytes.size && bytes[fin].toInt() != 'e'.code) fin++
            if (fin >= bytes.size) return null
            val texto = String(bytes, pos[0] + 1, fin - pos[0] - 1, Charsets.UTF_8)
            val valor = texto.toLongOrNull() ?: return null
            pos[0] = fin + 1
            return Benc.BInt(valor)
        }
        if (c == 'l'.code || c == 'd'.code) {
            val esLista = c == 'l'.code
            pos[0]++
            val items = ArrayList<Benc>()
            val entries = LinkedHashMap<String, Benc>()
            while (true) {
                if (pos[0] >= bytes.size) return null
                if (bytes[pos[0]].toInt() == 'e'.code) {
                    pos[0]++
                    return if (esLista) Benc.BList(items) else Benc.BDict(entries)
                }
                if (esLista) {
                    val item = escanear(bytes, pos) ?: return null
                    items.add(item)
                } else {
                    val clave = escanear(bytes, pos) ?: return null
                    if (clave !is Benc.BStr) return null
                    val valor = escanear(bytes, pos) ?: return null
                    entries[clave.text()] = valor
                }
            }
        }
        if (c >= '0'.code && c <= '9'.code) {
            var sep = pos[0]
            while (sep < bytes.size && bytes[sep].toInt() != ':'.code) sep++
            if (sep >= bytes.size) return null
            val largo = String(bytes, pos[0], sep - pos[0], Charsets.UTF_8).toIntOrNull()
                ?: return null
            if (largo < 0 || sep + 1 + largo > bytes.size) return null
            pos[0] = sep + 1 + largo
            return Benc.BStr(bytes.copyOfRange(sep + 1, sep + 1 + largo))
        }
        return null
    }

    private fun leerHandshake(input: InputStream): Boolean {
        val h = leer(input, 68) ?: return false
        if (h[0].toInt() != 19) return false
        val pstr = String(h, 1, 19, Charsets.US_ASCII)
        if (pstr != "BitTorrent protocol") return false
        return h.copyOfRange(28, 48).contentEquals(infoHash)
    }

    private fun leerMensaje(input: InputStream): WireMessage? {
        val cabecera = leer(input, 4) ?: return null
        val largo = entero32(cabecera, 0)
        if (largo == 0) return WireMessage(null, ByteArray(0))
        if (largo > 4 * 1024 * 1024) return null
        val cuerpo = leer(input, largo) ?: return null
        return WireMessage(cuerpo[0].toInt() and 0xff, cuerpo.copyOfRange(1, cuerpo.size))
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

/** Monta el torrent minimo (announce + info) desde los metadatos magnet. */
fun torrentDesdeInfo(announce: String, infoBytes: ByteArray): TorrentMeta {
    val info = Bencode.decode(infoBytes)
    val raiz = Benc.BDict(LinkedHashMap())
    raiz.entries["announce"] = Benc.BStr(announce.toByteArray(Charsets.UTF_8))
    raiz.entries["info"] = info
    return TorrentParser.parse(Bencode.encode(raiz))
}
