package nia.engine.profile

/**
 * T045 v1 (R0106, RES-0057, ADR-019): Fire, la limpieza de datos del
 * navegador con niveles SESION y TOTAL. Los niveles actuan sobre el
 * perfil activo:
 *
 * - SESION borra SOLO el sessionStorage del perfil (clearSessions de
 *   T033): cookies, cache, storage LOCAL e IndexedDB sobreviven.
 * - TOTAL borra TODO el estado vivo del perfil con conteo (FireReport)
 *   y ADEMAS el archivo persistido en disco (StoragePersistence.delete
 *   de T033 v3): un arranque posterior arranca vacio.
 *
 * Confirmacion explicita (ADR-019): el nivel se confirma en la UI antes
 * de aplicarse. Los marcadores NUNCA se borran por defecto (el motor
 * aun no tiene marcadores, T060).
 *
 * Exclusiones v1: sobre el perfil activo, sin gestor multi-perfil y sin
 * historial (el motor aun no tiene).
 */
enum class FireLevel { SESION, TOTAL }

/**
 * Resultado de DataFire.apply(): cuantas particiones de sessionStorage
 * murieron en SESION y cuantas cookies, entradas de cache, particiones
 * de storage y particiones de IndexedDb murieron en TOTAL.
 */
data class FireReport(
    val sessionPartitions: Int,
    val cookies: Int,
    val cacheEntries: Int,
    val storagePartitions: Int,
    val idbPartitions: Int
)

/**
 * Fire (T045 v1): aplica un nivel de limpieza sobre el perfil activo.
 */
object DataFire {

    /** Aplica el nivel y devuelve el conteo de lo borrado. */
    fun apply(level: FireLevel, profile: BrowserProfile): FireReport = when (level) {
        FireLevel.SESION ->
            FireReport(profile.storage.clearSessions(), 0, 0, 0, 0)
        FireLevel.TOTAL -> {
            val wipe = profile.wipe()
            profile.deletePersisted()
            FireReport(0, wipe.cookies, wipe.cacheEntries, wipe.storagePartitions, wipe.idbPartitions)
        }
    }
}
