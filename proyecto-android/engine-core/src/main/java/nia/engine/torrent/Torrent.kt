package nia.engine.torrent

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * T047 v1 (R0108, RES-0058, ADR-016): modulo torrent de nucleo puro JVM
 * con cliente PROPIO tras la auditoria de ADR-016 (ninguna candidata
 * cumple: las candidatas con el protocolo completo traen binarios
 * nativos SWIG por ABI y libtorrent4j exige Android API >= 28 contra
 * el minSdk 26 del proyecto, y las Java puro no traen DHT ni
 * metadatos magnet y su mantenimiento es esporadico; clausula de
 * respaldo decidida por el usuario).
 *
 * Frontera (ADR-016): TODO el dominio vive DETRAS de PieceSource, la
 * fuente de piezas inyectable (como FetchTransport y MediaBackend).
 * v1 modela el metainfo .torrent (BEP-3), el magnet (urn:btih), la
 * sesion con seleccion de archivos, el progreso por piezas con
 * verificacion SHA-1, la pausa, la reanudacion, la cancelacion, el
 * borrado y el gestor de sesiones.
 *
 * Exclusiones v1: sin red (trackers, DHT y peers van en v2 sobre la
 * misma frontera), sin empaquetado de modulo instalable (espera
 * T029/T030) y sin streaming de piezas contiguas (T048).
 */
class TorrentFormatException(message: String) : RuntimeException(message)

/** Archivo dentro del torrent: ruta (unida con barra) y tamano en bytes. */
data class TorrentFile(val path: String, val length: Long)

/**
 * Metainfo .torrent (BEP-3): nombre, tamano de pieza, hashes SHA-1 de
 * cada pieza, archivos en orden con offsets acumulados, tamano total,
 * infohash (SHA-1 de la codificacion canonica del diccionario info) y
 * trackers (announce y announce-list aplanado).
 */
class TorrentMeta(
    val name: String,
    val pieceLength: Long,
    val pieceHashes: List<ByteArray>,
    val files: List<TorrentFile>,
    val totalLength: Long,
    val infoHash: ByteArray,
    val infoHashHex: String,
    val trackers: List<String>
) {
    fun pieceCount(): Int = pieceHashes.size

    /** Primer byte del archivo index (acumulado en orden de la lista). */
    fun fileOffset(index: Int): Long = files.subList(0, index).sumOf { it.length }

    /** Piezas que toca el archivo index (un archivo vacio no toca ninguna). */
    fun piecesForFile(index: Int): IntRange {
        if (files[index].length == 0L) return IntRange.EMPTY
        val inicio = (fileOffset(index) / pieceLength).toInt()
        val fin = ((fileOffset(index) + files[index].length - 1) / pieceLength).toInt()
        return inicio..fin
    }
}

/** Parser de archivos .torrent (bencode del metainfo, BEP-3). */
object TorrentParser {

    fun parse(bytes: ByteArray): TorrentMeta {
        val raiz = Bencode.decode(bytes)
        if (raiz !is Benc.BDict) throw TorrentFormatException("raiz no es diccionario")
        val info = raiz.entries["info"] as? Benc.BDict
            ?: throw TorrentFormatException("sin info")
        val name = (info.entries["name"] as? Benc.BStr)?.text()
            ?: throw TorrentFormatException("sin name")
        val pieceLength = (info.entries["piece length"] as? Benc.BInt)?.value
            ?: throw TorrentFormatException("sin piece length")
        if (pieceLength <= 0L) throw TorrentFormatException("piece length invalida")
        val piecesRaw = (info.entries["pieces"] as? Benc.BStr)?.bytes
            ?: throw TorrentFormatException("sin pieces")
        if (piecesRaw.size % 20 != 0) {
            throw TorrentFormatException("pieces no es multiplo de 20")
        }
        val pieceHashes = (0 until piecesRaw.size / 20).map {
            piecesRaw.copyOfRange(it * 20, it * 20 + 20)
        }
        val archivos = archivos(info, name)
        val totalLength = archivos.sumOf { it.length }
        val infoHash = sha1(Bencode.encode(info))
        return TorrentMeta(
            name,
            pieceLength,
            pieceHashes,
            archivos,
            totalLength,
            infoHash,
            hex(infoHash),
            trackers(raiz)
        )
    }

    private fun archivos(info: Benc.BDict, name: String): List<TorrentFile> {
        val rawFiles = info.entries["files"]
        return if (rawFiles == null) {
            val length = (info.entries["length"] as? Benc.BInt)?.value
                ?: throw TorrentFormatException("sin length")
            listOf(TorrentFile(name, length))
        } else {
            val lista = rawFiles as? Benc.BList
                ?: throw TorrentFormatException("files invalido")
            lista.items.map { item ->
                val f = item as? Benc.BDict
                    ?: throw TorrentFormatException("archivo sin diccionario")
                val length = (f.entries["length"] as? Benc.BInt)?.value
                    ?: throw TorrentFormatException("archivo sin length")
                val path = (f.entries["path"] as? Benc.BList
                    ?: throw TorrentFormatException("archivo sin path")).items
                    .mapNotNull { (it as? Benc.BStr)?.text() }
                if (path.isEmpty()) throw TorrentFormatException("path vacio")
                TorrentFile(path.joinToString("/"), length)
            }
        }
    }

    private fun trackers(raiz: Benc.BDict): List<String> {
        val out = ArrayList<String>()
        (raiz.entries["announce"] as? Benc.BStr)?.let { out.add(it.text()) }
        raiz.entries["announce-list"]?.let { raw ->
            val tiers = raw as? Benc.BList
                ?: throw TorrentFormatException("announce-list invalido")
            for (tier in tiers.items) {
                val t = tier as? Benc.BList
                    ?: throw TorrentFormatException("announce-list invalido")
                for (item in t.items) {
                    val s = item as? Benc.BStr
                        ?: throw TorrentFormatException("announce-list invalido")
                    out.add(s.text())
                }
            }
        }
        return out
    }
}

/** Metadatos de un enlace magnet: btih en hex, nombre y trackers. */
data class MagnetInfo(val infoHashHex: String, val name: String?, val trackers: List<String>)

/** Parser de enlaces magnet con xt urn:btih (hex 40 o base32 32), dn y tr. */
object MagnetParser {

    fun parse(uri: String): MagnetInfo {
        if (!uri.startsWith("magnet:?")) throw TorrentFormatException("no es magnet")
        var infoHashHex: String? = null
        var name: String? = null
        val trackers = ArrayList<String>()
        for (par in uri.substring("magnet:?".length).split('&')) {
            if (par.isEmpty()) continue
            val sep = par.indexOf('=')
            if (sep < 0) continue
            val clave = par.substring(0, sep)
            val valor = par.substring(sep + 1)
            when (clave) {
                "xt" -> infoHashHex = btih(valor)
                "dn" -> name = valor
                "tr" -> trackers.add(valor)
            }
        }
        return MagnetInfo(
            infoHashHex ?: throw TorrentFormatException("magnet sin xt"),
            name,
            trackers
        )
    }

    private fun btih(valor: String): String {
        if (!valor.startsWith("urn:btih:")) {
            throw TorrentFormatException("xt no es urn:btih")
        }
        val hash = valor.substring("urn:btih:".length)
        if (hash.length == 40) {
            val minusculas = hash.lowercase()
            if (!minusculas.all { it in "0123456789abcdef" }) {
                throw TorrentFormatException("btih hex invalida")
            }
            return minusculas
        }
        if (hash.length == 32) return hex(base32Decode(hash))
        throw TorrentFormatException("btih de longitud invalida")
    }

    private fun base32Decode(s: String): ByteArray {
        val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = ByteArrayOutputStream()
        var bits = 0
        var valor = 0
        for (c in s) {
            val i = alfabeto.indexOf(c)
            if (i < 0) throw TorrentFormatException("base32 invalida")
            valor = (valor shl 5) or i
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((valor shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Fuente de piezas inyectable (ADR-016): la frontera reemplazable del
 * protocolo. v1 la aporta un test o la demo; v2 el cliente de red.
 */
interface PieceSource {
    /** Datos de la pieza index (length bytes) o null si aun no llega. */
    fun piece(index: Int, length: Int): ByteArray?
}

/** Estado de la sesion torrent. */
enum class TorrentState { QUEUED, RUNNING, PAUSED, DONE, CANCELLED }

/** Resultado de pump: que paso con la pieza pedida. */
enum class PumpResult { COMPLETED, REJECTED, UNAVAILABLE, PAUSED, DONE, CANCELLED }

/** Progreso de la sesion: piezas completas de las necesarias y bytes. */
data class TorrentProgress(
    val completedPieces: Int,
    val totalPieces: Int,
    val downloadedBytes: Long
)

/**
 * Sesion de descarga de UN torrent con seleccion de archivos: tira de
 * la fuente pieza a pieza (la primera necesaria incompleta), verifica
 * cada pieza contra su hash SHA-1 y solo avanza con piezas validas.
 * pump tira tambien en QUEUED (arranque perezoso).
 */
class TorrentSession(
    val meta: TorrentMeta,
    selectedFiles: Set<Int>
) {
    var state: TorrentState = TorrentState.QUEUED
        private set
    private val selected: Set<Int>
    private val completed = mutableSetOf<Int>()
    private var descargado = 0L
    val neededPieces: List<Int>

    init {
        require(selectedFiles.all { it in meta.files.indices }) {
            "archivo fuera de rango"
        }
        selected = if (selectedFiles.isEmpty()) {
            meta.files.indices.toSet()
        } else {
            selectedFiles
        }
        val necesarios = sortedSetOf<Int>()
        for (f in selected) {
            for (p in meta.piecesForFile(f)) necesarios.add(p)
        }
        neededPieces = necesarios.toList()
    }

    fun start() {
        if (state == TorrentState.QUEUED) state = TorrentState.RUNNING
    }

    fun pause() {
        if (state == TorrentState.QUEUED || state == TorrentState.RUNNING) {
            state = TorrentState.PAUSED
        }
    }

    fun resume() {
        if (state == TorrentState.PAUSED) state = TorrentState.RUNNING
    }

    fun cancel() {
        if (state != TorrentState.DONE && state != TorrentState.CANCELLED) {
            state = TorrentState.CANCELLED
        }
    }

    fun isFileNeeded(index: Int): Boolean = index in selected

    fun progress(): TorrentProgress =
        TorrentProgress(completed.size, neededPieces.size, descargado)

    fun pump(source: PieceSource): PumpResult {
        when (state) {
            TorrentState.PAUSED -> return PumpResult.PAUSED
            TorrentState.CANCELLED -> return PumpResult.CANCELLED
            TorrentState.DONE -> return PumpResult.DONE
            else -> {}
        }
        if (completed.size == neededPieces.size) {
            state = TorrentState.DONE
            return PumpResult.DONE
        }
        val index = neededPieces.first { it !in completed }
        val largo = minOf(meta.pieceLength, meta.totalLength - index * meta.pieceLength).toInt()
        val data = source.piece(index, largo) ?: return PumpResult.UNAVAILABLE
        if (data.size != largo) return PumpResult.REJECTED
        if (!MessageDigest.isEqual(sha1(data), meta.pieceHashes[index])) {
            return PumpResult.REJECTED
        }
        completed.add(index)
        descargado += largo
        if (completed.size == neededPieces.size) state = TorrentState.DONE
        return PumpResult.COMPLETED
    }
}

/**
 * Gestor de sesiones del modulo (borrado incluido) con el infohash hex
 * como clave: la instalacion como modulo (T029/T030) y el cliente de
 * red de v2 se montan sobre esta frontera.
 */
class TorrentEngine {
    private val sessions = LinkedHashMap<String, TorrentSession>()

    fun add(meta: TorrentMeta, selectedFiles: Set<Int>): TorrentSession {
        val session = TorrentSession(meta, selectedFiles)
        sessions[meta.infoHashHex] = session
        return session
    }

    fun find(infoHashHex: String): TorrentSession? = sessions[infoHashHex]

    fun delete(infoHashHex: String): Boolean = sessions.remove(infoHashHex) != null

    fun list(): List<TorrentSession> = sessions.values.toList()
}

internal fun sha1(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-1").digest(bytes)

internal fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
