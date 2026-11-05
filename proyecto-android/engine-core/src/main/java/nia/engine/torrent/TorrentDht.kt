package nia.engine.torrent

/**
 * T047 v3 (R0129, RES-0067, ADR-016): DHT del torrent (BEP-5) con
 * nucleo puro JVM y transporte inyectable (DhtTransport), como
 * TrackerTransport y PeerTransport en v2. DhtRouting es la tabla
 * Kademlia de 160 k-buckets: el bucket de un nodo es la posicion del
 * primer bit distinto del XOR con el id propio (0..159) y un bucket
 * lleno ignora al nuevo sin eviccion. DhtClient codifica las consultas
 * bencoded (ping, find_node, get_peers) con transaction id de 2 bytes
 * verificado en la respuesta y buscarPeers recorre los nodos en BFS
 * acotado por maxConsultas: los peers llegan en values (compactos de
 * 6 bytes) y los nodos nuevos en nodes (compactos de 26 bytes),
 * deduplicando endpoints.
 *
 * Exclusiones v3 (documentadas): sin announce_peer ni siembra en el
 * DHT (el cliente solo consulta), sin eviccion con ping en buckets
 * llenos y solo IPv4.
 */

/** Nodo del DHT: id de 20 bytes y endpoint IPv4. */
class DhtNode(val id: ByteArray, val ip: String, val port: Int)

/** Transporte DHT inyectable: datagrama ida y vuelta; null = sin respuesta. */
interface DhtTransport {
    fun exchange(ip: String, port: Int, request: ByteArray): ByteArray?
}

/** Tabla de enrutado Kademlia con k-buckets por primer bit distinto. */
class DhtRouting(private val selfId: ByteArray, private val bucketSize: Int = 8) {
    private val buckets = Array(160) { ArrayList<DhtNode>() }

    /** XOR byte a byte: la distancia Kademlia. */
    internal fun xorDistancia(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in out.indices) {
            out[i] = ((a[i].toInt() xor b[i].toInt()) and 0xff).toByte()
        }
        return out
    }

    /** Bucket del nodo: posicion del primer bit distinto (0..159) o -1. */
    internal fun indiceDe(id: ByteArray): Int {
        val x = xorDistancia(selfId, id)
        var bits = 0
        for (byte in x) {
            val v = byte.toInt() and 0xff
            for (j in 7 downTo 0) {
                if (((v shr j) and 1) == 1) return bits
                bits++
            }
        }
        return -1
    }

    /** Anade el nodo a su bucket; bucket lleno o id propia devuelven false. */
    fun add(node: DhtNode): Boolean {
        val idx = indiceDe(node.id)
        if (idx < 0) return false
        val bucket = buckets[idx]
        if (bucket.size >= bucketSize) return false
        for (n in bucket) {
            if (n.ip == node.ip && n.port == node.port) return false
        }
        bucket.add(node)
        return true
    }

    /** Los n nodos mas cercanos al objetivo por distancia XOR. */
    fun cercanos(target: ByteArray, n: Int): List<DhtNode> {
        val todos = ArrayList<DhtNode>()
        for (bucket in buckets) todos.addAll(bucket)
        todos.sortWith(Comparator { a, b ->
            val da = xorDistancia(target, a.id)
            val db = xorDistancia(target, b.id)
            for (i in da.indices) {
                val d = (da[i].toInt() and 0xff) - (db[i].toInt() and 0xff)
                if (d != 0) return@Comparator d
            }
            0
        })
        return if (n < todos.size) todos.subList(0, n) else todos
    }
}

/** Cliente DHT (BEP-5): consultas bencoded y get_peers acotado. */
class DhtClient(
    private val transport: DhtTransport,
    private val selfId: ByteArray,
    private val maxConsultas: Int = 8
) {

    /** Consulta bencoded: t (2 bytes), y=q, q y a. */
    internal fun construirConsulta(tx: Int, query: String, args: Benc.BDict): ByteArray {
        val raiz = Benc.BDict(LinkedHashMap())
        raiz.entries["t"] = Benc.BStr(byteArrayOf(((tx shr 8) and 0xff).toByte(), (tx and 0xff).toByte()))
        raiz.entries["y"] = Benc.BStr("q".toByteArray(Charsets.UTF_8))
        raiz.entries["q"] = Benc.BStr(query.toByteArray(Charsets.UTF_8))
        raiz.entries["a"] = args
        return Bencode.encode(raiz)
    }

    /** Respuesta r con transaction id verificado o null. */
    internal fun parsearRespuesta(body: ByteArray, tx: Int): Benc.BDict? {
        val raiz = try {
            Bencode.decode(body)
        } catch (e: BencodeException) {
            return null
        }
        if (raiz !is Benc.BDict) return null
        val y = raiz.entries["y"] as? Benc.BStr ?: return null
        if (y.text() != "r") return null
        val t = raiz.entries["t"] as? Benc.BStr ?: return null
        if (!t.bytes.contentEquals(byteArrayOf(((tx shr 8) and 0xff).toByte(), (tx and 0xff).toByte()))) {
            return null
        }
        return raiz.entries["r"] as? Benc.BDict
    }

    /** Nodos compactos de 26 bytes (id + IPv4 + puerto). */
    internal fun desempaquetarNodos(crudo: ByteArray): List<TrackerPeer> {
        if (crudo.size % 26 != 0) return emptyList()
        val out = ArrayList<TrackerPeer>()
        for (i in 0 until crudo.size / 26) {
            val base = i * 26
            val ip = (crudo[base + 20].toInt() and 0xff).toString() + "." +
                (crudo[base + 21].toInt() and 0xff) + "." +
                (crudo[base + 22].toInt() and 0xff) + "." +
                (crudo[base + 23].toInt() and 0xff)
            val puerto = (crudo[base + 24].toInt() and 0xff) * 256 +
                (crudo[base + 25].toInt() and 0xff)
            out.add(TrackerPeer(ip, puerto))
        }
        return out
    }

    /** get_peers en BFS acotado: recopila los peers que tienen el torrent. */
    fun buscarPeers(infoHash: ByteArray, bootstrap: List<TrackerPeer>): List<TrackerPeer> {
        val peers = LinkedHashMap<String, TrackerPeer>()
        val cola = ArrayDeque<TrackerPeer>()
        cola.addAll(bootstrap)
        val visitados = HashSet<String>()
        var consultas = 0
        while (cola.isNotEmpty() && consultas < maxConsultas) {
            val nodo = cola.removeFirst()
            val clave = nodo.ip + ":" + nodo.port
            if (!visitados.add(clave)) continue
            consultas++
            val args = Benc.BDict(LinkedHashMap())
            args.entries["id"] = Benc.BStr(selfId)
            args.entries["info_hash"] = Benc.BStr(infoHash)
            val respuesta = transport.exchange(
                nodo.ip, nodo.port, construirConsulta(consultas, "get_peers", args)
            ) ?: continue
            val r = parsearRespuesta(respuesta, consultas) ?: continue
            val valores = r.entries["values"] as? Benc.BList
            if (valores != null) {
                for (v in valores.items) {
                    val b = (v as? Benc.BStr)?.bytes ?: continue
                    if (b.size != 6) continue
                    val ip = (b[0].toInt() and 0xff).toString() + "." +
                        (b[1].toInt() and 0xff) + "." +
                        (b[2].toInt() and 0xff) + "." +
                        (b[3].toInt() and 0xff)
                    val puerto = (b[4].toInt() and 0xff) * 256 + (b[5].toInt() and 0xff)
                    val clavePeer = ip + ":" + puerto
                    if (!peers.containsKey(clavePeer)) {
                        peers[clavePeer] = TrackerPeer(ip, puerto)
                    }
                }
            }
            val crudo = r.entries["nodes"] as? Benc.BStr
            if (crudo != null) {
                cola.addAll(desempaquetarNodos(crudo.bytes))
            }
        }
        return peers.values.toList()
    }
}
