package app.nia.navegador

/**
 * Demo unificada de la app (T017 v3, R0072, RES-0041): la MISMA pagina
 * pinta PaintView y llena el panel DevTools. Los nodeIds se asignan en
 * pre-orden al parsear, de modo que dos parses del mismo HTML reparten
 * los MISMOS ids: el nodo seleccionado en la pagina pintada (hitTest
 * resuelto con DevToolsModel.handleOf) y el nodo del panel
 * (handleWithNodeId) comparten nodeId aunque cada pantalla parsee su
 * propio DomDocument.
 *
 * T035 v1 (R0088, RES-0049): la demo gana un input editable. El layout
 * no fija heights (limite v3 documentado), asi que la caja del input es
 * todo cromo: display:block con padding y bordes del CSS de la demo, y
 * PaintView pinta el value del atributo del DOM dentro de esa caja.
 */
object DemoPage {
    const val HTML: String = "<html><body>" +
        "<h1>Navegador NIA</h1>" +
        "<input id=\"busqueda\" name=\"busqueda\" value=\"\">" +
        "<p>Motor propio con CSS v2: hermanos, pseudo-clases, unidades relativas, margenes, padding y bordes. Sin WebView.</p>" +
        "<div id=\"card\"><p>Primera tarjeta: borde y padding reales (px, em, %).</p><p>La segunda raya queda mas cerca: p+p con margin-top.</p></div>" +
        "</body></html>"

    const val CSS: String = "body{background-color:#141428;font-size:18px;color:#f0eef8;padding:12px}" +
        "h1{font-size:28px;color:#7fe0bc;border-width:2px;border-color:#7fe0bc;padding:6px}" +
        "input{display:block;padding:12px;border-width:2px;border-color:#7fe0bc;background-color:#23233f;color:#f0eef8;font-size:14px;margin-top:8px}" +
        "p{margin-top:8px}" +
        "#card{background-color:#23233f;border-width:3px;border-color:#4a4a7a;padding:12px;margin-top:12px}" +
        "#card p:first-child{color:#ffd610}" +
        "p+p{margin-top:4px}"
}
