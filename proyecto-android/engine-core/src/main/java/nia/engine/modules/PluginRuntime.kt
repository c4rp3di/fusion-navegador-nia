package nia.engine.modules

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

import nia.engine.broker.BrokerOps

import nia.engine.js.JsAbort
import nia.engine.js.JsBool
import nia.engine.js.JsBuiltins
import nia.engine.js.JsFun
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsNative
import nia.engine.js.JsNull
import nia.engine.js.JsObj
import nia.engine.js.JsParser
import nia.engine.js.JsProp
import nia.engine.js.JsQuotas
import nia.engine.js.JsRuntime
import nia.engine.js.JsStr
import nia.engine.js.JsThrown
import nia.engine.js.JsUndefined
import nia.engine.js.JsValue

import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.net.RequestContext
import nia.engine.profile.ConfigStore

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
 *
 * T030 v2 (R0263, RES-0117, E4 C2 extendido al hook): api-version 2.
 * onRequest recibe el CONTEXTO del host (objeto con url, type,
 * initiator y thirdParty true/false/null) en vez de solo la url;
 * onStartup y onNavigate NO cambian. El objeto nia gana
 * nia.ajustes.leer(id) (solo ajustes DECLARADOS en el ui-model del
 * paquete, valor del perfil via C9 o el defecto), nia.datos.leer
 * (los archivos datos/ retenidos por Niamod) y nia.diag.evento
 * (canal de C10 con la cuota por modulo y el EventContext ACTIVO
 * de la fire; sin contexto activo no hay evento y devuelve false).
 * Migracion compatible: api-version 1 es identico a v1.
 */

/** Entorno del entry en api-version 2 (C2): lo monta el GESTOR con lo
 *  validado en la instalacion del paquete. config null = sin perfil
 *  (los ajustes devuelven su defecto); diag null = sin canal. */
data class EntornoModulo(
    val config: ConfigStore?,
    val uiModel: UiModelDeclarado,
    val datos: Map<String, String>,
    val diag: DiagModulos?
)

/** Punto de paso del EventContext activo durante UNA fire: lo toca
 *  la sesion (try/finally) y lo lee nia.diag; sin carrera posible
 *  porque los hooks son sincronos. */
internal class PuntoDiag {
    var contexto: EventContext? = null
}

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
    internal val limits: JsLimits,
    internal val puntoDiag: PuntoDiag = PuntoDiag()
) {
    internal var descargado = false

    val activo: Boolean get() = !descargado

    fun hookCount(evento: PluginEvent): Int = hooks[evento]?.size ?: 0

    /** Dispara el evento: cada hook recibe la url y el ultimo decide.
     *  C10 v2 (R0267, RES-0118): ctxDiag opcional es el EventContext
     *  real de la fire del shell; sin contexto no hay evento. */
    fun fire(evento: PluginEvent, url: String, ctxDiag: EventContext? = null): Outcome<String> =
        fire(evento, RequestContext(url, "", "", null), ctxDiag)

    /** T030 v2 (C2, R0263, RES-0117): fire con el contexto del host.
     *  Migracion compatible: api-version 1 sigue recibiendo SOLO la
     *  url; api-version 2 con onRequest recibe el objeto con url,
     *  type, initiator y thirdParty. El EventContext activo solo
     *  vive durante esta llamada (try/finally). */
    fun fire(
        evento: PluginEvent,
        context: RequestContext,
        ctxDiag: EventContext? = null
    ): Outcome<String> {
        if (descargado) return Outcome.failure(ErrorInfo(ErrorCode.DENIED, "plugin descargado"))
        val lista = hooks[evento] ?: emptyList()
        if (lista.isEmpty()) return Outcome.success("")
        JsQuotas.begin(limits)
        puntoDiag.contexto = ctxDiag
        var ultimo = ""
        try {
            for (cb in lista) {
                val v = JsInterpreter.callFunction(cb, JsUndefined, argumentosDe(evento, context))
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
            puntoDiag.contexto = null
            JsQuotas.end()
        }
        return Outcome.success(ultimo)
    }

    /** Argumentos del hook: SOLO la url en api-version 1; el objeto de
     *  contexto en api-version 2 con onRequest (el unico hook que gana
     *  contexto en v2; el objeto se crea DENTRO de la sesion de
     *  cuotas: es parte del trabajo del hook, no del host). */
    private fun argumentosDe(evento: PluginEvent, context: RequestContext): List<JsValue> {
        if (evento != PluginEvent.REQUEST || manifest.apiVersion < 2) {
            return listOf(JsStr(context.url))
        }
        val o = JsRuntime.newObj()
        o.props["url"] = JsProp.Value(JsStr(context.url))
        o.props["type"] = JsProp.Value(JsStr(context.resourceType))
        o.props["initiator"] = JsProp.Value(JsStr(context.initiatorUrl))
        val tercero = context.thirdParty
        o.props["thirdParty"] = JsProp.Value(if (tercero == null) JsNull else JsBool(tercero))
        return listOf(o)
    }
}

/** Runtime de plugins (T029/T030): carga con cuotas, revocacion y update. */
class PluginRuntime(
    private val registry: ModuleRegistry,
    private val limits: JsLimits = DEFAULT_LIMITS
) {
    private val sesiones = LinkedHashMap<String, PluginSession>()
    private val codigos = HashMap<String, String>()
    private val entornos = HashMap<String, EntornoModulo?>()

    /**
     * Carga el entry del modulo instalado: pre-parse, cuotas de plugin y
     * objeto nia. El parser real recupera en vez de lanzar (sus errores y
     * los del lexer viajan en el resultado, no como excepcion), asi que el
     * pre-parse es lo que detecta un entry que no compila.
     */
    fun load(id: String, codigo: String, entorno: EntornoModulo? = null): Outcome<PluginSession> {
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
        val punto = PuntoDiag()
        val nia = objetoNia(manifest, hooks, entorno, punto)
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
        val sesion = PluginSession(id, manifest, congelados, limits, punto)
        sesiones[id] = sesion
        codigos[id] = codigo
        entornos[id] = entorno
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
        entornos.remove(id)
        return Outcome.success(Unit)
    }

    /** Sesion activa del plugin o null si no esta cargado. */
    fun sessionOf(id: String): PluginSession? = sesiones[id]

    /**
     * Update con rollback automatico (T030): sustituye manifiesto y
     * entry SIN recompilar; si algo falla, el manifiesto viejo se
     * reinstala y la sesion vieja revive con sus hooks intactos.
     */
    fun update(
        id: String,
        nuevoManifestTexto: String,
        nuevoCodigo: String,
        entorno: EntornoModulo? = null
    ): Outcome<PluginSession> {
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
        val carga = load(id, nuevoCodigo, entorno)
        if (carga is Outcome.Failure) {
            registry.uninstall(id)
            registry.install(textoViejo)
            viejo.descargado = false
            sesiones[id] = viejo
            return Outcome.failure(carga.error)
        }
        return carga
    }

    /** Objeto nia del entry: solo los hooks que los permisos conceden
     *  y, en api-version 2 con entorno, las API de C2 (ajustes,
     *  datos y diagnostico). */
    internal fun objetoNia(
        manifest: ModuleManifest,
        hooks: HashMap<PluginEvent, MutableList<JsValue>>,
        entorno: EntornoModulo? = null,
        punto: PuntoDiag = PuntoDiag()
    ): JsValue {
        val nia = JsRuntime.newObj()
        registrarHook(nia, PluginEvent.STARTUP, hooks)
        registrarHook(nia, PluginEvent.NAVIGATE, hooks)
        if (manifest.permissions.contains(BrokerOps.NETWORK_FETCH)) {
            registrarHook(nia, PluginEvent.REQUEST, hooks)
        }
        if (manifest.apiVersion >= 2 && entorno != null) {
            registrarAjustes(nia, manifest, entorno)
            registrarDatos(nia, entorno)
            registrarDiag(nia, manifest, entorno, punto)
        }
        return nia
    }

    /** nia.ajustes.leer(id): SOLO ajustes DECLARADOS en el ui-model del
     *  paquete (C11); el valor lo pone el perfil via C9 (clave uim.<id>)
     *  y sin config manda el DEFECTO. Un id no declarado es undefined. */
    private fun registrarAjustes(nia: JsObj, manifest: ModuleManifest, entorno: EntornoModulo) {
        val ajustes = JsObj(LinkedHashMap(), JsRuntime.objProto, false, ArrayList())
        ajustes.props["leer"] = JsProp.Value(
            JsBuiltins.fn("leer") { args, _ ->
                val id = args.getOrNull(0)
                if (id !is JsStr) {
                    throw JsRuntime.throwNative("TypeError", "leer exige el id del ajuste")
                }
                val declarado = entorno.uiModel.ajustes.firstOrNull { it.id == id.value }
                if (declarado == null) return@fn JsUndefined
                val valor = entorno.config?.leer(manifest.id, "uim." + declarado.id)
                JsBool(if (valor == null) declarado.defecto else valor == "true")
            }
        )
        nia.props["ajustes"] = JsProp.Value(ajustes)
    }

    /** nia.datos.leer(nombre): los archivos datos/ del paquete
     *  retenidos por Niamod en la instalacion (NADA mas: el entry no
     *  toca el disco ni la red). Un nombre ausente es undefined. */
    private fun registrarDatos(nia: JsObj, entorno: EntornoModulo) {
        val datos = JsObj(LinkedHashMap(), JsRuntime.objProto, false, ArrayList())
        datos.props["leer"] = JsProp.Value(
            JsBuiltins.fn("leer") { args, _ ->
                val nombre = args.getOrNull(0)
                if (nombre !is JsStr) {
                    throw JsRuntime.throwNative("TypeError", "leer exige el nombre del dato")
                }
                val contenido = entorno.datos[nombre.value]
                if (contenido == null) JsUndefined else JsStr(contenido)
            }
        )
        nia.props["datos"] = JsProp.Value(datos)
    }

    /** nia.diag.evento(nivel, texto): canal de C10 (cuota por modulo,
     *  recorte y redaccion del HOST que manda). Solo escribe con el
     *  EventContext ACTIVO de la fire; sin contexto o sin canal
     *  devuelve false. Nivel: info, warning o error exactos. */
    private fun registrarDiag(
        nia: JsObj,
        manifest: ModuleManifest,
        entorno: EntornoModulo,
        punto: PuntoDiag
    ) {
        val diag = JsObj(LinkedHashMap(), JsRuntime.objProto, false, ArrayList())
        diag.props["evento"] = JsProp.Value(
            JsBuiltins.fn("evento") { args, _ ->
                val canal = entorno.diag
                val ctx = punto.contexto
                if (canal == null || ctx == null) return@fn JsBool(false)
                val nivelArg = args.getOrNull(0)
                val textoArg = args.getOrNull(1)
                if (nivelArg !is JsStr || textoArg !is JsStr) {
                    throw JsRuntime.throwNative("TypeError", "evento exige nivel (info, warning, error) y texto")
                }
                val nivel = when (nivelArg.value) {
                    "info" -> EventLevel.INFO
                    "warning" -> EventLevel.WARNING
                    "error" -> EventLevel.ERROR
                    else -> return@fn JsBool(false)
                }
                JsBool(canal.evento(manifest.id, nivel, textoArg.value, ctx))
            }
        )
        nia.props["diag"] = JsProp.Value(diag)
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
