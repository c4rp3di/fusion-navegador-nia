package nia.engine.storage

import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Scope del storage del motor (T033 v1): LOCAL vive entre sesiones del
 * navegador, SESSION muere con la sesion.
 */
enum class StorageScope { LOCAL, SESSION }

/**
 * Area de storage por particion (scope, topSite, origin) con cuota en bytes
 * UTF-8 de clave + valor. La cuota se observa sin excepcion: setItem
 * devuelve false cuando la escritura excederia la cuota y NO almacena.
 */
class StorageArea(
    val scope: StorageScope,
    val partitionKey: String,
    val quotaBytes: Long,
) {

    private val values = LinkedHashMap<String, String>()

    val length: Int get() = values.size

    fun key(index: Int): String? = if (index >= 0 && index < values.size) values.keys.elementAt(index) else null

    fun getItem(key: String): String? = values[key]

    fun setItem(key: String, value: String): Boolean {
        val current = utf8Bytes(key) + utf8Bytes(value)
        val previous = values[key]?.let { utf8Bytes(key) + utf8Bytes(it) } ?: 0L
        if (usageBytes() - previous + current > quotaBytes) return false
        values[key] = value
        return true
    }

    fun removeItem(key: String) {
        values.remove(key)
    }

    fun clear(): Int {
        val removed = values.size
        values.clear()
        return removed
    }

    fun usageBytes(): Long {
        var total = 0L
        for (entry in values) {
            total += utf8Bytes(entry.key) + utf8Bytes(entry.value)
        }
        return total
    }

    /** Entradas del area para persistencia (T033 v3, RES-0044). */
    internal fun entriesSnapshot(): Map<String, String> = LinkedHashMap(values)

    /** Restaura entradas persistidas (T033 v3); preserva el orden de insercion. */
    internal fun restoreEntries(entries: Map<String, String>) {
        values.clear()
        values.putAll(entries)
    }

    companion object {

        internal fun utf8Bytes(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()
    }
}

/**
 * Storage particionado del motor (T033 v1): una particion por (scope, sitio
 * de primer nivel, origen). El storage de terceros queda particionado: un
 * mismo origen embebido bajo dos top-sites distintos NO comparte area; los
 * origenes opacos reciben particion propia por razon y nunca se funden con
 * las tuplas (T010 Origin/Site).
 */
class StorageManager(private val defaultQuotaBytes: Long = 5_000_000L) {

    private val areas = LinkedHashMap<String, StorageArea>()

    fun area(scope: StorageScope, topSite: Site, origin: Origin): StorageArea {
        val key = partitionKey(scope, topSite, origin)
        return areas.getOrPut(key) { StorageArea(scope, key, defaultQuotaBytes) }
    }

    fun usageBytes(scope: StorageScope, topSite: Site, origin: Origin): Long =
        areas[partitionKey(scope, topSite, origin)]?.usageBytes() ?: 0L

    fun partitionCount(): Int = areas.size

    /** Borra TODAS las particiones del origen (cualquier top-site, ambos scopes). */
    fun removeOrigin(origin: Origin): Int {
        val suffix = "|" + originKey(origin)
        val dead = areas.keys.filter { it.endsWith(suffix) }
        for (key in dead) {
            areas.remove(key)
        }
        return dead.size
    }

    fun clearAll(): Int {
        val removed = areas.size
        areas.clear()
        return removed
    }

    /** El sessionStorage muere con la sesion; el localStorage sobrevive. */
    fun clearSessions(): Int {
        val dead = areas.filterValues { it.scope == StorageScope.SESSION }.keys.toList()
        for (key in dead) {
            areas.remove(key)
        }
        return dead.size
    }

    private fun partitionKey(scope: StorageScope, topSite: Site, origin: Origin): String =
        scope.name + "|" + siteKey(topSite) + "|" + originKey(origin)

    private fun siteKey(site: Site): String = site.scheme + "://" + site.registrableDomain

    private fun originKey(origin: Origin): String = when (origin) {
        is Origin.Tuple -> origin.scheme + "://" + origin.host + ":" + origin.port
        is Origin.Opaque -> "opaque[" + origin.reason + "]"
    }

    /** Snapshot de todas las areas para persistencia (T033 v3, RES-0044). */
    internal fun snapshotAreas(): List<StorageAreaSnapshot> =
        areas.values.map { StorageAreaSnapshot(it.scope, it.partitionKey, it.entriesSnapshot()) }

    /** Restaura areas persistidas con merge por partitionKey (T033 v3). */
    internal fun restoreAreas(snapshots: List<StorageAreaSnapshot>) {
        for (s in snapshots) {
            areas.getOrPut(s.partitionKey) { StorageArea(s.scope, s.partitionKey, defaultQuotaBytes) }.restoreEntries(s.entries)
        }
    }
}

/** Snapshot de un area para la persistencia en disco (T033 v3, RES-0044). */
internal class StorageAreaSnapshot(
    internal val scope: StorageScope,
    internal val partitionKey: String,
    internal val entries: Map<String, String>,
)
