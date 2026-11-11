package app.nia.navegador

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * T066 v1 (R0401, RES-0159): endpoint LAN temporal del casting
 * (ENMIENDA de ADR-020 aprobada por el usuario: una TV remota NO
 * puede bajar del loopback del telefono, asi que el servidor
 * escucha en la IPv4 de la LAN). Acotado como se aprobo: SOLO el
 * medio elegido y la pista elegida en rutas con token aleatorio
 * de sesion, SIN listados ni rutas arbitrarias, caducidad por
 * inactividad y cierre total al desconectar el cast. Las URLs
 * remotas JAMAS pasan por aqui: van directas al receptor.
 *
 * HTTP minimo con soporte Range (206) para que el seek de la TV
 * funcione; el medio file:// se sirve con RandomAccessFile y el
 * content:// (SAF) abre un FileDescriptor POR PETICION (seekable,
 * SIN copiar el archivo del usuario a cache).
 */
class ServidorLan {

    private var hilo: Thread? = null
    private var servidor: ServerSocket? = null
    private val token = nuevoToken()
    private var archivo: File? = null
    private var uriContenido: String? = null
    private var subs: String? = null
    private var resolver: ContentResolver? = null
    private var nombreMedio = "media"
    @Volatile private var detenido = false
    @Volatile private var ultimaPeticion = 0L

    /** Arranca el endpoint con el medio (archivo o uri SAF, uno de
     *  los dos), la pista WebVTT ya convertida y el nombre visible
     *  para el MIME; false honesto si no hay IPv4 de LAN o el
     *  puerto no abre (el shell avisa, JAMAS un exito fingido). */
    fun arrancar(
        archivoMedio: File?,
        uriMedio: String?,
        pistaVtt: String?,
        nombreVisible: String,
        resolverMedios: ContentResolver
    ): Boolean {
        archivo = archivoMedio
        uriContenido = uriMedio
        subs = pistaVtt
        resolver = resolverMedios
        nombreMedio = if (nombreVisible.isNotEmpty()) nombreVisible else "media"
        val ip = ipLanV4() ?: return false
        return try {
            val s = ServerSocket(0, 8, ip)
            servidor = s
            detenido = false
            ultimaPeticion = System.currentTimeMillis()
            hilo = Thread { atender(s) }
            hilo?.start()
            true
        } catch (e: Exception) {
            servidor = null
            false
        }
    }

    /** URL del medio para el receptor (solo tras arrancar OK). */
    fun urlMedio(): String = "http://" + direccion() + "/" + token + "/media"

    /** URL de la pista WebVTT (solo tras arrancar OK). */
    fun urlSubs(): String = "http://" + direccion() + "/" + token + "/subs.vtt"

    /** Cierra TODO (socket, hilo, referencias); idempotente. */
    fun detener() {
        detenido = true
        try {
            servidor?.close()
        } catch (e: Exception) {
        }
        hilo?.interrupt()
        hilo = null
        servidor = null
        archivo = null
        uriContenido = null
        subs = null
        resolver = null
    }

    private fun direccion(): String {
        val s = servidor ?: return "127.0.0.1:0"
        return s.inetAddress.hostAddress + ":" + s.localPort
    }

    /** Bucle de atencion: peticiones secuenciales del receptor con
     *  caducidad de CADUCIDAD_MS sin trafico; el hilo MUERE solo
     *  (nada residente, mismo criterio que R0137). */
    private fun atender(s: ServerSocket) {
        s.soTimeout = 2000
        while (!detenido) {
            if (System.currentTimeMillis() - ultimaPeticion > CADUCIDAD_MS) break
            val cliente = try {
                s.accept()
            } catch (e: Exception) {
                continue
            }
            try {
                cliente.soTimeout = 8000
                val pedido = leerPedido(cliente.getInputStream())
                if (pedido != null) servir(cliente, pedido)
            } catch (e: Exception) {
                // peticion rota o corte del receptor: se cierra y sigue
            } finally {
                try {
                    cliente.close()
                } catch (e: Exception) {
                }
            }
        }
        detenido = true
        try {
            s.close()
        } catch (e: Exception) {
        }
    }

    /** Lee la cabecera del GET hasta linea vacia (max 8 KiB, por
     *  los headers gordos de algunas teles). */
    private fun leerPedido(entrada: InputStream): Pedido? {
        val acumulado = ByteArrayOutputStream()
        val bufer = ByteArray(2048)
        while (acumulado.size() < 8192) {
            val n = entrada.read(bufer)
            if (n <= 0) break
            acumulado.write(bufer, 0, n)
            val texto = acumulado.toString("ISO-8859-1")
            if (texto.contains("\r\n\r\n") || texto.contains("\n\n")) {
                return Pedido.desde(texto)
            }
        }
        return null
    }

    /** Sirve SOLO las dos rutas con token valido; cualquier otra
     *  cosa (path traversal incluido) es 404. */
    private fun servir(cliente: Socket, pedido: Pedido) {
        ultimaPeticion = System.currentTimeMillis()
        val prefijo = "/" + token + "/"
        if (!pedido.ruta.startsWith(prefijo)) {
            responderNo(cliente)
            return
        }
        val que = pedido.ruta.substring(prefijo.length)
        if (que == "subs.vtt") {
            val pista = subs
            if (pista == null) {
                responderNo(cliente)
                return
            }
            responderBytes(cliente, 200, "OK", "text/vtt", pista.toByteArray(StandardCharsets.UTF_8))
            return
        }
        if (que != "media") {
            responderNo(cliente)
            return
        }
        servirMedio(cliente, pedido)
    }

    /** El medio elegido con Range: 206 parcial para el seek de la
     *  TV; file:// con RandomAccessFile y content:// con un
     *  FileDescriptor abierto POR PETICION (sin copia). */
    private fun servirMedio(cliente: Socket, pedido: Pedido) {
        val f = archivo
        val uriTexto = uriContenido
        val r = resolver
        if (f == null && (uriTexto == null || r == null)) {
            responderNo(cliente)
            return
        }
        var tam = -1L
        try {
            tam = if (f != null) {
                f.length()
            } else {
                r!!.openFileDescriptor(Uri.parse(uriTexto!!), "r").use { p ->
                    p.statSize
                }
            }
        } catch (e: Exception) {
            responderNo(cliente)
            return
        }
        if (tam <= 0L) {
            responderNo(cliente)
            return
        }
        val ini = pedido.inicio.coerceIn(0L, tam - 1)
        val fin = if (pedido.sinRango || pedido.fin < 0L || pedido.fin >= tam) tam - 1 else pedido.fin
        val mime = CastingDlna.mimeDe(nombreMedio)
        try {
            val salida = cliente.getOutputStream()
            salida.write(
                cabecera(
                    if (pedido.sinRango) 200 else 206,
                    if (pedido.sinRango) "OK" else "Partial Content",
                    mime, fin - ini + 1L, ini, fin, tam, pedido.sinRango
                ).toByteArray(StandardCharsets.ISO_8859_1)
            )
            val trozo = ByteArray(65536)
            if (f != null) {
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(ini)
                    var faltan = fin - ini + 1L
                    while (faltan > 0L) {
                        val n = raf.read(trozo, 0, faltan.coerceAtMost(trozo.size.toLong()).toInt())
                        if (n <= 0) break
                        salida.write(trozo, 0, n)
                        faltan -= n.toLong()
                    }
                }
            } else {
                r!!.openFileDescriptor(Uri.parse(uriTexto!!), "r").use { pfd ->
                    val ent = FileInputStream(pfd.fileDescriptor)
                    var saltar = ini
                    while (saltar > 0L) {
                        val s = ent.skip(saltar)
                        if (s <= 0L) break
                        saltar -= s
                    }
                    var faltan = fin - ini + 1L
                    while (faltan > 0L) {
                        val n = ent.read(trozo, 0, faltan.coerceAtMost(trozo.size.toLong()).toInt())
                        if (n <= 0) break
                        salida.write(trozo, 0, n)
                        faltan -= n.toLong()
                    }
                    ent.close()
                }
            }
            salida.flush()
        } catch (e: Exception) {
            // el receptor corto (seek, pausa o stop): normal en
            // DLNA, el socket se cierra fuera
        }
    }

    /** Cabecera de respuesta HTTP minima. */
    private fun cabecera(
        codigo: Int,
        texto: String,
        mime: String,
        longitud: Long,
        ini: Long,
        fin: Long,
        tam: Long,
        sinRango: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(codigo).append(" ").append(texto).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(longitud).append("\r\n")
        if (!sinRango) {
            sb.append("Content-Range: bytes ").append(ini).append("-")
                .append(fin).append("/").append(tam).append("\r\n")
        }
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    private fun responderBytes(
        cliente: Socket,
        codigo: Int,
        texto: String,
        mime: String,
        cuerpo: ByteArray
    ) {
        try {
            val salida = cliente.getOutputStream()
            salida.write(
                cabecera(
                    codigo, texto, mime, cuerpo.size.toLong(),
                    0L, 0L, cuerpo.size.toLong(), true
                ).toByteArray(StandardCharsets.ISO_8859_1)
            )
            salida.write(cuerpo)
            salida.flush()
        } catch (e: Exception) {
        }
    }

    private fun responderNo(cliente: Socket) {
        responderBytes(
            cliente, 404, "Not Found", "text/plain",
            "no encontrado".toByteArray(StandardCharsets.US_ASCII)
        )
    }

    /** IPv4 de la LAN (no loopback); null honesto sin red. */
    private fun ipLanV4(): InetAddress? {
        try {
            for (interfaz in NetworkInterface.getNetworkInterfaces()) {
                if (!interfaz.isUp || interfaz.isLoopback) continue
                for (direccion in interfaz.inetAddresses) {
                    if (direccion is Inet4Address && !direccion.isLoopbackAddress) {
                        return direccion
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /** Token de sesion: 48 hex de SecureRandom en la ruta; sin el,
     *  cualquier otra peticion es 404. */
    private fun nuevoToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** GET minimo: ruta y Range (inicio/fin, -1 = abierto). */
    private class Pedido(
        val ruta: String,
        val inicio: Long,
        val fin: Long,
        val sinRango: Boolean
    ) {
        companion object {
            fun desde(texto: String): Pedido? {
                val lineas = texto.split("\r\n")
                if (lineas.isEmpty()) return null
                val partes = lineas[0].split(" ")
                if (partes.size < 2 || partes[0] != "GET") return null
                var sinRango = true
                var inicio = 0L
                var fin = -1L
                for (linea in lineas) {
                    if (linea.startsWith("Range:", ignoreCase = true)) {
                        val valor = linea.substringAfter(":").trim()
                        val nums = valor.substringAfter("bytes=").split("-")
                        if (nums.size == 2) {
                            val i = nums[0].trim().toLongOrNull()
                            if (i != null) {
                                inicio = i
                                fin = nums[1].trim().toLongOrNull() ?: -1L
                                sinRango = false
                            }
                        }
                    }
                }
                return Pedido(partes[1], inicio, fin, sinRango)
            }
        }
    }

    companion object {
        /** Caducidad sin peticiones: 10 min (una tele en pausa no
         *  pide nada; si expira, el cast se reconecta con sesion
         *  nueva, aviso honesto del shell). */
        private const val CADUCIDAD_MS = 600000L
    }
}
