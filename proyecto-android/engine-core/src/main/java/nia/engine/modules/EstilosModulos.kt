package nia.engine.modules

import nia.engine.css.CssCascade
import nia.engine.css.CssComplexSelector
import nia.engine.css.CssRule
import nia.engine.css.CssStylesheet
import nia.engine.html.DomNode

/**
 * C6 v1 (R0276, RES-0120): validador y matcher de la hoja de estilos
 * de un modulo .niamod. La hoja es un dato en datos/ (tope 16 KiB en
 * Niamod) que el modulo declara en el manifiesto con la clave estilos
 * (exige capability modificar y permiso document.styles, ver
 * ModuleRegistry) y activa devolviendo el veredicto "estilos" desde
 * onNavigate; la resolucion por sitio queda EN EL MODULO (su logica
 * con la url), el host JAMAS interpreta sus ajustes.
 *
 * Lista cerrada del host (fail-closed): la hoja SOLO puede esconder
 * con display:none — una declaracion distinta, un selector
 * invalido, un @-rule (@media, @import) o mas de MAX_SELECTORES
 * selectores rechazan la hoja ENTERA (validar devuelve null). JAMAS
 * se inyecta un <style> en la pagina: el overlay lo aplica el layout
 * del host en el MISMO pase (display:none via noneElements), sin
 * flash y sin re-layout.
 *
 * Exclusiones v1: sin @media ni pseudoclases dinamicas (el modulo no
 * puede condicionar por viewport), sin url()/@import y la hoja vive y
 * muere con el documento (quitarla de la pagina en vivo exige
 * recargar; documentado en la ficha del modulo).
 */
object EstilosModulos {

    /** Tope de selectores de la hoja (suma de todas las reglas). */
    const val MAX_SELECTORES = 200

    /**
     * Valida la hoja contra la lista cerrada y devuelve sus reglas;
     * null = rechazada (fail-closed): error de sintaxis, @-rule,
     * declaracion distinta de una unica display:none o mas de
     * MAX_SELECTORES selectores.
     */
    fun validar(css: String): List<CssRule>? {
        val hoja = CssStylesheet.parse(css)
        if (hoja.errors.isNotEmpty()) return null
        var selectores = 0
        for (regla in hoja.rules) {
            if (regla.media != null) return null
            if (regla.declarations.size != 1) return null
            val decl = regla.declarations[0]
            if (decl.first != "display" || decl.second != "none") return null
            selectores += regla.selectors.size
            if (selectores > MAX_SELECTORES) return null
        }
        return hoja.rules
    }

    /**
     * Elementos del arbol que la hoja esconde; vacio si la hoja es
     * invalida (fail-closed) o si no casa nada. El matching es el de
     * la cascada real del host (CssCascade.matchesAny con el
     * parentMap del arbol).
     */
    fun ocultos(root: DomNode, css: String): Set<DomNode> {
        val reglas = validar(css) ?: return emptySet()
        val selectores = ArrayList<CssComplexSelector>()
        for (regla in reglas) selectores.addAll(regla.selectors)
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
