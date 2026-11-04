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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reloj falso del espejo TS validado offline. */
private class FireClock(var now: Long) : WallClock {
    override fun nowMillis(): Long = now
}

/** Directorio temporal de persistencia por test. */
private fun tempDirT045(): File = Files.createTempDirectory("nia-t045").toFile()

/** Entrada comprometida en la base demo del origen example.com. */
private fun commitDemo(idb: IndexedDb, value: String) {
    val db = idb.open(Site("https", "example.com"), Origin.Tuple("https", "example.com", 443), "demo")
    if (!db.storeNames.contains("s")) db.createObjectStore("s")
    val tx = db.transaction("s")
    tx.store("s").put("k", value)
    tx.commit()
}

/** Respuesta 200 fresca en la cache. */
private fun store200T045(cache: HttpCache) {
    cache.store("https://example.com/", FetchResponse(200, mapOf("cache-control" to "max-age=60"), "hola".toByteArray()))
}

/** Guarda una cookie demo, un item LOCAL, una entrada idb y una de cache. */
private fun storeDemoT045(p: BrowserProfile) {
    p.cookies.storeFromResponse("example.com", true, "demo=1")
    p.storage.area(
        StorageScope.LOCAL,
        Site("https", "example.com"),
        Origin.Tuple("https", "example.com", 443)
    ).setItem("demo", "1")
    commitDemo(p.idb, "x")
    store200T045(p.cache)
}

/**
 * Espejo Kotlin del gemelo TS validado offline (26 escenarios): cada
 * escenario del gemelo tiene su prueba aqui contra las clases reales.
 * T045 v1 (R0106, RES-0057): Fire con niveles SESION y TOTAL.
 */
class FireTest {

    private val clock = FireClock(1000L)
    private val site = Site("https", "example.com")
    private val origin = Origin.Tuple("https", "example.com", 443)

    @Test
    fun sesionSoloMuereSessionStorage() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, origin).setItem("b", "2")
        assertEquals(FireReport(1, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
        assertEquals(1, p.storage.partitionCount())
        assertEquals("1", p.storage.area(StorageScope.LOCAL, site, origin).getItem("a"))
    }

    @Test
    fun sesionCuentaParticionesSession() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.SESSION, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, Origin.Tuple("https", "otro.com", 443)).setItem("b", "2")
        assertEquals(FireReport(2, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
    }

    @Test
    fun sesionNoTocaCookies() {
        val p = BrowserProfile.incognito(clock)
        p.cookies.storeFromResponse("example.com", true, "demo=1")
        DataFire.apply(FireLevel.SESION, p)
        assertEquals(1, p.cookies.count())
    }

    @Test
    fun sesionNoTocaCache() {
        val p = BrowserProfile.incognito(clock)
        store200T045(p.cache)
        DataFire.apply(FireLevel.SESION, p)
        assertEquals(1, p.cache.count())
    }

    @Test
    fun sesionNoTocaIdb() {
        val p = BrowserProfile.incognito(clock)
        commitDemo(p.idb, "x")
        DataFire.apply(FireLevel.SESION, p)
        assertEquals(1, p.idb.partitionCount())
    }

    @Test
    fun sesionVaciaReportaCeros() {
        val p = BrowserProfile.incognito(clock)
        assertEquals(FireReport(0, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
    }

    @Test
    fun sesionSobrePerfilNormal() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, origin).setItem("b", "2")
        assertEquals(FireReport(1, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
        assertEquals("1", p.storage.area(StorageScope.LOCAL, site, origin).getItem("a"))
    }

    @Test
    fun sesionTrasSesionReportaCeros() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.SESSION, site, origin).setItem("a", "1")
        assertEquals(FireReport(1, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
        assertEquals(FireReport(0, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
    }

    @Test
    fun sesionPersisteLocalTrasSave() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, origin).setItem("b", "2")
        DataFire.apply(FireLevel.SESION, p)
        assertEquals(StoragePersistence.Persisted(1, 1, 0), p.save())
    }

    @Test
    fun sesionNoTocaDisco() {
        val dir = tempDirT045()
        val p = BrowserProfile.normal(clock, dir)
        storeDemoT045(p)
        p.save()
        assertEquals(FireReport(0, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals("1", p2.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
        assertTrue(p2.idb.databaseNames(site, origin).contains("demo"))
    }

    @Test
    fun sesionTresParticiones() {
        val p = BrowserProfile.incognito(clock)
        p.storage.area(StorageScope.SESSION, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.SESSION, site, Origin.Tuple("https", "otro.com", 443)).setItem("b", "2")
        p.storage.area(StorageScope.SESSION, site, Origin.Tuple("https", "tercero.com", 443)).setItem("c", "3")
        assertEquals(FireReport(3, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
    }

    @Test
    fun totalReportaTodoDelPerfil() {
        val p = BrowserProfile.incognito(clock)
        storeDemoT045(p)
        assertEquals(FireReport(0, 1, 1, 1, 1), DataFire.apply(FireLevel.TOTAL, p))
    }

    @Test
    fun totalDejaPerfilVacio() {
        val p = BrowserProfile.incognito(clock)
        storeDemoT045(p)
        DataFire.apply(FireLevel.TOTAL, p)
        assertEquals(0, p.cookies.count())
        assertEquals(0, p.cache.count())
        assertEquals(0, p.storage.partitionCount())
        assertEquals(0, p.idb.partitionCount())
    }

    @Test
    fun totalSobrePerfilNormal() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        storeDemoT045(p)
        assertEquals(FireReport(0, 1, 1, 1, 1), DataFire.apply(FireLevel.TOTAL, p))
    }

    @Test
    fun totalBorraArchivoPersistido() {
        val dir = tempDirT045()
        val p = BrowserProfile.normal(clock, dir)
        storeDemoT045(p)
        p.save()
        assertTrue(File(dir, "nia-storage.nia").exists())
        DataFire.apply(FireLevel.TOTAL, p)
        assertFalse(File(dir, "nia-storage.nia").exists())
    }

    @Test
    fun totalSinArchivoPrevioNoFalla() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        assertEquals(FireReport(0, 0, 0, 0, 0), DataFire.apply(FireLevel.TOTAL, p))
    }

    @Test
    fun totalRecargaEnVacio() {
        val dir = tempDirT045()
        val p = BrowserProfile.normal(clock, dir)
        storeDemoT045(p)
        p.save()
        DataFire.apply(FireLevel.TOTAL, p)
        val p2 = BrowserProfile.normal(clock, dir)
        assertEquals(0, p2.storage.partitionCount())
        assertEquals(0, p2.idb.partitionCount())
    }

    @Test
    fun totalDobleReportaCeros() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        storeDemoT045(p)
        assertEquals(FireReport(0, 1, 1, 1, 1), DataFire.apply(FireLevel.TOTAL, p))
        assertEquals(FireReport(0, 0, 0, 0, 0), DataFire.apply(FireLevel.TOTAL, p))
    }

    @Test
    fun totalTrasTotalPersisteVacio() {
        val dir = tempDirT045()
        val p = BrowserProfile.normal(clock, dir)
        storeDemoT045(p)
        p.save()
        DataFire.apply(FireLevel.TOTAL, p)
        assertEquals(StoragePersistence.Persisted(0, 0, 0), p.save())
        assertTrue(File(dir, "nia-storage.nia").exists())
    }

    @Test
    fun totalReportaMultiples() {
        val p = BrowserProfile.incognito(clock)
        p.cookies.storeFromResponse("example.com", true, "demo=1")
        p.cookies.storeFromResponse("otro.com", true, "otro=1")
        store200T045(p.cache)
        p.storage.area(StorageScope.LOCAL, site, origin).setItem("a", "1")
        p.storage.area(StorageScope.LOCAL, site, Origin.Tuple("https", "otro.com", 443)).setItem("b", "2")
        commitDemo(p.idb, "x")
        assertEquals(FireReport(0, 2, 1, 2, 1), DataFire.apply(FireLevel.TOTAL, p))
    }

    @Test
    fun aislamientoEntrePerfiles() {
        val p1 = BrowserProfile.normal(clock, tempDirT045())
        val p2 = BrowserProfile.normal(clock, tempDirT045())
        storeDemoT045(p1)
        storeDemoT045(p2)
        DataFire.apply(FireLevel.TOTAL, p1)
        assertEquals(0, p1.cookies.count())
        assertEquals(0, p1.storage.partitionCount())
        assertEquals(1, p2.cookies.count())
        assertEquals(1, p2.storage.partitionCount())
        assertEquals(1, p2.idb.partitionCount())
    }

    @Test
    fun totalNoTocaDiscoAjeno() {
        val dirA = tempDirT045()
        val dirB = tempDirT045()
        val p1 = BrowserProfile.normal(clock, dirA)
        storeDemoT045(p1)
        p1.save()
        val p2 = BrowserProfile.normal(clock, dirB)
        storeDemoT045(p2)
        p2.save()
        DataFire.apply(FireLevel.TOTAL, p1)
        assertFalse(File(dirA, "nia-storage.nia").exists())
        assertTrue(File(dirB, "nia-storage.nia").exists())
        val p3 = BrowserProfile.normal(clock, dirB)
        assertEquals("1", p3.storage.area(StorageScope.LOCAL, site, origin).getItem("demo"))
    }

    @Test
    fun deletePersistedIncognitoFalse() {
        val p = BrowserProfile.incognito(clock)
        assertFalse(p.deletePersisted())
    }

    @Test
    fun deletePersistedBorraTrasSave() {
        val dir = tempDirT045()
        val p = BrowserProfile.normal(clock, dir)
        storeDemoT045(p)
        p.save()
        assertTrue(p.deletePersisted())
        assertFalse(File(dir, "nia-storage.nia").exists())
        assertFalse(p.deletePersisted())
    }

    @Test
    fun deletePersistedSinArchivoFalse() {
        val p = BrowserProfile.normal(clock, tempDirT045())
        assertFalse(p.deletePersisted())
    }

    @Test
    fun sesionLuegoTotal() {
        val p = BrowserProfile.incognito(clock)
        storeDemoT045(p)
        p.storage.area(StorageScope.SESSION, site, origin).setItem("s", "1")
        assertEquals(FireReport(1, 0, 0, 0, 0), DataFire.apply(FireLevel.SESION, p))
        assertEquals(FireReport(0, 1, 1, 1, 1), DataFire.apply(FireLevel.TOTAL, p))
    }
}
