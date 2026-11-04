package nia.engine.profile

import nia.contracts.WallClock
import nia.engine.net.CookieJar
import nia.engine.net.HttpCache
import nia.engine.storage.IndexedDb
import nia.engine.storage.StorageManager
import nia.engine.storage.StoragePersistence
import java.io.File

/**
 * T044 v1 (R0104, RES-0056, ADR-019): perfil de navegador con modo
 * incognito. BrowserProfile es dueno del ciclo de vida de los motores
 * con estado de la sesion: cookies, cache HTTP, storage particionado e
 * IndexedDB propios y aislados entre perfiles.
 *
 * El perfil NORMAL persiste el storage LOCAL y el IndexedDb en disco via
 * StoragePersistence (T033 v3) y los recarga al arrancar; el INCOGNITO
 * vive solo en memoria: save() responde null sin escribir NADA en disco y
 * todo su estado muere con wipe().
 *
 * Exclusiones v1: sin historial (el motor aun no tiene), sin gestor
 * multi-perfil y las cookies no persisten (T026).
 */
enum class ProfileKind { NORMAL, INCOGNITO }

/**
 * Resultado de wipe(): cuantas cookies, entradas de cache, particiones de
 * storage y particiones de IndexedDb murieron.
 */
data class WipeReport(
    val cookies: Int,
    val cacheEntries: Int,
    val storagePartitions: Int,
    val idbPartitions: Int
)

/**
 * Perfil de navegador (T044 v1): estado de la sesion aislado por perfil.
 */
class BrowserProfile private constructor(
    val kind: ProfileKind,
    clock: WallClock,
    private val persistence: StoragePersistence?
) {

    /** Cookies del perfil (de sesion en v1; la persistencia llega en T026). */
    val cookies = CookieJar(clock)

    /** Cache HTTP del perfil. */
    val cache = HttpCache(clock)

    /** Storage particionado del perfil. */
    val storage = StorageManager()

    /** IndexedDb del perfil. */
    val idb = IndexedDb()

    /** El incognito nunca es un perfil normal. */
    fun isIncognito(): Boolean = kind == ProfileKind.INCOGNITO

    /**
     * Persiste el estado del perfil en disco; el incognito NUNCA escribe
     * (responde null). Devuelve lo que viajo: areas LOCAL y bases de
     * IndexedDb (el sessionStorage muere con la sesion).
     */
    fun save(): StoragePersistence.Persisted? = persistence?.save(storage, idb)

    /**
     * Borra TODO el estado vivo del perfil (cookies, cache, storage e
     * IndexedDb) con conteo de lo borrado. NO toca el disco: un save
     * posterior persiste el estado ya vacio.
     */
    fun wipe(): WipeReport = WipeReport(
        cookies.clear(),
        cache.clear(),
        storage.clearAll(),
        idb.clearAll()
    )

    companion object {

        /**
         * Perfil normal con persistencia en dir: recarga el storage y el
         * IndexedDb guardados y crea el archivo en el primer save.
         */
        fun normal(clock: WallClock, dir: File): BrowserProfile {
            val persistence = StoragePersistence(dir)
            val profile = BrowserProfile(ProfileKind.NORMAL, clock, persistence)
            persistence.load(profile.storage, profile.idb)
            return profile
        }

        /** Perfil incognito: todo vive en memoria y nada llega al disco. */
        fun incognito(clock: WallClock): BrowserProfile =
            BrowserProfile(ProfileKind.INCOGNITO, clock, null)
    }
}
