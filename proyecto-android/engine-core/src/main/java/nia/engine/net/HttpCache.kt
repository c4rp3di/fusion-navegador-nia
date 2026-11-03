package nia.engine.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import nia.contracts.WallClock

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
 * Limitaciones v2: solo GET, sin Vary, sin heuristica (sin
 * freshFor conocido la entrada nunca es fresca) y sin particiones
 * (T033).
 */
class HttpCache(private val clock: WallClock) {

    data class Entry(
        val url: String,
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val storedAtMillis: Long,
        val freshForMillis: Long,
        val mustRevalidate: Boolean
    )

    sealed class Lookup {
        object Miss : Lookup()
        data class Fresh(val entry: Entry) : Lookup()
        data class Stale(val entry: Entry) : Lookup()
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun count(): Int = entries.size

    fun entryFor(url: String): Entry? = entries[url]

    /** Guarda una respuesta 200; no-store -> null (no se almacena). */
    fun store(url: String, response: FetchResponse): Entry? {
        if (response.status != 200) return null
        val cc = directives(response.headers)
        if (cc["no-store"] != null) return null
        val now = clock.nowMillis()
        val entry = Entry(
            url,
            response.status,
            LinkedHashMap(response.headers),
            response.body,
            now,
            freshnessMillis(response.headers, now, cc),
            cc["no-cache"] != null || cc["must-revalidate"] != null
        )
        entries[url] = entry
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
            dir["no-cache"] != null || dir["must-revalidate"] != null
        )
        entries[url] = updated
        return FetchResponse(updated.status, updated.headers, updated.body)
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

    /** max-age=N en millis; si no, Expires-Date; si no, 0 (sin heuristica). */
    private fun freshnessMillis(headers: Map<String, String>, nowMillis: Long, directives: Map<String, String>): Long {
        val maxAge = directives["max-age"]?.toLongOrNull()
        if (maxAge != null) return maxAge * 1000
        val expires = dateMillis(headers, "expires")
        val date = dateMillis(headers, "date")
        if (expires != null && date != null) return (expires - date).coerceAtLeast(0)
        return 0
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
