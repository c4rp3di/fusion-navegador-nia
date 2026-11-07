package nia.engine.tabs

import java.io.File

import nia.contracts.WallClock
import nia.engine.nav.NavController
import nia.engine.net.FetchTransport
import nia.engine.profile.BrowserProfile
import nia.engine.profile.DataFire
import nia.engine.profile.FireLevel
import nia.engine.profile.FireReport
import nia.engine.profile.ProfileKind

/**
 * T073 v1 (RES-0079): tarjeta observable de una pestana. El preview es
 * solo una marca en memoria (el shell decide que pintar con el): los
 * previews privados JAMAS se persisten (ADR-019).
 */
data class TabTarjeta(
    val id: Long,
    val kind: ProfileKind,
    val title: String,
    val url: String,
    val preview: String?
)

/**
 * Resultado de crear/duplicar/reabrir una pestana: id nueva, rechazo por
 * presupuesto o inexistencia de la pestana origen.
 */
sealed class TabCrear {
    data class Ok(val id: Long) : TabCrear()
    object PresupuestoExcedido : TabCrear()
    object NoExiste : TabCrear()
}

/** Resultado de cerrar: si el cierre borro el perfil del tipo. */
data class TabCierre(val id: Long, val kind: ProfileKind, val perfilBorrado: Boolean)

/** Cerrada reciente (T075 v1, R0184, RES-0087): tarjeta de la lista
 * de recientes; JAMAS lleva el preview (solo vive en memoria para
 * reabrir) y las incognito de un perfil destruido JAMAS aparecen
 * (el cierre de la ultima las retira de la lista). */
data class TabCerrada(
    val kind: ProfileKind,
    val title: String,
    val url: String
)

/**
 * TabsManager puro JVM (T073 v1, RES-0079): pestanas normales e incognito
 * separadas sobre los perfiles verificados (T044) y el NavController
 * verificado (T071). Un perfil POR TIPO compartido por las pestanas del
 * tipo: las pestanas del mismo tipo comparten cookies/cache/storage (como
 * un navegador real) y los tipos NUNCA se contaminan entre si; el perfil
 * incognito vive solo en memoria.
 *
 * Presupuestos explicitos (nada de memoria ilimitada): tope de pestanas
 * por tipo (crear/duplicar responden PresupuestoExcedido) y tope de
 * tarjetas reabiertas con desalojo del mas viejo. Politica de background:
 * solo la pestana activa navega por decision del shell; el manager no
 * lanza trabajo en segundo plano.
 *
 * Cerrar una pestana cancela su trabajo en vuelo (invalidateGeneration).
 * Cerrar la ULTIMA incognito borra TODO el perfil (wipe con conteo) y
 * vacia las tarjetas reabiertas de incognito (el preview privado no
 * sobrevive al cierre del tipo); la tarjeta recien cerrada SI puede
 * reabrirse (titulo/URL en memoria, nunca en disco). El perfil normal
 * sobrevive a cerrar sus pestanas (persistido).
 *
 * Exclusiones v1: sin grupos de pestanas, sin UI de strip (llega con el
 * shell), sin previews pintadas y sin persistencia de la sesion de
 * pestanas. El gemelo TS validado offline (2 corridas identicas, huella
 * 32333.48541.155) espeja los 14 escenarios en TabsManagerTest.
 */
class TabsManager(
    private val clock: WallClock,
    private val transport: FetchTransport,
    private val normalDir: File,
    private val maxTabsPorTipo: Int = MAX_TABS_POR_TIPO,
    private val maxReabiertos: Int = MAX_REABIERTOS
) {
    companion object {
        /** Tope de pestanas vivas por tipo (normal o incognito). */
        const val MAX_TABS_POR_TIPO = 20

        /** Tope de tarjetas reabribles; el mas viejo se desaloja. */
        const val MAX_REABIERTOS = 10
    }

    private class Tab(
        val id: Long,
        val kind: ProfileKind,
        val nav: NavController,
        var title: String,
        var url: String,
        var preview: String?
    )

    private class Cerrada(
        val kind: ProfileKind,
        val title: String,
        val url: String,
        val preview: String?
    )

    private val perfiles = HashMap<ProfileKind, BrowserProfile>()
    private val tabs = ArrayList<Tab>()
    private val reabiertos = ArrayList<Cerrada>()
    private var activaId = -1L
    private var nextId = 1L

    /** Perfil del tipo, creado perezosamente: normal persistido, incognito en memoria. */
    private fun perfilDe(kind: ProfileKind): BrowserProfile {
        val existente = perfiles[kind]
        if (existente != null) return existente
        val nuevo = if (kind == ProfileKind.INCOGNITO) {
            BrowserProfile.incognito(clock)
        } else {
            BrowserProfile.normal(clock, normalDir)
        }
        perfiles[kind] = nuevo
        return nuevo
    }

    /** Crea una pestana del tipo con su NavController sobre el perfil del tipo. */
    fun crear(kind: ProfileKind, url: String, title: String, preview: String? = null): TabCrear {
        if (contar(kind) >= maxTabsPorTipo) return TabCrear.PresupuestoExcedido
        val tab = Tab(nextId, kind, NavController(transport, clock, perfilDe(kind)), title, url, preview)
        nextId += 1
        tabs.add(tab)
        if (activaId == -1L) activaId = tab.id
        return TabCrear.Ok(tab.id)
    }

    /** Pestanas vivas del tipo (null cuenta todas). */
    fun contar(kind: ProfileKind? = null): Int = tabs.count { kind == null || it.kind == kind }

    /** Tarjetas del strip en orden; filtradas por tipo si se pide. */
    fun tarjetas(kind: ProfileKind? = null): List<TabTarjeta> =
        tabs.filter { kind == null || it.kind == kind }
            .map { TabTarjeta(it.id, it.kind, it.title, it.url, it.preview) }

    /** Tarjeta de una pestana viva. */
    fun tarjeta(id: Long): TabTarjeta? {
        val t = tabs.firstOrNull { it.id == id } ?: return null
        return TabTarjeta(t.id, t.kind, t.title, t.url, t.preview)
    }

    /** Id de la pestana activa; null sin pestanas. */
    fun activa(): Long? = if (activaId == -1L) null else activaId

    /** Activa una pestana viva; false si no existe. */
    fun activar(id: Long): Boolean {
        if (tabs.none { it.id == id }) return false
        activaId = id
        return true
    }

    /** NavController de la pestana: el shell navega y cancela con el. */
    fun nav(id: Long): NavController? = tabs.firstOrNull { it.id == id }?.nav

    /** El shell actualiza titulo/URL/preview tras cargar o fallar. */
    fun actualizar(id: Long, title: String, url: String, preview: String?): Boolean {
        val t = tabs.firstOrNull { it.id == id } ?: return false
        t.title = title
        t.url = url
        t.preview = preview
        return true
    }

    /** Duplica una pestana: mismo tipo, titulo y URL, preview fresco. */
    fun duplicar(id: Long): TabCrear {
        val t = tabs.firstOrNull { it.id == id } ?: return TabCrear.NoExiste
        return crear(t.kind, t.url, t.title, null)
    }

    /** Cierra: cancela el trabajo en vuelo y devuelve si borro el perfil. */
    fun cerrar(id: Long): TabCierre? {
        val pos = tabs.indexOfFirst { it.id == id }
        if (pos < 0) return null
        val t = tabs.removeAt(pos)
        t.nav.invalidateGeneration()
        var borrado = false
        if (t.kind == ProfileKind.INCOGNITO && contar(ProfileKind.INCOGNITO) == 0) {
            val perfil = perfiles[ProfileKind.INCOGNITO]
            if (perfil != null) perfil.wipe()
            perfiles.remove(ProfileKind.INCOGNITO)
            reabiertos.removeAll { it.kind == ProfileKind.INCOGNITO }
            borrado = perfil != null
        }
        pushReabierta(Cerrada(t.kind, t.title, t.url, t.preview))
        if (activaId == id) {
            activaId = if (tabs.isEmpty()) -1L else tabs[tabs.size - 1].id
        }
        return TabCierre(t.id, t.kind, borrado)
    }

    private fun pushReabierta(c: Cerrada) {
        reabiertos.add(c)
        if (reabiertos.size > maxReabiertos) reabiertos.removeAt(0)
    }

    /** Reabre la ultima cerrada; NoExiste sin tarjetas o si el presupuesto lo impide. */
    fun reabrir(): TabCrear {
        if (reabiertos.isEmpty()) return TabCrear.NoExiste
        val c = reabiertos.removeAt(reabiertos.size - 1)
        val out = crear(c.kind, c.url, c.title, c.preview)
        if (out !is TabCrear.Ok) reabiertos.add(c)
        return out
    }

    /**
     * T075 v1 (R0184, RES-0087): instantaneo de las cerradas
     * recientes, la mas vieja primero (la ultima cerrada cierra
     * la lista). Solo kind, titulo y URL: el preview JAMAS viaja
     * y las incognito de un perfil destruido JAMAS aparecen (el
     * cierre de la ultima las retira; solo la recien cerrada
     * queda, y reabrirla crea un perfil NUEVO sin datos privados
     * revividos).
     */
    fun reabiertas(): List<TabCerrada> =
        reabiertos.map { TabCerrada(it.kind, it.title, it.url) }

    /**
     * T075 v1 (R0184, RES-0087): reabre la cerrada de la posicion
     * pos (0 = la mas vieja de la lista); NoExiste fuera de rango
     * y el presupuesto restituye la tarjeta en su sitio.
     */
    fun reabrirEn(pos: Int): TabCrear {
        if (pos < 0 || pos >= reabiertos.size) return TabCrear.NoExiste
        val c = reabiertos.removeAt(pos)
        val out = crear(c.kind, c.url, c.title, c.preview)
        if (out !is TabCrear.Ok) reabiertos.add(pos, c)
        return out
    }

    /** Cierra todas las pestanas del tipo; devuelve cuantas murieron. */
    fun cerrarTodas(kind: ProfileKind): Int {
        val ids = tabs.filter { it.kind == kind }.map { it.id }
        for (id in ids) cerrar(id)
        return ids.size
    }

    /** Mueve la pestana de la posicion desde a la hasta del strip completo. */
    fun reordenar(desde: Int, hasta: Int): Boolean {
        if (desde < 0 || desde >= tabs.size) return false
        if (hasta < 0 || hasta >= tabs.size) return false
        if (desde == hasta) return true
        val t = tabs.removeAt(desde)
        tabs.add(hasta, t)
        return true
    }

    /** Fire del tipo (T045) sobre el perfil del tipo; null sin perfil vivo. */
    fun fire(level: FireLevel, kind: ProfileKind): FireReport? {
        val perfil = perfiles[kind] ?: return null
        return DataFire.apply(level, perfil)
    }

    /**
     * T093 v4 (R0183): borra SOLO el cache HTTP de los perfiles
     * vivos (normal e incognito) y devuelve cuantas entradas
     * murieron. NO es Fire: las cookies, el storage, el IndexedDb
     * y el historial del perfil normal viven (limpieza dedicada
     * del cache para Ajustes).
     */
    fun limpiarCache(): Int {
        var n = 0
        for (p in perfiles.values) n += p.cache.clear()
        return n
    }

    /** T085 v1 (RES-0098): perfil del tipo vivo para los datos por
     *  sitio del shell (conteo/borrado por origen sobre los motores
     *  reales); null si el tipo aun no tiene perfil creado. */
    fun perfil(kind: ProfileKind): BrowserProfile? = perfiles[kind]
}
