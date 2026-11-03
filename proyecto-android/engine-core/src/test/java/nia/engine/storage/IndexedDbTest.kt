package nia.engine.storage

import nia.engine.url.Origin
import nia.engine.url.Site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexedDbTest {

    private val siteA = Site("https", "a.com")
    private val siteB = Site("https", "b.com")
    private val originA = Origin.Tuple("https", "a.com", 443)
    private val originC = Origin.Tuple("https", "c.com", 443)

    private fun dbConStore(idb: IndexedDb, name: String = "test", store: String = "s"): IdbDatabase {
        val db = idb.open(siteA, originA, name)
        db.createObjectStore(store)
        return db
    }

    @Test
    fun openCreaConVersionUno() {
        val idb = IndexedDb()
        val db = idb.open(siteA, originA, "nueva")
        assertEquals(1L, db.version)
        assertEquals(listOf("nueva"), idb.databaseNames(siteA, originA))
    }

    @Test
    fun openReabreSinCambiarVersion() {
        val idb = IndexedDb()
        val db = dbConStore(idb)
        val re = idb.open(siteA, originA, "test")
        assertTrue(re === db)
        assertEquals(1L, re.version)
    }

    @Test
    fun openVersionMenorLanzaVersionError() {
        val idb = IndexedDb()
        idb.open(siteA, originA, "d", 5L)
        val e = assertFailsWith<IdbException> { idb.open(siteA, originA, "d", 3L) }
        assertEquals("VersionError", e.name)
    }

    @Test
    fun openUpgradePreservaDatos() {
        val idb = IndexedDb()
        val db = dbConStore(idb)
        val tx = db.transaction("s")
        tx.store("s").put("k", "v")
        tx.commit()
        val up = idb.open(siteA, originA, "test", 2L)
        assertTrue(up === db)
        assertEquals(2L, up.version)
        val tx2 = db.transaction("s")
        assertEquals("v", tx2.store("s").get("k"))
    }

    @Test
    fun createObjectStoreDuplicadoLanzaInvalidState() {
        val db = dbConStore(IndexedDb())
        val e = assertFailsWith<IdbException> { db.createObjectStore("s") }
        assertEquals("InvalidStateError", e.name)
    }

    @Test
    fun deleteObjectStoreQuitaDeStoreNames() {
        val db = dbConStore(IndexedDb())
        db.deleteObjectStore("s")
        assertEquals(0, db.storeNames.size)
    }

    @Test
    fun deleteObjectStoreInexistenteLanzaNotFound() {
        val db = dbConStore(IndexedDb())
        val e = assertFailsWith<IdbException> { db.deleteObjectStore("nope") }
        assertEquals("NotFoundError", e.name)
    }

    @Test
    fun transactionEnBaseCerradaLanzaInvalidState() {
        val db = dbConStore(IndexedDb())
        db.close()
        val e = assertFailsWith<IdbException> { db.transaction("s") }
        assertEquals("InvalidStateError", e.name)
    }

    @Test
    fun transactionVaciaLanzaInvalidState() {
        val db = dbConStore(IndexedDb())
        val e = assertFailsWith<IdbException> { db.transaction() }
        assertEquals("InvalidStateError", e.name)
    }

    @Test
    fun putGetCommitYConsultas() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        val st = tx.store("s")
        st.put("k1", "v1")
        st.put("k2", "v2")
        assertEquals("v1", st.get("k1"))
        assertEquals(listOf("k1", "k2"), st.keys())
        assertEquals(2, st.count())
        assertEquals(listOf(IdbEntry("k1", "v1"), IdbEntry("k2", "v2")), st.getAll())
        tx.commit()
        val tx2 = db.transaction("s")
        assertEquals("v1", tx2.store("s").get("k1"))
    }

    @Test
    fun getVeComprometidoYBuffer() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        tx.store("s").put("a", "va")
        tx.commit()
        val tx2 = db.transaction("s")
        val st = tx2.store("s")
        assertEquals("va", st.get("a"))
        st.put("a", "nueva")
        assertEquals("nueva", st.get("a"))
    }

    @Test
    fun deleteOcultaEnBufferYAplicaTrasCommit() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        tx.store("s").put("a", "va")
        tx.commit()
        val tx2 = db.transaction("s")
        val st = tx2.store("s")
        st.delete("a")
        assertNull(st.get("a"))
        assertEquals(0, st.count())
        tx2.commit()
        val tx3 = db.transaction("s")
        assertNull(tx3.store("s").get("a"))
    }

    @Test
    fun abortDescartaElBuffer() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        tx.store("s").put("k", "v")
        tx.abort()
        val tx2 = db.transaction("s")
        assertNull(tx2.store("s").get("k"))
        val e = assertFailsWith<IdbException> { tx.store("s") }
        assertEquals("TransactionInactiveError", e.name)
    }

    @Test
    fun storeTrasCommitLanzaTransactionInactive() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        tx.commit()
        val e = assertFailsWith<IdbException> { tx.store("s") }
        assertEquals("TransactionInactiveError", e.name)
    }

    @Test
    fun storeFueraDeAlcanceLanzaInvalidState() {
        val db = dbConStore(IndexedDb())
        db.createObjectStore("otro")
        val tx = db.transaction("s")
        val e = assertFailsWith<IdbException> { tx.store("otro") }
        assertEquals("InvalidStateError", e.name)
    }

    @Test
    fun storeInexistenteLanzaNotFound() {
        val db = dbConStore(IndexedDb())
        val tx = db.transaction("s")
        val e = assertFailsWith<IdbException> { tx.store("nope") }
        assertEquals("NotFoundError", e.name)
    }

    @Test
    fun cuotaAlCommitLanzaQuotaExceededSinAplicar() {
        val idb = IndexedDb(30)
        val db = dbConStore(idb)
        val tx = db.transaction("s")
        tx.store("s").put("k", "x".repeat(40))
        val e = assertFailsWith<IdbException> { tx.commit() }
        assertEquals("QuotaExceededError", e.name)
        tx.abort()
        val tx2 = db.transaction("s")
        assertNull(tx2.store("s").get("k"))
    }

    @Test
    fun particionesPorTopSiteYOrigen() {
        val idb = IndexedDb()
        val db1 = idb.open(siteA, originA, "d")
        db1.createObjectStore("s")
        val tx1 = db1.transaction("s")
        tx1.store("s").put("k", "A")
        tx1.commit()
        val db2 = idb.open(siteB, originA, "d")
        db2.createObjectStore("s")
        val tx2 = db2.transaction("s")
        assertNull(tx2.store("s").get("k"))
        assertEquals(2, idb.partitionCount())
        val db3 = idb.open(siteA, originC, "d")
        assertTrue(db3 !== db1)
        assertEquals(3, idb.partitionCount())
    }

    @Test
    fun deleteDatabaseYDatabaseNames() {
        val idb = IndexedDb()
        idb.open(siteA, originA, "d1")
        idb.open(siteA, originA, "d2")
        assertEquals(listOf("d1", "d2"), idb.databaseNames(siteA, originA))
        assertTrue(idb.deleteDatabase(siteA, originA, "d1"))
        assertEquals(listOf("d2"), idb.databaseNames(siteA, originA))
        assertFalse(idb.deleteDatabase(siteA, originA, "d1"))
    }
}
