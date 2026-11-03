package app.nia.navegador

/**
 * Seleccion interactiva (T017 v3, R0072, RES-0041): nodeId elegido con un
 * toque en la pagina pintada. Estado en memoria de la app: PaintView
 * escribe (hitTest -> DevToolsModel.handleOf) y DevToolsActivity lee
 * (handleWithNodeId). El nodeId es estable entre parses iguales
 * (asignacion en pre-orden), asi que la seleccion sobrevive a que cada
 * pantalla parsee su propio documento.
 */
object DevToolsState {
    @Volatile
    var selectedNodeId: Int? = null
}
