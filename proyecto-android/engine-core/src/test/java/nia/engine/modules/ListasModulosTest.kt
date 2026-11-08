package nia.engine.modules

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.CancellationToken
import nia.contracts.Outcome
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportFailure
import nia.engine.net.TransportResult
import nia.engine.profile.ConfigStore

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas identicas,
 * huella 26.26.d0808ed7): ListasModulos de C7+C8 v1 (R0255, RES-0114)
 * contra disco y transporte REAL de los tests: registro con limites,
 * TSV write-through con lineas corruptas ignoradas, solo https sin
 * credenciales, la denegacion por cuota DISTINGUIBLE del error de red
 * y el planificador con estado en la config del perfil (C9).
 */
class ListasModulosTest {

    private class FakeTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        val rutas = HashMap<String, FetchResponse>()
        var fallaRed = false

        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            if (fallaRed) {
                return TransportResult.Failure(TransportFailure.NETWORK)
            }
            val respuesta = rutas[request.url]
            return if (respuesta == null) {
                TransportResult.Failure(TransportFailure.NETWORK)
            } else {
                TransportResult.Success(respuesta)
            }
        }
    }

    private fun respuesta(estado: Int, cuerpo: String): FetchResponse =
        FetchResponse(estado, emptyMap(), cuerpo.toByteArray())

    private var secuencia = 0

    private fun dir(): File {
        secuencia += 1
        val d = File(File(System.getProperty("java.io.tmpdir"), "nia-listas-test"), "caso-" + secuencia)
        d.deleteRecursively()
        d.mkdirs()
        return d
    }

    @Test
    fun registrarYListarFuentes() {
        val l = ListasModulos(dir())
        assertTrue(l.registrar("demo.uno", "https://host.test/lista.txt") is Outcome.Success)
        val fuentes = l.fuentes("demo.uno")
        assertEquals(1, fuentes.size)
        assertEquals("https://host.test/lista.txt", fuentes[0].url)
        assertEquals(ListasModulos.INTERVALO_DEFECTO_HORAS, fuentes[0].intervaloHoras)
    }

    @Test
    fun rechazaListasInsegurasOFueraDeRegla() {
        val l = ListasModulos(null)
        for (url in listOf(
            "http://host.test/lista.txt",
            "https://usuario:clave@host.test/lista.txt",
            "host.test/lista.txt",
            "https://host.test/lista con espacio",
            "https://" + "x".repeat(600),
            "https://"
        )) {
            assertTrue("url rechazada: " + url, l.registrar("demo.uno", url) is Outcome.Failure)
        }
        assertTrue(l.fuentes("demo.uno").isEmpty())
    }

    @Test
    fun rechazaIntervalosFueraDeRango() {
        val l = ListasModulos(null)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 0) is Outcome.Failure)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 721) is Outcome.Failure)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 1) is Outcome.Success)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 720) is Outcome.Success)
    }

    @Test
    fun reRegistrarActualizaElIntervalo() {
        val l = ListasModulos(null)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 48) is Outcome.Success)
        assertTrue(l.registrar("demo.uno", "https://host.test/a.txt", 72) is Outcome.Success)
        val fuentes = l.fuentes("demo.uno")
        assertEquals(1, fuentes.size)
        assertEquals(72, fuentes[0].intervaloHoras)
    }

    @Test
    fun limiteDeListasPorModulo() {
        val l = ListasModulos(null)
        for (n in 1..8) {
            assertTrue(l.registrar("demo.uno", "https://host.test/l" + n + ".txt") is Outcome.Success)
        }
        assertTrue("la novena lista se rechaza", l.registrar("demo.uno", "https://host.test/l9.txt") is Outcome.Failure)
        assertTrue(
            "actualizar una existente sigue permitido",
            l.registrar("demo.uno", "https://host.test/l1.txt", 48) is Outcome.Success
        )
    }

    @Test
    fun limiteDeModulosConListas() {
        val l = ListasModulos(null)
        for (n in 1..32) {
            assertTrue(l.registrar("demo." + n, "https://host.test/a.txt") is Outcome.Success)
        }
        assertTrue("el modulo 33 con listas se rechaza", l.registrar("demo.33", "https://host.test/a.txt") is Outcome.Failure)
    }

    @Test
    fun persisteEntreInstanciasSobreDisco() {
        val raiz = dir()
        val l1 = ListasModulos(raiz)
        assertTrue(l1.registrar("demo.uno", "https://host.test/a.txt", 48) is Outcome.Success)
        assertTrue(l1.registrar("demo.uno", "https://host.test/b.txt") is Outcome.Success)
        val l2 = ListasModulos(raiz)
        val fuentes = l2.fuentes("demo.uno")
        assertEquals(2, fuentes.size)
        assertEquals(48, fuentes[0].intervaloHoras)
        assertEquals(ListasModulos.INTERVALO_DEFECTO_HORAS, fuentes[1].intervaloHoras)
    }

    @Test
    fun lineasCorruptasSeIgnoranAlCargar() {
        val raiz = dir()
        val archivo = File(raiz, "listas.tsv")
        archivo.writeText(
            "demo.uno\thttps://host.test/ok.txt\t24\n" +
                "demo.dos\thttps://host.test/sin-intervalo\n" +
                "demo.tres\thttp://host.test/insegura.txt\t24\n" +
                "demo.cuatro\thttps://host.test/malo.txt\tveinticuatro\n" +
                "demo cinco\thttps://host.test/id-malo.txt\t24\n"
        )
        val l = ListasModulos(raiz)
        assertEquals("solo la linea valida sobrevive", 1, l.fuentes("demo.uno").size)
        assertTrue(l.fuentes("demo.dos").isEmpty())
        assertTrue(l.fuentes("demo.tres").isEmpty())
        assertTrue(l.fuentes("demo.cuatro").isEmpty())
    }

    @Test
    fun quitarCuentaYPersiste() {
        val raiz = dir()
        val l1 = ListasModulos(raiz)
        l1.registrar("demo.uno", "https://host.test/a.txt")
        l1.registrar("demo.uno", "https://host.test/b.txt")
        assertEquals(1, l1.quitar("demo.uno", "https://host.test/a.txt"))
        assertEquals(0, l1.quitar("demo.uno", "https://host.test/no-existe.txt"))
        val l2 = ListasModulos(raiz)
        assertEquals(1, l2.fuentes("demo.uno").size)
        assertEquals(1, l2.quitar("demo.uno", "https://host.test/b.txt"))
        assertEquals(0, l2.fuentes("demo.uno").size)
        assertEquals(
            "el tsv sin fuentes del modulo no lo revive",
            0,
            ListasModulos(raiz).fuentes("demo.uno").size
        )
    }

    @Test
    fun actualizarAhoraDescargaYEscribeEstado() {
        val raiz = dir()
        val l = ListasModulos(raiz)
        val config = ConfigStore(dir())
        val url = "https://host.test/lista.txt"
        assertTrue(l.registrar("demo.uno", url) is Outcome.Success)
        val t = FakeTransport()
        t.rutas[url] = respuesta(200, "doubleclick.net\n")
        val informe = l.actualizarAhora("demo.uno", t, config, 1000L)
        assertEquals(1, informe.ok.size)
        assertEquals("doubleclick.net\n".length, informe.ok[0].bytes)
        assertEquals(1, t.requests.size)
        assertEquals("GET", t.requests[0].method)
        val nombre = ListasModulos.nombreDe(url)
        assertEquals("1000", config.leer("demo.uno", "listas." + nombre + ".actualizada"))
        assertEquals("16", config.leer("demo.uno", "listas." + nombre + ".bytes"))
        val archivo = File(File(File(raiz, "contenido"), "demo.uno"), nombre + ".txt")
        assertTrue("el contenido vive junto al registro", archivo.exists())
        assertEquals("doubleclick.net\n", archivo.readText())
    }

    @Test
    fun estadoNo200EsFalloSinEstadoNiContenido() {
        val raiz = dir()
        val l = ListasModulos(raiz)
        val config = ConfigStore(dir())
        val url = "https://host.test/lista.txt"
        l.registrar("demo.uno", url)
        val t = FakeTransport()
        t.rutas[url] = respuesta(404, "nope")
        val informe = l.actualizarAhora("demo.uno", t, config, 1000L)
        assertEquals(1, informe.fallo.size)
        assertTrue(informe.fallo[0].motivo.contains("estado 404"))
        assertEquals(0, informe.ok.size)
        val nombre = ListasModulos.nombreDe(url)
        assertNull(config.leer("demo.uno", "listas." + nombre + ".actualizada"))
        assertFalse(File(File(File(raiz, "contenido"), "demo.uno"), nombre + ".txt").exists())
    }

    @Test
    fun listaSobreElTopeEsCuotaDistintaDelFallo() {
        val raiz = dir()
        val l = ListasModulos(raiz)
        val config = ConfigStore(dir())
        val url = "https://host.test/grande.txt"
        l.registrar("demo.uno", url)
        val t = FakeTransport()
        t.rutas[url] = respuesta(200, "x".repeat(ListasModulos.MAX_BYTES_LISTA + 1))
        val informe = l.actualizarAhora("demo.uno", t, config, 1000L)
        assertEquals("la denegacion por cuota se distingue del error", 1, informe.cuota.size)
        assertEquals(0, informe.fallo.size)
        assertEquals(0, informe.ok.size)
        val nombre = ListasModulos.nombreDe(url)
        assertNull(config.leer("demo.uno", "listas." + nombre + ".actualizada"))
        assertFalse(File(File(File(raiz, "contenido"), "demo.uno"), nombre + ".txt").exists())
    }

    @Test
    fun falloDeRedEsFalloYNoCuota() {
        val l = ListasModulos(null)
        l.registrar("demo.uno", "https://host.test/lista.txt")
        val t = FakeTransport()
        t.fallaRed = true
        val informe = l.actualizarAhora("demo.uno", t, null, 1000L)
        assertEquals(1, informe.fallo.size)
        assertEquals(0, informe.cuota.size)
        assertTrue(informe.fallo[0].motivo.contains("fallo de red"))
    }

    @Test
    fun soloVencidasRespetaElIntervalo() {
        val l = ListasModulos(null)
        val config = ConfigStore(null)
        val url = "https://host.test/lista.txt"
        l.registrar("demo.uno", url)
        val nombre = ListasModulos.nombreDe(url)
        val t = FakeTransport()
        t.rutas[url] = respuesta(200, "reglas\n")
        config.escribir("demo.uno", "listas." + nombre + ".actualizada", "999000")
        val fresco = l.actualizarVencidas("demo.uno", t, config, 1_000_000L)
        assertEquals("una lista fresca no se descarga", 0, fresco.resultados.size)
        assertEquals(0, t.requests.size)
        config.escribir("demo.uno", "listas." + nombre + ".actualizada", "1")
        val vencido = l.actualizarVencidas("demo.uno", t, config, 100_000_000L)
        assertEquals(1, vencido.ok.size)
        assertEquals(1, t.requests.size)
    }

    @Test
    fun haceFaltaActualizarNombraLasVencidas() {
        val l = ListasModulos(null)
        val config = ConfigStore(null)
        val url = "https://host.test/lista.txt"
        l.registrar("demo.uno", url)
        val nombre = ListasModulos.nombreDe(url)
        assertEquals(
            "sin estado previo hace falta",
            listOf(nombre),
            l.haceFaltaActualizar("demo.uno", 1000L, config)
        )
        config.escribir("demo.uno", "listas." + nombre + ".actualizada", "0")
        assertTrue(
            "fresca no hace falta",
            l.haceFaltaActualizar("demo.uno", 3_600_000L, config).isEmpty()
        )
        assertEquals(
            "vencida si hace falta",
            listOf(nombre),
            l.haceFaltaActualizar("demo.uno", 25L * 3_600_000L, config)
        )
    }

    @Test
    fun sinAlmacenDescargaSinEscribirEnDisco() {
        val l = ListasModulos(null)
        val config = ConfigStore(null)
        val url = "https://host.test/lista.txt"
        l.registrar("demo.uno", url)
        val t = FakeTransport()
        t.rutas[url] = respuesta(200, "reglas\n")
        val informe = l.actualizarAhora("demo.uno", t, config, 1000L)
        assertEquals(1, informe.ok.size)
        val nombre = ListasModulos.nombreDe(url)
        assertEquals("1000", config.leer("demo.uno", "listas." + nombre + ".actualizada"))
    }

    @Test
    fun sinFuentesElInformeEsVacio() {
        val l = ListasModulos(null)
        val informe = l.actualizarAhora("demo.inexistente", FakeTransport(), null, 1000L)
        assertTrue(informe.resultados.isEmpty())
        assertEquals("0 ok, 0 por cuota, 0 con fallo", informe.resumen())
    }

    @Test
    fun resumenCuentaPorTipo() {
        val l = ListasModulos(null)
        l.registrar("demo.uno", "https://host.test/ok.txt")
        l.registrar("demo.uno", "https://host.test/rota.txt")
        l.registrar("demo.uno", "https://host.test/falta.txt")
        l.registrar("demo.uno", "https://host.test/grande.txt")
        val t = FakeTransport()
        t.rutas["https://host.test/ok.txt"] = respuesta(200, "reglas\n")
        t.rutas["https://host.test/rota.txt"] = respuesta(500, "x")
        t.rutas["https://host.test/grande.txt"] = respuesta(200, "x".repeat(ListasModulos.MAX_BYTES_LISTA + 1))
        val informe = l.actualizarAhora("demo.uno", t, null, 1000L)
        assertEquals("1 ok, 1 por cuota, 2 con fallo", informe.resumen())
    }
}
