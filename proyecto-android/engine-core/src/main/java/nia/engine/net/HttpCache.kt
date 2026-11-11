package nia.engine.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import nia.contracts.WallClock
import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser

/**
 * T026 v2 (R0060, RES-0035): cache HTTP para el motor de red.
 *
 * HttpCache es un cache por sesion de motor (la persistencia en disco
 * llega con las particiones de storage, T033). Modela lo minimo del
 * RFC 9111 que un navegador real necesita en v2:
 *
 * - store: solo respuestas 200; no-store no se almacena; freshFor
 *   sale de max-age o de Expires-Date; mustRevalidate marca las
 *   respuestas con must-revalidate/no-cache.
 * - lookup: solo GET. Fresh si la edad no pasa freshFor y no hay que
 *   revalidar; Stale si caduca o hay que revalidar (el que llama
 *   revalida); Miss sin entrada o en metodos que no cachean.
 * - revalidationHeaders: If-None-Match con el ETag guardado o
 *   If-Modified-Since con Last-Modified.
 * - serveRevalidated: un 304 revive la entrada guardada (merge de
 *   cabeceras nuevas sobre las guardadas) y sirve el cuerpo guardado.
 *
 * T026 v3 (R0311, RES-0127): presupuesto LRU. El cache JAMAS crece
 * sin tope: maxEntries (256 por defecto) y maxBodyBytes (8 MiB de
 * cuerpos en total) desalojan la entrada menos usada recientemente
 * (access-order); un cuerpo que no cabe ni solo en el presupuesto
 * no se almacena (como no-store). bytes() expone el consumo para
 * que los ajustes puedan mostrarlo.
 *
 * Limitaciones v2: solo GET, sin Vary y sin particiones
 * (T033); v5 (R0324, RES-0132): frescura heuristica RFC 9111
 * cuando no hay max-age ni Expires (10% de Date-Last-Modified,
 * tope 24 h).
 *
 * T113 v2 (R0413, RES-0164): la Entry conserva el TlsInfo de la
 * respuesta almacenada; lookup Fresh y el 304 revivido lo
 * devuelven: el panel de identidad no pierde el certificado al
 * volver a una pagina servida de cache.
 */
class HttpCache(
    private val clock: WallClock,
    private val maxEntries: Int = 256,
    private val maxBodyBytes: Long = 8_388_608L
) {
    init {
        require(maxEntries > 0) { "maxEntries debe ser positivo" }
        require(maxBodyBytes > 0) { "maxBodyBytes debe ser positivo" }
    }

    data class Entry(
        val url: String,
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val storedAtMillis: Long,
        val freshForMillis: Long,
        val mustRevalidate: Boolean,
        val seguridad: TlsInfo? = null
    )

    sealed class Lookup {
        object Miss : Lookup()
        data class Fresh(val entry: Entry) : Lookup()
        data class Stale(val entry: Entry) : Lookup()
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes: Long = 0

    /** T026 v3 (R0311): presupuesto consumido por los cuerpos guardados. */
    fun bytes(): Long = totalBytes

    fun count(): Int = entries.size

    fun entryFor(url: String): Entry? = entries[url]

    /** T044 v1 (R0104, RES-0056): borra todas las entradas; devuelve cuantas. */
    fun clear(): Int {
        val removed = entries.size
        entries.clear()
        totalBytes = 0
        return removed
    }

    /** T085 v1 (RES-0098): conteo de las entradas de cache del
     *  origen (scheme, host, puerto efectivo); una URL ilegible
     *  guardada simplemente no matchea (jamas revienta). */
    fun contarDe(scheme: String, host: String, port: Int): Int =
        entries.values.count { esDeOrigen(it.url, scheme, host, port) }

    /** T085 v1: borra SOLO las entradas del origen; los demas
     *  origenes quedan intactos. */
    fun borrarDe(scheme: String, host: String, port: Int): Int {
        val victimas = entries.filter { esDeOrigen(it.value.url, scheme, host, port) }.keys.toList()
        for (k in victimas) totalBytes -= entries.remove(k)!!.body.size.toLong()
        return victimas.size
    }

    private fun esDeOrigen(url: String, scheme: String, host: String, port: Int): Boolean {
        return try {
            val u = UrlParser.parse(url)
            u.scheme.equals(scheme, ignoreCase = true) &&
                u.host.equals(host, ignoreCase = true) &&
                u.effectivePort == port
        } catch (e: UrlParseException) {
            false
        }
    }

    /** Guarda una respuesta 200; no-store -> null (no se almacena). */
    fun store(url: String, response: FetchResponse): Entry? {
        if (response.status != 200) return null
        val cc = directives(response.headers)
        if (cc["no-store"] != null) return null
        if (response.body.size.toLong() > maxBodyBytes) return null
        val now = clock.nowMillis()
        val entry = Entry(
            url,
            response.status,
            LinkedHashMap(response.headers),
            response.body,
            now,
            freshnessMillis(response.headers, now, cc),
            cc["no-cache"] != null || cc["must-revalidate"] != null,
            response.seguridad
        )
        entries[url]?.let { totalBytes -= it.body.size.toLong() }
        entries[url] = entry
        totalBytes += entry.body.size.toLong()
        evictToBudget()
        return entry
    }

    /** GET con entrada vigente -> Fresh; caducada o de revalidar -> Stale; resto Miss. */
    fun lookup(method: String, url: String): Lookup {
        if (method != "GET") return Lookup.Miss
        val entry = entries[url] ?: return Lookup.Miss
        val age = clock.nowMillis() - entry.storedAtMillis
        if (entry.mustRevalidate || age >= entry.freshForMillis) return Lookup.Stale(entry)
        return Lookup.Fresh(entry)
    }

    /** Cabecera condicional para revalidar una entrada. */
    fun revalidationHeaders(entry: Entry): Map<String, String> {
        val etag = headerValue(entry.headers, "etag")
        if (etag != null) return mapOf("if-none-match" to etag)
        val lastModified = headerValue(entry.headers, "last-modified")
        if (lastModified != null) return mapOf("if-modified-since" to lastModified)
        return emptyMap()
    }

    /** Un 304 revive la entrada: merge de cabeceras y cuerpo guardado. */
    fun serveRevalidated(url: String, response: FetchResponse): FetchResponse? {
        if (response.status != 304) return null
        val entry = entries[url] ?: return null
        val merged = LinkedHashMap(entry.headers)
        for ((k, v) in response.headers) merged[k] = v
        val now = clock.nowMillis()
        val dir = directives(merged)
        val updated = Entry(
            url,
            entry.status,
            merged,
            entry.body,
            now,
            freshnessMillis(merged, now, dir),
            dir["no-cache"] != null || dir["must-revalidate"] != null,
            entry.seguridad
        )
        entries[url] = updated
        return FetchResponse(updated.status, updated.headers, updated.body, updated.seguridad)
    }

    /** T026 v3 (R0311, RES-0127): desaloja la LRU (access-order)
     *  hasta caber en el presupuesto de entradas y de bytes. */
    private fun evictToBudget() {
        while (entries.isNotEmpty() && (entries.size > maxEntries || totalBytes > maxBodyBytes)) {
            val eldest = entries.entries.iterator().next()
            totalBytes -= eldest.value.body.size.toLong()
            entries.remove(eldest.key)
        }
    }

    /** cache-control en minusculas: directiva -> valor (vacio si no hay). */
    private fun directives(headers: Map<String, String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val cc = headerValue(headers, "cache-control")
        if (cc != null) {
            for (part in cc.split(',')) {
                val p = part.trim().lowercase()
                if (p.isEmpty()) continue
                val e = p.indexOf('=')
                if (e < 0) out[p] = "" else out[p.substring(0, e).trim()] = p.substring(e + 1).trim()
            }
        }
        return out
    }

    /** T026 v5 (R0324, RES-0132): max-age=N en millis; si no,
     *  Expires-Date; si no, heuristica RFC 9111 con Last-Modified
     *  (10% de Date-Last-Modified, Date ausente -> nowMillis, tope
     *  24 h); si nada, 0. El orden MANDA: max-age, Expires y solo
     *  entonces la heuristica. */
    private fun freshnessMillis(headers: Map<String, String>, nowMillis: Long, directives: Map<String, String>): Long {
        val maxAge = directives["max-age"]?.toLongOrNull()
        if (maxAge != null) return maxAge * 1000
        val expires = dateMillis(headers, "expires")
        val date = dateMillis(headers, "date")
        if (expires != null && date != null) return (expires - date).coerceAtLeast(0)
        val lastModified = dateMillis(headers, "last-modified") ?: return 0
        val base = (date ?: nowMillis) - lastModified
        if (base <= 0) return 0
        return (base / 10).coerceAtMost(86_400_000L)
    }

    /** Fecha de una cabecera en millis; ilegible -> null. */
    private fun dateMillis(headers: Map<String, String>, name: String): Long? {
        val raw = headerValue(headers, name) ?: return null
        return try {
            ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Busque case-insensitive. */
    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.lowercase() == name }?.value
}
