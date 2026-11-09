package nia.engine.modules

import nia.engine.css.CssCascade
import nia.engine.css.CssComplexSelector
import nia.engine.html.DomNode

/**
 * T053 v1 (R0289, RES-0123): parser y matcher del subconjunto COSMETICO
 * de EasyList que el host retiene de un paquete .niamod (clave
 * cosmeticos de datos/, tope 64 KiB en Niamod; exige capability
 * modificar y permiso document.cosmetic, ver ModuleRegistry) y activa
 * devolviendo el veredicto "cosmeticos" desde onNavigate; la lista la
 * resuelve cosmeticosParaNavegacion (GestorModulos) y su overlay lo
 * aplica el layout del host en el MISMO pase de display:none
 * (PaintView.styleOfDe, unido al de la hoja de estilos).
 *
 * Formato aceptado por linea (fail-closed): "##sel" (todos los
 * origenes), "dom1,dom2##sel" (sufijo de dominio), "#@#sel" y
 * "dom#@#sel" (excepciones que retiran por TEXTO de selector). Se
 * IGNORAN las reglas de red, los comentarios (lineas que empiezan por
 * "!" o "[") y los snippets (#$#, #?#); se DESCARTAN contadas las
 * lineas con selector ilegible (CssCascade.parseSelectorList), mas de
 * MAX_DOMINIOS dominios o negacion de dominio (~dom). Topes que
 * rechazan la lista ENTERA (parse devuelve null): mas de MAX_LINEAS
 * lineas, mas de MAX_SELECTORES selectores o cero reglas validas.
 *
 * Exclusiones v1: sin snippets #$#/#?#, sin negacion de dominio ~dom,
 * la excepcion retira por TEXTO en cualquier origen (sin dominio de
 * excepcion), UNA lista por documento (gana el primero, C5/C6) y sin
 * convertidor EasyList completo: el dominio casa por SUFIJO
 * (aplicaDominio).
 */
object CosmeticosModulos {

    /** Tope de lineas de la lista (split('\n'), fail-closed). */
    const val MAX_LINEAS = 4096

    /** Tope de selectores de la lista (suma de todas las reglas). */
    const val MAX_SELECTORES = 2000

    /** Tope de dominios por regla; mas la descarta. */
    const val MAX_DOMINIOS = 8

    /** Una regla cosmética por linea (dominios vacios = todos). */
    data class ReglaCosmetica(
        val dominios: Set<String>,
        val selectores: List<CssComplexSelector>,
        val texto: String
    )

    /** Lista cosmética parseada: reglas, excepciones y descartes. */
    data class ListaCosmetica(
        val reglas: List<ReglaCosmetica>,
        val excepciones: Set<String>,
        val descartados: Int
    )

    /**
     * Parsea la lista EasyList cosmética; null = rechazada
     * (fail-closed): demasiado larga, demasiados selectores o cero
     * reglas validas.
     */
    fun parse(texto: String): ListaCosmetica? {
        val lineas = texto.split('\n')
        if (lineas.size > MAX_LINEAS) return null
        val reglas = ArrayList<ReglaCosmetica>()
        val excepciones = HashSet<String>()
        var descartados = 0
        var selectores = 0
        for (cruda in lineas) {
            val linea = cruda.trim()
            if (linea.isEmpty() || linea.startsWith("!") || linea.startsWith("[")) continue
            if (linea.contains("#$#") || linea.contains("#?#")) continue
            val ex = linea.indexOf("#@#")
            if (ex >= 0) {
                val sel = linea.substring(ex + 3).trim()
                if (sel.isNotEmpty() && CssCascade.parseSelectorList(sel) != null) {
                    excepciones.add(sel)
                }
                continue
            }
            val at = linea.indexOf("##")
            if (at < 0) continue
            val sel = linea.substring(at + 2).trim()
            if (sel.isEmpty()) {
                descartados++
                continue
            }
            var ilegible = false
            val dominios = HashSet<String>()
            if (at > 0) {
                val partes = linea.substring(0, at).split(',')
                if (partes.size > MAX_DOMINIOS) ilegible = true
                for (d in partes) {
                    val dom = d.trim().lowercase()
                    if (dom.isEmpty() || dom.startsWith("~")) {
                        ilegible = true
                        break
                    }
                    dominios.add(dom)
                }
            }
            if (ilegible) {
                descartados++
                continue
            }
            val parseados = CssCascade.parseSelectorList(sel)
            if (parseados == null || parseados.isEmpty()) {
                descartados++
                continue
            }
            selectores += parseados.size
            if (selectores > MAX_SELECTORES) return null
            reglas.add(ReglaCosmetica(dominios, parseados, sel))
        }
        if (reglas.isEmpty()) return null
        return ListaCosmetica(reglas, excepciones, descartados)
    }

    /** True si el host casa la lista de dominios (vacio = todos). */
    fun aplicaDominio(dominios: Set<String>, host: String): Boolean {
        if (dominios.isEmpty()) return true
        val h = host.lowercase()
        for (d in dominios) {
            if (h == d || h.endsWith("." + d)) return true
        }
        return false
    }

    /** Selectores de la lista que aplican al host, sin los exceptuados. */
    fun selectoresPara(lista: ListaCosmetica, host: String): List<CssComplexSelector> {
        val fuera = ArrayList<CssComplexSelector>()
        for (regla in lista.reglas) {
            if (!aplicaDominio(regla.dominios, host)) continue
            if (lista.excepciones.contains(regla.texto)) continue
            fuera.addAll(regla.selectores)
        }
        return fuera
    }

    /**
     * Elementos del arbol que la lista esconde en ese host; vacio si
     * nada casa. El matching es el de la cascada real del host
     * (CssCascade.matchesAny con el parentMap del arbol).
     */
    fun ocultos(root: DomNode, lista: ListaCosmetica, host: String): Set<DomNode> {
        val selectores = selectoresPara(lista, host)
        if (selectores.isEmpty()) return emptySet()
        val padres = CssCascade.parentMap(root)
        val fuera = HashSet<DomNode>()
        fun go(nodo: DomNode) {
            if (nodo is DomNode.Element && CssCascade.matchesAny(nodo, selectores, padres)) {
                fuera.add(nodo)
            }
            for (hijo in hijosDe(nodo)) go(hijo)
        }
        go(root)
        return fuera
    }

    private fun hijosDe(n: DomNode): List<DomNode> = when (n) {
        is DomNode.Document -> n.children
        is DomNode.Element -> n.children
        else -> emptyList()
    }
}
