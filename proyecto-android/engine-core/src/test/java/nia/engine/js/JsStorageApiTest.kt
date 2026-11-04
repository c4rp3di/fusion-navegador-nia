package nia.engine.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nia.engine.dom.DomDocument
import nia.engine.storage.IndexedDb
import nia.engine.storage.StorageManager
import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Tests de los bindings JS de storage (T033 v2, RES-0043): localStorage,
 * sessionStorage e indexedDB sobre el StorageManager y el IndexedDb
 * particionados de T033 v1, instalados en evaluateWithDom con la particion
 * (scope, topSite, origin) fijada por la sesion. La API es PROPIA del
 * motor (sincrona, desviacion documentada); las expectativas vienen del
 * nucleo ya verificado en R0067 (StorageTest/IndexedDbTest) y del spec
 * Web Storage donde el motor lo espeja (QuotaExceededError al exceder la
 * cuota, inexistente -> null).
 */
class JsStorageApiTest {

    private fun str(r: JsEvalResult): String = (r.value as JsStr).value

    private val origin = Origin.Tuple("https", "app.nia.dev", 443)
    private val site = Site.fromOrigin(origin)

    private fun api(s: StorageManager = StorageManager(), d: IndexedDb = IndexedDb()): JsStorageApi =
        JsStorageApi(s, d, site, origin)

    private fun eval(source: String, a: JsStorageApi): JsEvalResult =
        JsInterpreter.evaluateWithDom(source, DomDocument.parse("<body></body>"), JsLimits(), null, a)

    @Test fun localStorageSetItemGetItem() {
        val r = eval("localStorage.setItem('k', 'v'); localStorage.getItem('k')", api())
        assertEquals("v", str(r))
    }

    @Test fun getItemInexistenteNull() {
        val r = eval("String(localStorage.getItem('x'))", api())
        assertEquals("null", str(r))
    }

    @Test fun valoresCoercidosAString() {
        val r = eval("localStorage.setItem(1, 2); localStorage.getItem('1')", api())
        assertEquals("2", str(r))
    }

    @Test fun removeItemYLengthAccessor() {
        val r = eval(
            "localStorage.setItem('a', '1'); localStorage.setItem('b', '2');" +
                " localStorage.removeItem('a'); String(localStorage.length)",
            api()
        )
        assertEquals("1", str(r))
    }

    @Test fun keyPorOrdenDeInsercion() {
        val r = eval(
            "localStorage.setItem('a', '1'); localStorage.setItem('b', '2');" +
                " localStorage.setItem('c', '3');" +
                " localStorage.key(1) + ',' + String(localStorage.key(9))",
            api()
        )
        assertEquals("b,null", str(r))
    }

    @Test fun clearVaciaElArea() {
        val r = eval(
            "localStorage.setItem('a', '1'); localStorage.setItem('b', '2');" +
                " localStorage.clear(); String(localStorage.length)",
            api()
        )
        assertEquals("0", str(r))
    }

    @Test fun cuotaLanzaQuotaExceededError() {
        val r = eval(
            "var r = 'ok';" +
                " try { localStorage.setItem('x', '12345678901234567890123'); }" +
                " catch (e) { r = e.name; }" +
                " r + ':' + String(localStorage.getItem('x'))",
            api(StorageManager(20))
        )
        assertEquals("QuotaExceededError:null", str(r))
    }

    @Test fun sessionYLocalSonAreasDistintas() {
        val r = eval(
            "localStorage.setItem('k', 'local'); sessionStorage.setItem('k', 'session');" +
                " localStorage.getItem('k') + ',' + sessionStorage.getItem('k')",
            api()
        )
        assertEquals("local,session", str(r))
    }

    @Test fun particionPorTopSiteDesdeJs() {
        val manager = StorageManager()
        val idb = IndexedDb()
        val uno = JsStorageApi(manager, idb, site, origin)
        val otro = JsStorageApi(manager, idb, Site("https", "otro.org"), origin)
        val doc = DomDocument.parse("<body></body>")
        JsInterpreter.evaluateWithDom("localStorage.setItem('k', 'del-uno')", doc, JsLimits(), null, uno)
        val r = JsInterpreter.evaluateWithDom("String(localStorage.getItem('k'))", doc, JsLimits(), null, otro)
        assertEquals("null", str(r))
        assertTrue(manager.partitionCount() == 2)
    }

    @Test fun idbFlujoPutCommitGetYVersion() {
        val r = eval(
            "var db = indexedDB.open('demo', 1);" +
                " db.createObjectStore('s');" +
                " var tx = db.transaction('s'); tx.store('s').put('k', 'v'); tx.commit();" +
                " var tx2 = db.transaction('s'); tx2.store('s').get('k') + ':' + db.version",
            api()
        )
        assertEquals("v:1", str(r))
    }

    @Test fun idbGetVeElBufferAntesDelCommit() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s'); tx.store('s').put('k', 'v');" +
                " tx.store('s').get('k')",
            api()
        )
        assertEquals("v", str(r))
    }

    @Test fun idbAbortHaceRollback() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s'); tx.store('s').put('k', 'v'); tx.abort();" +
                " var tx2 = db.transaction('s'); String(tx2.store('s').get('k'))",
            api()
        )
        assertEquals("null", str(r))
    }

    @Test fun idbVersionMenorLanzaVersionError() {
        val r = eval(
            "indexedDB.open('d', 2);" +
                " var r = 'ok'; try { indexedDB.open('d', 1); } catch (e) { r = e.name; } r",
            api()
        )
        assertEquals("VersionError", str(r))
    }

    @Test fun idbStoreFueraDeAmbitoInvalidState() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s');" +
                " var r = 'ok'; try { tx.store('otro'); } catch (e) { r = e.name; } r",
            api()
        )
        assertEquals("InvalidStateError", str(r))
    }

    @Test fun idbStoreInexistenteNotFound() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('ghost');" +
                " var r = 'ok'; try { tx.store('ghost'); } catch (e) { r = e.name; } r",
            api()
        )
        assertEquals("NotFoundError", str(r))
    }

    @Test fun idbTrasCommitTransactionInactive() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s'); tx.commit();" +
                " var r = 'ok'; try { tx.store('s'); } catch (e) { r = e.name; } r",
            api()
        )
        assertEquals("TransactionInactiveError", str(r))
    }

    @Test fun idbCuotaAlCommitSinAplicar() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s');" +
                " tx.store('s').put('k', '1234567890123456789012345678901234');" +
                " var r = 'ok'; try { tx.commit(); } catch (e) { r = e.name; }" +
                " r + ':' + String(db.transaction('s').store('s').get('k'))",
            api(StorageManager(), IndexedDb(30))
        )
        assertEquals("QuotaExceededError:null", str(r))
    }

    @Test fun idbDeleteDatabaseYNombres() {
        val r = eval(
            "indexedDB.open('d', 1);" +
                " indexedDB.databases().length + ',' + String(indexedDB.deleteDatabase('d'))" +
                " + ',' + indexedDB.databases().length + ',' + String(indexedDB.deleteDatabase('d'))",
            api()
        )
        assertEquals("1,true,0,false", str(r))
    }

    @Test fun idbCloseInvalidaLaBase() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.close();" +
                " var r = 'ok'; try { db.createObjectStore('x'); } catch (e) { r = e.name; } r",
            api()
        )
        assertEquals("InvalidStateError", str(r))
    }

    @Test fun idbGetAllKeysCount() {
        val r = eval(
            "var db = indexedDB.open('d', 1); db.createObjectStore('s');" +
                " var tx = db.transaction('s'); var st = tx.store('s');" +
                " st.put('a', '1'); st.put('b', '2');" +
                " st.keys().join(',') + ':' + st.getAll().join(',') + ':' + st.count()",
            api()
        )
        assertEquals("a,b:1,2:2", str(r))
    }
}
