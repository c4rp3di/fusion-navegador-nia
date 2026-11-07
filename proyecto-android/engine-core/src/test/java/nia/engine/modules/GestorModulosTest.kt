package nia.engine.modules

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline para el gestor (T094
 * v1, R0226, RES-0106, ADR-005): estados del gestor, alcance
 * global/sitio del host, contadores de actividad, fail-open ante un
 * hook roto y persistencia del alcance cuyo archivo corrupto se
 * ignora. Con el registro y el runtime reales (T009/T029/T030).
 */
class GestorModulosTest {

    private fun <T> ok(o: Outcome<T>): T {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as T
    }

    private fun nuevo(almacen: File? = null): GestorModulos {
        val registry = ModuleRegistry()
        return GestorModulos(registry, PluginRuntime(registry), almacen)
    }

    private fun par(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun spki(kp: KeyPair): String =
        Base64.getEncoder().encodeToString(kp.public.encoded)

    private fun sha256(d: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(d)

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private fun huella(kp: KeyPair): String = hex(sha256(kp.public.encoded))

    /** Canonico niamod/1 INDEPENDIENTE del de Niamod (oraculo X08). */
    private fun canonico(manifest: ByteArray, archivos: List<Pair<String, ByteArray>>): ByteArray {
        val sb = StringBuilder()
        sb.append("niamod/1\n")
        sb.append("manifest-sha256=").append(hex(sha256(manifest))).append('\n')
        for ((n, d) in archivos.sortedBy { it.first }) {
            sb.append("file=").append(n).append(" sha256=").append(hex(sha256(d))).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun firmar(priv: PrivateKey, datos: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(priv)
            update(datos)
            sign()
        }

    private fun zip(entradas: List<Pair<String, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            for ((n, d) in entradas) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun manifiestoNiamod(author: String, permisos: String, next: String = ""): String {
        val sb = StringBuilder()
        sb.append("id = demo.niamod\n")
        sb.append("version = 2\n")
        sb.append("entry = entry.js\n")
        if (permisos.isNotEmpty()) {
            sb.append("permissions = ").append(permisos).append('\n')
        }
        sb.append("name = Demo niamod\n")
        sb.append("category = background\n")
        sb.append("capabilities = observar\n")
        sb.append("api-version = 1\n")
        sb.append("author-key = ").append(author).append('\n')
        if (next.isNotEmpty()) sb.append("next-key = ").append(next).append('\n')
        return sb.toString()
    }

    private fun paqueteNiamod(manifest: String, kp: KeyPair): ByteArray {
        val m = manifest.toByteArray(Charsets.UTF_8)
        val e = ("nia.onStartup(function (url) { return \"hola\"; });\n" +
            "nia.onNavigate(function (url) { return \"hola\"; });\n").toByteArray(Charsets.UTF_8)
        val can = canonico(m, listOf("entry.js" to e))
        val firma = firmar(kp.private, can)
        return zip(listOf("manifest.txt" to m, "entry.js" to e, "firma.bin" to firma))
    }

    @Test
    fun instalarDemoActivaYFichaVisible() {
        val g = nuevo()
        ok(g.instalarDemo())
        val fichas = g.fichas()
        assertEquals(1, fichas.size)
        val f = fichas[0]
        assertEquals("demo.observador", f.id)
        assertEquals(EstadoModulo.ACTIVO, f.estado)
        assertEquals(1, f.hooksStartup)
        assertEquals(1, f.hooksNavigate)
        assertEquals(0, f.hooksRequest)
        assertTrue(f.alcanceGlobal)
    }

    @Test
    fun fichasMuestranMetadatosV2() {
        val g = nuevo()
        ok(g.instalarDemo())
        val f = g.ficha("demo.observador")!!
        assertEquals("Observador demo", f.name)
        assertEquals(2, f.version)
        assertTrue(f.categories.contains("background"))
        assertTrue(f.capabilities.contains("observar"))
        assertTrue(f.permissions.isEmpty())
    }

    @Test
    fun dispararNavegarCuentaYVeredicto() {
        val g = nuevo()
        ok(g.instalarDemo())
        val res = g.dispararNavegar("https://ejemplo.test:443", "https://ejemplo.test:443/a")
        assertEquals(1, res.disparados)
        assertEquals(0, res.errores)
        val f = g.ficha("demo.observador")!!
        assertEquals(1, f.disparos)
        assertEquals(0, f.errores)
        assertEquals("observa", f.ultimoVeredicto)
    }

    @Test
    fun alcanceGlobalDisparaEnCualquierOrigen() {
        val g = nuevo()
        ok(g.instalarDemo())
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
        assertEquals(1, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
    }

    @Test
    fun alcanceSoloSitioFiltra() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.soloEnEsteSitio("demo.observador", "https://a.test:443"))
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
        assertEquals(0, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
        val f = g.ficha("demo.observador")!!
        assertTrue(!f.alcanceGlobal)
        assertEquals(listOf("https://a.test:443"), f.alcanceOrigenes)
    }

    @Test
    fun hacerGlobalRestaura() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.soloEnEsteSitio("demo.observador", "https://a.test:443"))
        ok(g.hacerGlobal("demo.observador"))
        assertEquals(1, g.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
    }

    @Test
    fun desactivarDetieneLosDisparos() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desactivar("demo.observador"))
        assertEquals(EstadoModulo.DESACTIVADO, g.ficha("demo.observador")!!.estado)
        assertEquals(0, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun reactivarVuelveADisparar() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desactivar("demo.observador"))
        ok(g.activar("demo.observador"))
        assertEquals(1, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun hookRotoCuentaErrorYNoRompe() {
        val g = nuevo()
        ok(
            g.instalar(
                "id = demo.roto\nversion = 2\nentry = roto.js\ncapabilities = observar\n",
                "nia.onNavigate(function (url) { throw new Error(\"roto\"); });\n"
            )
        )
        val res = g.dispararNavegar("https://a.test:443", "https://a.test:443")
        assertEquals(1, res.disparados)
        assertEquals(1, res.errores)
        val f = g.ficha("demo.roto")!!
        assertEquals(EstadoModulo.ERROR, f.estado)
        assertEquals(1, f.errores)
        assertEquals(1, f.disparos)
    }

    @Test
    fun desinstalarRetiraTodo() {
        val g = nuevo()
        ok(g.instalarDemo())
        ok(g.desinstalar("demo.observador"))
        assertEquals(0, g.fichas().size)
        assertTrue(g.desinstalar("demo.observador") is Outcome.Failure)
        assertEquals(0, g.dispararNavegar("https://a.test:443", "https://a.test:443").disparados)
    }

    @Test
    fun activarSinEntryConocidaFalla() {
        val g = nuevo()
        assertTrue(g.activar("nada.modulo") is Outcome.Failure)
        assertEquals(ErrorCode.INVALID_INPUT, (g.activar("nada.modulo") as Outcome.Failure).error.code)
    }

    @Test
    fun soloEnSitioDeNoInstaladoFalla() {
        val g = nuevo()
        assertTrue(g.soloEnEsteSitio("nada.modulo", "https://a.test:443") is Outcome.Failure)
    }

    @Test
    fun alcancePersisteEntreInstancias() {
        val dir = Files.createTempDirectory("gestor-test").toFile()
        try {
            val almacen = File(dir, "modulos.tsv")
            val g1 = nuevo(almacen)
            ok(g1.instalarDemo())
            ok(g1.soloEnEsteSitio("demo.observador", "https://a.test:443"))
            val g2 = nuevo(almacen)
            ok(g2.instalarDemo())
            val f = g2.ficha("demo.observador")!!
            assertTrue(!f.alcanceGlobal)
            assertEquals(listOf("https://a.test:443"), f.alcanceOrigenes)
            assertEquals(0, g2.dispararNavegar("https://b.test:443", "https://b.test:443").disparados)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun archivoCorruptoSeIgnora() {
        val dir = Files.createTempDirectory("gestor-test").toFile()
        try {
            val almacen = File(dir, "modulos.tsv")
            almacen.writeText("basura sin tab\n\ndemo.observador\tQUE\n")
            val g = nuevo(almacen)
            ok(g.instalarDemo())
            val f = g.ficha("demo.observador")!!
            assertTrue(f.alcanceGlobal)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun paqueteFirmadoInstalaYActiva() {
        val dir = Files.createTempDirectory("gestor-niamod").toFile()
        try {
            val registry = ModuleRegistry()
            val g = GestorModulos(registry, PluginRuntime(registry), null, File(dir, "claves.tsv"))
            val kp = par()
            val bytes = paqueteNiamod(manifiestoNiamod(spki(kp), "storage.read"), kp)
            val previa = ok(g.previsualizarPaquete(bytes))
            assertEquals(EstadoClave.NUEVA_AUTORIA, previa.estadoClave)
            assertEquals("demo.niamod", previa.id)
            ok(g.instalarPaquete(bytes, AprobacionPaquete(previa.huellaClave, true)))
            val f = g.ficha("demo.niamod")!!
            assertEquals(EstadoModulo.ACTIVO, f.estado)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anclaSinAprobacionRechazada() {
        val registry = ModuleRegistry()
        val g = GestorModulos(registry, PluginRuntime(registry))
        val kp = par()
        val bytes = paqueteNiamod(manifiestoNiamod(spki(kp), "storage.read"), kp)
        assertTrue(
            g.instalarPaquete(bytes, AprobacionPaquete("no-la-vio", true)) is Outcome.Failure
        )
    }

    @Test
    fun permisosNuevosRequierenReconfirmacion() {
        val g = nuevo()
        val kp = par()
        val v1 = paqueteNiamod(manifiestoNiamod(spki(kp), "storage.read"), kp)
        ok(g.instalarPaquete(v1, AprobacionPaquete(huella(kp), true)))
        val v2 = paqueteNiamod(
            manifiestoNiamod(spki(kp), "storage.read, network.fetch"), kp
        )
        val previa = ok(g.previsualizarPaquete(v2))
        assertTrue(previa.esUpdate)
        assertEquals(listOf("network.fetch"), previa.permisosNuevos)
        assertTrue(
            g.instalarPaquete(v2, AprobacionPaquete(previa.huellaClave, false)) is Outcome.Failure
        )
        ok(g.instalarPaquete(v2, AprobacionPaquete(previa.huellaClave, true)))
        val f = g.ficha("demo.niamod")!!
        assertTrue(f.permissions.contains("network.fetch"))
        assertEquals(EstadoModulo.ACTIVO, f.estado)
    }

    @Test
    fun rotacionUnPasoReancla() {
        val g = nuevo()
        val a = par()
        val b = par()
        val c = par()
        val v1 = paqueteNiamod(manifiestoNiamod(spki(a), "storage.read", next = spki(b)), a)
        ok(g.instalarPaquete(v1, AprobacionPaquete(huella(a), true)))
        // firma ajena sin pendiente: rechazada
        val ajena = paqueteNiamod(manifiestoNiamod(spki(c), "storage.read"), c)
        assertTrue(
            g.instalarPaquete(ajena, AprobacionPaquete(huella(a), true)) is Outcome.Failure
        )
        // el paquete siguiente firmado por la clave rotada instala y re-ancla
        val v2 = paqueteNiamod(manifiestoNiamod(spki(b), "storage.read"), b)
        ok(g.instalarPaquete(v2, AprobacionPaquete(huella(a), true)))
        val previa3 = ok(g.previsualizarPaquete(v1))
        assertEquals(EstadoClave.CAMBIO_MANUAL, previa3.estadoClave)
    }

    @Test
    fun claveDistintaExigeReaprobacionManual() {
        val g = nuevo()
        val a = par()
        val b = par()
        ok(
            g.instalarPaquete(
                paqueteNiamod(manifiestoNiamod(spki(a), "storage.read"), a),
                AprobacionPaquete(huella(a), true)
            )
        )
        val ajena = paqueteNiamod(manifiestoNiamod(spki(b), "storage.read"), b)
        // la aprobacion que cita la huella VIEJA no sirve
        assertTrue(
            g.instalarPaquete(ajena, AprobacionPaquete(huella(a), true)) is Outcome.Failure
        )
        // la reaprobacion manual citando la huella NUEVA re-ancla
        val previa = ok(g.previsualizarPaquete(ajena))
        assertEquals(EstadoClave.CAMBIO_MANUAL, previa.estadoClave)
        ok(g.instalarPaquete(ajena, AprobacionPaquete(previa.huellaClave, true)))
    }

    @Test
    fun anclaPersisteEntreInstancias() {
        val dir = Files.createTempDirectory("gestor-claves").toFile()
        try {
            val claves = File(dir, "claves.tsv")
            val kp = par()
            val bytes = paqueteNiamod(manifiestoNiamod(spki(kp), "storage.read"), kp)
            val r1 = ModuleRegistry()
            val g1 = GestorModulos(r1, PluginRuntime(r1), null, claves)
            ok(g1.instalarPaquete(bytes, AprobacionPaquete(huella(kp), true)))
            val r2 = ModuleRegistry()
            val g2 = GestorModulos(r2, PluginRuntime(r2), null, claves)
            val previa = ok(g2.previsualizarPaquete(bytes))
            assertEquals(EstadoClave.MISMA_CLAVE, previa.estadoClave)
        } finally {
            dir.deleteRecursively()
        }
    }
}
