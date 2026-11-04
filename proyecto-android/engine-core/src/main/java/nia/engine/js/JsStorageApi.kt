package nia.engine.js

import nia.engine.storage.IdbDatabase
import nia.engine.storage.IdbException
import nia.engine.storage.IdbObjectStore
import nia.engine.storage.IdbTransaction
import nia.engine.storage.IndexedDb
import nia.engine.storage.StorageArea
import nia.engine.storage.StorageManager
import nia.engine.storage.StorageScope
import nia.engine.url.Origin
import nia.engine.url.Site

/**
 * Bindings JS de storage (T033 v2, RES-0043): instala localStorage,
 * sessionStorage e indexedDB en evaluateWithDom sobre el StorageManager y
 * el IndexedDb particionados de T033 v1. La particion (scope, topSite,
 * origin) la fija quien construye la sesion: el renderer real la derivara
 * de la URL al navegar; el contrato de particion queda observable desde
 * JS (dos top-sites distintos NO comparten area).
 *
 * API Web Storage: length como accessor real (patron de T024 v2: JsFun
 * parseado que llama al nativo oculto __lenGet; JsBuiltins.fn devuelve
 * JsNative y no vale para JsProp.Accessor), getItem con coercion a string
 * e inexistente -> null, setItem con coercion y QuotaExceededError cuando
 * la escritura excederia la cuota (espejo del spec Web Storage; el nucleo
 * v1 devolvia false sin almacenar), removeItem, clear y key(i) fuera de
 * rango -> null.
 *
 * indexedDB es SINCRONO sobre el nucleo T033 v1 (desviacion documentada
 * frente al spec asincrono, como fetch v1 bloqueante): open con version
 * (VersionError al pedir menor), transacciones con buffer, commit/abort
 * explicitos y los IdbException llegan a JS con su name del spec como
 * e.name (VersionError, TransactionInactiveError, InvalidStateError,
 * NotFoundError, QuotaExceededError).
 *
 * Exclusiones v2: sin eventos upgrade/onupgradeneeded ni requests del
 * spec asincrono, sin indices/cursors (v1), claves y valores string (v1).
 */
class JsStorageApi(
    private val storage: StorageManager,
    private val idb: IndexedDb,
    private val topSite: Site,
    private val origin: Origin
) {

    /** Accessors parseados una vez (patron de JsDomBindings). */
    private val lenGet = parseAccessorFun("(function () { return this.__lenGet(); })")
    private val versionGet = parseAccessorFun("(function () { return this.__versionGet(); })")
    private val storeNamesGet = parseAccessorFun("(function () { return this.__storeNamesGet(); })")

    private fun parseAccessorFun(src: String): JsFun {
        val r = JsInterpreter.evaluate(src)
        val v = r.value
        if (v is JsFun) return v
        throw JsRuntime.throwNative("TypeError", "accessor-no-compilable")
    }

    /** Instala localStorage, sessionStorage e indexedDB en el global. */
    fun install(genv: Env) {
        genv.declare("localStorage", webStorage(StorageScope.LOCAL))
        genv.declare("sessionStorage", webStorage(StorageScope.SESSION))
        genv.declare("indexedDB", idbObject())
    }

    private fun area(scope: StorageScope): StorageArea = storage.area(scope, topSite, origin)

    private fun webStorage(scope: StorageScope): JsObj {
        val o = JsRuntime.newObj()
        o.props["__lenGet"] = JsProp.Value(JsBuiltins.fn("__lenGet") { _, _ ->
            JsNum(area(scope).length.toDouble())
        })
        o.props["length"] = JsProp.Accessor(lenGet, null)
        o.props["getItem"] = JsProp.Value(JsBuiltins.fn("getItem") { args, _ ->
            val k = JsRuntime.toStringJs(JsBuiltins.a0(args))
            val v = area(scope).getItem(k)
            if (v == null) JsNull else JsStr(v)
        })
        o.props["setItem"] = JsProp.Value(JsBuiltins.fn("setItem") { args, _ ->
            val k = JsRuntime.toStringJs(JsBuiltins.a0(args))
            val v = JsRuntime.toStringJs(JsBuiltins.a1(args))
            if (!area(scope).setItem(k, v)) {
                throw JsRuntime.throwNative("QuotaExceededError", "cuota del area excedida al escribir: " + k)
            }
            JsUndefined
        })
        o.props["removeItem"] = JsProp.Value(JsBuiltins.fn("removeItem") { args, _ ->
            area(scope).removeItem(JsRuntime.toStringJs(JsBuiltins.a0(args)))
            JsUndefined
        })
        o.props["clear"] = JsProp.Value(JsBuiltins.fn("clear") { _, _ ->
            area(scope).clear()
            JsUndefined
        })
        o.props["key"] = JsProp.Value(JsBuiltins.fn("key") { args, _ ->
            val k = area(scope).key(JsRuntime.toInt32(JsBuiltins.a0(args)))
            if (k == null) JsNull else JsStr(k)
        })
        return o
    }

    private fun idbObject(): JsObj {
        val o = JsRuntime.newObj()
        o.props["open"] = JsProp.Value(JsBuiltins.fn("open") { args, _ ->
            val name = JsRuntime.toStringJs(JsBuiltins.a0(args))
            var version: Long? = null
            val v = JsBuiltins.a1(args)
            if (v !is JsUndefined) {
                val n = JsRuntime.toNumber(v)
                if (n.isNaN() || n <= 0) {
                    throw JsRuntime.throwNative("TypeError", "version de IndexedDB invalida: " + JsRuntime.toStringJs(v))
                }
                version = n.toLong()
            }
            dbOf(idbOp { idb.open(topSite, origin, name, version) })
        })
        o.props["deleteDatabase"] = JsProp.Value(JsBuiltins.fn("deleteDatabase") { args, _ ->
            JsBool(idbOp { idb.deleteDatabase(topSite, origin, JsRuntime.toStringJs(JsBuiltins.a0(args))) })
        })
        o.props["databases"] = JsProp.Value(JsBuiltins.fn("databases") { _, _ ->
            strArr(idbOp { idb.databaseNames(topSite, origin) })
        })
        return o
    }

    private fun dbOf(db: IdbDatabase): JsObj {
        val o = JsRuntime.newObj()
        o.props["__versionGet"] = JsProp.Value(JsBuiltins.fn("__versionGet") { _, _ ->
            JsNum(db.version.toDouble())
        })
        o.props["version"] = JsProp.Accessor(versionGet, null)
        o.props["__storeNamesGet"] = JsProp.Value(JsBuiltins.fn("__storeNamesGet") { _, _ ->
            strArr(idbOp { db.storeNames })
        })
        o.props["storeNames"] = JsProp.Accessor(storeNamesGet, null)
        o.props["createObjectStore"] = JsProp.Value(JsBuiltins.fn("createObjectStore") { args, _ ->
            val name = JsRuntime.toStringJs(JsBuiltins.a0(args))
            val kp = JsBuiltins.a1(args)
            idbOp { db.createObjectStore(name, if (kp is JsUndefined) null else JsRuntime.toStringJs(kp)) }
            JsUndefined
        })
        o.props["deleteObjectStore"] = JsProp.Value(JsBuiltins.fn("deleteObjectStore") { args, _ ->
            idbOp { db.deleteObjectStore(JsRuntime.toStringJs(JsBuiltins.a0(args))) }
            JsUndefined
        })
        o.props["transaction"] = JsProp.Value(JsBuiltins.fn("transaction") { args, _ ->
            val names = args.map { JsRuntime.toStringJs(it) }
            txOf(idbOp { db.transaction(*names.toTypedArray()) })
        })
        o.props["close"] = JsProp.Value(JsBuiltins.fn("close") { _, _ ->
            idbOp { db.close() }
            JsUndefined
        })
        return o
    }

    private fun txOf(tx: IdbTransaction): JsObj {
        val o = JsRuntime.newObj()
        o.props["store"] = JsProp.Value(JsBuiltins.fn("store") { args, _ ->
            storeOf(idbOp { tx.store(JsRuntime.toStringJs(JsBuiltins.a0(args))) })
        })
        o.props["commit"] = JsProp.Value(JsBuiltins.fn("commit") { _, _ ->
            idbOp { tx.commit() }
            JsUndefined
        })
        o.props["abort"] = JsProp.Value(JsBuiltins.fn("abort") { _, _ ->
            idbOp { tx.abort() }
            JsUndefined
        })
        return o
    }

    private fun storeOf(s: IdbObjectStore): JsObj {
        val o = JsRuntime.newObj()
        o.props["put"] = JsProp.Value(JsBuiltins.fn("put") { args, _ ->
            idbOp { s.put(JsRuntime.toStringJs(JsBuiltins.a0(args)), JsRuntime.toStringJs(JsBuiltins.a1(args))) }
            JsUndefined
        })
        o.props["get"] = JsProp.Value(JsBuiltins.fn("get") { args, _ ->
            val v = idbOp { s.get(JsRuntime.toStringJs(JsBuiltins.a0(args))) }
            if (v == null) JsNull else JsStr(v)
        })
        o.props["delete"] = JsProp.Value(JsBuiltins.fn("delete") { args, _ ->
            idbOp { s.delete(JsRuntime.toStringJs(JsBuiltins.a0(args))) }
            JsUndefined
        })
        o.props["getAll"] = JsProp.Value(JsBuiltins.fn("getAll") { _, _ ->
            strArr(idbOp { s.getAll().map { it.value } })
        })
        o.props["keys"] = JsProp.Value(JsBuiltins.fn("keys") { _, _ ->
            strArr(idbOp { s.keys() })
        })
        o.props["count"] = JsProp.Value(JsBuiltins.fn("count") { _, _ ->
            JsNum(idbOp { s.count() }.toDouble())
        })
        return o
    }

    private fun strArr(items: List<String>): JsObj {
        val arr = JsRuntime.newArr()
        for (item in items) arr.elems.add(JsStr(item))
        return arr
    }

    /** Los IdbException del nucleo llegan a JS con su name del spec. */
    private fun <R> idbOp(op: () -> R): R = try {
        op()
    } catch (e: IdbException) {
        throw JsRuntime.throwNative(e.name, e.message ?: e.name)
    }
}
