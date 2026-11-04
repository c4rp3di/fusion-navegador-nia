package nia.engine.profile

import nia.contracts.WallClock
import nia.engine.net.FetchResponse
import nia.engine.net.HttpCache
import nia.engine.storage.IndexedDb
import nia.engine.storage.StoragePersistence
import nia.engine.storage.StorageScope
import nia.engine.url.Origin
import nia.engine.url.Site
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reloj falso del espejo TS validado offline. */
private class FakeClock(var now: Long) : WallClock {
    override fun nowMillis(): Long = now
}

/** Directorio temporal de persistencia por test. */
private fun tempDir(): File = Files.createTempDirectory("nia-t044").toFile()

/** Entrada comprometida en la base demo del origen example.com. */
private fun commitEntry(idb: IndexedDb, value: String) {
    val db = idb.open(Site("https", "example.com"), Origin.Tuple("https", "example.com", 443), "demo")
    if (!db.storeNames.contains("s")) db.createObjectStore("s")
    val tx = db.transaction("s")
    tx.store("s").put("k", value)
    tx.commit()
}

/** Respuesta 200 fresca en la cache. */
private fun store200(cache: HttpCache) {
    cache.store("https://example.com/", FetchResponse(200, mapOf("cache-control" to "max-age=60"), "hola".toByteArray()))
}

/** Guarda una cookie demo, un item LOCAL, una entrada idb y una de cache. */
private fun storeDemo(p: BrowserProfile) {
    p.cookies.storeFromResponse("example.com", true, "demo=1")
    p.storage.area(
        StorageScope.LOCAL,
        Site("https", "example.com"),
        Origin.Tuple("https", "example.com", 443)
    ).setItem("demo", "1")
    commitEntry(p.idb, "x")
    store200(p.cache)
}

/**
 * Espejo Kotlin del gemelo TS validado offline (26 escenarios): cada
 * escenario del gemelo tiene su prueba aqui contra las clases reales.
 * T044 v1 (R0104, RES-0056): perfiles y modo incognito.
 */
class ProfileTest {

    private val clock = FakeClock(1000L)
    private val site = Site("https", "example.com")
    private val origin = Origin.Tuple("https", "example.com", 443)

    @Test
    fun incognitoEsIncognito() {
        assertTrue(BrowserProfile.incognito(clock).isIncognito())
    }

    @Test
    fun normalNoEsIncognito() {
        assertFalse(BrowserProfile.normal(clock, tempDir()).isIncognito())
    }

    @Test
    fun incognitoArrancaVacio() {
        val p = BrowserProfile.incognito(clock)
        assertEquals(0, p.cookies.count())
        assertEquals(0, p.cache.count())
        assertEquals(0, p.storage.partitionCount())
        assertEquals(0, p.idb.partitionCount())
    }

    @Test
    fun normalArrancaVacioConDirNuevo() {
        val dir = tempDir()
        val p = BrowserProfile.normal(clock, dir)
        assertEquals(0, p.cookies.count())
        assertEquals(0, p.storage.partitionCount())
        assertEquals(0, p.idb.partitionCount())
        assertEquals(StoragePersistence.Persisted(0, 0, 0), p.save())
        assertTrue(File(dir, "nia-storage.nia").exists())
    }

    @Test
    fun incognitoSaveDevuelveNulo() {
        val p = BrowserProfile.incognito(clock)
        storeDemo(p)
        assertNull(p.save())
    }

    @Test
    fun incognitoTodoMuereConWipe() {
        val p = BrowserProfile.incognito(clock)
        storeDemo(p)
        assertEquals(WipeReport(1, 1, 1, 1), p.wipe())
        assertEquals(0, p.cookies.count())
        assertEquals(0, p.cache.count())
        assertEquals(0, p.storage.partitionCount())
        assertEquals(0, p.idb.partitionCount())
    }

    @Test
    fun normalSavePersisteStorageEIdb() {
        val p = BrowserProfile.normal(clock, tempDir())
        storeDemo(p)
        assertEquals(StoragePersistence.Persisted(1, 2, 1), p.save())
    }

    @Test
    fun normalRecargaEnNuevaInstancia() {
        val dir = tempDir()
        val p1 = BrowserProfile.normal(clock, dir)
        storeDemo(p1)
        p1.save()
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals("1", p2.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
        assertTrue(p2.idb.databaseNames(site, origin).contains("demo"))
        assertEquals("x", p2.idb.open(site, origin, "demo").transaction("s").store("s").get("k"))
    }

    @Test
    fun aislamientoCookies() {
        val pi = BrowserProfile.incognito(clock)
        val pn = BrowserProfile.normal(clock, tempDir())
        pi.cookies.storeFromResponse("example.com", true, "demo=1")
        pn.cookies.storeFromResponse("example.com", true, "otro=1")
        pi.wipe()
        assertEquals(0, pi.cookies.count())
        assertEquals(1, pn.cookies.count())
    }

    @Test
    fun aislamientoStorage() {
        val pi = BrowserProfile.incognito(clock)
        val pn = BrowserProfile.normal(clock, tempDir())
        pi.storage.area(StorageScope.LOCAL, site, origin).setItem("demo", "1")
        pn.storage.area(StorageScope.LOCAL, site, origin).setItem("otro", "2")
        pi.wipe()
        assertEquals(0, pi.storage.partitionCount())
        assertEquals("2", pn.storage.area(StorageScope.LOCAL, site, origin).getItem("otro"))
    }

    @Test
    fun aislamientoIdb() {
        val pi = BrowserProfile.incognito(clock)
        val pn = BrowserProfile.normal(clock, tempDir())
        commitEntry(pi.idb, "x")
        commitEntry(pn.idb, "y")
        pi.wipe()
        assertEquals(0, pi.idb.partitionCount())
        assertEquals(1, pn.idb.partitionCount())
    }

    @Test
    fun aislamientoCache() {
        val pi = BrowserProfile.incognito(clock)
        val pn = BrowserProfile.normal(clock, tempDir())
        store200(pi.cache)
        store200(pn.cache)
        pi.wipe()
        assertEquals(0, pi.cache.count())
        assertEquals(1, pn.cache.count())
    }

    @Test
    fun incognitoCookiesVivos() {
        val p = BrowserProfile.incognito(clock)
        p.cookies.storeFromResponse("example.com", true, "demo=1")
        assertEquals(1, p.cookies.count())
        assertEquals("demo=1", p.cookies.cookieHeader("example.com", "/", true))
    }

    @Test
    fun incognitoStorageVivos() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("demo", "1")
        assertEquals("1", p.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
        assertEquals(1, p.storage.partitionCount())
    }

    @Test
    fun incognitoIdbVivos() {
        val p = BrowserProfile.incognito(clock)
        commitEntry(p.idb, "x")
        assertEquals(1, p.idb.partitionCount())
        assertTrue(p.idb.databaseNames(site, origin).contains("demo"))
    }

    @Test
    fun incognitoCacheVivos() {
        val p = BrowserProfile.incognito(clock)
        store200(p.cache)
        assertEquals(1, p.cache.count())
        assertNotNull(p.cache.entryFor("https://example.com/"))
    }

    @Test
    fun wipeReportCuentaTodo() {
        val p = BrowserProfile.incognito(clock)
        p.cookies.storeFromResponse("example.com", true, "demo=1")
        p.cookies.storeFromResponse("otro.com", true, "otro=1")
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.LOCAL, site, Origin.Tuple("https", "otro.com", 443)).setItem("b", "2")
        commitEntry(p.idb, "x")
        store200(p.cache)
        assertEquals(WipeReport(2, 1, 2, 1), p.wipe())
    }

    @Test
    fun wipeDejaTodoVacio() {
        val p = BrowserProfile.incognito(clock)
        storeDemo(p)
        p.wipe()
        assertEquals(0, p.cookies.count())
        assertEquals(0, p.cache.count())
        assertEquals(0, p.storage.partitionCount())
        assertEquals(0, p.idb.partitionCount())
    }

    @Test
    fun wipeDosVecesReportaCeros() {
        val p = BrowserProfile.incognito(clock)
        storeDemo(p)
        assertEquals(WipeReport(1, 1, 1, 1), p.wipe())
        assertEquals(WipeReport(0, 0, 0, 0), p.wipe())
    }

    @Test
    fun normalSaveTrasWipePersisteVacio() {
        val dir = tempDir()
        val p = BrowserProfile.normal(clock, dir)
        storeDemo(p)
        assertEquals(StoragePersistence.Persisted(1, 2, 1), p.save())
        p.wipe()
        assertEquals(StoragePersistence.Persisted(0, 0, 0), p.save())
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals(0, p2.storage.partitionCount())
        assertEquals(0, p2.idb.partitionCount())
    }

    @Test
    fun incognitoWipeNoTocaDiscoAjeno() {
        val dir = tempDir()
        val pn = BrowserProfile.normal(clock, dir)
        storeDemo(pn)
        pn.save()
        val pi = BrowserProfile.incognito(clock)
        storeDemo(pi)
        pi.wipe()
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals("1", p2.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
        assertTrue(p2.idb.databaseNames(site, origin).contains("demo"))
    }

    @Test
    fun normalWipeNoBorraDisco() {
        val dir = tempDir()
        val p = BrowserProfile.normal(clock, dir)
        storeDemo(p)
        p.save()
        val file = File(dir, "nia-storage.nia")
        val before = file.length()
        assertTrue(before > 0)
        p.wipe()
        assertEquals(before, file.length())
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals("1", p2.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
    }

    @Test
    fun persistenciaSoloAreasLocal() {
        val p = BrowserProfile.normal(clock, tempDir())
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, origin).setItem("b", "2")
        assertEquals(StoragePersistence.Persisted(1, 1, 0), p.save())
    }

    @Test
    fun perfilesTienenMotoresDistintos() {
        val p1 = BrowserProfile.incognito(clock)
        val p2 = BrowserProfile.incognito(clock)
        assertNotSame(p1.cookies, p2.cookies)
        assertNotSame(p1.cache, p2.cache)
        assertNotSame(p1.storage, p2.storage)
        assertNotSame(p1.idb, p2.idb)
    }

    @Test
    fun wipeBorraSoloEsePerfil() {
        val p1 = BrowserProfile.normal(clock, tempDir())
        val p2 = BrowserProfile.normal(clock, tempDir())
        storeDemo(p1)
        storeDemo(p2)
        p1.wipe()
        assertEquals(0, p1.cookies.count())
        assertEquals(0, p1.storage.partitionCount())
        assertEquals(0, p1.idb.partitionCount())
        assertEquals(1, p2.cookies.count())
        assertEquals(1, p2.storage.partitionCount())
        assertEquals(1, p2.idb.partitionCount())
    }

    @Test
    fun incognitoStorageLocalMuereConWipe() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("demo", "1")
        assertEquals(1, p.storage.partitionCount())
        p.wipe()
        assertEquals(0, p.storage.partitionCount())
    }
}
