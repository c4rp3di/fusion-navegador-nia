package nia.engine.modules

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

import nia.engine.broker.BrokerOps

import nia.engine.js.JsAbort
import nia.engine.js.JsBuiltins
import nia.engine.js.JsFun
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsNative
import nia.engine.js.JsObj
import nia.engine.js.JsParser
import nia.engine.js.JsProp
import nia.engine.js.JsQuotas
import nia.engine.js.JsRuntime
import nia.engine.js.JsStr
import nia.engine.js.JsThrown
import nia.engine.js.JsUndefined
import nia.engine.js.JsValue

/**
 * T029/T030 v1 (R0131, RES-0068, ADR-016): runtime de plugins
 * restringido sobre el registro de T009. El entry de un modulo
 * instalado se evalua en el interprete con cuotas de plugin (topes
 * reducidos) y SIN acceso host libre: el plugin solo ve el objeto nia
 * que el runtime inyecta, con los hooks que sus permisos conceden
 * (onStartup y onNavigate siempre; onRequest exige network.fetch).
 * El entry corre sincrono; los hooks se capturan como funciones y
 * fire los ejecuta en una sesion de cuotas propia, con el ultimo
 * hook como resultado (decision en cadena tipo adblock).
 * T030: revocacion con unload (los fire posteriores caen en DENIED)
 * y update con rollback automatico sin recompilar: si el manifiesto
 * nuevo o el entry nuevo falla, el manifiesto viejo se reinstala y la
 * sesion vieja revive con sus hooks intactos.
 *
 * Exclusiones v1: hooks asincronos no esperados (el valor de retorno
 * de una Promise se ignora), sin persistencia del lado del plugin,
 * sin eventos del host hacia el plugin mas alla de los tres hooks y
 * sin actualizacion parcial (update reemplaza el modulo entero).
 */

/** Evento de plugin v1: nombre expuesto en el objeto nia del entry. */
enum class PluginEvent(val jsName: String) {
    STARTUP("onStartup"),
    NAVIGATE("onNavigate"),
    REQUEST("onRequest")
}

/** Sesion de un plugin cargado: sus hooks congelados y sus cuotas. */
class PluginSession internal constructor(
    val moduleId: String,
    val manifest: ModuleManifest,
    internal val hooks: Map<PluginEvent, List<JsValue>>,
    internal val limits: JsLimits
) {
    internal var descargado = false

    val activo: Boolean get() = !descargado

    fun hookCount(evento: PluginEvent): Int = hooks[evento]?.size ?: 0

    /** Dispara el evento: cada hook recibe la url y el ultimo decide. */
    fun fire(evento: PluginEvent, url: String): Outcome<String> {
        if (descargado) return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "plugin descargado"))
        val lista = hooks[evento] ?: emptyList()
        if (lista.isEmpty()) return Outcome.success("")
        JsQuotas.begin(limits)
        var ultimo = ""
        try {
            for (cb in lista) {
                val v = JsInterpreter.callFunction(cb, JsUndefined, listOf(JsStr(url)))
                ultimo = JsRuntime.toStringJs(v)
            }
        } catch (ex: JsAbort) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "hook abortado por cuotas (" + ex.reason.name + ")")
            )
        } catch (ex: JsThrown) {
            return Outcome.failure(
                ErrorInfo(
                    ErrorCode.INVALID_INPUT,
                    "hook de " + evento.jsName + " del modulo " + moduleId + " lanzo una excepcion"
                )
            )
        } catch (ex: Throwable) {
            return Outcome.failure(
                ErrorInfo(
                    ErrorCode.INVALID_INPUT,
                    "hook de " + evento.jsName + " del modulo " + moduleId + " fallo"
                )
            )
        } finally {
            JsQuotas.end()
        }
        return Outcome.success(ultimo)
    }
}

/** Runtime de plugins (T029/T030): carga con cuotas, revocacion y update. */
class PluginRuntime(
    private val registry: ModuleRegistry,
    private val limits: JsLimits = DEFAULT_LIMITS
) {
    private val sesiones = LinkedHashMap<String, PluginSession>()
    private val codigos = HashMap<String, String>()

    /**
     * Carga el entry del modulo instalado: pre-parse, cuotas de plugin y
     * objeto nia. El parser real recupera en vez de lanzar (sus errores y
     * los del lexer viajan en el resultado, no como excepcion), asi que el
     * pre-parse es lo que detecta un entry que no compila.
     */
    fun load(id: String, codigo: String): Outcome<PluginSession> {
        val manifest = registry.manifestOf(id)
        if (manifest == null) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id))
        }
        val pre = JsParser.parse(codigo)
        if (pre.program.errors.isNotEmpty() || pre.lexErrors.isNotEmpty()) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "entry del modulo " + id + " es invalido: no compila o lanzo")
            )
        }
        val hooks = HashMap<PluginEvent, MutableList<JsValue>>()
        val nia = objetoNia(manifest, hooks)
        var fallo: String? = null
        val genv = JsInterpreter.startLoopSession(limits)
        try {
            genv.declare("nia", nia)
            val r = JsInterpreter.runInSession(codigo, genv)
            if (r.abortReason != null) {
                fallo = "entry del modulo " + id + " abortado por cuotas (" + r.abortReason.name + ")"
            } else if (r.thrown) {
                fallo = "entry del modulo " + id + " es invalido: no compila o lanzo"
            }
        } catch (ex: Throwable) {
            fallo = "entry del modulo " + id + " es invalido: no compila o lanzo"
        } finally {
            JsInterpreter.endLoopSession()
        }
        if (fallo != null) return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, fallo))
        val congelados = HashMap<PluginEvent, List<JsValue>>()
        for ((evento, lista) in hooks) congelados[evento] = ArrayList(lista)
        val sesion = PluginSession(id, manifest, congelados, limits)
        sesiones[id] = sesion
        codigos[id] = codigo
        return Outcome.success(sesion)
    }

    /** Revoca el plugin: los fire posteriores caen en DENIED (T030). */
    fun unload(id: String): Outcome<Unit> {
        val sesion = sesiones[id]
        if (sesion == null) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "plugin no cargado: " + id))
        }
        sesion.descargado = true
        sesiones.remove(id)
        codigos.remove(id)
        return Outcome.success(Unit)
    }

    /** Sesion activa del plugin o null si no esta cargado. */
    fun sessionOf(id: String): PluginSession? = sesiones[id]

    /**
     * Update con rollback automatico (T030): sustituye manifiesto y
     * entry SIN recompilar; si algo falla, el manifiesto viejo se
     * reinstala y la sesion vieja revive con sus hooks intactos.
     */
    fun update(id: String, nuevoManifestTexto: String, nuevoCodigo: String): Outcome<PluginSession> {
        val viejo = sesiones[id]
        if (viejo == null) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "plugin no cargado: " + id))
        }
        val textoViejo = manifestTexto(viejo.manifest)
        registry.uninstall(id)
        val instalado = registry.install(nuevoManifestTexto)
        if (instalado is Outcome.Failure) {
            registry.install(textoViejo)
            return Outcome.failure(instalado.error)
        }
        viejo.descargado = true
        sesiones.remove(id)
        val carga = load(id, nuevoCodigo)
        if (carga is Outcome.Failure) {
            registry.uninstall(id)
            registry.install(textoViejo)
            viejo.descargado = false
            sesiones[id] = viejo
            return Outcome.failure(carga.error)
        }
        return carga
    }

    /** Objeto nia del entry: solo los hooks que los permisos conceden. */
    internal fun objetoNia(
        manifest: ModuleManifest,
        hooks: HashMap<PluginEvent, MutableList<JsValue>>
    ): JsValue {
        val nia = JsRuntime.newObj()
        registrarHook(nia, PluginEvent.STARTUP, hooks)
        registrarHook(nia, PluginEvent.NAVIGATE, hooks)
        if (manifest.permissions.contains(BrokerOps.NETWORK_FETCH)) {
            registrarHook(nia, PluginEvent.REQUEST, hooks)
        }
        return nia
    }

    private fun registrarHook(
        nia: JsObj,
        evento: PluginEvent,
        hooks: HashMap<PluginEvent, MutableList<JsValue>>
    ) {
        nia.props[evento.jsName] = JsProp.Value(
            JsBuiltins.fn(evento.jsName) { args, _ ->
                val cb = if (args.isNotEmpty()) args[0] else JsUndefined
                if (cb !is JsFun && cb !is JsNative) {
                    throw JsRuntime.throwNative("TypeError", evento.jsName + " exige una funcion")
                }
                var lista = hooks[evento]
                if (lista == null) {
                    lista = ArrayList()
                    hooks[evento] = lista
                }
                lista.add(cb)
                JsUndefined
            }
        )
    }

    /** Manifiesto re-serializado para el rollback del update. */
    internal fun manifestTexto(m: ModuleManifest): String {
        val sb = StringBuilder()
        sb.append("id = ").append(m.id)
        sb.append("\nversion = ").append(m.version)
        sb.append("\nentry = ").append(m.entry)
        if (m.permissions.isNotEmpty()) {
            sb.append("\npermissions = ").append(m.permissions.joinToString(","))
        }
        if (m.name.isNotEmpty()) {
            sb.append("\nname = ").append(m.name)
        }
        if (m.description.isNotEmpty()) {
            sb.append("\ndescription = ").append(m.description)
        }
        if (m.categories.isNotEmpty()) {
            sb.append("\ncategory = ").append(m.categories.joinToString(","))
        }
        if (m.capabilities.isNotEmpty()) {
            sb.append("\ncapabilities = ").append(m.capabilities.joinToString(","))
        }
        if (m.authorKey.isNotEmpty()) {
            sb.append("\nauthor-key = ").append(m.authorKey)
        }
        if (m.nextKey.isNotEmpty()) {
            sb.append("\nnext-key = ").append(m.nextKey)
        }
        return sb.toString()
    }

    companion object {

        /** Cuotas por defecto de un plugin: topes reducidos (T029). */
        val DEFAULT_LIMITS: JsLimits = JsLimits(100_000L, 10_000L)
    }
}
