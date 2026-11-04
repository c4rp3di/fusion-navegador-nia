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
 * Limitaciones v1: sin reanudacion (Range) ni reintentos, sin apertura
 * del archivo (v2), sin gestor de descargas persistente (T047 monta el
 * torrent sobre esta frontera) y una cancelacion sobre un estado
 * terminal se ignora (devuelve false).
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
    data class Opened(val stream: DownloadStream, val totalBytes: Long) : DownloadOpen()
    data class Failed(val kind: TransportFailure) : DownloadOpen()
}

interface DownloadTransport {
    fun open(url: String, token: nia.contracts.CancellationToken): DownloadOpen
}

/** Destino de los bytes; close se llama siempre al terminar. */
interface DownloadSink {
    fun write(bytes: ByteArray, length: Int)
    fun close()
}

class DownloadEngine(
    private val transport: DownloadTransport,
    private val executor: Executor = Executor { task -> task.run() },
    private val maxConcurrent: Int = 2,
    private val chunkSize: Int = 8192,
    private val clock: WallClock = SystemWallClock()
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent >= 1" }
        require(chunkSize >= 1) { "chunkSize >= 1" }
    }

    private class Entry(
        val url: String,
        val sink: DownloadSink,
        val cts: CancellationTokenSource,
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

    /** Corre un item: abre, lee por chunks consultando el token y termina. */
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
        when (val opened = transport.open(entry.url, entry.cts.token)) {
            is DownloadOpen.Failed ->
                finish(id, entry, DownloadState.FAILED, failureMessage(opened.kind))
            is DownloadOpen.Opened -> {
                synchronized(lock) {
                    if (opened.totalBytes >= 0) entry.total = opened.totalBytes
                }
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
                    cancelledReason != null -> finish(id, entry, DownloadState.CANCELLED, cancelledReason)
                    ioFailed -> finish(id, entry, DownloadState.FAILED, "io")
                    else -> finish(id, entry, DownloadState.COMPLETED, null)
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
    }
}
