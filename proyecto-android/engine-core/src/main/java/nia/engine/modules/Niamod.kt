package nia.engine.modules

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

import nia.engine.js.JsParser

/**
 * T095 v1 (R0227, RES-0107, ADR-005 APROBADO en R0225): validacion
 * del contenedor .niamod con los limites ANTES del pre-parser, en el
 * orden del ADR: (1) SOLO con la central directory del ZIP, sin leer
 * contenidos: entradas <= 64, contenedor <= 4 MiB, entrada <= 2 MiB,
 * total descomprimido <= 8 MiB, ratio <= 100:1 (zipbomb), sin
 * symlinks, nombres relativos sin "..", sin duplicados, sin cifrado
 * y solo stored/deflate; (2) y (3) stream con topes: manifiesto (tope
 * 4096) y firma (tope 1024) sin parsear nada, hash SHA-256 y CRC de
 * cada archivo con lecturas ACOTADAS (nunca un byte[] grande antes
 * del tope); (4) canonicacion niamod/1 y firma ECDSA P-256
 * SHA256withECDSA (la clave llega en author-key del manifiesto, SPKI
 * base64; huella de la clave = sha256 del SPKI); (5) recien entonces
 * el parseo del manifiesto (T009), las versiones y la coherencia;
 * (5b) parseo de los .json de ui-model con el parser del host (C11 v2,
 * uimodel/1, R0260); (5c) extraccion de datos/ para el entry (T030 v2,
 * R0263, RES-0117: api-version 2 la lee con nia.datos.leer; topes 256
 * KiB por archivo y 1 MiB total, nombre relativo plano);
 * (5d) C3 (R0271, RES-0119): recursos de sustitucion de datos/ en
 * bytes crudos con su sha256 (tope 64 KiB, declarados en sustitutos);
 * (6) pre-parse del entry (JsParser de T029). Nada se parsea ni
 * evalua antes de 1-4 (fail-closed para la integridad, ADR-005: firma
 * mal, hash mal, limite mal o incoherencia = NO se instala).
 *
 * Exclusiones: sin zip64,
 * sin lista de bloqueo importable (ADR-005: futuro de api-version, no
 * prometida) y la central directory es la del formato clasico. Los
 * datos/ son para el ENTRY (T030 v2), no para el host: el pipeline del
 * matcher del host sigue llegando con ADR-018.
 */

/** Entrada de la central directory (solo metadatos, sin contenidos). */
data class EntradaZip(
    val nombre: String,
    val crc: Long,
    val comprimido: Long,
    val descomprimido: Long,
    val metodo: Int,
    val flags: Int,
    val extAttrs: Long
)

/** Recurso de sustitucion validado (C3 v1, R0271, RES-0119). */
data class RecursoSustituto(
    val nombre: String,
    val bytes: ByteArray,
    val sha256: String
)

/** Paquete .niamod validado por completo (T095, ADR-005). */
data class PaqueteNiamod(
    val manifiestoTexto: String,
    val manifest: ModuleManifest,
    val entryTexto: String,
    val huellaPaquete: String,
    val huellaClave: String,
    val nextHuella: String?,
    val uiModel: UiModelDeclarado,
    val archivosDatos: Int,
    val bytesDatos: Long,
    val datos: Map<String, String>,
    val sustitutos: Map<String, RecursoSustituto> = emptyMap()
)

object Niamod {

    const val MAX_ENTRADAS = 64
    const val MAX_CONTENEDOR = 4L * 1024 * 1024
    const val MAX_ENTRADA = 2L * 1024 * 1024
    const val MAX_TOTAL = 8L * 1024 * 1024
    const val MAX_RATIO = 100L
    const val MAX_FIRMA = 1024

    /** C11 v2 (R0260, RES-0116): tope de archivos .json de ui-model. */
    const val MAX_UI_ARCHIVOS = 8

    /** T030 v2 (R0263, RES-0117): tope de un archivo de datos/. */
    const val MAX_DATOS_ARCHIVO = 262_144

    /** T030 v2 (R0263, RES-0117): tope total acumulado de datos/. */
    const val MAX_DATOS_TOTAL = 1_048_576

    /** C3 v1 (R0271, RES-0119): tope de UN recurso de sustitucion. */
    const val MAX_SUSTITUTO = 65_536

    /**
     * Validacion completa del paquete en el ORDEN del ADR-005; el
     * resultado solo se usa cuando es Success (fail-closed).
     */
    fun inspeccionar(bytes: ByteArray): Outcome<PaqueteNiamod> {
        // (1) limites SOLO con la central directory, sin leer contenidos
        if (bytes.size > MAX_CONTENEDOR) return invalid("contenedor demasiado grande")
        val entradas = centralDirectory(bytes) ?: return invalid("zip sin central directory valida")
        val cdNombres = HashSet<String>()
        var totalDeclarado = 0L
        var uiVistos = 0
        var datosArchivos = 0
        var datosBytes = 0L
        for (e in entradas) {
            val razon = razonNombre(e.nombre)
            if (razon != null) return invalid("entrada " + razon)
            if (!cdNombres.add(e.nombre)) return invalid("entrada duplicada: " + e.nombre)
            if (e.metodo != 0 && e.metodo != 8) {
                return invalid("metodo de compresion no soportado")
            }
            if ((e.flags and 1) != 0) return invalid("entrada cifrada")
            if (((e.extAttrs shr 16) and 0xF000) == 0xA000L) {
                return invalid("symlink no permitido")
            }
            if (e.comprimido > MAX_ENTRADA || e.descomprimido > MAX_ENTRADA) {
                return invalid("entrada demasiado grande")
            }
            if (e.descomprimido > 0 && e.comprimido == 0L) {
                return invalid("ratio de zipbomb")
            }
            if (e.comprimido > 0 && e.descomprimido > e.comprimido * MAX_RATIO) {
                return invalid("ratio de zipbomb")
            }
            if (e.nombre.startsWith("ui-model/")) {
                uiVistos += 1
                if (uiVistos > MAX_UI_ARCHIVOS) {
                    return invalid("demasiados archivos ui-model")
                }
            }
            if (e.nombre.startsWith("datos/")) {
                val relativo = e.nombre.removePrefix("datos/")
                if (relativo.isEmpty() || relativo.contains("/") ||
                    !UiModelModulos.idValido(relativo)
                ) {
                    return invalid("nombre de dato invalido: " + e.nombre)
                }
                if (e.descomprimido > MAX_DATOS_ARCHIVO) {
                    return invalid("dato demasiado grande: " + e.nombre)
                }
                datosArchivos += 1
                datosBytes += e.descomprimido
                if (datosBytes > MAX_DATOS_TOTAL) {
                    return invalid("datos/ demasiado grande en total")
                }
            }
            totalDeclarado += e.descomprimido
            if (totalDeclarado > MAX_TOTAL) {
                return invalid("total descomprimido demasiado grande")
            }
        }
        if (!cdNombres.contains("manifest.txt")) {
            return invalid("paquete sin manifiesto")
        }
        if (!cdNombres.contains("firma.bin")) return invalid("paquete sin firma")

        // (2) y (3): stream con topes, hash y CRC por archivo
        val cdPorNombre = HashMap<String, EntradaZip>()
        for (e in entradas) cdPorNombre[e.nombre] = e
        val hashes = LinkedHashMap<String, String>()
        val capturados = HashMap<String, ByteArray>()
        var totalLeido = 0L
        val vistos = HashSet<String>()
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        var ent: ZipEntry? = zin.nextEntry
        while (ent != null) {
            val nombre = ent.name
            if (!cdNombres.contains(nombre)) {
                return invalid("entrada fuera de la central directory")
            }
            if (!vistos.add(nombre)) return invalid("entrada repetida en el stream")
            val sha = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val captura = if (
                nombre == "manifest.txt" ||
                nombre == "firma.bin" ||
                nombre.startsWith("ui-model/") ||
                nombre.startsWith("datos/")
            ) {
                ByteArrayOutputStream()
            } else {
                null
            }
            val buf = ByteArray(65536)
            var leidos = 0L
            while (true) {
                val n = zin.read(buf)
                if (n < 0) break
                leidos += n.toLong()
                if (leidos > MAX_ENTRADA) return invalid("entrada demasiado grande")
                totalLeido += n.toLong()
                if (totalLeido > MAX_TOTAL) {
                    return invalid("total descomprimido demasiado grande")
                }
                if (nombre == "manifest.txt" && leidos > ModuleSchema.MANIFEST_MAX_CHARS) {
                    return invalid("manifiesto demasiado grande")
                }
                if (nombre == "firma.bin" && leidos > MAX_FIRMA) {
                    return invalid("firma demasiado grande")
                }
                if (nombre.startsWith("ui-model/") && leidos > UiModelModulos.MAX_JSON_CHARS) {
                    return invalid("ui-model demasiado grande")
                }
                if (nombre.startsWith("datos/") && leidos > MAX_DATOS_ARCHIVO) {
                    return invalid("dato demasiado grande")
                }
                sha.update(buf, 0, n)
                crc.update(buf, 0, n)
                captura?.write(buf, 0, n)
            }
            val cd = cdPorNombre[nombre] ?: return invalid("entrada sin central directory")
            if (leidos != cd.descomprimido) {
                return invalid("tamano real no coincide con la central directory")
            }
            if (crc.value != cd.crc) return invalid("crc no coincide")
            hashes[nombre] = hex(sha.digest())
            if (captura != null) capturados[nombre] = captura.toByteArray()
            ent = zin.nextEntry
        }
        zin.close()
        if (vistos.size != cdNombres.size) {
            return invalid("faltan entradas de la central directory")
        }
        val manifestBytes = capturados["manifest.txt"] ?: return invalid("paquete sin manifiesto")
        val firma = capturados["firma.bin"] ?: return invalid("paquete sin firma")
        val manifiesto = String(manifestBytes, Charsets.UTF_8)
        if (manifiesto.length > ModuleSchema.MANIFEST_MAX_CHARS) {
            return invalid("manifiesto demasiado grande")
        }

        // (4) canonicacion niamod/1 y firma ECDSA P-256 (ADR-005)
        val sb = StringBuilder()
        sb.append("niamod/1\n")
        sb.append("manifest-sha256=").append(hashes["manifest.txt"]).append('\n')
        val archivos = ArrayList<String>()
        for (nombre in hashes.keys) {
            if (nombre != "manifest.txt" && nombre != "firma.bin") archivos.add(nombre)
        }
        archivos.sort()
        for (nombre in archivos) {
            sb.append("file=").append(nombre)
                .append(" sha256=").append(hashes[nombre]).append('\n')
        }
        val canonico = sb.toString().toByteArray(Charsets.UTF_8)
        val huellaPaquete = hex(MessageDigest.getInstance("SHA-256").digest(canonico))
        val authorB64 = valorClave(manifiesto, "author-key")
        if (authorB64 == null || authorB64.isEmpty()) {
            return invalid("paquete sin author-key (solo fixtures de validacion, ADR-005)")
        }
        val spkiAuthor = decodificar(authorB64) ?: return invalid("author-key no es base64 valida")
        val pub = clavePublica(spkiAuthor) ?: return invalid("author-key no es una clave EC valida")
        val huellaClave = hex(MessageDigest.getInstance("SHA-256").digest(spkiAuthor))
        var nextHuella: String? = null
        val nextB64 = valorClave(manifiesto, "next-key")
        if (nextB64 != null && nextB64.isNotEmpty()) {
            val spkiNext = decodificar(nextB64) ?: return invalid("next-key no es base64 valida")
            clavePublica(spkiNext) ?: return invalid("next-key no es una clave EC valida")
            nextHuella = hex(MessageDigest.getInstance("SHA-256").digest(spkiNext))
        }
        val verificado = try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(canonico)
            sig.verify(firma)
        } catch (_: Exception) {
            false
        }
        if (!verificado) {
            return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "firma del paquete no valida"))
        }

        // (5) recien ahora el parseo del manifiesto (T009) y las reglas
        val parseo = ModuleParser.parse(manifiesto)
        if (parseo is Outcome.Failure) return Outcome.failure(parseo.error)
        val manifest = (parseo as Outcome.Success).value as ModuleManifest
        for (nombre in hashes.keys) {
            if (nombre == "manifest.txt" || nombre == "firma.bin") continue
            if (nombre == manifest.entry) continue
            if (nombre.startsWith("datos/")) continue
            if (nombre.startsWith("ui-model/") && nombre.endsWith(".json")) continue
            return invalid("entrada no declarada: " + nombre)
        }

        // (5b) C11 v2: parseo del ui-model del paquete con el parser
        // JSON del host y fusion fail-closed: un archivo ilegible o
        // ids duplicados entre archivos rechazan el paquete ENTERO.
        val uiOrden = ArrayList<String>()
        for (nombre in hashes.keys) {
            if (nombre.startsWith("ui-model/")) uiOrden.add(nombre)
        }
        uiOrden.sort()
        val uiModelosPaquete = ArrayList<UiModelDeclarado>()
        for (nombre in uiOrden) {
            val capturado = capturados[nombre]
            if (capturado == null) return invalid("ui-model sin captura: " + nombre)
            when (val ui = UiModelModulos.parse(String(capturado, Charsets.UTF_8))) {
                is Outcome.Failure -> return Outcome.failure(ui.error)
                is Outcome.Success -> uiModelosPaquete.add(ui.value as UiModelDeclarado)
            }
        }
        val uiModel = when (val f = UiModelModulos.fusionar(uiModelosPaquete)) {
            is Outcome.Failure -> return Outcome.failure(f.error)
            is Outcome.Success -> f.value as UiModelDeclarado
        }

        // (5c) T030 v2 (R0263, RES-0117): los datos/ del paquete quedan
        // retenidos para que el entry api-version 2 los lea con
        // nia.datos.leer (ya validados por topes y nombre en la fase 1).
        val datosPaquete = LinkedHashMap<String, String>()
        for (nombre in hashes.keys) {
            if (!nombre.startsWith("datos/")) continue
            val capturado = capturados[nombre]
            if (capturado == null) return invalid("dato sin captura: " + nombre)
            datosPaquete[nombre.removePrefix("datos/")] = String(capturado, Charsets.UTF_8)
        }

        // (5d) C3 v1 (R0271, RES-0119): recursos de sustitucion
        // declarados en el manifiesto, retenidos de datos/ en BYTES
        // crudos (no el String del entry) con su sha256; ausente o
        // demasiado grande NO instala (fail-closed).
        val sustitutosPaquete = LinkedHashMap<String, RecursoSustituto>()
        for (nombre in manifest.sustitutos) {
            val crudo = capturados["datos/" + nombre]
            if (crudo == null) return invalid("sustituto ausente en datos/: " + nombre)
            if (crudo.size > MAX_SUSTITUTO) {
                return invalid("sustituto demasiado grande: " + nombre)
            }
            sustitutosPaquete[nombre] = RecursoSustituto(
                nombre, crudo, hex(MessageDigest.getInstance("SHA-256").digest(crudo))
            )
        }

        // (6) pre-parse del entry (JsParser de T029)
        val entryTexto = extraerEntry(bytes, manifest.entry)
            ?: return invalid("paquete sin entry declarada")
        val pre = JsParser.parse(entryTexto)
        if (pre.program.errors.isNotEmpty() || pre.lexErrors.isNotEmpty()) {
            return invalid("entry del paquete no compila")
        }
        return Outcome.success(
            PaqueteNiamod(
                manifiesto, manifest, entryTexto, huellaPaquete, huellaClave, nextHuella,
                uiModel, datosArchivos, datosBytes, datosPaquete, sustitutosPaquete
            )
        )
    }

    /** Nombre de entrada relativo y sin traversal (validEntry de T009
     *  extendida al arbol, ADR-005): la razon o null si es valido. */
    private fun razonNombre(nombre: String): String? {
        if (nombre.isEmpty()) return "vacia"
        if (nombre.startsWith("/")) return "absoluta"
        if (nombre.endsWith("/")) return "de directorio"
        for (seg in nombre.split('/')) {
            if (seg.isEmpty()) return "con segmento vacio"
            if (seg == "." || seg == "..") return "con traversal"
            for (c in seg) {
                val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c == '.' || c == '-' || c == '_'
                if (!ok) return "con caracteres no permitidos"
            }
        }
        return null
    }

    /** Central directory sin leer contenidos; null si el zip no es
     *  clasico valido (sin zip64, sin basura tras el EOCD). */
    private fun centralDirectory(bytes: ByteArray): List<EntradaZip>? {
        if (bytes.size < 22) return null
        var eocd = -1
        var i = bytes.size - 22
        val minimo = maxOf(0, bytes.size - 22 - 65535)
        while (i >= minimo) {
            if (u32(bytes, i) == 0x06054b50L) {
                eocd = i
                break
            }
            i -= 1
        }
        if (eocd < 0) return null
        val total = u16(bytes, eocd + 10)
        val cdTam = u32(bytes, eocd + 12)
        val cdOff = u32(bytes, eocd + 16)
        val comLen = u16(bytes, eocd + 20)
        if (eocd + 22 + comLen != bytes.size) return null
        if (total == 0xFFFF || cdTam == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) return null
        if (total == 0) return null
        if (total > MAX_ENTRADAS) return null
        if (cdOff + cdTam > eocd.toLong()) return null
        val lista = ArrayList<EntradaZip>(total)
        var p = cdOff.toInt()
        repeat(total) {
            if (p + 46 > eocd) return null
            if (u32(bytes, p) != 0x02014b50L) return null
            val flags = u16(bytes, p + 8)
            val metodo = u16(bytes, p + 10)
            val crc = u32(bytes, p + 16)
            val comprimido = u32(bytes, p + 20)
            val descomprimido = u32(bytes, p + 24)
            val nl = u16(bytes, p + 28)
            val el = u16(bytes, p + 30)
            val cl = u16(bytes, p + 32)
            val extAttrs = u32(bytes, p + 38)
            if (p + 46 + nl > eocd) return null
            val nombre = String(bytes, p + 46, nl, Charsets.UTF_8)
            p += 46 + nl + el + cl
            lista.add(EntradaZip(nombre, crc, comprimido, descomprimido, metodo, flags, extAttrs))
        }
        if (p != eocd) return null
        return lista
    }

    /** Extrae el entry declarado en un pase acotado o null. */
    private fun extraerEntry(bytes: ByteArray, entry: String): String? {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        var ent: ZipEntry? = zin.nextEntry
        while (ent != null) {
            if (ent.name == entry) {
                val captura = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                var leidos = 0L
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    leidos += n.toLong()
                    if (leidos > MAX_ENTRADA) {
                        zin.close()
                        return null
                    }
                    captura.write(buf, 0, n)
                }
                zin.close()
                return String(captura.toByteArray(), Charsets.UTF_8)
            }
            ent = zin.nextEntry
        }
        zin.close()
        return null
    }

    /** Valor de una clave del manifiesto sin parsearlo (para la firma
     *  del paso 4, antes del parseo del paso 5). */
    private fun valorClave(texto: String, clave: String): String? {
        for (raw in texto.lines()) {
            val linea = raw.trim()
            val eq = linea.indexOf('=')
            if (eq <= 0) continue
            val k = linea.substring(0, eq).trim()
            if (k == clave) return linea.substring(eq + 1).trim()
        }
        return null
    }

    private fun decodificar(b64: String): ByteArray? {
        if (b64.length > 1024) return null
        return try {
            val dec = Base64.getDecoder().decode(b64)
            if (dec.isEmpty() || dec.size > 512) null else dec
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun clavePublica(spki: ByteArray): PublicKey? {
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        } catch (_: Exception) {
            null
        }
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF)) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF)) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private fun invalid(safe: String): Outcome<PaqueteNiamod> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, safe))
}
