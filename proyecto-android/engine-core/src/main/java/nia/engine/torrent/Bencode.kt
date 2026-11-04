package nia.engine.torrent

import java.io.ByteArrayOutputStream

/**
 * T047 v1 (R0108, RES-0058, ADR-016): codec bencode del metainfo
 * BitTorrent (BEP-3), sin dependencias. Decodificacion estricta (nada
 * de contenido tras el valor ni valores truncados) y codificacion
 * CANONICA: las claves de los diccionarios SIEMPRE van en orden de
 * bytes al codificar, porque el infohash de un torrent es el SHA-1 de
 * la codificacion canonica del diccionario info.
 */
sealed class Benc {
    data class BInt(val value: Long) : Benc()
    class BStr(val bytes: ByteArray) : Benc() {
        override fun equals(other: Any?): Boolean =
            other is BStr && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        fun text(): String = bytes.toString(Charsets.UTF_8)
    }
    class BList(val items: List<Benc>) : Benc() {
        override fun equals(other: Any?): Boolean =
            other is BList && items == other.items
        override fun hashCode(): Int = items.hashCode()
    }
    class BDict(val entries: LinkedHashMap<String, Benc>) : Benc() {
        override fun equals(other: Any?): Boolean =
            other is BDict && entries == other.entries
        override fun hashCode(): Int = entries.hashCode()
    }
}

/** Excepcion de bencode: truncado, byte invalido o cola tras el valor. */
class BencodeException(message: String) : RuntimeException(message)

/** Codec bencode (BEP-3) con codificacion canonica. */
object Bencode {

    /** Decodifica TODO el byte array como UN valor bencode. */
    fun decode(bytes: ByteArray): Benc {
        val pos = intArrayOf(0)
        val valor = parse(bytes, pos)
        if (pos[0] != bytes.size) {
            throw BencodeException("contenido tras el valor en " + pos[0])
        }
        return valor
    }

    /** Codifica canonico (diccionarios con claves en orden de bytes). */
    fun encode(v: Benc): ByteArray {
        val out = ByteArrayOutputStream()
        escribir(out, v)
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray, pos: IntArray): Benc {
        if (pos[0] >= bytes.size) throw BencodeException("truncado en " + pos[0])
        val c = bytes[pos[0]].toInt() and 0xff
        return when {
            c == 'i'.code -> parseInt(bytes, pos)
            c == 'l'.code -> {
                pos[0]++
                val items = ArrayList<Benc>()
                while (!esDelimitador(bytes, pos)) items.add(parse(bytes, pos))
                pos[0]++
                Benc.BList(items)
            }
            c == 'd'.code -> {
                pos[0]++
                val entries = LinkedHashMap<String, Benc>()
                while (!esDelimitador(bytes, pos)) {
                    val clave = parse(bytes, pos)
                    if (clave !is Benc.BStr) {
                        throw BencodeException("clave no string en " + pos[0])
                    }
                    entries[clave.text()] = parse(bytes, pos)
                }
                pos[0]++
                Benc.BDict(entries)
            }
            c >= '0'.code && c <= '9'.code -> parseStr(bytes, pos)
            else -> throw BencodeException("byte invalido en " + pos[0])
        }
    }

    private fun esDelimitador(bytes: ByteArray, pos: IntArray): Boolean {
        if (pos[0] >= bytes.size) throw BencodeException("truncado en " + pos[0])
        return bytes[pos[0]].toInt() == 'e'.code
    }

    private fun parseInt(bytes: ByteArray, pos: IntArray): Benc {
        val fin = indexOfByte(bytes, pos[0] + 1, 'e'.code)
        val texto = String(bytes, pos[0] + 1, fin - pos[0] - 1, Charsets.UTF_8)
        if (texto.isEmpty() || !esEntero(texto)) {
            throw BencodeException("entero invalido en " + pos[0])
        }
        pos[0] = fin + 1
        return Benc.BInt(texto.toLong())
    }

    private fun esEntero(s: String): Boolean {
        val cuerpo = if (s.startsWith("-")) s.substring(1) else s
        return cuerpo.isNotEmpty() && cuerpo.all { it in '0'..'9' }
    }

    private fun parseStr(bytes: ByteArray, pos: IntArray): Benc {
        val sep = indexOfByte(bytes, pos[0], ':'.code)
        val largo = String(bytes, pos[0], sep - pos[0], Charsets.UTF_8).toIntOrNull()
            ?: throw BencodeException("largo invalido en " + pos[0])
        if (largo < 0 || sep + 1 + largo > bytes.size) {
            throw BencodeException("string truncado en " + pos[0])
        }
        pos[0] = sep + 1 + largo
        return Benc.BStr(bytes.copyOfRange(sep + 1, sep + 1 + largo))
    }

    private fun indexOfByte(bytes: ByteArray, desde: Int, objetivo: Int): Int {
        for (i in desde until bytes.size) {
            if ((bytes[i].toInt() and 0xff) == objetivo) return i
        }
        throw BencodeException("truncado esperando delimitador desde " + desde)
    }

    private fun escribir(out: ByteArrayOutputStream, v: Benc) {
        when (v) {
            is Benc.BInt -> out.write(("i" + v.value + "e").toByteArray(Charsets.UTF_8))
            is Benc.BStr -> {
                out.write((v.bytes.size.toString() + ":").toByteArray(Charsets.UTF_8))
                out.write(v.bytes)
            }
            is Benc.BList -> {
                out.write('l'.code)
                for (item in v.items) escribir(out, item)
                out.write('e'.code)
            }
            is Benc.BDict -> {
                out.write('d'.code)
                val claves = v.entries.keys.toList()
                    .sortedWith(Comparator { a, b -> compararClaves(a, b) })
                for (clave in claves) {
                    escribir(out, Benc.BStr(clave.toByteArray(Charsets.UTF_8)))
                    escribir(out, v.entries.getValue(clave))
                }
                out.write('e'.code)
            }
        }
    }

    private fun compararClaves(a: String, b: String): Int {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        val n = minOf(ba.size, bb.size)
        for (i in 0 until n) {
            val d = (ba[i].toInt() and 0xff) - (bb[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return ba.size - bb.size
    }
}
