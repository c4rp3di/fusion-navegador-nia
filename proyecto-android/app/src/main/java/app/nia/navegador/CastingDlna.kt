package app.nia.navegador

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import org.xmlpull.v1.XmlPullParser

/**
 * T066 v1 (R0401, RES-0159): cliente DLNA/UPnP AV minimo a mano
 * (framework puro, SIN dependencias nuevas, decision del usuario;
 * ADR-020 con la enmienda de R0401). Descubrimiento SSDP de
 * MediaRenderer con MulticastLock SOLO durante la busqueda,
 * descripcion del dispositivo con el XmlPullParser del framework y
 * acciones SOAP de AVTransport: SetAVTransportURI (DIDL-Lite
 * minimo con pv:subtitle si hay pista), Play, Pause, Stop, Seek
 * REL_TIME, GetPositionInfo y GetTransportInfo.
 *
 * Exclusiones honestas v1: SIN RenderingControl (el volumen del
 * receptor se maneja con el mando de la tele), sin cola, sin
 * Google Cast (v2 aparte por ADR-020). Toda accion devuelve
 * honesto: null o false ante cualquier fallo, JAMAS exito fingido.
 */
class CastingDlna(private val context: Context) {

    /** Busca renderers por SSDP: M-SEARCH de MediaRenderer:1 y
     *  ssdp:all (algunos receptores solo responden a uno de los
     *  dos), las respuestas LOCATION se deduplican y cada
     *  dispositivo se DESCRIBE de verdad: solo cuentan los que
     *  tienen servicio AVTransport. Sin lock de multicast muchas
     *  redes tiran las respuestas: se toma SOLO aqui y se suelta
     *  SIEMPRE. */
    fun buscar(maxMs: Int): List<ReceptorDlna> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        var lock: WifiManager.MulticastLock? = null
        val ubicaciones = LinkedHashMap<String, Boolean>()
        try {
            lock = wifi?.createMulticastLock("nia-cast")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            val socket = DatagramSocket()
            socket.soTimeout = maxMs
            val destinos = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "ssdp:all"
            )
            for (st in destinos) {
                val busca = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: " + st + "\r\n" +
                        "\r\n"
                    ).toByteArray(StandardCharsets.ISO_8859_1)
                socket.send(
                    DatagramPacket(
                        busca, busca.size,
                        InetAddress.getByName("239.255.255.250"), 1900
                    )
                )
            }
            val bufer = ByteArray(4096)
            while (true) {
                val paquete = DatagramPacket(bufer, bufer.size)
                try {
                    socket.receive(paquete)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val texto = String(
                    bufer, 0, paquete.length, StandardCharsets.ISO_8859_1
                )
                val ubicacion = cabecera(texto, "LOCATION") ?: continue
                if (ubicacion.startsWith("http")) ubicaciones[ubicacion] = true
            }
        } catch (e: Exception) {
            // red caida o wifi sin multicast: lista vacia y el
            // shell avisa honesto, JAMAS un falso receptor
        } finally {
            lock?.release()
        }
        val renderers = ArrayList<ReceptorDlna>()
        for (ubicacion in ubicaciones.keys) {
            val r = describir(ubicacion)
            if (r != null) renderers.add(r)
        }
        return renderers
    }

    /** Envia el medio al receptor: SetAVTransportURI con DIDL-Lite
     *  minimo (titulo, clase por tipo, res con MIME por extension
     *  y pv:subtitle si hay pista WebVTT servida). */
    fun enviar(
        receptor: ReceptorDlna,
        uri: String,
        titulo: String,
        mime: String,
        subsUrl: String?
    ): Boolean {
        val clase = if (mime.startsWith("audio/")) {
            "object.item.audioItem"
        } else {
            "object.item.videoItem"
        }
        val didl = StringBuilder()
        didl.append("<DIDL-Lite")
        didl.append(" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
        didl.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        didl.append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
        didl.append(" xmlns:pv=\"http://www.pv.org/metadata-1-0/pv/\">")
        didl.append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        didl.append("<dc:title>").append(escaparXml(titulo)).append("</dc:title>")
        didl.append("<upnp:class>").append(clase).append("</upnp:class>")
        didl.append("<res protocolInfo=\"http-get:*:").append(mime).append(":*\">")
        didl.append(escaparXml(uri)).append("</res>")
        if (subsUrl != null) {
            didl.append("<pv:subtitle type=\"text/vtt\" mimeType=\"text/vtt\">")
            didl.append(escaparXml(subsUrl)).append("</pv:subtitle>")
        }
        didl.append("</item></DIDL-Lite>")
        val cuerpo = "<u:SetAVTransportURI" +
            " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "<CurrentURI>" + escaparXml(uri) + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escaparXml(didl.toString()) +
            "</CurrentURIMetaData>" +
            "</u:SetAVTransportURI>"
        return soap(receptor, "SetAVTransportURI", cuerpo) != null
    }

    fun play(receptor: ReceptorDlna): Boolean =
        soap(receptor, "Play", cuerpoAccion("Play", "<Speed>1</Speed>")) != null

    fun pausa(receptor: ReceptorDlna): Boolean =
        soap(receptor, "Pause", cuerpoAccion("Pause", "")) != null

    fun parar(receptor: ReceptorDlna): Boolean =
        soap(receptor, "Stop", cuerpoAccion("Stop", "")) != null

    /** Seek REL_TIME al receptor (en segundos). */
    fun saltar(receptor: ReceptorDlna, segundos: Long): Boolean =
        soap(
            receptor, "Seek",
            cuerpoAccion("Seek", "<Unit>REL_TIME</Unit><Target>" + hms(segundos) + "</Target>")
        ) != null

    /** Posicion del receptor: (posMs, durMs) de GetPositionInfo;
     *  null honesto si el receptor no responde. */
    fun posicion(receptor: ReceptorDlna): Pair<Long, Long>? {
        val respuesta = soap(
            receptor, "GetPositionInfo", cuerpoAccion("GetPositionInfo", "")
        ) ?: return null
        val pos = deHms(valor(respuesta, "RelTime"))
        val dur = deHms(valor(respuesta, "TrackDuration"))
        if (pos == null || dur == null) return null
        return pos to dur
    }

    /** Estado del transporte (PLAYING / PAUSED_PLAYBACK / STOPPED);
     *  null honesto si el receptor no responde. */
    fun estado(receptor: ReceptorDlna): String? {
        val respuesta = soap(
            receptor, "GetTransportInfo", cuerpoAccion("GetTransportInfo", "")
        ) ?: return null
        return valor(respuesta, "CurrentTransportState")
    }

    /** POST SOAP al controlURL del AVTransport del receptor. */
    private fun soap(
        receptor: ReceptorDlna,
        accion: String,
        cuerpo: String
    ): String? {
        return try {
            val con = URL(receptor.avTransport).openConnection() as HttpURLConnection
            con.connectTimeout = 5000
            con.readTimeout = 8000
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            con.setRequestProperty(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:AVTransport:1#" + accion + "\""
            )
            con.outputStream.use { sal ->
                sal.write(
                    (
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
                            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                            "<s:Body>" + cuerpo + "</s:Body></s:Envelope>"
                    ).toByteArray(StandardCharsets.UTF_8)
                )
            }
            if (con.responseCode != 200) return null
            val datos = ByteArrayOutputStream()
            con.inputStream.use { ent ->
                val trozo = ByteArray(4096)
                while (true) {
                    val n = ent.read(trozo)
                    if (n <= 0) break
                    datos.write(trozo, 0, n)
                }
            }
            datos.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    /** Trae la descripcion XML del dispositivo y extrae friendlyName
     *  y el controlURL del AVTransport (resuelto contra LOCATION,
     *  absoluto o relativo); null si no es un renderer util. */
    private fun describir(ubicacion: String): ReceptorDlna? {
        return try {
            val xml = traer(ubicacion) ?: return null
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            var nombre = ""
            var controlUrl: String? = null
            var dentroServicio = false
            var esAvTransport = false
            var etiqueta = ""
            var evento = parser.eventType
            while (evento != XmlPullParser.END_DOCUMENT) {
                if (evento == XmlPullParser.START_TAG) {
                    etiqueta = parser.name
                    if (etiqueta == "service") {
                        dentroServicio = true
                        esAvTransport = false
                    }
                } else if (evento == XmlPullParser.TEXT) {
                    val texto = parser.text.trim()
                    if (etiqueta == "friendlyName" && nombre.isEmpty()) nombre = texto
                    if (dentroServicio && etiqueta == "serviceType" &&
                        texto.startsWith("urn:schemas-upnp-org:service:AVTransport")
                    ) {
                        esAvTransport = true
                    }
                    if (dentroServicio && esAvTransport && etiqueta == "controlURL") {
                        controlUrl = texto
                    }
                } else if (evento == XmlPullParser.END_TAG) {
                    if (parser.name == "service") dentroServicio = false
                    if (parser.name == etiqueta) etiqueta = ""
                }
                evento = parser.next()
            }
            val control = controlUrl ?: return null
            val absoluta = URL(URL(ubicacion), control).toString()
            ReceptorDlna(if (nombre.isEmpty()) "Receptor DLNA" else nombre, absoluta)
        } catch (e: Exception) {
            null
        }
    }

    /** GET de la descripcion (max 256 KiB, timeouts cortos). */
    private fun traer(url: String): String? {
        return try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 4000
            con.readTimeout = 4000
            if (con.responseCode != 200) return null
            val datos = ByteArrayOutputStream()
            con.inputStream.use { ent ->
                val trozo = ByteArray(4096)
                while (datos.size() < 262144) {
                    val n = ent.read(trozo)
                    if (n <= 0) break
                    datos.write(trozo, 0, n)
                }
            }
            datos.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    /** Cabecera de una respuesta SSDP (case-insensitive). */
    private fun cabecera(texto: String, nombre: String): String? {
        for (linea in texto.split("\r\n")) {
            val pos = linea.indexOf(':')
            if (pos > 0 && linea.substring(0, pos).trim().equals(nombre, ignoreCase = true)) {
                return linea.substring(pos + 1).trim()
            }
        }
        return null
    }

    /** Cuerpo SOAP de una accion de AVTransport. */
    private fun cuerpoAccion(nombre: String, extra: String): String =
        "<u:" + nombre + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" + extra +
            "</u:" + nombre + ">"

    /** Valor de una etiqueta de la respuesta SOAP (respuestas de
     *  AVTransport planas; null si no viene o viene vacio). */
    private fun valor(xml: String, etiqueta: String): String? {
        val i = xml.indexOf("<" + etiqueta + ">")
        if (i < 0) return null
        val desde = i + etiqueta.length + 2
        val j = xml.indexOf("</" + etiqueta + ">", desde)
        if (j < 0) return null
        val texto = xml.substring(desde, j).trim()
        return if (texto.isEmpty()) null else texto
    }

    /** H:MM:SS para Seek REL_TIME. */
    private fun hms(segundos: Long): String {
        val s = segundos.coerceAtLeast(0L)
        return (s / 3600).toString() + ":" +
            ((s % 3600) / 60).toString().padStart(2, '0') + ":" +
            (s % 60).toString().padStart(2, '0')
    }

    /** H:MM:SS(.mmm) a milisegundos; null si viene vacio o raro. */
    private fun deHms(t: String?): Long? {
        if (t == null || t.isEmpty() || t == "NOT_IMPLEMENTED") return null
        val partes = t.split(":")
        if (partes.size != 3) return null
        val h = partes[0].toLongOrNull() ?: return null
        val m = partes[1].toLongOrNull() ?: return null
        val s = partes[2].substringBefore('.').toLongOrNull() ?: return null
        return ((h * 3600) + (m * 60) + s) * 1000L
    }

    private fun escaparXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /** MIME por extension para el DIDL y el endpoint LAN;
         *  octet-stream honesto si no se reconoce. */
        fun mimeDe(nombre: String): String {
            val n = nombre.lowercase()
            return when {
                n.endsWith(".mp4") || n.endsWith(".m4v") -> "video/mp4"
                n.endsWith(".webm") -> "video/webm"
                n.endsWith(".mkv") -> "video/x-matroska"
                n.endsWith(".avi") -> "video/avi"
                n.endsWith(".mov") -> "video/quicktime"
                n.endsWith(".ts") -> "video/mp2t"
                n.endsWith(".3gp") -> "video/3gpp"
                n.endsWith(".mp3") -> "audio/mpeg"
                n.endsWith(".m4a") || n.endsWith(".aac") -> "audio/mp4"
                n.endsWith(".flac") -> "audio/flac"
                n.endsWith(".ogg") || n.endsWith(".opus") -> "audio/ogg"
                n.endsWith(".wav") -> "audio/wav"
                else -> "application/octet-stream"
            }
        }
    }
}

/** Receptor DLNA descubierto: nombre visible y controlURL del
 *  AVTransport (absoluto). */
data class ReceptorDlna(val nombre: String, val avTransport: String)
