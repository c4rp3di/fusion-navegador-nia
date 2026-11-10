package nia.engine.download

import java.util.concurrent.Executor
import nia.contracts.CancellationTokenSource
import nia.contracts.SystemWallClock
import nia.contracts.WallClock
import nia.engine.net.TransportFailure

/**
 * T046 v1 (R0097, RES-0053): cola de descargas del motor. El nucleo es
 * puro JVM (testeable en CI) y agnostico de tres fronteras inyectables:
 *
 * - DownloadTransport abre la fuente (lectura por chunks y total si se
 *   conoce; los fallos se tipifican con TransportFailure de T011) y
 *   respetar el token de cancelacion (desconecta su socket).
 * - DownloadSink recibe los bytes (en la app escribe al OutputStream
 *   del SAF) y se cierra SIEMPRE: completada, cancelada o fallida.
 * - Executor decide el hilo: sincrono en los tests, un pool fijo en la
 *   app. El motor garantiza maxConcurrent descargas a la vez y el resto
 *   espera en cola FIFO.
 *
 * Cancelacion cooperativa (T011): el bucle consulta el token ANTES de
 * cada lectura; una cancelacion que llega durante la lectura del chunk
 * N deja ese chunk escrito y corta antes del siguiente (documentado,
 * espejo del gate de FetchManager donde la cancelacion gana al fallo de
 * red). Un item QUEUED cancelado pasa directo a CANCELLED sin correr.
 *
 * T046 v2 (R0124, RES-0065): reanudacion y reintentos.
 *
 * - DownloadTransport.open gana startByte: 0 pide la fuente desde el
 *   principio y N pide la cabecera Range bytes=N- ; DownloadOpen.Opened
 *   informa el byte de arranque REAL del stream (206 = N, 200 = 0: el
 *   servidor ignoro el Range) y el total COMPLETO de la descarga.
 * - ReopenableSink es un sink que sabe reposicionarse: reopen(N)
 *   devuelve un NUEVO sink posicionado en el byte N (0 trunca y
 *   escribe desde cero; en la app SAF wt trunca y wa continua).
 * - Reintentos opt-in por constructor: maxRetries (0 por defecto, el
 *   comportamiento v1) reintenta SOLO fallos transitorios de apertura
 *   y de lectura (NETWORK y DNS); TLS_INVALID nunca se reintenta
 *   (politica de certificado, no un fallo transitorio) y la
 *   cancelacion gana SIEMPRE al reintento. Los mensajes de error no
 *   cambian por reintentar.
 * - resume(id) re-encola un item FAILED con bytes en su sitio y sink
 *   reabrible: sigue desde el byte que fallo con Range y, si el
 *   servidor responde 200, reinicia desde cero truncando el destino.
 *   Fuera de ese contrato devuelve false.
 *
 * T046 v3 (R0334, RES-0135): limpiarTerminadas() retira del
 * registro los items en estado terminal y devuelve SUS IDS en
 * orden; los vivos (QUEUED/RUNNING) NUNCA se tocan (retirar una
 * descarga en marcha es cancelar, no barrer) y los ids JAMAS se
 * reutilizan.
 *
 * Limitaciones v2: sin gestor de descargas persistente (T047 monta el
 * torrent sobre esta frontera), sin Range multiple y una cancelacion
 * sobre un estado terminal se ignora (devuelve false).
 */

enum class DownloadState { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }

data class DownloadItem(
    val id: Long,
    val url: String,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val lastError: String?
)

/** Fuente abierta por el transporte: lectura por chunks y cierre. */
interface DownloadStream {
    fun read(buffer: ByteArray): Int
    fun close()
}

sealed class DownloadOpen {
    data class Opened(
        val stream: DownloadStream,
        val totalBytes: Long,
        val startByte: Long
    ) : DownloadOpen()
    data class Failed(val kind: TransportFailure) : DownloadOpen()
}

interface DownloadTransport {
    fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen
}

/** Destino de los bytes; close se llama siempre al terminar. */
interface DownloadSink {
    fun write(bytes: ByteArray, length: Int)
    fun close()
}

/**
 * T046 v2: sink reabrible para reanudar. reopen(startByte) devuelve un
 * NUEVO sink posicionado en ese byte del destino (0 = truncado y
 * escribe desde cero); el motor cierra el sink anterior SIEMPRE.
 */
interface ReopenableSink : DownloadSink {
    fun reopen(startByte: Long): DownloadSink
}

class DownloadEngine(
    private val transport: DownloadTransport,
    private val executor: Executor = Executor { task -> task.run() },
    private val maxConcurrent: Int = 2,
    private val chunkSize: Int = 8192,
    private val clock: WallClock = SystemWallClock(),
    private val maxRetries: Int = 0
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent >= 1" }
        require(chunkSize >= 1) { "chunkSize >= 1" }
        require(maxRetries >= 0) { "maxRetries >= 0" }
    }

    private class Entry(
        val url: String,
        var sink: DownloadSink,
        var cts: CancellationTokenSource,
        var state: DownloadState,
        var bytes: Long,
        var total: Long,
        var error: String?
    )

    private val lock = Object()
    private val items = LinkedHashMap<Long, Entry>()
    private val queue = ArrayList<Long>()
    private val listeners = ArrayList<(DownloadItem) -> Unit>()
    private var nextId = 0L
    private var running = 0

    fun addListener(listener: (DownloadItem) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun list(): List<DownloadItem> = synchronized(lock) { items.entries.map { snapshot(it.key, it.value) } }

    fun status(id: Long): DownloadItem? = synchronized(lock) { items[id]?.let { snapshot(id, it) } }

    private fun snapshot(id: Long, entry: Entry): DownloadItem =
        DownloadItem(id, entry.url, entry.state, entry.bytes, entry.total, entry.error)

    private fun notifyLocked(id: Long, entry: Entry) {
        val snap = snapshot(id, entry)
        for (listener in listeners) listener(snap)
    }

    private fun isTerminal(state: DownloadState): Boolean =
        state == DownloadState.COMPLETED || state == DownloadState.CANCELLED || state == DownloadState.FAILED

    private fun closeQuietly(entry: Entry) {
        try {
            entry.sink.close()
        } catch (e: java.io.IOException) {
            // el cierre del sink no revierte el estado del item
        }
    }

    private fun retryable(kind: TransportFailure): Boolean =
        kind == TransportFailure.NETWORK || kind == TransportFailure.DNS

    /** Encola una descarga; devuelve su id monotomico. */
    fun enqueue(url: String, sink: DownloadSink): Long {
        val entry = Entry(url, sink, CancellationTokenSource(clock), DownloadState.QUEUED, 0L, -1L, null)
        synchronized(lock) {
            nextId += 1
            val id = nextId
            items[id] = entry
            val lower = url.trim().lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                entry.state = DownloadState.FAILED
                entry.error = "esquema no soportado"
                closeQuietly(entry)
                notifyLocked(id, entry)
                return id
            }
            queue.add(id)
            scheduleNextLocked()
            return id
        }
    }

    /** Cancela un item vivo: QUEUED pasa directo a CANCELLED sin correr. */
    fun cancel(id: Long): Boolean {
        synchronized(lock) {
            val entry = items[id] ?: return false
            if (isTerminal(entry.state)) return false
            entry.cts.cancel()
            if (entry.state == DownloadState.QUEUED) {
                entry.state = DownloadState.CANCELLED
                entry.error = "cancelada"
                closeQuietly(entry)
                notifyLocked(id, entry)
            }
            return true
        }
    }

    /**
     * T046 v2: reanuda un item FAILED con bytes y sink reabrible;
     * vuelve a QUEUED con token nuevo y sigue desde el byte que fallo.
     * Devuelve false fuera de ese contrato.
     */
    fun resume(id: Long): Boolean {
        synchronized(lock) {
            val entry = items[id] ?: return false
            if (entry.state != DownloadState.FAILED) return false
            if (entry.bytes <= 0L) return false
            if (entry.sink !is ReopenableSink) return false
            entry.cts = CancellationTokenSource(clock)
            entry.state = DownloadState.QUEUED
            entry.error = null
            notifyLocked(id, entry)
            queue.add(id)
            scheduleNextLocked()
            return true
        }
    }

    /**
     * T046 v3 (R0334, RES-0135): retira de la lista los items en
     * estado terminal (COMPLETED/CANCELLED/FAILED) y devuelve SUS
     * IDS en orden; los vivos (QUEUED/RUNNING) NUNCA se tocan:
     * retirar una descarga en marcha es cancelar, no barrer. Sin
     * listeners: el visor vuelve a listar y ya no los ve.
     */
    fun limpiarTerminadas(): List<Long> = synchronized(lock) {
        val fuera = ArrayList<Long>()
        for ((id, entry) in items) {
            if (isTerminal(entry.state)) fuera.add(id)
        }
        for (id in fuera) {
            items.remove(id)
        }
        fuera
    }

    /** Programa descargas mientras haya hueco (llamar con el lock). */
    private fun scheduleNextLocked() {
        while (running < maxConcurrent && queue.isNotEmpty()) {
            val id = queue.removeAt(0)
            val entry = items[id]
            if (entry == null || entry.state != DownloadState.QUEUED) continue
            running += 1
            executor.execute { runItem(id) }
        }
    }

    /**
     * T046 v2: corre un item con intentos. Cada intento abre el
     * transporte desde el byte actual (Range), escribe por chunks
     * consultando el token y reintenta los fallos transitorios hasta
     * agotar maxRetries; la cancelacion gana SIEMPRE al reintento.
     */
    private fun runItem(id: Long) {
        val entry: Entry
        synchronized(lock) {
            val e = items[id]
            if (e == null || e.state != DownloadState.QUEUED) {
                running -= 1
                scheduleNextLocked()
                return
            }
            e.state = DownloadState.RUNNING
            entry = e
            notifyLocked(id, e)
        }
        var attempt = 0
        var firstOpen = true
        var done = false
        while (!done) {
            val cancelReason = entry.cts.token.reason()
            if (cancelReason != null) {
                finish(id, entry, DownloadState.CANCELLED, "cancelada: " + cancelReason)
                break
            }
            val startAt = synchronized(lock) { entry.bytes }
            when (val opened = transport.open(entry.url, startAt, entry.cts.token)) {
                is DownloadOpen.Failed -> {
                    if (retryable(opened.kind) && attempt < maxRetries) {
                        attempt += 1
                        continue
                    }
                    finish(id, entry, DownloadState.FAILED, failureMessage(opened.kind))
                    done = true
                }
                is DownloadOpen.Opened -> {
                    synchronized(lock) {
                        if (opened.totalBytes >= 0) entry.total = opened.totalBytes
                    }
                    if (opened.startByte != startAt) {
                        // el servidor ignoro el Range (o salto raro):
                        // reiniciar desde cero truncando el destino.
                        val reabrible = entry.sink as? ReopenableSink
                        if (reabrible == null) {
                            finish(id, entry, DownloadState.FAILED, "reanudacion no soportada por el destino")
                            done = true
                        } else {
                            closeQuietly(entry)
                            entry.sink = reabrible.reopen(0L)
                            synchronized(lock) {
                                entry.bytes = 0L
                            }
                        }
                    } else if (startAt > 0L && firstOpen) {
                        // arranque de un item reanudado: el sink estaba
                        // cerrado desde el FAILED; reabrir en startAt.
                        val reabrible = entry.sink as? ReopenableSink
                        if (reabrible == null) {
                            finish(id, entry, DownloadState.FAILED, "reanudacion no soportada por el destino")
                            done = true
                        } else {
                            entry.sink = reabrible.reopen(startAt)
                        }
                    }
                    if (!done) {
                        firstOpen = false
                        val stream = opened.stream
                        var cancelled: String? = null
                        var ioFailed = false
                        try {
                            val buffer = ByteArray(chunkSize)
                            while (true) {
                                val reason = entry.cts.token.reason()
                                if (reason != null) {
                                    cancelled = "cancelada: " + reason
                                    break
                                }
                                val n = stream.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                entry.sink.write(buffer, n)
                                synchronized(lock) {
                                    entry.bytes += n
                                    notifyLocked(id, entry)
                                }
                            }
                        } catch (e: java.io.IOException) {
                            val reason = entry.cts.token.reason()
                            if (reason != null) cancelled = "cancelada: " + reason else ioFailed = true
                        } finally {
                            try {
                                stream.close()
                            } catch (e: java.io.IOException) {
                            }
                        }
                        val cancelledReason = cancelled
                        when {
                            cancelledReason != null -> {
                                finish(id, entry, DownloadState.CANCELLED, cancelledReason)
                                done = true
                            }
                            ioFailed -> {
                                if (attempt < maxRetries) {
                                    attempt += 1
                                    continue
                                }
                                finish(id, entry, DownloadState.FAILED, "io")
                                done = true
                            }
                            else -> {
                                finish(id, entry, DownloadState.COMPLETED, null)
                                done = true
                            }
                        }
                    }
                }
            }
        }
        synchronized(lock) {
            running -= 1
            scheduleNextLocked()
        }
    }

    /** Estado terminal + cierre del sink + aviso; irreversible. */
    private fun finish(id: Long, entry: Entry, state: DownloadState, error: String?) {
        synchronized(lock) {
            if (isTerminal(entry.state)) return
            entry.state = state
            entry.error = error
            closeQuietly(entry)
            notifyLocked(id, entry)
        }
    }

    private fun failureMessage(kind: TransportFailure): String = when (kind) {
        TransportFailure.TLS_INVALID -> "TLS invalido: conexion rechazada"
        TransportFailure.DNS -> "fallo de red: DNS"
        TransportFailure.NETWORK -> "fallo de red"
        TransportFailure.TOO_LARGE -> "respuesta demasiado grande"
    }
}
