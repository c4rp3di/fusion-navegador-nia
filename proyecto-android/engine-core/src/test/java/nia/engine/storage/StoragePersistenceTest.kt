package nia.engine.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Tests de la persistencia en disco del storage particionado (T033 v3,
 * RES-0044): round trip de las areas LOCAL del StorageManager y de las
 * bases del IndexedDb en un unico archivo UTF-8 con escritura atomica y
 * formato de lineas tabuladas con escapes. El sessionStorage NO se
 * persiste (muere con la sesion, como el spec Web Storage), las cuotas
 * son configuracion del manager destino y un archivo corrupto NO toca
 * el estado vivo (el parseo completo va antes de restaurar).
 */
class StoragePersistenceTest {

    private fun tempDir(): File = Files.createTempDirectory("nia-storage-persistence-").toFile()

    private val origin = Origin.Tuple("https", "app.nia.dev", 443)
    private val site = Site.fromOrigin(origin)
    private val otroOrigin = Origin.Tuple("https", "otro.org", 8443)
    private val otroSite = Site.fromOrigin(otroOrigin)

    @Test fun loadSinArchivoDevuelveNull() {
        val p = StoragePersistence(tempDir())
        val m = StorageManager()
        val i = IndexedDb()
        assertNull(p.load(m, i))
        assertEquals(0, m.partitionCount())
    }

    @Test fun localStorageRoundTrip() {
        val dir = tempDir()
        val m1 = StorageManager()
        val a1 = m1.area(StorageScope.LOCAL, site, origin)
        a1.setItem("k", "v")
        a1.setItem("k2", "v2")
        val saved = StoragePersistence(dir).save(m1, IndexedDb())
        assertEquals(1, saved.areas)
        assertEquals(2, saved.entries)
        assertEquals(0, saved.databases)
        val m2 = StorageManager()
        val loaded = StoragePersistence(dir).load(m2, IndexedDb())
        assertTrue(loaded != null)
        val a2 = m2.area(StorageScope.LOCAL, site, origin)
        assertEquals("v", a2.getItem("k"))
        assertEquals("v2", a2.getItem("k2"))
        assertEquals(2, a2.length)
        assertEquals(1, m2.partitionCount())
    }

    @Test fun sessionStorageNoSePersiste() {
        val dir = tempDir()
        val m1 = StorageManager()
        m1.area(StorageScope.SESSION, site, origin).setItem("k", "v")
        val saved = StoragePersistence(dir).save(m1, IndexedDb())
        assertEquals(0, saved.areas)
        val m2 = StorageManager()
        val loaded = StoragePersistence(dir).load(m2, IndexedDb())
        assertTrue(loaded != null)
        assertEquals(0, loaded.areas)
        assertEquals(0, m2.partitionCount())
    }

    @Test fun clavesYValoresConEscapesRoundTrip() {
        val dir = tempDir()
        val k = "a\nb\tc\\d|e"
        val v = "x\r\ny\\z\tw" + "\u00f1"
        val m1 = StorageManager()
        m1.area(StorageScope.LOCAL, site, origin).setItem(k, v)
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager()
        StoragePersistence(dir).load(m2, IndexedDb())
        assertEquals(v, m2.area(StorageScope.LOCAL, site, origin).getItem(k))
    }

    @Test fun ordenDeInsercionPreservado() {
        val dir = tempDir()
        val m1 = StorageManager()
        val a1 = m1.area(StorageScope.LOCAL, site, origin)
        a1.setItem("c", "3")
        a1.setItem("a", "1")
        a1.setItem("b", "2")
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager()
        StoragePersistence(dir).load(m2, IndexedDb())
        val a2 = m2.area(StorageScope.LOCAL, site, origin)
        assertEquals("c", a2.key(0))
        assertEquals("a", a2.key(1))
        assertEquals("b", a2.key(2))
    }

    @Test fun particionesPorTopSiteSeparadas() {
        val dir = tempDir()
        val m1 = StorageManager()
        m1.area(StorageScope.LOCAL, site, origin).setItem("k", "del-uno")
        m1.area(StorageScope.LOCAL, otroSite, otroOrigin).setItem("k", "del-otro")
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager()
        StoragePersistence(dir).load(m2, IndexedDb())
        assertEquals("del-uno", m2.area(StorageScope.LOCAL, site, origin).getItem("k"))
        assertEquals("del-otro", m2.area(StorageScope.LOCAL, otroSite, otroOrigin).getItem("k"))
        assertEquals(2, m2.partitionCount())
    }

    @Test fun areaVaciaSeRestaura() {
        val dir = tempDir()
        val m1 = StorageManager()
        m1.area(StorageScope.LOCAL, site, origin)
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager()
        val loaded = StoragePersistence(dir).load(m2, IndexedDb())
        assertTrue(loaded != null)
        assertEquals(1, loaded.areas)
        assertEquals(1, m2.partitionCount())
        assertEquals(0, m2.area(StorageScope.LOCAL, site, origin).length)
    }

    @Test fun cuotaSigueActivaTrasRestore() {
        val dir = tempDir()
        val m1 = StorageManager()
        m1.area(StorageScope.LOCAL, site, origin).setItem("k", "12345")
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager(8)
        StoragePersistence(dir).load(m2, IndexedDb())
        val a2 = m2.area(StorageScope.LOCAL, site, origin)
        assertEquals("12345", a2.getItem("k"))
        assertFalse(a2.setItem("n", "abcd"))
        assertEquals(1, a2.length)
    }

    @Test fun idbRoundTripCompleto() {
        val dir = tempDir()
        val i1 = IndexedDb()
        val db1 = i1.open(site, origin, "demo", 3L)
        db1.createObjectStore("s1")
        db1.createObjectStore("s2")
        val tx1 = db1.transaction("s1", "s2")
        tx1.store("s1").put("k", "v")
        tx1.store("s2").put("k2", "v2")
        tx1.commit()
        val saved = StoragePersistence(dir).save(StorageManager(), i1)
        assertEquals(1, saved.databases)
        assertEquals(2, saved.entries)
        val i2 = IndexedDb()
        StoragePersistence(dir).load(StorageManager(), i2)
        assertEquals(listOf("demo"), i2.databaseNames(site, origin))
        val db2 = i2.open(site, origin, "demo")
        assertEquals(3L, db2.version)
        assertEquals(listOf("s1", "s2"), db2.storeNames)
        val tx2 = db2.transaction("s1", "s2")
        assertEquals("v", tx2.store("s1").get("k"))
        assertEquals("v2", tx2.store("s2").get("k2"))
    }

    @Test fun idbKeyPathPreservado() {
        val dir = tempDir()
        val i1 = IndexedDb()
        i1.open(site, origin, "d", 1L).createObjectStore("s", "id")
        StoragePersistence(dir).save(StorageManager(), i1)
        val i2 = IndexedDb()
        StoragePersistence(dir).load(StorageManager(), i2)
        val db2 = i2.open(site, origin, "d")
        assertEquals("id", db2.keyPaths["s"])
    }

    @Test fun idbStoreVacioPersiste() {
        val dir = tempDir()
        val i1 = IndexedDb()
        i1.open(site, origin, "d", 1L).createObjectStore("vacio")
        StoragePersistence(dir).save(StorageManager(), i1)
        val i2 = IndexedDb()
        StoragePersistence(dir).load(StorageManager(), i2)
        val db2 = i2.open(site, origin, "d")
        assertTrue(db2.storeNames.contains("vacio"))
        assertEquals(0, db2.transaction("vacio").store("vacio").count())
    }

    @Test fun idbVersionErrorSigueActivoTrasRestore() {
        val dir = tempDir()
        val i1 = IndexedDb()
        i1.open(site, origin, "d", 3L)
        StoragePersistence(dir).save(StorageManager(), i1)
        val i2 = IndexedDb()
        StoragePersistence(dir).load(StorageManager(), i2)
        val e = assertFailsWith<IdbException> { i2.open(site, origin, "d", 2L) }
        assertEquals("VersionError", e.name)
    }

    @Test fun idbCuotaAlCommitTrasRestore() {
        val dir = tempDir()
        val i1 = IndexedDb()
        val db1 = i1.open(site, origin, "d", 1L)
        db1.createObjectStore("s")
        val tx1 = db1.transaction("s")
        tx1.store("s").put("k", "123456789012345678901234")
        tx1.commit()
        StoragePersistence(dir).save(StorageManager(), i1)
        val i2 = IndexedDb(30)
        StoragePersistence(dir).load(StorageManager(), i2)
        val db2 = i2.open(site, origin, "d")
        assertEquals("123456789012345678901234", db2.transaction("s").store("s").get("k"))
        val tx = db2.transaction("s")
        tx.store("s").put("n", "abcde")
        val e = assertFailsWith<IdbException> { tx.commit() }
        assertEquals("QuotaExceededError", e.name)
        assertNull(db2.transaction("s").store("s").get("n"))
        assertEquals("123456789012345678901234", db2.transaction("s").store("s").get("k"))
    }

    @Test fun idbBorradaNoVuelve() {
        val dir = tempDir()
        val i1 = IndexedDb()
        val db1 = i1.open(site, origin, "d", 1L)
        db1.createObjectStore("s")
        val tx1 = db1.transaction("s")
        tx1.store("s").put("k", "v")
        tx1.commit()
        assertTrue(i1.deleteDatabase(site, origin, "d"))
        StoragePersistence(dir).save(StorageManager(), i1)
        val i2 = IndexedDb()
        StoragePersistence(dir).load(StorageManager(), i2)
        assertEquals(0, i2.databaseNames(site, origin).size)
    }

    @Test fun saveSobrescribeElEstado() {
        val dir = tempDir()
        val m1 = StorageManager()
        val a1 = m1.area(StorageScope.LOCAL, site, origin)
        a1.setItem("k", "antes")
        StoragePersistence(dir).save(m1, IndexedDb())
        a1.removeItem("k")
        a1.setItem("n", "despues")
        StoragePersistence(dir).save(m1, IndexedDb())
        val m2 = StorageManager()
        StoragePersistence(dir).load(m2, IndexedDb())
        val a2 = m2.area(StorageScope.LOCAL, site, origin)
        assertNull(a2.getItem("k"))
        assertEquals("despues", a2.getItem("n"))
    }

    @Test fun archivoCorruptoNoRestaura() {
        val dir = tempDir()
        val m = StorageManager()
        m.area(StorageScope.LOCAL, site, origin).setItem("k", "v")
        val f = File(dir, "nia-storage.nia")
        f.writeText("basura\ttotal\n", Charsets.UTF_8)
        val e = assertFailsWith<StoragePersistence.PersistenceException> {
            StoragePersistence(dir).load(m, IndexedDb())
        }
        val mensaje = e.message
        assertTrue(mensaje != null && mensaje.contains("cabecera"))
        assertEquals(1, m.partitionCount())
        assertEquals("v", m.area(StorageScope.LOCAL, site, origin).getItem("k"))
    }
}
