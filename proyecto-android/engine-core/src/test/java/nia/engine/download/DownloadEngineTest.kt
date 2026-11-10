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
 *
 * T046 v2 (R0124, RES-0065): espejo del gemelo TS de reanudacion
 * Range (206 y servidor que ignora el Range), reintentos opt-in con
 * maxRetries (TLS nunca se reintenta, la cancelacion gana), resume
 * con su contrato (FAILED + bytes + sink reabrible) y el
 * comportamiento v1 por defecto sin reintentos.
 *
 * T046 v3 (R0334, RES-0135): limpiarTerminadas retira las
 * terminadas, NUNCA toca las vivas y no reutiliza ids.
 *
 * T046 v4 (R0345, RES-0140): pausa real - pausar de QUEUED no
 * corre, pausar de RUNNING termina PAUSED con bytes y sink
 * cerrado, resume sigue por Range desde el byte pausado y cancel
 * sobre PAUSED cancela de verdad.
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
        var onOpen: ((Long) -> Unit)? = null
    ) : DownloadTransport {
        var opened = 0
        var lastStream: FakeStream? = null
        var lastStartByte = 0L
        override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
            opened += 1
            lastStartByte = startByte
            val kind = failWith
            if (kind != null) return DownloadOpen.Failed(kind)
            val stream = FakeStream(chunks)
            lastStream = stream
            val hook = onOpen
            if (hook != null) hook(startByte)
            return DownloadOpen.Opened(stream, total, startByte)
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
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen =
                DownloadOpen.Opened(BrokenStream(), 10L, startByte)
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

    /** T046 v2: stream que rompe en la lectura rompeEn (indice 0): los
     * chunks anteriores quedan escritos y la falla es IOException. */
    private class StreamQueRompe(chunks: List<Int>, private val rompeEn: Int) : DownloadStream {
        private val base = FakeStream(chunks)
        override fun read(buffer: ByteArray): Int {
            if (base.index == rompeEn) throw java.io.IOException("io")
            return base.read(buffer)
        }
        override fun close() { base.close() }
    }

    /** T046 v2: registro compartido de los sinks reabribles de un item. */
    private class TallerDescargas {
        val reopens = ArrayList<Long>()
        val bytes = ArrayList<Int>()
    }

    /** T046 v2: sink reabrible de prueba: reopen registra el byte y
     * devuelve un sink NUEVO que escribe en el mismo registro. */
    private class FakeReopenSink(private val taller: TallerDescargas) : ReopenableSink {
        override fun write(bytes: ByteArray, length: Int) {
            taller.bytes.add(length)
        }
        override fun close() {
        }
        override fun reopen(startByte: Long): DownloadSink {
            taller.reopens.add(startByte)
            return FakeReopenSink(taller)
        }
    }

    @Test
    fun resumeReanudaDesdeElByteConRange() {
        val startBytes = ArrayList<Long>()
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                startBytes.add(startByte)
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(30)), 60L, 30L)
                }
            }
        }
        val taller = TallerDescargas()
        val engine = DownloadEngine(transport, syncExecutor)
        val id = engine.enqueue("https://a.test/res1", FakeReopenSink(taller))
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertEquals(30, taller.bytes.sum())
        assertTrue(engine.resume(id))
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
        assertEquals(60L, engine.status(id)?.bytesDownloaded)
        assertEquals(60L, engine.status(id)?.totalBytes)
        assertEquals("0,30", startBytes.joinToString(","))
        assertEquals("30", taller.reopens.joinToString(","))
    }

    @Test
    fun resumeReiniciaDesdeCeroSiElServidorIgnoraRange() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(10, 20, 30)), 60L, 0L)
                }
            }
        }
        val taller = TallerDescargas()
        val engine = DownloadEngine(transport, syncExecutor)
        val id = engine.enqueue("https://a.test/res2", FakeReopenSink(taller))
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertTrue(engine.resume(id))
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
        assertEquals(60L, engine.status(id)?.bytesDownloaded)
        assertEquals("0", taller.reopens.joinToString(","))
        assertEquals(90, taller.bytes.sum())
    }

    @Test
    fun resumeRechazaItemsFueraDelContrato() {
        val ok = DownloadEngine(FakeTransport(listOf(5)), syncExecutor)
        val idOk = ok.enqueue("https://a.test/c1", FakeSink())
        assertEquals(DownloadState.COMPLETED, ok.status(idOk)?.state)
        assertFalse(ok.resume(idOk))
        assertFalse(ok.resume(999))
        val dns = DownloadEngine(FakeTransport(listOf(5), failWith = TransportFailure.DNS), syncExecutor)
        val idDns = dns.enqueue("https://a.test/c2", FakeSink())
        assertEquals(DownloadState.FAILED, dns.status(idDns)?.state)
        assertFalse(dns.resume(idDns))
        val transport = object : DownloadTransport {
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen =
                DownloadOpen.Opened(StreamQueRompe(listOf(10, 20), 1), 20L, startByte)
        }
        val plano = DownloadEngine(transport, syncExecutor)
        val idPlano = plano.enqueue("https://a.test/c3", FakeSink())
        assertEquals(DownloadState.FAILED, plano.status(idPlano)?.state)
        assertEquals(10L, plano.status(idPlano)?.bytesDownloaded)
        assertFalse(plano.resume(idPlano))
    }

    @Test
    fun resumeNotificaQueuedYCorreHastaCompletar() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(30)), 60L, 30L)
                }
            }
        }
        val engine = DownloadEngine(transport, syncExecutor)
        val notifs = ArrayList<DownloadItem>()
        engine.addListener { item -> notifs.add(item) }
        val id = engine.enqueue("https://a.test/rn", FakeReopenSink(TallerDescargas()))
        assertEquals(DownloadState.FAILED, engine.status(id)?.state)
        assertTrue(engine.resume(id))
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
        assertEquals(
            "RUNNING:0,RUNNING:10,RUNNING:30,FAILED:30,QUEUED:30,RUNNING:30,RUNNING:60,COMPLETED:60",
            notifs.joinToString(",") { it.state.name + ":" + it.bytesDownloaded }
        )
    }

    @Test
    fun reintentoContinuaConElMismoSinkConRange() {
        val startBytes = ArrayList<Long>()
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                startBytes.add(startByte)
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(30)), 60L, 30L)
                }
            }
        }
        val taller = TallerDescargas()
        val engine = DownloadEngine(transport, syncExecutor, maxRetries = 1)
        engine.enqueue("https://a.test/re1", FakeReopenSink(taller))
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(60L, engine.status(1)?.bytesDownloaded)
        assertEquals("0,30", startBytes.joinToString(","))
        assertEquals("", taller.reopens.joinToString(","))
        assertEquals(60, taller.bytes.sum())
    }

    @Test
    fun reintentoConServidorQueIgnoraRangeTruncaDesdeCero() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(10, 20, 30)), 60L, 0L)
                }
            }
        }
        val taller = TallerDescargas()
        val engine = DownloadEngine(transport, syncExecutor, maxRetries = 1)
        engine.enqueue("https://a.test/re2", FakeReopenSink(taller))
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(60L, engine.status(1)?.bytesDownloaded)
        assertEquals("0", taller.reopens.joinToString(","))
        assertEquals(90, taller.bytes.sum())
    }

    @Test
    fun reintentosAgotadosTerminanFailed() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, startByte)
            }
        }
        val engine = DownloadEngine(transport, syncExecutor, maxRetries = 1)
        val sink = FakeSink()
        engine.enqueue("https://a.test/re3", sink)
        assertEquals(DownloadState.FAILED, engine.status(1)?.state)
        assertEquals("io", engine.status(1)?.lastError)
        assertEquals(2, transport.calls)
        assertEquals(60, sink.bytes)
        assertEquals(60L, engine.status(1)?.bytesDownloaded)
        assertTrue(sink.closed)
    }

    @Test
    fun tlsNoSeReintenta() {
        val transport = FakeTransport(listOf(5), failWith = TransportFailure.TLS_INVALID)
        val engine = DownloadEngine(transport, syncExecutor, maxRetries = 2)
        engine.enqueue("https://a.test/tls", FakeSink())
        assertEquals(DownloadState.FAILED, engine.status(1)?.state)
        assertEquals("TLS invalido: conexion rechazada", engine.status(1)?.lastError)
        assertEquals(1, transport.opened)
    }

    @Test
    fun cancelacionDuranteElReintentoGana() {
        lateinit var engine: DownloadEngine
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                if (calls == 2) engine.cancel(1)
                return if (calls == 1) {
                    DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(30)), 60L, 30L)
                }
            }
        }
        val sink = FakeSink()
        engine = DownloadEngine(transport, syncExecutor, maxRetries = 1)
        engine.enqueue("https://a.test/cr", sink)
        assertEquals(DownloadState.CANCELLED, engine.status(1)?.state)
        assertEquals(2, transport.calls)
        assertEquals(30, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun openFallidoSeReintentaHastaCompletar() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return when (calls) {
                    1 -> DownloadOpen.Failed(TransportFailure.NETWORK)
                    2 -> DownloadOpen.Failed(TransportFailure.DNS)
                    else -> DownloadOpen.Opened(FakeStream(listOf(50)), 50L, startByte)
                }
            }
        }
        val engine = DownloadEngine(transport, syncExecutor, maxRetries = 2)
        engine.enqueue("https://a.test/or", FakeSink())
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(50L, engine.status(1)?.bytesDownloaded)
        assertEquals(3, transport.calls)
    }

    @Test
    fun sinReintentosPorDefectoMantieneV1() {
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                return DownloadOpen.Opened(StreamQueRompe(listOf(10, 20, 30), 2), 60L, startByte)
            }
        }
        val engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/v1", sink)
        assertEquals(DownloadState.FAILED, engine.status(1)?.state)
        assertEquals("io", engine.status(1)?.lastError)
        assertEquals(1, transport.calls)
        assertEquals(30, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun limpiarRetiraTerminadasYDejaVivas() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        engine.enqueue("https://a.test/l1", FakeSink())
        engine.enqueue("https://a.test/l2", FakeSink())
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(DownloadState.QUEUED, engine.status(2)?.state)
        assertEquals("1", engine.limpiarTerminadas().joinToString(","))
        assertNull(engine.status(1))
        assertEquals(DownloadState.QUEUED, engine.status(2)?.state)
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(2)?.state)
    }

    @Test
    fun limpiarSinTerminalesDevuelveVaciaYNoTocaNada() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        engine.enqueue("https://a.test/lq", FakeSink())
        assertEquals("", engine.limpiarTerminadas().joinToString(","))
        assertEquals(DownloadState.QUEUED, engine.status(1)?.state)
        assertEquals(1, engine.list().size)
    }

    @Test
    fun limpiarNoReutilizaIds() {
        val engine = DownloadEngine(FakeTransport(listOf(5)), syncExecutor)
        engine.enqueue("https://a.test/lu1", FakeSink())
        engine.enqueue("https://a.test/lu2", FakeSink())
        assertEquals("1,2", engine.limpiarTerminadas().joinToString(","))
        assertEquals(0, engine.list().size)
        val id = engine.enqueue("https://a.test/lu3", FakeSink())
        assertEquals(3L, id)
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
    }

    @Test
    fun pausarRunningTerminaPausadaConBytesYSinkCerrado() {
        lateinit var engine: DownloadEngine
        val transport = FakeTransport(listOf(10, 10, 10))
        transport.onOpen = {
            val stream = transport.lastStream
            if (stream != null) {
                stream.onChunk = { i -> if (i == 1) engine.pausar(1) }
            }
        }
        engine = DownloadEngine(transport, syncExecutor)
        val sink = FakeSink()
        engine.enqueue("https://a.test/p1", sink)
        assertEquals(DownloadState.PAUSED, engine.status(1)?.state)
        assertEquals("pausada", engine.status(1)?.lastError)
        assertEquals(20, sink.bytes)
        assertTrue(sink.closed)
    }

    @Test
    fun pausarQueuedNoCorreYSobreviveAlBarridoYReanuda() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        val sinkB = FakeSink()
        engine.enqueue("https://a.test/p2a", FakeSink())
        engine.enqueue("https://a.test/p2b", sinkB)
        assertTrue(engine.pausar(2))
        assertEquals(DownloadState.PAUSED, engine.status(2)?.state)
        assertFalse(sinkB.closed)
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(1)?.state)
        assertEquals(DownloadState.PAUSED, engine.status(2)?.state)
        assertEquals(0, exec.tasks.size)
        assertEquals("1", engine.limpiarTerminadas().joinToString(","))
        assertEquals(DownloadState.PAUSED, engine.status(2)?.state)
        assertTrue(engine.resume(2))
        exec.runOne()
        assertEquals(DownloadState.COMPLETED, engine.status(2)?.state)
        assertEquals(5, sinkB.bytes)
        assertTrue(sinkB.closed)
    }

    @Test
    fun resumeDePausadaSiguePorRangeDesdeElByte() {
        lateinit var engine: DownloadEngine
        val startBytes = ArrayList<Long>()
        val transport = object : DownloadTransport {
            var calls = 0
            override fun open(url: String, startByte: Long, token: nia.contracts.CancellationToken): DownloadOpen {
                calls += 1
                startBytes.add(startByte)
                return if (calls == 1) {
                    val stream = FakeStream(listOf(10, 10, 10))
                    stream.onChunk = { i -> if (i == 1) engine.pausar(1) }
                    DownloadOpen.Opened(stream, 30L, 0L)
                } else {
                    DownloadOpen.Opened(FakeStream(listOf(10)), 30L, 20L)
                }
            }
        }
        val taller = TallerDescargas()
        engine = DownloadEngine(transport, syncExecutor)
        val id = engine.enqueue("https://a.test/p3", FakeReopenSink(taller))
        assertEquals(DownloadState.PAUSED, engine.status(id)?.state)
        assertEquals(20L, engine.status(id)?.bytesDownloaded)
        assertTrue(engine.resume(id))
        assertEquals(DownloadState.COMPLETED, engine.status(id)?.state)
        assertEquals(30L, engine.status(id)?.bytesDownloaded)
        assertEquals("0,20", startBytes.joinToString(","))
        assertEquals("20", taller.reopens.joinToString(","))
        assertEquals(30, taller.bytes.sum())
    }

    @Test
    fun cancelDePausadaLaCancelaDeVerdad() {
        val exec = CapturingExecutor()
        val engine = DownloadEngine(FakeTransport(listOf(5)), exec, maxConcurrent = 1)
        val sinkB = FakeSink()
        engine.enqueue("https://a.test/p4a", FakeSink())
        engine.enqueue("https://a.test/p4b", sinkB)
        assertTrue(engine.pausar(2))
        assertEquals(DownloadState.PAUSED, engine.status(2)?.state)
        assertTrue(engine.cancel(2))
        assertEquals(DownloadState.CANCELLED, engine.status(2)?.state)
        assertEquals("cancelada", engine.status(2)?.lastError)
        assertTrue(sinkB.closed)
    }

    @Test
    fun maxRetriesNegativoRechaza() {
        assertFailsWith<IllegalArgumentException> {
            DownloadEngine(FakeTransport(listOf(5)), syncExecutor, maxRetries = -1)
        }
    }
}
