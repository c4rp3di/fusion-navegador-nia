package nia.engine.storage

import nia.engine.url.Origin
import nia.engine.url.Site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {

    private val siteA = Site("https", "a.com")
    private val siteB = Site("https", "b.com")
    private val originA = Origin.Tuple("https", "a.com", 443)
    private val originC = Origin.Tuple("https", "c.com", 443)

    @Test
    fun particionesPorTopSite() {
        val m = StorageManager()
        val a1 = m.area(StorageScope.LOCAL, siteA, originC)
        assertTrue(a1.setItem("k", "v"))
        val a2 = m.area(StorageScope.LOCAL, siteB, originC)
        assertNull(a2.getItem("k"))
        assertEquals(2, m.partitionCount())
    }

    @Test
    fun particionesPorScope() {
        val m = StorageManager()
        val local = m.area(StorageScope.LOCAL, siteA, originA)
        val session = m.area(StorageScope.SESSION, siteA, originA)
        assertTrue(local.setItem("k", "local"))
        assertNull(session.getItem("k"))
        assertEquals(2, m.partitionCount())
    }

    @Test
    fun particionesPorOrigen() {
        val m = StorageManager()
        val aA = m.area(StorageScope.LOCAL, siteA, originA)
        val aC = m.area(StorageScope.LOCAL, siteA, originC)
        assertTrue(aA.setItem("k", "A"))
        assertNull(aC.getItem("k"))
        assertEquals(2, m.partitionCount())
    }

    @Test
    fun origenesOpacosParticionPropia() {
        val m = StorageManager()
        val tuple = m.area(StorageScope.LOCAL, siteA, originA)
        assertTrue(tuple.setItem("k", "tuple"))
        val o1 = m.area(StorageScope.LOCAL, siteA, Origin.Opaque("data"))
        val o2 = m.area(StorageScope.LOCAL, siteA, Origin.Opaque("sandbox"))
        assertNull(o1.getItem("k"))
        assertNull(o2.getItem("k"))
        assertTrue(o1.setItem("k", "uno"))
        assertNull(o2.getItem("k"))
        assertEquals(3, m.partitionCount())
    }

    @Test
    fun setItemGetItemKeyYLength() {
        val a = StorageManager().area(StorageScope.LOCAL, siteA, originA)
        assertTrue(a.setItem("a", "1"))
        assertTrue(a.setItem("b", "2"))
        assertEquals("1", a.getItem("a"))
        assertEquals(2, a.length)
        assertEquals("a", a.key(0))
        assertEquals("b", a.key(1))
        assertNull(a.key(2))
        assertNull(a.key(-1))
    }

    @Test
    fun cuotaExcedidaNoAlmacena() {
        val m = StorageManager(12)
        val a = m.area(StorageScope.LOCAL, siteA, originA)
        assertTrue(a.setItem("k", "0123456789"))
        assertFalse(a.setItem("z", "1"))
        assertNull(a.getItem("z"))
        assertEquals(11L, a.usageBytes())
    }

    @Test
    fun cuotaPermiteReemplazoMenor() {
        val a = StorageManager(12).area(StorageScope.LOCAL, siteA, originA)
        assertTrue(a.setItem("k", "0123456789"))
        assertTrue(a.setItem("k", "01"))
        assertEquals(3L, a.usageBytes())
    }

    @Test
    fun removeItemReduceElArea() {
        val a = StorageManager().area(StorageScope.LOCAL, siteA, originA)
        a.setItem("a", "1")
        a.setItem("b", "2")
        a.removeItem("a")
        assertNull(a.getItem("a"))
        assertEquals(1, a.length)
    }

    @Test
    fun clearDevuelveConteo() {
        val a = StorageManager().area(StorageScope.LOCAL, siteA, originA)
        a.setItem("a", "1")
        a.setItem("b", "2")
        assertEquals(2, a.clear())
        assertEquals(0, a.length)
    }

    @Test
    fun usageBytesCuentaClaveYValor() {
        val a = StorageManager().area(StorageScope.LOCAL, siteA, originA)
        a.setItem("k", "hola")
        assertEquals(5L, a.usageBytes())
    }

    @Test
    fun usageBytesPorParticion() {
        val m = StorageManager()
        m.area(StorageScope.LOCAL, siteA, originA).setItem("k", "hola")
        assertEquals(5L, m.usageBytes(StorageScope.LOCAL, siteA, originA))
        assertEquals(0L, m.usageBytes(StorageScope.LOCAL, siteB, originC))
    }

    @Test
    fun removeOriginBorraTodasLasParticiones() {
        val m = StorageManager()
        m.area(StorageScope.LOCAL, siteA, originC)
        m.area(StorageScope.SESSION, siteA, originC)
        m.area(StorageScope.LOCAL, siteB, originC)
        assertEquals(3, m.partitionCount())
        assertEquals(3, m.removeOrigin(originC))
        assertEquals(0, m.partitionCount())
    }

    @Test
    fun removeOriginConservaOtrosOrigenes() {
        val m = StorageManager()
        val aA = m.area(StorageScope.LOCAL, siteA, originA)
        m.area(StorageScope.LOCAL, siteA, originC)
        aA.setItem("k", "A")
        assertEquals(1, m.removeOrigin(originC))
        assertEquals("A", aA.getItem("k"))
        assertEquals(1, m.partitionCount())
    }

    @Test
    fun clearAllVaciaTodo() {
        val m = StorageManager()
        m.area(StorageScope.LOCAL, siteA, originA)
        m.area(StorageScope.SESSION, siteA, originA)
        m.area(StorageScope.LOCAL, siteB, originC)
        assertEquals(3, m.clearAll())
        assertEquals(0, m.partitionCount())
    }

    @Test
    fun clearSessionsSoloSession() {
        val m = StorageManager()
        val local = m.area(StorageScope.LOCAL, siteA, originA)
        val session = m.area(StorageScope.SESSION, siteA, originA)
        m.area(StorageScope.LOCAL, siteB, originC)
        local.setItem("k", "local")
        session.setItem("k", "session")
        assertEquals(1, m.clearSessions())
        assertEquals("local", local.getItem("k"))
        assertEquals(2, m.partitionCount())
        assertEquals(0, m.area(StorageScope.SESSION, siteA, originA).length)
    }

    @Test
    fun reusoDeAreaNoCreaParticiones() {
        val m = StorageManager()
        m.area(StorageScope.LOCAL, siteA, originA).setItem("k", "1")
        m.area(StorageScope.LOCAL, siteA, originA).setItem("k", "2")
        assertEquals(1, m.partitionCount())
        m.area(StorageScope.SESSION, siteA, originA)
        assertEquals(2, m.partitionCount())
    }
}
