package nia.engine.workers

import nia.engine.js.JsServiceWorkers
import nia.engine.js.JsWorkers

/**
 * Visor de workers y service workers del motor (T102 v1, RES-0110):
 * espejo JVM puro de lo que los gestores REALES del motor exponen
 * (JsWorkers y JsServiceWorkers de T034): filas con el estado del
 * LIFECYCLE del propio backend (nunca inventado), scopes en orden de
 * registro, caches con tamanos MEDIDOS en caracteres (url + cuerpo) y
 * acciones que usan SOLO operaciones del backend (terminate, unregister
 * y borrado de cache), de modo que el visor y el SW vivo comparten el
 * mismo mapa de caches.
 *
 * Los gestores son OPCIONALES: con null el visor declara honestamente
 * que la navegacion todavia no registra workers ni SW de pagina (los
 * scripts bloqueantes de LOADED de T071 v1 no traen event loop ni
 * service workers); la UI nunca fabrica actividad inexistente.
 *
 * Espejo del gemelo TS (47 checks en verde antes del push).
 *
 * Exclusiones v1: sin drawer NavTools (T017-v6 aun propuesta; dialogo
 * del shell), CacheStorage unico por gestor (la particion por origen
 * del spec no existe en el backend de T034 v2: se declara, no se
 * simula), update = re-registro de la propia pagina (simplificacion v2
 * documentada de T034), sin estimaciones: todo lo mostrado es MEDIDO.
 */
class VisorWorkers(
    private val workers: JsWorkers?,
    private val sw: JsServiceWorkers?,
) {
    /** Fila de un worker dedicado: identidad y contadores del backend. */
    data class FilaWorker(
        val id: Long,
        val estado: String,
        val mensajesPendientes: Int,
        val timersVivos: Int,
        val abort: Boolean,
    )

    /** Fila de un service worker por scope con su estado del lifecycle. */
    data class FilaSw(
        val scope: String,
        val estado: String,
        val mensajesPendientes: Int,
        val abort: Boolean,
    )

    /** Fila de un cache: entradas y tamano MEDIDO en caracteres. */
    data class FilaCache(
        val nombre: String,
        val entradas: Int,
        val caracteres: Long,
    )

    /** Cierto si ningun gestor esta cableado (mensaje honesto). */
    fun sinGestores(): Boolean = workers == null && sw == null

    /** Workers del manager en orden de creacion, con su estado REAL. */
    fun filasWorkers(): List<FilaWorker> {
        val w = workers ?: return emptyList()
        val out = ArrayList<FilaWorker>()
        for (h in w.handles()) {
            out.add(
                FilaWorker(
                    h.id,
                    if (h.running) "corriendo" else "terminado",
                    h.pendingMessages(),
                    h.pendingTimers(),
                    h.lastAbort() != null,
                )
            )
        }
        return out
    }

    /** SWs registrados por scope en orden de registro, estado REAL. */
    fun filasSw(): List<FilaSw> {
        val s = sw ?: return emptyList()
        val out = ArrayList<FilaSw>()
        for ((scope, handle) in s.entradas()) {
            out.add(
                FilaSw(
                    scope,
                    handle.state.name.lowercase(),
                    handle.pendingMessages(),
                    handle.lastAbort() != null,
                )
            )
        }
        return out
    }

    /** Caches abiertos con entradas y tamano MEDIDO (url + cuerpo). */
    fun filasCaches(): List<FilaCache> {
        val s = sw ?: return emptyList()
        val out = ArrayList<FilaCache>()
        for (nombre in s.cacheNames()) {
            var entradas = 0
            var caracteres = 0L
            for ((url, _, cuerpo) in s.cacheSnapshot(nombre)) {
                entradas += 1
                caracteres += url.length.toLong() + cuerpo.toLong()
            }
            out.add(FilaCache(nombre, entradas, caracteres))
        }
        return out
    }

    /** Termina un worker por id: false si no existe o ya termino. */
    fun pararWorker(id: Long): Boolean {
        val w = workers ?: return false
        for (h in w.handles()) {
            if (h.id == id) return w.terminate(h)
        }
        return false
    }

    /** Termina el SW de un scope (queda registrado, estado terminado). */
    fun pararSw(scope: String): Boolean {
        val s = sw ?: return false
        for ((sc, handle) in s.entradas()) {
            if (sc == scope) return s.terminate(handle)
        }
        return false
    }

    /** Quita del registro el SW del scope: false si no esta registrado. */
    fun quitarSw(scope: String): Boolean {
        val s = sw ?: return false
        return s.unregister(scope)
    }

    /** Borra un cache por nombre (desde el host, no solo del SW): false si no existe. */
    fun borrarCache(nombre: String): Boolean {
        val s = sw ?: return false
        return s.borrarCache(nombre)
    }

    /** Lineas del dialogo del shell (honestas: nada fabricado). */
    fun texto(): List<String> {
        val out = ArrayList<String>()
        if (sinGestores()) {
            out.add("Gestores de workers/SW del motor sin cablear a la navegacion todavia.")
            out.add("Los gestores existen (T034) y sus tests cubren el lifecycle; los scripts de pagina todavia no registran workers ni SW (T071 v1).")
            return out
        }
        val fw = filasWorkers()
        var vivos = 0
        for (f in fw) if (f.estado == "corriendo") vivos += 1
        out.add("Workers dedicados: " + vivos + " corriendo de " + fw.size)
        for (f in fw) {
            out.add("  #" + f.id + " " + f.estado + ", " + f.mensajesPendientes + " mensajes, " + f.timersVivos + " timers" + if (f.abort) ", abort" else "")
        }
        val fs = filasSw()
        out.add("Service workers: " + fs.size + " registro(s)")
        for (f in fs) {
            out.add("  " + f.scope + " -> " + f.estado + ", " + f.mensajesPendientes + " mensajes" + if (f.abort) ", abort" else "")
        }
        val fc = filasCaches()
        out.add("Caches: " + fc.size + " (tamanos MEDIDOS en caracteres; sin particion por origen en el backend v2)")
        for (f in fc) {
            out.add("  " + f.nombre + ": " + f.entradas + " entrada(s), " + f.caracteres + " caracteres")
        }
        return out
    }
}
