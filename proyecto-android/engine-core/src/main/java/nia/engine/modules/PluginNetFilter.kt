package nia.engine.modules

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import nia.contracts.Outcome

import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.net.FilterDecision
import nia.engine.net.RequestContext
import nia.engine.net.RequestFilter
import nia.engine.profile.ConfigStore
import nia.engine.url.Origin
import nia.engine.url.UrlParser
import nia.engine.url.UrlParseException

/**
 * T052 v1 (R0242, RES-0111): RequestFilter que consulta los plugins
 * cargados (ADR-005, carril AntiAds). Recorre los modulos INSTALADOS
 * en su orden de instalacion estable, dispara onRequest de cada
 * sesion activa y el MAS restrictivo gana: veredicto exacto "block"
 * (tras trim) -> Block(moduleId).
 *
 * Fail-open ante hook que lanza, cuotas agotadas, modulo sin sesion
 * cargada o sin hooks de red: abstencion y sigue la cadena; si nadie
 * bloquea, Allow (la navegacion NUNCA se rompe por un modulo roto).
 *
 * T052 v2/C9 (R0253, RES-0113): config opcional del perfil. Un modulo
 * con general.enabled="false" en la config se abstiene SIN disparar su
 * hook (no gasta cuotas); la desactivacion del gestor SIEMPRE gana
 * (la config solo se consulta con sesion cargada). Sin config el
 * filtro es identico a la v1 (ausencia de ajuste = habilitado).
 *
 * T030 v2 (R0263, RES-0117, E4 C2): el filtro pasa el RequestContext
 * ENTERO a la sesion; el hook decide la forma del argumento su
 * api-version (1: solo la url; 2: el objeto con url, type, initiator
 * y thirdParty).
 *
 * C10 v2 (R0267, RES-0118): contextoDe opcional aporta el EventContext
 * REAL de cada peticion (la pestana que navega) para que el
 * diagnostico del modulo escriba en el inspector del host; sin
 * contexto o con proveedor null nia.diag se abstiene (devuelve false).
 *
 * C3 v1 (R0271, RES-0119): veredicto "sustituir:<nombre>" (solo
 * api-version 2). Gates fail-closed en orden: tipo fetch/otro (una
 * navegacion de documento JAMAS se sustituye), capability modificar
 * y permiso network.response del manifiesto, nombre declarado en
 * sustitutos, recurso resuelto por recursosDe, <= 64 KiB, sha256
 * intacto y tope de 50 por documento; cualquier fallo cae en Block
 * con diagnostico "no disponible" (WARNING) y el exito escribe
 * "sustituido" (INFO) via el DiagModulos compartido del shell.
 *
 * T054 v1 (R0284, RES-0122): excepciones de sitio del host y
 * estadisticas. El consultor opcional exceptuado(id, origen)
 * recibe el origen del CONTEXTO de la peticion (el iniciador si
 * lo hay; si no, la url) y un modulo exceptuado se abstiene SIN
 * disparar su hook (fail-open si la url es ilegible). Ademas el
 * filtro cuenta bloqueos y sustituciones por modulo EN MEMORIA
 * (companion del PROCESO, compartido entre pestanas; se pierde
 * al reiniciar).
 */
class PluginNetFilter(
    private val runtime: PluginRuntime,
    private val registry: ModuleRegistry,
    private val config: ConfigStore? = null,
    private val contextoDe: ((RequestContext) -> EventContext?)? = null,
    private val recursosDe: ((String, String) -> RecursoSustituto?)? = null,
    private val diag: DiagModulos? = null,
    private val exceptuado: ((String, String) -> Boolean)? = null
) : RequestFilter {

    override fun decide(context: RequestContext): FilterDecision {
        val origenCtx = if (exceptuado == null) {
            null
        } else {
            origenDe(
                if (context.initiatorUrl.isNotEmpty()) context.initiatorUrl else context.url
            )
        }
        for (id in registry.installedIds()) {
            val session = runtime.sessionOf(id) ?: continue
            if (config != null && !config.enabled(id)) continue
            if (origenCtx != null && exceptuado?.invoke(id, origenCtx) == true) continue
            if (session.hookCount(PluginEvent.REQUEST) == 0) continue
            val fired = session.fire(PluginEvent.REQUEST, context, contextoDe?.invoke(context))
            if (fired is Outcome.Success) {
                val veredicto = fired.value.trim()
                if (veredicto == "block") {
                    bloquear(id)
                    return FilterDecision.Block(id)
                }
                if (veredicto.startsWith("sustituir:")) {
                    val decision = sustitucionDe(id, veredicto, context)
                    if (decision != null) {
                        if (decision is FilterDecision.Substitute) sustituir(id) else bloquear(id)
                        return decision
                    }
                }
            }
        }
        return FilterDecision.Allow
    }

    /** T054 v1: origen scheme://host:port de una URL cruda; ilegible
     *  u opaco -> null (fail-open: sin excepcion consultada). */
    private fun origenDe(url: String): String? {
        return try {
            when (val o = Origin.of(UrlParser.parse(url))) {
                is Origin.Tuple -> o.scheme + "://" + o.host + ":" + o.port
                else -> null
            }
        } catch (_: UrlParseException) {
            null
        }
    }

    /** T054 v1: cuenta un bloqueo del modulo (companion del proceso);
     *  T094 v5 (R0296, RES-0124) marca pendientes SIN I/O en el camino
     *  caliente (el guardado es best-effort desde el shell). */
    private fun bloquear(id: String) {
        bloqueosProceso.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
        pendientes.set(true)
    }

    /** T054 v1: cuenta una sustitucion del modulo (companion);
     *  T094 v5 marca pendientes SIN I/O en el camino caliente. */
    private fun sustituir(id: String) {
        sustitucionesProceso.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
        pendientes.set(true)
    }

    /**
     * C3 v1 (R0271, RES-0119): gates del veredicto sustituir:<nombre>
     * en orden fail-closed; null = abstencion (api-version 1 mantiene
     * su semantica). El EventContext de contextoDe identifica el
     * documento para el tope y el diagnostico del canal del host.
     */
    private fun sustitucionDe(
        id: String,
        veredicto: String,
        context: RequestContext
    ): FilterDecision? {
        val manifest = registry.manifestOf(id)
        if (manifest == null || manifest.apiVersion < 2) return null
        val ctx = contextoDe?.invoke(context)
        val nombre = veredicto.removePrefix("sustituir:").trim()
        fun noDisponible(): FilterDecision {
            if (ctx != null) {
                diag?.evento(id, EventLevel.WARNING, "no disponible: " + nombre, ctx)
            }
            return FilterDecision.Block(id)
        }
        if (context.resourceType != "fetch" && context.resourceType != "otro") {
            return noDisponible()
        }
        if (!manifest.capabilities.contains("modificar")) return noDisponible()
        if (!manifest.permissions.contains(ModuleSchema.NETWORK_RESPONSE)) return noDisponible()
        if (nombre.isEmpty() || !manifest.sustitutos.contains(nombre)) return noDisponible()
        val recurso = recursosDe?.invoke(id, nombre) ?: return noDisponible()
        if (recurso.bytes.size > Niamod.MAX_SUSTITUTO) return noDisponible()
        val sha = MessageDigest.getInstance("SHA-256").digest(recurso.bytes)
        if (recurso.sha256 != hexDe(sha)) return noDisponible()
        val doc = ctx?.documentId ?: "(sin-contexto)"
        val usados = topeSustituciones[doc] ?: 0
        if (usados >= MAX_SUSTITUCIONES_POR_DOCUMENTO) {
            return noDisponible()
        }
        topeSustituciones[doc] = usados + 1
        if (ctx != null) {
            diag?.evento(id, EventLevel.INFO, "sustituido " + nombre, ctx)
        }
        return FilterDecision.Substitute(id, recurso.bytes, contentTypeDe(nombre))
    }

    /** ContentType por extension (C3); lo calcula el host, no el modulo. */
    private fun contentTypeDe(nombre: String): String {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun hexDe(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private val topeSustituciones = HashMap<String, Int>()

    companion object {
        /** C3 v1 (R0271, RES-0119): tope de sustituciones por documento. */
        const val MAX_SUSTITUCIONES_POR_DOCUMENTO = 50

        /** T054 v1 (R0284, RES-0122): contadores del PROCESO (hay un
         *  filtro por pestana/perfil); T094 v5 (R0296, RES-0124) los
         *  persiste en un TSV opcional fijado por el shell. */
        internal val bloqueosProceso = ConcurrentHashMap<String, AtomicInteger>()
        internal val sustitucionesProceso = ConcurrentHashMap<String, AtomicInteger>()

        fun bloqueosDe(id: String): Int = bloqueosProceso[id]?.get() ?: 0

        fun sustitucionesDe(id: String): Int = sustitucionesProceso[id]?.get() ?: 0

        /** T094 v5: archivo TSV de los contadores (id TAB bloqueos TAB
         *  sustituciones por linea, ids ordenados); null = SIN
         *  persistencia y los contadores vivos NO se pierden. */
        private val persistencia = AtomicReference<File?>(null)

        /** T094 v5: hay incrementos desde el ultimo guardado (el camino
         *  caliente solo MARCA, nunca escribe). */
        private val pendientes = AtomicBoolean(false)

        /** T094 v5: fija el archivo y CARGA el TSV existente
         *  (best-effort: lineas corruptas o partes no numericas se
         *  ignoran, igual que modulos.tsv). */
        fun persistirContadoresEn(archivo: File?) {
            persistencia.set(archivo)
            if (archivo != null) cargarContadores(archivo)
        }

        /** T094 v5: escribe SOLO si hay incrementos sin guardar (el
         *  shell lo llama en onPause; un kill puede perder lo contado
         *  desde el ultimo guardado, sin doble contabilidad). */
        fun guardarContadoresSiPendientes() {
            if (!pendientes.get()) return
            val archivo = persistencia.get() ?: return
            guardarContadores(archivo)
            pendientes.set(false)
        }

        /** T094 v5: consulta honesta para test y diagnostico. */
        fun hayContadoresPendientes(): Boolean = pendientes.get()

        /** T094 v5: borra TODOS los contadores y persiste el vacio
         *  (idempotente: no queda nada colgado). */
        fun reiniciarContadores() {
            bloqueosProceso.clear()
            sustitucionesProceso.clear()
            val archivo = persistencia.get()
            if (archivo != null) guardarContadores(archivo)
            pendientes.set(false)
        }

        private fun cargarContadores(archivo: File) {
            if (!archivo.exists()) return
            try {
                for (linea in archivo.readText().lines()) {
                    val t = linea.trim()
                    if (t.isEmpty()) continue
                    val p = t.split('\t')
                    if (p.size < 3) continue
                    val b = p[1].trim().toIntOrNull() ?: continue
                    val s = p[2].trim().toIntOrNull() ?: continue
                    if (b > 0) bloqueosProceso[p[0]] = AtomicInteger(b)
                    if (s > 0) sustitucionesProceso[p[0]] = AtomicInteger(s)
                }
            } catch (_: Exception) {
            }
        }

        private fun guardarContadores(archivo: File) {
            try {
                val ordenados = (bloqueosProceso.keys + sustitucionesProceso.keys).toSortedSet()
                val sb = StringBuilder()
                for (id in ordenados) {
                    sb.append(id).append('\t')
                        .append(bloqueosDe(id)).append('\t')
                        .append(sustitucionesDe(id)).append('\n')
                }
                archivo.writeText(sb.toString())
            } catch (_: Exception) {
            }
        }
    }
}
