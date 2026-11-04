package nia.engine.download

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nia.engine.net.TransportFailure

/**
 * T046 v1 (R0097, RES-0053): tests de la cola de descargas. Espejo del
 * gemelo JS validado offline (39 escenarios): descarga completa con
 * progreso, cola con maxConcurrent, cancelacion cooperativa (QUEUED,
 * RUNNING y durante la apertura) y fallos tipificados. El executor
 * capturado deja ver la cola sin hilos: nada corre hasta runOne().
 */
class DownloadEngineTest {

    private class FakeStream(val chunks: List<Int>) : DownloadStream {
        var index = 0
        var closed = false
        var onChunk: ((Int) -> Unit)? = null
        override fun read(buffer: ByteArray): Int {
            val hook = onChunk
            if (hook != null) hook(index)
            if (index < chunks.size) {
                val n = chunks[index]
                index += 1
                return n
            }
            return -1
        }
        override fun close() { closed = true }
    }

    private class FakeTransport(
        private val chunks: List<Int>,
        private val total: Long = chunks.sum().toLong(),
        private val failWith: TransportFailure? = null,
        var onOpen: (() -> Unit)? = null
    ) : DownloadTransport {
        var opened = 0
        var lastStream: FakeStream? = null
        override fun open(url: String, token: nia.contracts.CancellationToken): DownloadOpen {
            opened += 1
            val kind = failWith
            if (kind != null) return DownloadOpen.Failed(kind)
            val stream = FakeStream(chunks)
            lastStream = stream
            val hook = onOpen
            if (hook != null) hook()
            return DownloadOpen.Opened(stream, total)
        }
    }

    private class BrokenStream : DownloadStream {
        override fun read(buffer: ByteArray): Int { throw java.io.IOException("io") }
        override fun close() { }
    }

    private class FakeSink : DownloadSink {
        var bytes = 0
        var closed = false
        var writes = 0
        override fun write(bytes: ByteArray, length: Int) {
            this.bytes += length
            writes += 1
        }
        override fun close() { closed = true }
    }

    private class SyncExecutor : Executor {
        override fun execute(task: Runnable) { task.run() }
    }

    private class CapturingExecutor : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun runOne() { tasks.removeAt(0).run() }
    }

    private val syncExecutor = SyncExecutor()

    @Test
    fun descargaCompletaEscribeBytesYQuedaCompletada() {
        val transport = FakeTransport(listOf(100))
        val engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        val id = engine.enqueue("https://a.test/x", sink)
        assertEquals(100, sink.bytes)
        assertTrue(sink.closed)
        val item = engine.status(id)
        assertEquals(DownloadState.COMPLETED, item?.state)
        assertEquals(100, item?.totalBytes)
        assertEquals(100, item?.bytesDownloaded)
        assertNull(item?.lastError)
        assertEquals(1, transport.opened)
    }

    @Test
    fun multiChunkNotificaProgresoPorChunk() {
        val transport = FakeTransport(listOf(100, 200, 50))
        val engine = DownloadEngine(transport, syncExecutor)
        val notifs = ArrayList<DownloadItem>()
        engine.addListener { item -> notifs.add(item) }
        val sink = FakeSink()
        engine.enqueue("https://a.test/y", sink)
        assertEquals(350, sink.bytes)
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals("RUNNING:0", notifs[0].state.name + ":" + notifs[0].bytesDownloaded)
        assertEquals("COMPLETED:350", notifs[notifs.size - 1].state.name + ":" + notifs[notifs.size - 1].bytesDownloaded)
        assertEquals(5, notifs.size)
    }

    @Test
    fun totalDesconocidoQuedaEnMenosUno() {
        val transport = FakeTransport(listOf(10, 20), total = -1L)
        val engine = DownloadEngine(transport, syncExecutor)
        engine.enqueue("https://a.test/z", FakeSink())
        assertEquals(-1L, engine.status(1)?.totalBytes)
        assertEquals(30L, engine.status(1)?.bytesDownloaded)
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
    }

    @Test
    fun esquemaNoSoportadoFallaSinAbrirTransporte() {
        val transport = FakeTransport(listOf(1))
        val engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        val id = engine.enqueue("ftp://a.test/x", sink)
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertEquals("esquema no soportado", engine.status(id)?.lastError)
        assertEquals(0, transport.opened)
        assertEquals(0, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun cancelacionDuranteLecturaDejaElChunkEnVueloEscrito() {
        lateinit var engine: DownloadEngine
        val transport = FakeTransport(listOf(10, 10, 10))
        transport.onOpen = {
            val stream = transport.lastStream
            if (stream != null) {
                stream.onChunk = { i -> if (i == 1) engine.cancel(1) }
            }
        }
        engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/c", sink)
        assertEquals(DownloadState.CANCELLED, engine.status(1)?.state)
        assertEquals(20, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun cancelacionDuranteLaAperturaEscribeCeroBytes() {
        lateinit var engine: DownloadEngine
        val transport = FakeTransport(listOf(10, 10))
        transport.onOpen = { engine.cancel(1) }
        engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/o", sink)
        assertEquals(DownloadState.CANCELLED, engine.status(1)?.state)
        assertEquals(0, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun itemQueuedCanceladoPasaACancelledSinCorrer() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        val sinkA = FakeSink()
        val sinkB = FakeSink()
        engine.enqueue("https://a.test/1", sinkA)
        engine.enqueue("https://a.test/2", sinkB)
        assertEquals(1, exec.tasks.size)
        assertEquals(DownloadState.QUEUED, engine.status(2)?.state)
        assertTrue(engine.cancel(2))
        assertEquals(DownloadState.CANCELLED, engine.status(2)?.state)
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(0, sinkB.bytes)
        assertTrue(sinkB.closed)
        assertEquals(0, exec.tasks.size)
    }

    @Test
    fun colaRespetaMaxConcurrentConTresDescargas() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 2)
        engine.enqueue("https://a.test/1", FakeSink())
        engine.enqueue("https://a.test/2", FakeSink())
        engine.enqueue("https://a.test/3", FakeSink())
        assertEquals(2, exec.tasks.size)
        assertEquals(DownloadState.QUEUED, engine.status(3)?.state)
    }

    @Test
    fun alCompletarUnaDescargaArrancaLaSiguiente() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 2)
        engine.enqueue("https://a.test/1", FakeSink())
        engine.enqueue("https://a.test/2", FakeSink())
        engine.enqueue("https://a.test/3", FakeSink())
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(2, exec.tasks.size)
        exec.runOne()
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(2)?.state)
        assertEquals(DownloadState.COMPLETED, engine.status(3)?.state)
        assertEquals(0, exec.tasks.size)
    }

    @Test
    fun falloDNSDejaFailedConMensaje() {
        val engine = DownloadEngine(FakeTransport(listOf(5), failWith = TransportFailure.DNS), syncExecutor)
        val id = engine.enqueue("https://a.test/e", FakeSink())
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertEquals("fallo de red: DNS", engine.status(id)?.lastError)
    }

    @Test
    fun falloTLSInvalidoDejaFailedConMensajeSeguro() {
        val engine = DownloadEngine(FakeTransport(listOf(5), failWith = TransportFailure.TLS_INVALID), syncExecutor)
        val id = engine.enqueue("https://a.test/t", FakeSink())
        val item = engine.status(id)
        assertEquals(DownloadState.FAILED, item?.state)
        assertEquals("TLS invalido: conexion rechazada", item?.lastError)
        assertFalse("https://a.test/t" == item?.lastError)
    }

    @Test
    fun lecturaQueLanzaIOExceptionDejaFailed() {
        val transport = object : DownloadTransport {
            override fun open(url: String, token: nia.contracts.CancellationToken): DownloadOpen =
                DownloadOpen.Opened(BrokenStream(), 10L)
        }
        val engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        val id = engine.enqueue("https://a.test/io", sink)
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertEquals("io", engine.status(id)?.lastError)
        assertTrue(sink.closed)
    }

    @Test
    fun cancelDeIdInexistenteDevuelveFalse() {
        val engine = DownloadEngine(FakeTransport(listOf(5)), syncExecutor)
        engine.enqueue("https://a.test/n", FakeSink())
        assertFalse(engine.cancel(999))
    }

    @Test
    fun cancelDeItemTerminalDevuelveFalse() {
        val engine = DownloadEngine(FakeTransport(listOf(5)), syncExecutor)
        val id = engine.enqueue("https://a.test/d", FakeSink())
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
        assertFalse(engine.cancel(id))
    }

    @Test
    fun estadoRunningObservableMientrasCorre() {
        lateinit var engine: DownloadEngine
        var seen = ""
        val transport = FakeTransport(listOf(5))
        transport.onOpen = { seen = engine.status(1)?.state?.name ?: "none" }
        engine = DownloadEngine(transport, syncExecutor)
        engine.enqueue("https://a.test/r", FakeSink())
        assertEquals("RUNNING", seen)
    }

    @Test
    fun totalConocidoSeInformaDesdeLaApertura() {
        val engine = DownloadEngine(FakeTransport(listOf(40, 60)), syncExecutor)
        engine.enqueue("https://a.test/t2", FakeSink())
        assertEquals(100L, engine.status(1)?.totalBytes)
    }

    @Test
    fun idsMonotonicosEnOrdenDeInsercion() {
        val engine = DownloadEngine(FakeTransport(listOf(5)), syncExecutor)
        engine.enqueue("https://a.test/a", FakeSink())
        engine.enqueue("https://a.test/b", FakeSink())
        engine.enqueue("https://a.test/c", FakeSink())
        assertEquals("1,2,3", engine.list().map { it.id }.joinToString(","))
    }

    @Test
    fun readCeroBytesNoEscribeNiAborta() {
        val transport = FakeTransport(listOf(0, 5))
        val engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/z0", sink)
        assertEquals(5, sink.bytes)
        assertEquals(1, sink.writes)
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
    }

    @Test
    fun sinkCerradoAlFallar() {
        val engine = DownloadEngine(FakeTransport(listOf(5), failWith = TransportFailure.NETWORK), syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/f", sink)
        assertTrue(sink.closed)
        assertEquals(DownloadState.FAILED, engine.status(1)?.state)
    }

    @Test
    fun sinkCerradoAlCancelarEnVuelo() {
        lateinit var engine: DownloadEngine
        val transport = FakeTransport(listOf(10, 10))
        transport.onOpen = {
            val stream = transport.lastStream
            if (stream != null) {
                stream.onChunk = { i -> if (i == 0) engine.cancel(1) }
            }
        }
        engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/cc", sink)
        assertEquals(DownloadState.CANCELLED, engine.status(1)?.state)
        assertEquals(10, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun cancelacionDeQueuedCierraElSink() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        val sinkB = FakeSink()
        engine.enqueue("https://a.test/q1", FakeSink())
        engine.enqueue("https://a.test/q2", sinkB)
        engine.cancel(2)
        assertEquals(DownloadState.CANCELLED, engine.status(2)?.state)
        assertTrue(sinkB.closed)
    }

    @Test
    fun maxConcurrentInvalidoRechaza() {
        assertFailsWith<IllegalArgumentException> {
            DownloadEngine(FakeTransport(listOf(5)), syncExecutor, maxConcurrent = 0)
        }
    }

    @Test
    fun chunkSizeInvalidoRechaza() {
        assertFailsWith<IllegalArgumentException> {
            DownloadEngine(FakeTransport(listOf(5)), syncExecutor, chunkSize = 0)
        }
    }

    @Test
    fun listaConEstadosMixtos() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        engine.enqueue("https://a.test/m1", FakeSink())
        engine.enqueue("https://a.test/m2", FakeSink())
        engine.cancel(2)
        exec.runOne()
        val estados = engine.list().map { it.state }.toSet()
        assertEquals(setOf(DownloadState.COMPLETED, DownloadState.CANCELLED), estados)
    }

    @Test
    fun cancelDevuelveTrueEnVivo() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 2)
        val id1 = engine.enqueue("https://a.test/v1", FakeSink())
        val id2 = engine.enqueue("https://a.test/v2", FakeSink())
        assertTrue(engine.cancel(id1))
        assertTrue(engine.cancel(id2))
        assertTrue(engine.list().all { it.state == DownloadState.CANCELLED })
    }

    @Test
    fun cancelacionDeRunningArrancaLaSiguienteEncolada() {
        lateinit var engine: DownloadEngine
        val transport = FakeTransport(listOf(10, 10, 10))
        transport.onOpen = {
            val stream = transport.lastStream
            if (stream != null) {
                stream.onChunk = { i -> if (i == 1) engine.cancel(1) }
            }
        }
        engine = DownloadEngine(transport, syncExecutor, maxConcurrent = 1)
        val sinkA = FakeSink()
        val sinkB = FakeSink()
        engine.enqueue("https://a.test/s1", sinkA)
        engine.enqueue("https://a.test/s2", sinkB)
        assertEquals(DownloadState.CANCELLED, engine.status(1)?.state)
        assertEquals(DownloadState.COMPLETED, engine.status(2)?.state)
        assertEquals(20, sinkA.bytes)
        assertEquals(30, sinkB.bytes)
    }
}
