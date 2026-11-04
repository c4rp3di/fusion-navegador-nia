package nia.engine.storage

import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Excepcion tipada de IndexedDB: name usa los nombres del spec
 * (VersionError, TransactionInactiveError, InvalidStateError,
 * NotFoundError, QuotaExceededError).
 */
class IdbException(val name: String, message: String) : RuntimeException(message)

/** Par clave/valor de un store (claves string, valores string). */
data class IdbEntry(val key: String, val value: String)

/**
 * Store de una transaccion: las operaciones viven sobre el buffer del tx
 * (get ve buffer + comprometido; put y delete quedan en el buffer hasta
 * el commit).
 */
class IdbObjectStore internal constructor(val name: String, private val tx: IdbTransaction) {

    fun put(key: String, value: String) {
        tx.put(name, key, value)
    }

    fun get(key: String): String? = tx.get(name, key)

    fun delete(key: String) {
        tx.delete(name, key)
    }

    fun getAll(): List<IdbEntry> = tx.getAll(name)

    fun keys(): List<String> = tx.keys(name)

    fun count(): Int = tx.count(name)
}

/**
 * Transaccion con buffer: las escrituras no tocan la base hasta el commit;
 * abort descarta el buffer. Las operaciones tras terminar lanzan
 * TransactionInactiveError.
 */
class IdbTransaction internal constructor(
    internal val db: IdbDatabase,
    internal val storeNames: List<String>,
) {

    private val puts = LinkedHashMap<String, MutableMap<String, String>>()
    private val deletes = LinkedHashMap<String, MutableSet<String>>()

    internal var finished = false
        private set

    fun store(name: String): IdbObjectStore {
        if (finished) {
            throw IdbException("TransactionInactiveError", "la transaccion ya termino")
        }
        if (!storeNames.contains(name)) {
            throw IdbException("InvalidStateError", "store fuera del alcance de la transaccion: " + name)
        }
        if (!db.storeNames.contains(name)) {
            throw IdbException("NotFoundError", "store inexistente: " + name)
        }
        return IdbObjectStore(name, this)
    }

    /** Aplica el buffer contra la base; la cuota se comprueba ANTES de aplicar. */
    fun commit() {
        if (finished) {
            throw IdbException("TransactionInactiveError", "la transaccion ya termino")
        }
        db.applyTransaction(this)
        finished = true
    }

    /** Rollback: descarta el buffer y termina la transaccion. */
    fun abort() {
        puts.clear()
        deletes.clear()
        finished = true
    }

    internal fun put(store: String, key: String, value: String) {
        if (finished) {
            throw IdbException("TransactionInactiveError", "la transaccion ya termino")
        }
        deletes[store]?.remove(key)
        puts.getOrPut(store) { LinkedHashMap() }[key] = value
    }

    internal fun get(store: String, key: String): String? {
        puts[store]?.let { buffer ->
            if (buffer.containsKey(key)) return buffer[key]
        }
        if (deletes[store]?.contains(key) == true) return null
        return db.committedGet(store, key)
    }

    internal fun delete(store: String, key: String) {
        if (finished) {
            throw IdbException("TransactionInactiveError", "la transaccion ya termino")
        }
        puts[store]?.remove(key)
        deletes.getOrPut(store) { mutableSetOf() }.add(key)
    }

    internal fun getAll(store: String): List<IdbEntry> {
        val merged = LinkedHashMap<String, String>()
        for (entry in db.committedEntries(store)) {
            merged[entry.key] = entry.value
        }
        deletes[store]?.let { dead ->
            for (key in dead) {
                merged.remove(key)
            }
        }
        puts[store]?.let { buffer ->
            for (entry in buffer) {
                merged[entry.key] = entry.value
            }
        }
        return merged.map { IdbEntry(it.key, it.value) }
    }

    internal fun keys(store: String): List<String> = getAll(store).map { it.key }

    internal fun count(store: String): Int = getAll(store).size

    internal fun putsFor(store: String): Map<String, String> = puts[store] ?: emptyMap()

    internal fun deletesFor(store: String): Set<String> = deletes[store] ?: emptySet()
}

/**
 * Base de datos IndexedDB de una particion (T033 v1): stores de claves
 * string, transacciones con buffer y cuota compartida por particion.
 */
class IdbDatabase internal constructor(
    val name: String,
    var version: Long,
    internal val partition: Partition,
) {

    private val stores = LinkedHashMap<String, MutableMap<String, String>>()
    internal val keyPaths = mutableMapOf<String, String?>()

    internal var closed = false
        private set

    val storeNames: List<String> get() = stores.keys.toList()

    fun createObjectStore(name: String, keyPath: String? = null) {
        if (closed) {
            throw IdbException("InvalidStateError", "la base de datos esta cerrada")
        }
        if (stores.containsKey(name)) {
            throw IdbException("InvalidStateError", "store duplicado: " + name)
        }
        stores[name] = LinkedHashMap()
        keyPaths[name] = keyPath
    }

    fun deleteObjectStore(name: String) {
        if (closed) {
            throw IdbException("InvalidStateError", "la base de datos esta cerrada")
        }
        if (!stores.containsKey(name)) {
            throw IdbException("NotFoundError", "store inexistente: " + name)
        }
        stores.remove(name)
        keyPaths.remove(name)
    }

    fun transaction(vararg names: String): IdbTransaction {
        if (closed) {
            throw IdbException("InvalidStateError", "la base de datos esta cerrada")
        }
        if (names.isEmpty()) {
            throw IdbException("InvalidStateError", "transaccion sin stores")
        }
        return IdbTransaction(this, names.toList())
    }

    fun close() {
        closed = true
    }

    internal fun applyTransaction(tx: IdbTransaction) {
        val projected = partition.projectedUsage(tx)
        if (projected > partition.quotaBytes) {
            throw IdbException("QuotaExceededError", "cuota de la particion excedida al commit: " + projected + " > " + partition.quotaBytes)
        }
        for (store in tx.storeNames) {
            val data = stores[store] ?: continue
            for (key in tx.deletesFor(store)) {
                data.remove(key)
            }
            for (entry in tx.putsFor(store)) {
                data[entry.key] = entry.value
            }
        }
    }

    internal fun committedGet(store: String, key: String): String? = stores[store]?.get(key)

    internal fun committedEntries(store: String): List<IdbEntry> {
        val data = stores[store] ?: return emptyList()
        return data.map { IdbEntry(it.key, it.value) }
    }

    internal fun usageBytes(): Long {
        var total = 0L
        for (data in stores.values) {
            for (entry in data) {
                total += StorageArea.utf8Bytes(entry.key) + StorageArea.utf8Bytes(entry.value)
            }
        }
        return total
    }

    /** Stores comprometidos para persistencia (T033 v3, RES-0044). */
    internal fun storesSnapshot(): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((name, data) in stores) {
            out[name] = LinkedHashMap(data)
        }
        return out
    }

    /** Restaura stores persistidos (T033 v3); sustituye el contenido. */
    internal fun restoreStores(data: Map<String, Map<String, String>>, paths: Map<String, String?>) {
        stores.clear()
        for ((name, entries) in data) {
            stores[name] = LinkedHashMap(entries)
        }
        keyPaths.clear()
        keyPaths.putAll(paths)
    }
}

/**
 * Particion IndexedDB por (topSite, origin) con cuota propia: todas las
 * bases de la particion comparten la cuota (10 MB por defecto).
 */
internal class Partition internal constructor(internal val quotaBytes: Long) {

    internal val dbs = LinkedHashMap<String, IdbDatabase>()

    internal fun usageBytes(): Long {
        var total = 0L
        for (db in dbs.values) {
            total += db.usageBytes()
        }
        return total
    }

    /** Uso proyectado de la particion si el commit aplicara el buffer del tx. */
    internal fun projectedUsage(tx: IdbTransaction): Long {
        var delta = 0L
        for (store in tx.storeNames) {
            val puts = tx.putsFor(store)
            for (entry in puts) {
                val old = tx.db.committedGet(store, entry.key)
                if (old != null) {
                    delta -= StorageArea.utf8Bytes(entry.key) + StorageArea.utf8Bytes(old)
                }
                delta += StorageArea.utf8Bytes(entry.key) + StorageArea.utf8Bytes(entry.value)
            }
            for (key in tx.deletesFor(store)) {
                if (!puts.containsKey(key)) {
                    val old = tx.db.committedGet(store, key)
                    if (old != null) {
                        delta -= StorageArea.utf8Bytes(key) + StorageArea.utf8Bytes(old)
                    }
                }
            }
        }
        return usageBytes() + delta
    }
}

/**
 * IndexedDB del motor (T033 v1): particionado por (topSite, origin) igual
 * que StorageManager, con versionado por base (VersionError al pedir una
 * version menor; upgrade que PRESERVA los datos), transacciones con buffer
 * y cuota por particion comprobada al commit. Limitaciones v1: claves y
 * valores string, sin indices/cursors, sin eventos de version, en memoria.
 */
class IndexedDb(private val quotaBytes: Long = 10_000_000L) {

    private val partitions = LinkedHashMap<String, Partition>()

    fun open(topSite: Site, origin: Origin, name: String, version: Long? = null): IdbDatabase {
        val p = partition(topSite, origin)
        val existing = p.dbs[name]
        if (version == null) {
            if (existing != null) return existing
            val db = IdbDatabase(name, 1L, p)
            p.dbs[name] = db
            return db
        }
        if (existing == null) {
            val db = IdbDatabase(name, version, p)
            p.dbs[name] = db
            return db
        }
        if (version < existing.version) {
            throw IdbException("VersionError", "version pedida " + version + " menor que la existente " + existing.version)
        }
        existing.version = version
        return existing
    }

    fun deleteDatabase(topSite: Site, origin: Origin, name: String): Boolean {
        val p = partitions[partitionKey(topSite, origin)] ?: return false
        return p.dbs.remove(name) != null
    }

    fun databaseNames(topSite: Site, origin: Origin): List<String> {
        val p = partitions[partitionKey(topSite, origin)]
        return p?.dbs?.keys?.toList() ?: emptyList()
    }

    fun partitionCount(): Int = partitions.size

    /** T044 v1 (R0104, RES-0056): borra TODAS las particiones; devuelve cuantas. */
    fun clearAll(): Int {
        val removed = partitions.size
        partitions.clear()
        return removed
    }

    internal fun partition(topSite: Site, origin: Origin): Partition =
        partitions.getOrPut(partitionKey(topSite, origin)) { Partition(quotaBytes) }

    private fun partitionKey(topSite: Site, origin: Origin): String =
        "idb|" + siteKey(topSite) + "|" + originKey(origin)

    private fun siteKey(site: Site): String = site.scheme + "://" + site.registrableDomain

    private fun originKey(origin: Origin): String = when (origin) {
        is Origin.Tuple -> origin.scheme + "://" + origin.host + ":" + origin.port
        is Origin.Opaque -> "opaque[" + origin.reason + "]"
    }

    /** Snapshot plano de todas las bases para persistencia (T033 v3, RES-0044). */
    internal fun snapshotDatabases(): List<IdbDbSnapshot> {
        val out = ArrayList<IdbDbSnapshot>()
        for ((key, p) in partitions) {
            for (db in p.dbs.values) {
                out.add(IdbDbSnapshot(key, db.name, db.version, db.storesSnapshot(), LinkedHashMap(db.keyPaths)))
            }
        }
        return out
    }

    /** Particion por clave compuesta de persistencia (T033 v3). */
    internal fun ensurePartition(key: String): Partition =
        partitions.getOrPut(key) { Partition(quotaBytes) }

    /** Restaura bases persistidas con merge por particion (T033 v3). */
    internal fun restoreDatabases(snapshots: List<IdbDbSnapshot>) {
        for (s in snapshots) {
            val p = ensurePartition(s.partitionKey)
            val db = p.dbs.getOrPut(s.name) { IdbDatabase(s.name, s.version, p) }
            db.version = s.version
            db.restoreStores(s.stores, s.keyPaths)
        }
    }
}

/** Snapshot de una base para la persistencia en disco (T033 v3, RES-0044). */
internal class IdbDbSnapshot(
    internal val partitionKey: String,
    internal val name: String,
    internal val version: Long,
    internal val stores: Map<String, Map<String, String>>,
    internal val keyPaths: Map<String, String?>,
)
