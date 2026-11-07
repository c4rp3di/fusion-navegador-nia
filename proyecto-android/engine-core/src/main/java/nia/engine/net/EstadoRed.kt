package nia.engine.net

import nia.engine.inspector.SecretRedactor

/**
 * T106 v1 (RES-0100): linea de estado de red de UNA navegacion de
 * UNA pestana. Contextual por pestana: el registro JAMAS mezcla la
 * red de una pestana con la de otra. Los SECRETOS se redactan EN LA
 * INGESTA con el SecretRedactor verificado (T008), asi el registro
 * nunca guarda el texto crudo. La latencia la mide el shell alrededor
 * de la navegacion real; el transporte es el real del perfil (http).
 */
data class LineaRed(
    val tabId: Long,
    val urlRedactada: String,
    val transporte: String,
    val latenciaMillis: Long,
    val exito: Boolean,
    val codigo: String?,
    val detalleRedactado: String,
    val cuandoMillis: Long
) {
    /** Linea de dialogo discreta: todo YA redactado en la ingesta. */
    fun texto(): String {
        val resultado = if (exito) {
            "ok"
        } else {
            "fallo " + (codigo ?: "?") + ": " + detalleRedactado
        }
        return "pestana " + tabId + " | " + urlRedactada + " | " + transporte +
            " | " + latenciaMillis + " ms | " + resultado
    }
}

/**
 * T106 v1 (RES-0100): registro del estado de red por pestana: SOLO la
 * ultima navegacion de cada pestana, con redaccion en la ingesta.
 */
class RegistroRed {

    private val porTab = HashMap<Long, LineaRed>()

    /** Ingresa la ultima navegacion de la pestana tabId: redacta los
     *  secretos AQUI (en la ingesta) y devuelve la linea guardada. */
    fun registrar(
        tabId: Long,
        url: String,
        transporte: String,
        latenciaMillis: Long,
        exito: Boolean,
        codigo: String?,
        detalle: String,
        ahoraMillis: Long
    ): LineaRed {
        val linea = LineaRed(
            tabId,
            SecretRedactor.redactUrl(url),
            transporte,
            latenciaMillis,
            exito,
            codigo,
            SecretRedactor.redactText(detalle),
            ahoraMillis
        )
        porTab[tabId] = linea
        return linea
    }

    /** Estado de ESA pestana (null si aun no navego); nunca el de otra. */
    fun de(tabId: Long): LineaRed? = porTab[tabId]

    /** Retira el estado al cerrar la pestana; devuelve lo retirado. */
    fun borrar(tabId: Long): LineaRed? = porTab.remove(tabId)

    /** Pestanas con estado registrado. */
    fun contar(): Int = porTab.size
}
