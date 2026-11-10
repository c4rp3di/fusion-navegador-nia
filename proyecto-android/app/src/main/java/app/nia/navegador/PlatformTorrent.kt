package app.nia.navegador

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import nia.engine.torrent.PeerConnection
import nia.engine.torrent.PeerTransport
import nia.engine.torrent.TrackerPeer
import nia.engine.torrent.TrackerTransport

/**
 * T103 v1 (R0349, RES-0142): transportes REALES del torrent para la
 * app sobre las fronteras inyectables del motor T047 (ninguna demo
 * enlatada): el announce HTTP del tracker sobre HttpURLConnection
 * (la misma pila de PlatformDownloadTransport, con el permiso
 * INTERNET del shell) y los peers del protocolo wire sobre Socket
 * con timeouts de conexion y lectura.
 */
class PlatformTrackerTransport(
    private val timeoutMs: Int = 15000
) : TrackerTransport {

    override fun get(url: String): ByteArray {
        val conexion = URL(url).openConnection() as HttpURLConnection
        try {
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.instanceFollowRedirects = true
            val codigo = conexion.responseCode
            if (codigo !in 200..299) {
                throw IOException("tracker HTTP " + codigo)
            }
            return conexion.inputStream.use { it.readBytes() }
        } finally {
            conexion.disconnect()
        }
    }
}

/** Peers del wire sobre Socket: timeouts de conexion y lectura y el
 * close SIEMPRE cae al socket (el cliente del motor cierra tras cada
 * pieza; Socket.close() tolera dobles cierres). */
class PlatformPeerTransport(
    private val timeoutMs: Int = 20000
) : PeerTransport {

    override fun connect(peer: TrackerPeer): PeerConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(peer.ip, peer.port), timeoutMs)
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (e2: IOException) {
            }
            throw e
        }
        socket.soTimeout = timeoutMs
        return object : PeerConnection {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override fun close() {
                try {
                    socket.close()
                } catch (e: IOException) {
                }
            }
        }
    }
}
