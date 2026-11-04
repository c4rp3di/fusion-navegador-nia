package nia.engine.storage

import java.io.File
import java.io.IOException

/**
 * Persistencia en disco del storage particionado (T033 v3, RES-0044):
 * guarda y restaura el StorageManager (SOLO areas LOCAL: el sessionStorage
 * muere con la sesion, como el spec Web Storage) y el IndexedDb completo
 * (bases con version, keyPaths, stores y entradas comprometidas) en UN
 * archivo de texto UTF-8 con escritura atomica (tmp + rename: un volcado
 * nunca deja un archivo a medias) y formato propio de lineas tabuladas
 * con escapes (\t tab, \n salto, \r CR, \\ backslash): sin dependencias
 * de serializacion, el archivo es depurable a simple vista.
 *
 * Contrato: load() parsea TODO el archivo ANTES de restaurar (un archivo
 * corrupto lanza PersistenceException SIN tocar el estado vivo); el restore
 * hace merge por partitionKey (las areas vivas no persistidas sobreviven);
 * las cuotas NO se persisten, son configuracion de quien construye el
 * motor (un area restaurada confia en lo persistido y las escrituras
 * nuevas vuelven a comprobar la cuota del manager destino).
 *
 * Formato (campos tabulados, escapados):
 * nia-storage<TAB>1                        cabecera obligatoria
 * A<TAB>scope<TAB>partitionKey            inicio de area Web Storage
 * a<TAB>clave<TAB>valor                    entrada del area (en orden)
 * D<TAB>idbPartitionKey<TAB>nombre<TAB>ver base de IndexedDB
 * k<TAB>store<TAB>keyPath                  keyPath del store (solo no-null)
 * e<TAB>store<TAB>clave<TAB>valor          entrada comprometida del store
 *
 * Exclusiones v3: la integracion del ciclo de vida en :app (CUANDO guardar
 * al navegar) llega con el renderer real; sin cifrado ni compactacion del
 * archivo; las transacciones vivas no se persisten (solo lo comprometido).
 */
class StoragePersistence(private val dir: File) {

    private val fileName = "nia-storage.nia"

    /** Resultado de un save/load: lo que viajo por el disco. */
    data class Persisted(val areas: Int, val entries: Int, val databases: Int)

    /** Excepcion tipeda de formato: el archivo no es un volcado valido. */
    class PersistenceException(message: String) : RuntimeException(message)

    /**
     * Guarda el estado en un unico archivo con escritura atomica.
     * Devuelve lo guardado; IOException si el directorio no se puede escribir.
     */
    fun save(manager: StorageManager, idb: IndexedDb): Persisted {
        val areas = manager.snapshotAreas().filter { it.scope == StorageScope.LOCAL }
        val databases = idb.snapshotDatabases()
        val sb = StringBuilder()
        sb.append("nia-storage").append('\t').append(1).append('\n')
        var entries = 0
        for (area in areas) {
            sb.append('A').append('\t').append(area.scope.name).append('\t').append(esc(area.partitionKey)).append('\n')
            for ((k, v) in area.entries) {
                sb.append('a').append('\t').append(esc(k)).append('\t').append(esc(v)).append('\n')
                entries++
            }
        }
        for (db in databases) {
            sb.append('D').append('\t').append(esc(db.partitionKey)).append('\t').append(esc(db.name)).append('\t').append(db.version).append('\n')
            for ((store, path) in db.keyPaths) {
                if (path != null) {
                    sb.append('k').append('\t').append(esc(store)).append('\t').append(esc(path)).append('\n')
                }
            }
            for ((store, data) in db.stores) {
                for ((k, v) in data) {
                    sb.append('e').append('\t').append(esc(store)).append('\t').append(esc(k)).append('\t').append(esc(v)).append('\n')
                    entries++
                }
            }
        }
        writeAtomic(sb.toString())
        return Persisted(areas.size, entries, databases.size)
    }

    /**
     * Restaura el archivo en manager e idb con merge por particion.
     * Devuelve null si nunca se guardo; PersistenceException si el archivo
     * esta corrupto (SIN restaurar nada); IOException si no se puede leer.
     */
    fun load(manager: StorageManager, idb: IndexedDb): Persisted? {
        val f = File(dir, fileName)
        if (!f.exists()) return null
        val text = f.readText(Charsets.UTF_8)
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0] != "nia-storage\t1") {
            throw PersistenceException("cabecera de persistencia no reconocida")
        }
        val areas = ArrayList<AreaParse>()
        val dbs = ArrayList<DbParse>()
        var area: AreaParse? = null
        var db: DbParse? = null
        var entries = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "A" -> {
                    expectFields(parts, 3, i)
                    val scope = when (parts[1]) {
                        "LOCAL" -> StorageScope.LOCAL
                        "SESSION" -> StorageScope.SESSION
                        else -> throw PersistenceException("scope desconocido en linea " + (i + 1))
                    }
                    area = AreaParse(scope, unesc(parts[2], i))
                    db = null
                    areas.add(area)
                }
                "a" -> {
                    expectFields(parts, 3, i)
                    val a = area ?: throw PersistenceException("entrada de area sin area en linea " + (i + 1))
                    a.entries[unesc(parts[1], i)] = unesc(parts[2], i)
                    entries++
                }
                "D" -> {
                    expectFields(parts, 4, i)
                    val version = parts[3].toLongOrNull() ?: throw PersistenceException("version no numerica en linea " + (i + 1))
                    db = DbParse(unesc(parts[1], i), unesc(parts[2], i), version)
                    area = null
                    dbs.add(db)
                }
                "k" -> {
                    expectFields(parts, 3, i)
                    val d = db ?: throw PersistenceException("keyPath sin base en linea " + (i + 1))
                    d.keyPaths[unesc(parts[1], i)] = unesc(parts[2], i)
                }
                "e" -> {
                    expectFields(parts, 4, i)
                    val d = db ?: throw PersistenceException("entrada sin base en linea " + (i + 1))
                    val data = d.stores.getOrPut(unesc(parts[1], i)) { LinkedHashMap() }
                    data[unesc(parts[2], i)] = unesc(parts[3], i)
                    entries++
                }
                else -> throw PersistenceException("linea desconocida en la persistencia: " + (i + 1))
            }
        }
        // Parseo completo sin errores: restaurar con merge por particion.
        manager.restoreAreas(areas.map { StorageAreaSnapshot(it.scope, it.key, it.entries) })
        idb.restoreDatabases(dbs.map { IdbDbSnapshot(it.key, it.name, it.version, it.stores, it.keyPaths) })
        return Persisted(areas.size, entries, dbs.size)
    }

    private fun expectFields(parts: List<String>, n: Int, line: Int) {
        if (parts.size != n) {
            throw PersistenceException("campos inesperados en linea " + (line + 1) + ": " + parts.size + " en vez de " + n)
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unesc(s: String, line: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') {
                sb.append(ch)
                i++
                continue
            }
            if (i + 1 >= s.length) {
                throw PersistenceException("escape incompleto en linea " + (line + 1))
            }
            when (s[i + 1]) {
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> throw PersistenceException("escape desconocido en linea " + (line + 1))
            }
            i += 2
        }
        return sb.toString()
    }

    private fun writeAtomic(text: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("no se puede crear el directorio de persistencia: " + dir)
        }
        val tmp = File(dir, fileName + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val dest = File(dir, fileName)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}

/** Estado de parseo de un area (uso interno de load). */
private class AreaParse(val scope: StorageScope, val key: String) {
    val entries = LinkedHashMap<String, String>()
}

/** Estado de parseo de una base (uso interno de load). */
private class DbParse(val key: String, val name: String, val version: Long) {
    val keyPaths = LinkedHashMap<String, String?>()
    val stores = LinkedHashMap<String, LinkedHashMap<String, String>>()
}
