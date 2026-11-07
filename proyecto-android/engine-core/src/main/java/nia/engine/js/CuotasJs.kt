package nia.engine.js

/**
 * Cuotas visibles de JavaScript (T100, RES-0104).
 *
 * Politica PURA sobre las cuotas del motor (T021): el shell consulta
 * los ajustes del usuario y resuelve UN JsLimits SIEMPRE dentro de los
 * rangos honestos. La ejecucion SIN limite NO existe: resolver() acota
 * cualquier valor absurdo (0, negativo, fuera de rango) y el tope duro
 * (hard cap) JAMAS se elimina.
 *
 * Mapeo honesto: los "pasos" cuentan sentencias/expresiones ejecutadas
 * por evaluacion (NO tiempo de CPU) y los "allocs" cuentan
 * objetos/arrays creados por evaluacion (NO son bytes de heap
 * reales). La cuota de tiempo no existe en v1: la interrupcion manual
 * llega con el Stop del shell sobre la sesion volatile de JsQuotas
 * (T021) y un bucle infinito JAMAS bloquea la UI.
 */

/** Rangos y defectos de las cuotas visibles (T100): el defecto es el del motor (T021) y el tope duro NO se elimina. */
object CuotasJs {

    const val DEFAULT_PASOS = 100_000_000L
    const val MIN_PASOS = 1_000_000L
    const val HARD_CAP_PASOS = 1_000_000_000L

    const val DEFAULT_ALLOCS = 10_000_000L
    const val MIN_ALLOCS = 100_000L
    const val HARD_CAP_ALLOCS = 100_000_000L

    const val AVISO_SIN_LIMITE = "no existe la ejecucion sin limite: el tope duro no se elimina"

    /**
     * Resuelve los topes SIEMPRE dentro del rango: un valor absurdo
     * (0 o negativo) cae al DEFAULT, lo menor que el minimo cae al
     * minimo y lo que supera el tope duro cae al tope duro. JAMAS
     * devuelve "sin limite".
     */
    fun resolver(pasos: Long, allocs: Long): JsLimits {
        val p = when {
            pasos <= 0L -> DEFAULT_PASOS
            pasos < MIN_PASOS -> MIN_PASOS
            pasos > HARD_CAP_PASOS -> HARD_CAP_PASOS
            else -> pasos
        }
        val a = when {
            allocs <= 0L -> DEFAULT_ALLOCS
            allocs < MIN_ALLOCS -> MIN_ALLOCS
            allocs > HARD_CAP_ALLOCS -> HARD_CAP_ALLOCS
            else -> allocs
        }
        return JsLimits(p, a)
    }

    /**
     * Cuota TEMPORAL de reintento (T100): duplica los topes base por
     * cada reintento concedido hasta el tope duro, que JAMAS se pasa.
     * La semantica del reintento es volver a ejecutar los scripts de la
     * pagina desde el arranque (no hay reanudacion a mitad): "reanudar"
     * en v1 es recargar con mas cuota.
     */
    fun temporal(intentos: Int, base: JsLimits): JsLimits {
        var p = base.maxSteps
        var a = base.maxAllocs
        var n = intentos
        while (n > 0) {
            if (p < HARD_CAP_PASOS) {
                p = p * 2L
                if (p > HARD_CAP_PASOS) p = HARD_CAP_PASOS
            }
            if (a < HARD_CAP_ALLOCS) {
                a = a * 2L
                if (a > HARD_CAP_ALLOCS) a = HARD_CAP_ALLOCS
            }
            n -= 1
        }
        return JsLimits(p, a)
    }

    /**
     * Mapa honesto de la interrupcion (T100): que cuenta cada tope y
     * que NO mide, con el aviso de que sin limite no existe. Linea por
     * linea, SIEMPRE en este orden.
     */
    fun mapa(l: JsLimits): List<String> {
        val out = ArrayList<String>()
        out.add("pasos: " + l.maxSteps + " sentencias/expresiones por evaluacion (NO tiempo de CPU)")
        out.add("allocs: " + l.maxAllocs + " objetos/arrays por evaluacion (NO bytes de heap reales)")
        out.add("tiempo: sin cuota de tiempo en v1; Stop interrumpe la sesion en el siguiente safepoint (T021)")
        out.add("reintento: recarga los scripts desde el arranque con la cuota doblada hasta el tope duro")
        out.add(AVISO_SIN_LIMITE)
        return out
    }
}
