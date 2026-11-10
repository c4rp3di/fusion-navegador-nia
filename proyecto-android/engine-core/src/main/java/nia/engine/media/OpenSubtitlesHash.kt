package nia.engine.media

import java.io.File
import java.io.RandomAccessFile

/**
 * T050 v2 (R0373, RES-0150): moviehash OSDB de un archivo de video
 * local, puro JVM (T104 busca la pista exacta del archivo, no por
 * nombre). El hash OSDB suma el tamano del archivo y los primeros y
 * ultimos 64 KiB leidos como enteros little-endian de 64 bits (mod
 * 2^64) y se escribe en 16 hex minusculas.
 *
 * Un archivo ilegible o mas pequeno que un chunk NO es una
 * excepcion suelta: hashDe responde null y el host decide el
 * mensaje honesto.
 *
 * Exclusiones v2: sin moviebytesize aparte (el tamano viaja en el
 * propio hash) y sin hash de URL (solo archivos locales).
 */
object OpenSubtitlesHash {

    /** Chunk del algoritmo OSDB: 64 KiB por extremo. */
    private const val CHUNK = 65536L

    /** Hash OSDB del archivo o null si es ilegible o demasiado corto. */
    fun hashDe(archivo: File): String? {
        val tamano = archivo.length()
        if (tamano < CHUNK) return null
        var h = tamano
        try {
            RandomAccessFile(archivo, "r").use { raf ->
                val buf = ByteArray(CHUNK.toInt())
                raf.readFully(buf)
                h += sumar(buf)
                raf.seek(tamano - CHUNK)
                raf.readFully(buf)
                h += sumar(buf)
            }
        } catch (ilegible: Exception) {
            return null
        }
        return String.format("%016x", h)
    }

    /** T104 v2 (R0391, RES-0156): hash OSDB desde los chunks YA
     *  leidos por el host (SAF da un flujo, no un File); el MISMO
     *  algoritmo de hashDe, JAMAS duplicado en el shell. Null si el
     *  tamano o los chunks son demasiado cortos. */
    fun hashDeFlujo(tamano: Long, primer: ByteArray, ultimo: ByteArray): String? {
        if (tamano < CHUNK) return null
        if (primer.size < CHUNK.toInt() || ultimo.size < CHUNK.toInt()) return null
        val h = tamano + sumar(primer) + sumar(ultimo)
        return String.format("%016x", h)
    }

    /** Suma los 8192 enteros little-endian del chunk leido. */
    private fun sumar(buf: ByteArray): Long {
        var suma = 0L
        var i = 0
        while (i + 8 <= buf.size) {
            var v = 0L
            for (b in 7 downTo 0) {
                v = (v shl 8) or (buf[i + b].toLong() and 0xff)
            }
            suma += v
            i += 8
        }
        return suma
    }
}
