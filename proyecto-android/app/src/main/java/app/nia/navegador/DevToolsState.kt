package app.nia.navegador

/**
 * Seleccion interactiva (T017 v3, R0072, RES-0041): nodeId elegido con un
 * toque en la pagina pintada. Estado en memoria de la app: PaintView
 * escribe (hitTest -> DevToolsModel.handleOf) y DevToolsActivity lee
 * (handleWithNodeId). El nodeId es estable entre parses iguales
 * (asignacion en pre-orden), asi que la seleccion sobrevive a que cada
 * pantalla parsee su propio documento.
 *
 * T017 v4 (R0074, RES-0042): reglas CSS del usuario anadidas desde el
 * panel (userCss + revision); PaintView rebuild al cambiar revision.
 */
object DevToolsState {
    @Volatile
    var selectedNodeId: Int? = null

    /** Reglas CSS del usuario (v4): se appendean a DemoPage.CSS al parsear. */
    @Volatile
    var userCss: String = ""

    /** Version del estado de estilos: PaintView rebuild al cambiar. */
    @Volatile
    var revision: Int = 0

    fun addUserRule(rule: String) {
        val r = rule.trim()
        if (r.isEmpty()) return
        userCss = if (userCss.isEmpty()) r else userCss + "\n" + r
        revision++
    }

    fun clearUserRules() {
        userCss = ""
        revision++
    }
}
