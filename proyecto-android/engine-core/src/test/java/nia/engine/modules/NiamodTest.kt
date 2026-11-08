package nia.engine.modules

import java.io.ByteArrayOutputStream
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
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline para el instalador
 * .niamod (T095 v1, R0227, RES-0107, ADR-005): limites ANTES del
 * pre-parser con la central directory, canonicacion niamod/1 con el
 * oraculo INDEPENDIENTE del implantado (X08), firma ECDSA P-256 con
 * claves de test generadas en el momento, fixtures maliciosos
 * (traversal, zipbomb de ratio, entrada gigante, duplicados,
 * symlink, entrada no declarada) rechazados sin agotar memoria,
 * firma alterada DENIED, y el paquete de prueba del ADR reproduce
 * los hashes del documento.
 */
class NiamodTest {

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

    private fun manifiesto(
        author: String,
        permisos: String = "storage.read",
        version: Int = 2,
        apiVersion: Int = 1,
        caps: String = "observar",
        next: String = "",
        extra: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("id = demo.niamod\n")
        sb.append("version = ").append(version).append('\n')
        sb.append("entry = entry.js\n")
        if (permisos.isNotEmpty()) {
            sb.append("permissions = ").append(permisos).append('\n')
        }
        sb.append("name = Demo niamod\n")
        sb.append("category = background\n")
        sb.append("capabilities = ").append(caps).append('\n')
        sb.append("api-version = ").append(apiVersion).append('\n')
        sb.append("author-key = ").append(author).append('\n')
        if (next.isNotEmpty()) sb.append("next-key = ").append(next).append('\n')
        sb.append(extra)
        return sb.toString()
    }

    private fun paquete(
        manifest: String,
        entry: String,
        kp: KeyPair,
        extras: List<Pair<String, ByteArray>> = emptyList(),
        firmante: PrivateKey = kp.private
    ): ByteArray {
        val m = manifest.toByteArray(Charsets.UTF_8)
        val e = entry.toByteArray(Charsets.UTF_8)
        val archivos = listOf("entry.js" to e) + extras
        val can = canonico(m, archivos)
        val firma = firmar(firmante, can)
        return zip(listOf("manifest.txt" to m) + archivos + listOf("firma.bin" to firma))
    }

    private fun codigo(): String =
        "nia.onStartup(function (url) { return \"hola\"; });\n" +
        "nia.onNavigate(function (url) { return \"hola\"; });\n"

    private fun fallo(o: Outcome<*>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    /** ZIP clasico de UNA entrada stored con atributos de symlink
     * (ZipOutputStream no puede escribirlos: binario a mano). */
    private fun zipSymlink(): ByteArray {
        val nombre = "mal.js".toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        fun w16(v: Int) {
            baos.write(v and 0xFF)
            baos.write((v shr 8) and 0xFF)
        }
        fun w32(v: Long) {
            baos.write((v and 0xFF).toInt())
            baos.write(((v shr 8) and 0xFF).toInt())
            baos.write(((v shr 16) and 0xFF).toInt())
            baos.write(((v shr 24) and 0xFF).toInt())
        }
        w32(0x04034b50L)
        w16(20); w16(0); w16(0); w16(0); w16(0)
        w32(0); w32(0); w32(0)
        w16(nombre.size); w16(0)
        baos.write(nombre)
        val cdOff = baos.size().toLong()
        w32(0x02014b50L)
        w16(20); w16(20); w16(0); w16(0); w16(0); w16(0)
        w32(0); w32(0); w32(0)
        w16(nombre.size); w16(0); w16(0); w16(0); w16(0)
        w32(0xA1FFL shl 16)
        w32(cdOff)
        val cdTam = baos.size().toLong() - cdOff
        w32(0x06054b50L)
        w16(0); w16(0); w16(1); w16(1)
        w32(cdTam)
        w32(cdOff)
        w16(0)
        return baos.toByteArray()
    }

    /** ZIP clasico de DOS entradas con el MISMO nombre: el duplicado
     *  vive en la central directory (ZipOutputStream lanza ZipException
     *  ante entradas duplicadas, asi que el fixture es binario a mano
     *  igual que el de symlink). */
    private fun zipDuplicados(): ByteArray {
        val nombre = "entry.js".toByteArray(Charsets.UTF_8)
        val contenido = "x".toByteArray(Charsets.UTF_8)
        val crc = java.util.zip.CRC32()
        crc.update(contenido)
        val crcV = crc.value
        val baos = ByteArrayOutputStream()
        val offsets = ArrayList<Long>()
        fun w16(v: Int) {
            baos.write(v and 0xFF)
            baos.write((v shr 8) and 0xFF)
        }
        fun w32(v: Long) {
            baos.write((v and 0xFF).toInt())
            baos.write(((v shr 8) and 0xFF).toInt())
            baos.write(((v shr 16) and 0xFF).toInt())
            baos.write(((v shr 24) and 0xFF).toInt())
        }
        fun local() {
            offsets.add(baos.size().toLong())
            w32(0x04034b50L)
            w16(20); w16(0); w16(0); w16(0); w16(0)
            w32(crcV); w32(contenido.size.toLong()); w32(contenido.size.toLong())
            w16(nombre.size); w16(0)
            baos.write(nombre)
            baos.write(contenido)
        }
        fun cd(off: Long) {
            w32(0x02014b50L)
            w16(20); w16(20); w16(0); w16(0); w16(0); w16(0)
            w32(crcV); w32(contenido.size.toLong()); w32(contenido.size.toLong())
            w16(nombre.size); w16(0); w16(0); w16(0); w16(0)
            w32(0); w32(off)
            baos.write(nombre)
        }
        local()
        local()
        val cdOff = baos.size().toLong()
        cd(offsets[0])
        cd(offsets[1])
        val cdTam = baos.size().toLong() - cdOff
        w32(0x06054b50L)
        w16(0); w16(0); w16(2); w16(2)
        w32(cdTam)
        w32(cdOff)
        w16(0)
        return baos.toByteArray()
    }

    @Test
    fun paqueteValidoInspecciona() {
        val kp = par()
        val bytes = paquete(manifiesto(spki(kp)), codigo(), kp)
        val r = Niamod.inspeccionar(bytes)
        assertTrue(r is Outcome.Success)
        val p = (r as Outcome.Success).value as PaqueteNiamod
        assertEquals("demo.niamod", p.manifest.id)
        assertEquals("Demo niamod", p.manifest.name)
        assertEquals(hex(sha256(kp.public.encoded)), p.huellaClave)
        assertEquals(64, p.huellaPaquete.length)
        assertTrue(p.nextHuella == null)
    }

    @Test
    fun fixtureDelADRReproduceHashes() {
        val m = ("id = demo.observador\nversion = 2\nentry = entry.js\n" +
            "permissions = storage.read\nname = Observador demo\n" +
            "description = Paquete de prueba del ADR-005: observa navegaciones sin bloquear nada\n" +
            "category = background\ncapabilities = observar\napi-version = 1\n")
            .toByteArray(Charsets.UTF_8)
        val e = ("// demo.observador (ADR-005): observa sin bloquear ni modificar.\n" +
            "nia.onStartup(function (url) { return \"startup: \" + url; });\n" +
            "nia.onNavigate(function (url) { return \"navigate: \" + url; });\n")
            .toByteArray(Charsets.UTF_8)
        val d = ("Dato de solo lectura del paquete de prueba demo.observador (ADR-005).\n" +
            "En api-version 1 el modulo NO puede leer este archivo: solo el host lo consume.\n")
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            "d3e964afa179398d6c366a8f8755ac23dd845e9b10d544a22a68b19a06967ad0",
            hex(sha256(m))
        )
        assertEquals(
            "d178576f5b657fe7fdec0e6e7aa9c95e1c290d692028aead5754a6582083ae48",
            hex(sha256(e))
        )
        assertEquals(
            "d26438487843783299ce7d15a0e5d0d9446d277b7876e03a1f1ad66e137c5d9d",
            hex(sha256(d))
        )
        val huella = hex(sha256(canonico(m, listOf("datos/ejemplo.txt" to d, "entry.js" to e))))
        assertEquals(
            "f365ee4632cc2209d668eba5fe212a2494e85b64e0f5fbdac7f98720eefb8529",
            huella
        )
        // sin author-key vale SOLO como fixture: el instalador lo rechaza
        val z = zip(listOf("manifest.txt" to m, "entry.js" to e, "datos/ejemplo.txt" to d))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun traversalRechazado() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val z = zip(listOf("manifest.txt" to m, "../mal.js" to "x".toByteArray()))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun duplicadosRechazados() {
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(zipDuplicados())).code)
    }

    @Test
    fun zipbombRatioRechazado() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val z = zip(listOf("manifest.txt" to m, "datos/z.txt" to ByteArray(1024 * 1024)))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun totalDescomprimidoRechazado() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val bloque = ByteArray(5 * 1024)
        java.util.Random(7).nextBytes(bloque)
        val contenido = ByteArray(300 * 1024)
        var pos = 0
        while (pos < contenido.size) {
            val n = minOf(bloque.size, contenido.size - pos)
            System.arraycopy(bloque, 0, contenido, pos, n)
            pos += n
        }
        val entradas = ArrayList<Pair<String, ByteArray>>()
        entradas.add("manifest.txt" to m)
        for (i in 0 until 30) {
            entradas.add("datos/f" + i + ".txt" to contenido)
        }
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(zip(entradas))).code)
    }

    @Test
    fun entradaGiganteRechazada() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val grande = ByteArray(2 * 1024 * 1024 + 1)
        java.util.Random(42).nextBytes(grande)
        val z = zip(listOf("manifest.txt" to m, "datos/r.bin" to grande))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun symlinkRechazado() {
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(zipSymlink())).code)
    }

    @Test
    fun firmaAlteradaFalla() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val e = codigo().toByteArray(Charsets.UTF_8)
        var firma = firmar(kp.private, canonico(m, listOf("entry.js" to e)))
        firma[firma.size - 1] = (firma[firma.size - 1].toInt() xor 1).toByte()
        val z = zip(listOf("manifest.txt" to m, "entry.js" to e, "firma.bin" to firma))
        assertEquals(ErrorCode.DENIED, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun hashAlteradoFalla() {
        val kp = par()
        val m = manifiesto(spki(kp)).toByteArray(Charsets.UTF_8)
        val e = codigo().toByteArray(Charsets.UTF_8)
        val firma = firmar(kp.private, canonico(m, listOf("entry.js" to e)))
        val eMal = (codigo() + "// colado\n").toByteArray(Charsets.UTF_8)
        val z = zip(listOf("manifest.txt" to m, "entry.js" to eMal, "firma.bin" to firma))
        assertEquals(ErrorCode.DENIED, fallo(Niamod.inspeccionar(z)).code)
    }

    @Test
    fun entradaNoDeclaradaRechazado() {
        val kp = par()
        val bytes = paquete(
            manifiesto(spki(kp)), codigo(), kp,
            listOf("suelto.js" to "x".toByteArray())
        )
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun versionFuturaRechazada() {
        val kp = par()
        val bytes = paquete(manifiesto(spki(kp), version = 3), codigo(), kp)
        assertEquals(ErrorCode.UNSUPPORTED, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun apiVersionTresRechazadaUnsupported() {
        val kp = par()
        val bytes = paquete(manifiesto(spki(kp), apiVersion = 3), codigo(), kp)
        assertEquals(ErrorCode.UNSUPPORTED, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun capabilitiesIncoherentesRechazadas() {
        val kp = par()
        val bytes = paquete(manifiesto(spki(kp), caps = "bloquear"), codigo(), kp)
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun entryInvalidoRechazado() {
        val kp = par()
        val bytes = paquete(manifiesto(spki(kp)), "esto no compila ((", kp)
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun paqueteConUiModelValidoDeclaraAjustes() {
        val kp = par()
        val ui = ("{\"ajustes\":[{\"id\":\"filtrar.subrecursos\"," +
            "\"titulo\":\"Filtrar subrecursos\",\"tipo\":\"bool\",\"defecto\":true}]," +
            "\"acciones\":[{\"id\":\"actualizar\",\"titulo\":\"Actualizar ahora\"}]," +
            "\"paneles\":[]}")
            .toByteArray(Charsets.UTF_8)
        val bytes = paquete(manifiesto(spki(kp)), codigo(), kp, listOf("ui-model/ajustes.json" to ui))
        val r = Niamod.inspeccionar(bytes)
        assertTrue(r is Outcome.Success)
        val p = (r as Outcome.Success).value as PaqueteNiamod
        assertEquals(1, p.uiModel.ajustes.size)
        assertEquals("Filtrar subrecursos", p.uiModel.ajustes[0].titulo)
        assertEquals(true, p.uiModel.ajustes[0].defecto)
        assertEquals(1, p.uiModel.acciones.size)
        assertTrue(p.uiModel.paneles.isEmpty())
    }

    @Test
    fun paqueteConUiModelRotoRechazado() {
        val kp = par()
        val ui = "{\"ajustes\":[{\"id\":\"x\",".toByteArray(Charsets.UTF_8)
        val bytes = paquete(manifiesto(spki(kp)), codigo(), kp, listOf("ui-model/ajustes.json" to ui))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun paqueteConDemasiadosUiModelRechazado() {
        val kp = par()
        val ui = "{}".toByteArray(Charsets.UTF_8)
        val extras = (1..9).map { n -> "ui-model/f$n.json" to ui }
        val bytes = paquete(manifiesto(spki(kp)), codigo(), kp, extras)
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun paqueteConDatosLosRetieneParaElEntry() {
        val kp = par()
        val dato = "dominios demo".toByteArray(Charsets.UTF_8)
        val bytes = paquete(
            manifiesto(spki(kp)), codigo(), kp,
            listOf("datos/lista.txt" to dato)
        )
        val r = Niamod.inspeccionar(bytes)
        assertTrue(r is Outcome.Success)
        val p = (r as Outcome.Success).value as PaqueteNiamod
        assertEquals("dominios demo", p.datos["lista.txt"])
        assertEquals(1, p.archivosDatos)
    }

    @Test
    fun paqueteConDatoDemasiadoGrandeRechazado() {
        val kp = par()
        val dato = ByteArray(300 * 1024)
        val bytes = paquete(
            manifiesto(spki(kp)), codigo(), kp,
            listOf("datos/grande.txt" to dato)
        )
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }

    @Test
    fun paqueteConDatoConNombreInvalidoRechazado() {
        val kp = par()
        val dato = "x".toByteArray(Charsets.UTF_8)
        val bytes = paquete(
            manifiesto(spki(kp)), codigo(), kp,
            listOf("datos/sub/dir.txt" to dato)
        )
        assertEquals(ErrorCode.INVALID_INPUT, fallo(Niamod.inspeccionar(bytes)).code)
    }
}
