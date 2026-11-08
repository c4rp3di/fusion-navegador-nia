package nia.engine.modules

import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.inspector.InspectorSession

/**
 * C10 v1 (R0258, RES-0115, E4 1/2.4): canal de diagnostico del modulo
 * montado SOBRE el InspectorSession del host (T008 v1), que ya redacta
 * EN LA INGESTA con su SecretRedactor: el redactor del HOST manda, el
 * crudo NO se persiste y la redaccion NO se desactiva; el
 * DefaultRedactor del modulo NO se ratifica sin comparacion
 * documentada. El aislamiento por perfil/pestana/documento que pide
 * el modulo ya lo modela EventContext y lo aporta el HOST (sin
 * contexto no hay evento).
 *
 * Cuota por modulo A MEDIR (la propuesta del modulo de 2.500
 * eventos/30 min NO esta aprobada): al superar MAX_EVENTOS_POR_MODULO
 * el evento se DESCARTA y se cuenta aparte (descartados), distinguible
 * en el diagnostico; el buffer global sigue siendo el de
 * InspectorProtocol. Los textos se recortan a MAX_LARGO_TEXTO ANTES
 * de la ingesta; la redaccion la hace SIEMPRE el host.
 *
 * La DISTINCION de C7 viaja explicita: informeListas escribe un
 * resumen INFO y un evento por resultado con resultado=ok (INFO),
 * resultado=cuota (WARNING: denegacion por cuota DISTINGUIBLE de
 * error de red) y resultado=fallo (ERROR); la distincion
 * no-disponible vs bloqueada la conserva quien escribe: el canal solo
 * garantiza que viaja.
 *
 * v1 es del lado HOST: el entry del modulo NO tiene API de
 * diagnostico en api-version 1 (E4); cuando el hook reabra (T030) el
 * modulo podra escribir con su propio EventContext.
 */
class DiagModulos(
    private val inspector: InspectorSession,
    private val maxEventosPorModulo: Int = MAX_EVENTOS_POR_MODULO
) {

    companion object {
        /** Kind del canal generico del modulo. */
        const val KIND_MODULO = "modulo"

        /** Kind del informe de listas (C7: cuota distinguible de fallo). */
        const val KIND_LISTAS = "modulo.listas"

        /** Cuota de eventos por modulo (A MEDIR; 2.500/30 min NO aprobada). */
        const val MAX_EVENTOS_POR_MODULO = 200

        /** Recorte del texto ANTES de la ingesta (la redaccion es del host). */
        const val MAX_LARGO_TEXTO = 256

        fun idValido(s: String): Boolean =
            s.isNotEmpty() && s.length <= 64 &&
                s.all { c ->
                    c == '.' || c == '-' || c == '_' ||
                        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
                }
    }

    init {
        require(maxEventosPorModulo > 0) { "la cuota de eventos debe ser positiva" }
    }

    private val escritos = HashMap<String, Int>()
    private val descartados = HashMap<String, Int>()

    /** Evento generico del modulo; false si el id no vale o la cuota
     *  del modulo ya esta llena (el descarte se cuenta aparte). */
    fun evento(id: String, nivel: EventLevel, texto: String, context: EventContext): Boolean {
        val recortado = if (texto.length > MAX_LARGO_TEXTO) texto.substring(0, MAX_LARGO_TEXTO) else texto
        return escribir(id, KIND_MODULO, nivel, mapOf("modulo" to id, "texto" to recortado), context)
    }

    /** Informe de listas de C7 con la DISTINCION explicita: resumen
     *  INFO primero (la sintesis siempre viaja) y un evento por
     *  resultado - ok INFO, cuota WARNING, fallo ERROR - consumiendo
     *  la MISMA cuota por modulo. Devuelve el informe para encadenar. */
    fun informeListas(id: String, informe: ListasInforme, context: EventContext): ListasInforme {
        if (informe.resultados.isEmpty()) return informe
        escribir(
            id, KIND_LISTAS, EventLevel.INFO,
            mapOf("modulo" to id, "resumen" to informe.resumen()), context
        )
        for (r in informe.resultados) {
            when (r) {
                is ListaResultado.Ok -> escribir(
                    id, KIND_LISTAS, EventLevel.INFO,
                    mapOf(
                        "modulo" to id, "resultado" to "ok",
                        "nombre" to r.nombre, "url" to r.url, "bytes" to r.bytes.toString()
                    ), context
                )
                is ListaResultado.Cuota -> escribir(
                    id, KIND_LISTAS, EventLevel.WARNING,
                    mapOf(
                        "modulo" to id, "resultado" to "cuota",
                        "nombre" to r.nombre, "url" to r.url, "motivo" to r.motivo
                    ), context
                )
                is ListaResultado.Fallo -> escribir(
                    id, KIND_LISTAS, EventLevel.ERROR,
                    mapOf(
                        "modulo" to id, "resultado" to "fallo",
                        "nombre" to r.nombre, "url" to r.url, "motivo" to r.motivo
                    ), context
                )
            }
        }
        return informe
    }

    /** Eventos escritos por el modulo (diagnostico del host). */
    fun escritos(id: String): Int = escritos[id] ?: 0

    /** Eventos DESCARTADOS por cuota del modulo (distinguible). */
    fun descartados(id: String): Int = descartados[id] ?: 0

    private fun escribir(
        id: String,
        kind: String,
        nivel: EventLevel,
        payload: Map<String, String>,
        context: EventContext
    ): Boolean {
        if (!idValido(id)) return false
        val n = escritos[id] ?: 0
        if (n >= maxEventosPorModulo) {
            descartados[id] = (descartados[id] ?: 0) + 1
            return false
        }
        inspector.custom(context, kind, nivel, payload)
        escritos[id] = n + 1
        return true
    }
}
