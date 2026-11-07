package nia.engine.seleccion

import kotlin.math.hypot
import kotlin.math.roundToInt
import nia.engine.paint.PaintOp
import nia.engine.paint.PaintScene

/**
 * T079 v1 (RES-0095): seleccion de texto del motor. Logica PURA, sin
 * una sola API de Android. Los fragmentos salen de la escena de pintado
 * (una caja por palabra del layout), el offset se mide en unidades
 * UTF-16 como mide el motor monoespaciado (anchoChar = fontSize/2)
 * pero SIEMPRE alineado a limites de code point (nunca parte un
 * surrogate par), y la identidad de cada fragmento es su ordinal
 * documental en la escena (estable ante re-maqueteo y zoom porque el
 * numero de palabras por nodo no cambia). El shell traduce pantalla a
 * documento y decide el fallback cuando un anclaje no cabe.
 */
class FragmentoTexto(
    val indice: Int,
    val texto: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val fontSize: Double
) {
    /** Ancho de un char del layout monoespaciado (fs/2), en unidades
     *  UTF-16 como mide el motor. */
    val anchoChar: Double get() = fontSize / 2.0

    /** Largo del texto en unidades UTF-16. */
    val largo: Int get() = texto.length
}

/** Posicion de seleccion: fragmento (ordinal documental) + offset. */
data class PosSeleccion(val fragmento: Int, val offset: Int)

/** Rectangulo de resaltado en coordenadas de documento. */
data class CajaSeleccion(val x: Double, val y: Double, val width: Double, val height: Double)

/** Punto donde el shell dibuja un handle (coordenadas de documento). */
data class PuntoSeleccion(val x: Double, val y: Double)

/** Extrae los fragmentos de texto de la escena en ORDEN documental. */
object Fragmentos {
    fun de(scene: PaintScene): List<FragmentoTexto> {
        val salida = ArrayList<FragmentoTexto>()
        var indice = 0
        for (op in scene.ops) {
            if (op is PaintOp.DrawText) {
                salida.add(
                    FragmentoTexto(
                        indice,
                        op.text,
                        op.x,
                        op.y,
                        op.width,
                        op.height,
                        op.fontSize
                    )
                )
                indice++
            }
        }
        return salida
    }
}

/** Funciones puras de seleccion sobre la lista de fragmentos. */
object Seleccion {

    /** Distancia euclidea de (x, y) al rect del fragmento clampeado. */
    fun distancia(fr: FragmentoTexto, x: Double, y: Double): Double {
        val cx = x.coerceIn(fr.x, fr.x + fr.width)
        val cy = y.coerceIn(fr.y, fr.y + fr.height)
        return hypot(x - cx, y - cy)
    }

    /** Ajusta un offset UTF-16 para no partir un surrogate par. */
    fun snap(fr: FragmentoTexto, offset: Int): Int {
        var o = offset.coerceIn(0, fr.largo)
        if (o > 0 && o < fr.largo) {
            if (fr.texto[o - 1].isHighSurrogate() && fr.texto[o].isLowSurrogate()) {
                o++
            }
        }
        return o
    }

    /** Ancla (x, y) al fragmento mas cercano dentro de radio (el radio
     *  es inclusivo); null si nada esta en radio = senal de fallback
     *  para el shell. El offset se redondea y se ajusta a code points. */
    fun anclarMasCercano(
        frags: List<FragmentoTexto>,
        x: Double,
        y: Double,
        radio: Double
    ): PosSeleccion? {
        var mejor: FragmentoTexto? = null
        var mejorD = Double.MAX_VALUE
        for (f in frags) {
            val d = distancia(f, x, y)
            if (d < mejorD) {
                mejorD = d
                mejor = f
            }
        }
        val f = mejor ?: return null
        if (mejorD > radio) return null
        val rel = ((x - f.x) / f.anchoChar).roundToInt().coerceIn(0, f.largo)
        return PosSeleccion(f.indice, snap(f, rel))
    }

    /** Palabra completa bajo (x, y): exige estar dentro del rect y
     *  devuelve la seleccion que cubre de 0 a largo de la palabra. */
    fun palabraBajo(
        frags: List<FragmentoTexto>,
        x: Double,
        y: Double
    ): Pair<PosSeleccion, PosSeleccion>? {
        for (f in frags) {
            if (x >= f.x && x <= f.x + f.width && y >= f.y && y <= f.y + f.height) {
                return Pair(PosSeleccion(f.indice, 0), PosSeleccion(f.indice, f.largo))
            }
        }
        return null
    }

    /** Ordena (a, b) por orden documental (fragmento y offset). */
    fun normalizar(
        a: PosSeleccion,
        b: PosSeleccion
    ): Pair<PosSeleccion, PosSeleccion> {
        if (a.fragmento > b.fragmento || (a.fragmento == b.fragmento && a.offset > b.offset)) {
            return Pair(b, a)
        }
        return Pair(a, b)
    }

    /** Texto entre dos anclas: una pieza por fragmento (parcial en los
     *  extremos) unidas con UN espacio, como colapsa el layout. Vacio
     *  si el rango no cubre nada o un fragmento no existe. */
    fun texto(
        frags: List<FragmentoTexto>,
        a0: PosSeleccion,
        b0: PosSeleccion
    ): String {
        val par = normalizar(a0, b0)
        val a = par.first
        val b = par.second
        if (a.fragmento < 0 || b.fragmento >= frags.size) return ""
        val piezas = ArrayList<String>()
        var i = a.fragmento
        while (i <= b.fragmento) {
            val f = frags[i]
            val desde = if (i == a.fragmento) a.offset else 0
            val hasta = if (i == b.fragmento) b.offset else f.largo
            if (hasta > desde) piezas.add(f.texto.substring(desde, hasta))
            i++
        }
        return piezas.joinToString(" ")
    }

    /** Rectangulos de resaltado: recorte parcial por offset en los
     *  extremos del rango; cajas de ancho nulo se omiten. */
    fun cajas(
        frags: List<FragmentoTexto>,
        a0: PosSeleccion,
        b0: PosSeleccion
    ): List<CajaSeleccion> {
        val par = normalizar(a0, b0)
        val a = par.first
        val b = par.second
        if (a.fragmento < 0 || b.fragmento >= frags.size) return emptyList()
        val salida = ArrayList<CajaSeleccion>()
        var i = a.fragmento
        while (i <= b.fragmento) {
            val f = frags[i]
            val izq = if (i == a.fragmento) a.offset * f.anchoChar else 0.0
            val der = if (i == b.fragmento) b.offset * f.anchoChar else f.largo * f.anchoChar
            if (der > izq) {
                salida.add(CajaSeleccion(f.x + izq, f.y, der - izq, f.height))
            }
            i++
        }
        return salida
    }

    /** Punto del handle de un ancla (en unidades de documento). */
    fun punto(frags: List<FragmentoTexto>, p: PosSeleccion): PuntoSeleccion {
        if (p.fragmento < 0 || p.fragmento >= frags.size) {
            return PuntoSeleccion(0.0, 0.0)
        }
        val f = frags[p.fragmento]
        return PuntoSeleccion(f.x + p.offset * f.anchoChar, f.y + f.height)
    }
}

/**
 * Fragmento de texto W3C (:~:text=) para favoritos: ancla el texto
 * seleccionado en la URL. Si el texto no es anclable (vacio, solo
 * espacios, con saltos, o de mas de 300 code points) devuelve la URL
 * sola y el shell avisa del fallback.
 */
object FragmentoUrl {
    private const val HEX = "0123456789ABCDEF"

    fun crear(url: String, texto: String): String {
        val t = texto.trim()
        if (t.isEmpty()) return url
        if (t.contains('\n') || t.contains('\r')) return url
        if (t.codePointCount(0, t.length) > 300) return url
        return url + "#:~:text=" + codificar(t)
    }

    /** Percent-encoding UTF-8 manual: unreserved pasan, espacio %20,
     *  todo lo demas va por bytes del code point (surrogates juntos). */
    private fun codificar(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else if (c == ' ') {
                sb.append("%20")
            } else {
                val cp = s.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 15])
                }
                if (Character.isSupplementaryCodePoint(cp)) i++
            }
            i++
        }
        return sb.toString()
    }
}
