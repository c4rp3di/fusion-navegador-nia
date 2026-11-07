package nia.engine.busqueda

import nia.engine.seleccion.FragmentoTexto
import nia.engine.seleccion.PosSeleccion

/**
 * T081 v1 (RES-0096): buscar en la pagina sobre los fragmentos de
 * palabra de T079. El texto virtual es una pieza por fragmento unida
 * con UN espacio (exactly como colapsa el layout y como reconstruye
 * Seleccion.texto), con mapa posicion -> (fragmento, offset); las
 * coincidencias pueden cruzar nodos y se representan como el par
 * (inicio, fin) que Seleccion.cajas/texto ya saben resaltar. Logica
 * PURA, sin una sola API de Android. Limites honestos: consulta de
 * hasta MAX_CONSULTA chars y hasta MAX_COINCIDENCIAS resultados (sin
 * repasar ilimitadamente); case-insensitive SIMPLE por unidad UTF-16
 * (Character.toLowerCase 1:1, sin full case folding) y matches NO
 * solapados en orden documental (cada coincidencia consume su ancho).
 */
object Busqueda {
    const val MAX_CONSULTA = 200
    const val MAX_COINCIDENCIAS = 500

    /** Coincidencia: del ancla inicio a la ancla fin (ambas dentro
     *  de fragmentos: la consulta se recorta y nunca empieza ni
     *  acaba en los espacios de colapso). */
    data class Coincidencia(val inicio: PosSeleccion, val fin: PosSeleccion)

    /** Busca la consulta (recortada) en los fragmentos en orden
     *  documental; vacio si no hay consulta, es demasiado larga o
     *  no hay nada que encajar. */
    fun buscar(frags: List<FragmentoTexto>, consulta: String): List<Coincidencia> {
        val q = consulta.trim()
        if (q.isEmpty() || q.length > MAX_CONSULTA || frags.isEmpty()) return emptyList()
        val sb = StringBuilder()
        val fragDe = ArrayList<Int>()
        val offDe = ArrayList<Int>()
        for (f in frags) {
            for (k in 0 until f.largo) {
                sb.append(f.texto[k])
                fragDe.add(f.indice)
                offDe.add(k)
            }
            if (f.indice < frags.size - 1) {
                sb.append(' ')
                fragDe.add(-1)
                offDe.add(-1)
            }
        }
        val texto = sb.toString()
        val tL = CharArray(texto.length)
        for (i in texto.indices) tL[i] = Character.toLowerCase(texto[i])
        val qL = CharArray(q.length)
        for (i in q.indices) qL[i] = Character.toLowerCase(q[i])
        val out = ArrayList<Coincidencia>()
        var i = 0
        while (i + q.length <= texto.length && out.size < MAX_COINCIDENCIAS) {
            var ok = true
            for (k in q.indices) {
                if (tL[i + k] != qL[k]) {
                    ok = false
                    break
                }
            }
            if (ok) {
                val j = i + q.length - 1
                var io = offDe[i]
                val fI = frags[fragDe[i]]
                if (io > 0 && fI.texto[io].isLowSurrogate() && fI.texto[io - 1].isHighSurrogate()) io--
                var jo = offDe[j]
                val fJ = frags[fragDe[j]]
                val fin = if (jo < fJ.largo - 1 && fJ.texto[jo].isHighSurrogate() && fJ.texto[jo + 1].isLowSurrogate()) jo + 2 else jo + 1
                out.add(Coincidencia(PosSeleccion(fragDe[i], io), PosSeleccion(fragDe[j], fin)))
                i += q.length
            } else {
                i++
            }
        }
        return out
    }

    /** Navegacion circular definida: -1 si no hay matches; desde -1
     *  el siguiente abre el primero y el anterior el ultimo. */
    fun navegar(total: Int, actual: Int, delta: Int): Int {
        if (total <= 0) return -1
        if (actual < 0) return if (delta >= 0) 0 else total - 1
        return ((actual + delta) % total + total) % total
    }
}
