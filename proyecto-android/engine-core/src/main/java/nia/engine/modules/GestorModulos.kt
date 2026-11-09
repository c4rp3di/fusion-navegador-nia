package nia.engine.modules

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome
import nia.engine.inspector.EventContext
import nia.engine.inspector.EventLevel
import nia.engine.profile.ConfigStore

/**
 * T094 v1 (R0226, RES-0106, ADR-005 APROBADO en R0225): gestor de
 * modulos del shell sobre el registro (T009) y el runtime (T029/T030)
 * TAL CUAL. Un modulo instalado sin cargar esta DESACTIVADO; con
 * hooks cargados ACTIVO; con fallos de hook contados ERROR (sigue
 * activo: fail-open, un modulo roto JAMAS rompe la navegacion y su
 * veredicto cuenta como abstencion, ADR-005). El alcance global/sitio
 * lo guarda el HOST, no el modulo (ADR-005): GLOBAL dispara en todo
 * origen, SITIO solo en los origenes anotados. Los contadores
 * explican la actividad: disparos, errores y ultimo veredicto
 * truncado a 80 caracteres.
 *
 * Exclusiones v1: activar exige el entry conocido por el host (el
 * canal de instalacion .niamod con firma llega con T095; aqui activa
 * el demo embebido), sin UI de paneles (la categoria panel se
 * declara, la superficie llega con los paneles), sin hook de
 * contenido (la capability modificar NO tiene soporte en v1) y la
 * persistencia del alcance es un TSV propio (modulos.tsv) cuyas
 * lineas corruptas se ignoran sin romper el estado vivo.
 *
 * C11 v2 (R0260, RES-0116): el gestor registra el ui-model
 * declarado del paquete (uimodel/1) y lo expone al shell; los
 * VALORES de los ajustes declarados persisten por perfil via C9
 * (clave "uim.<id>").
 *
 * T030 v2 (R0263, RES-0117, E4 C2): el gestor monta el ENTORNO del
 * entry (ajustes del ui-model + config del perfil, datos/ del
 * paquete, canal de diagnostico) y lo pasa al runtime en load,
 * update y activar; con api-version 2 el entry lee ajustes con
 * nia.ajustes.leer, datos con nia.datos.leer y escribe diagnostico
 * con nia.diag.evento. Sin config o sin diag el entorno llega con
 * esas piezas en null: el entry de api-version 1 no cambia.
 *
 * C3 v1 (R0271, RES-0119): el gestor retiene los recursos de
 * sustitucion del paquete instalado y los expone al filtro de red
 * del shell (recursoSustituible); viven y mueren con el modulo.
 *
 * C6 v1 (R0276, RES-0120): el gestor retiene la hoja de estilos del
 * paquete instalado (hojaEstilos) y resuelve la hoja del documento
 * tras cada disparo de navegacion (hojaParaNavegacion): gana el
 * primer modulo en orden de instalacion cuyo veredicto fue "estilos"
 * en el ultimo disparo (api-version 2 con hoja declarada); los
 * modulos api-version 1 se ignoran y los que pierden reciben
 * diagnostico "no disponible" (C10, solo con EventContext). UNA
 * hoja por documento: vive y muere con la navegacion.
 *
 * C5 v1 (R0280, RES-0121): el gestor retiene el script de documento
 * del paquete instalado (scriptDeModulo) y resuelve el script del
 * documento tras cada disparo de navegacion
 * (scriptParaNavegacion): gana el primer modulo en orden de
 * instalacion cuyo veredicto fue "script" en el ultimo disparo
 * (api-version 2 con script declarado); UN script por documento:
 * vive y muere con la navegacion (la cancelacion de su sesion la
 * hace el shell al pintar el documento siguiente).
 *
 * T054 v1 (R0284, RES-0122): excepciones de sitio. Alcance gana
 * excepto (origenes donde un modulo GLOBAL se abstiene);
 * exceptuarSitio/permitirSitio las anotan (solo alcance GLOBAL,
 * INVALID_INPUT honesto en SITIO), hacerGlobal las CONSERVA y
 * estaExceptuado las expone al filtro de red del shell; el TSV
 * escribe GLOBAL_EXCEPTO para el alcance global con excepciones
 * (las lineas GLOBAL/SITIO de antes cargan identicas).
 *
 * T053 v1 (R0289, RES-0123): el gestor retiene la lista cosmetica del
 * paquete instalado (recursoCosmeticos) y resuelve la lista del
 * documento tras cada disparo de navegacion
 * (cosmeticosParaNavegacion): gana el primer modulo en orden de
 * instalacion cuyo veredicto fue "cosmeticos" en el ultimo disparo
 * (api-version 2 con cosmeticos declarados); UNA lista por documento:
 * vive y muere con la navegacion.
 */

/** Estado visible de un modulo en el gestor (ADR-005). */
enum class EstadoModulo { DESACTIVADO, ACTIVO, ERROR }

/** Resumen de una ronda de disparos (para el estado del shell). */
data class ResumenDisparos(val disparados: Int, val errores: Int)

/** Ficha de un modulo para el gestor (T094). */
data class FichaModulo(
    val id: String,
    val version: Int,
    val entry: String,
    val name: String,
    val description: String,
    val categories: Set<String>,
    val capabilities: Set<String>,
    val permissions: Set<String>,
    val estado: EstadoModulo,
    val alcanceGlobal: Boolean,
    val alcanceOrigenes: List<String>,
    val alcanceExcepciones: List<String>,
    val hooksStartup: Int,
    val hooksNavigate: Int,
    val hooksRequest: Int,
    val disparos: Int,
    val errores: Int,
    val ultimoVeredicto: String
)

/** Confianza de un paquete frente al ancla del id (T095, ADR-005). */
enum class EstadoClave { NUEVA_AUTORIA, MISMA_CLAVE, ROTACION_PENDIENTE, CAMBIO_MANUAL }

/** Previa de un paquete .niamod para pedir la aprobacion del usuario
 *  (ADR-028: instalar = anclar con consentimiento explicito). */
data class PreviaPaquete(
    val id: String,
    val name: String,
    val version: Int,
    val entry: String,
    val permisos: Set<String>,
    val categories: Set<String>,
    val capabilities: Set<String>,
    val huellaPaquete: String,
    val huellaClave: String,
    val nextHuella: String?,
    val esUpdate: Boolean,
    val permisosNuevos: List<String>,
    val estadoClave: EstadoClave,
    val ajustesDeclarados: Int,
    val accionesDeclaradas: Int,
    val panelesDeclarados: Int,
    val archivosDatos: Int,
    val bytesDatos: Long
)

/** Aprobacion explicita del usuario: cita la huella de clave que EL
 *  VIO en la previa (el gestor rechaza una aprobacion de otra huella). */
data class AprobacionPaquete(
    val huellaAprobada: String,
    val aceptaNuevosPermisos: Boolean
)

/** Gestor sobre el registro y el runtime del motor. */
class GestorModulos(
    private val registry: ModuleRegistry,
    private val runtime: PluginRuntime,
    private val almacen: File? = null,
    private val claves: File? = null,
    private val config: ConfigStore? = null,
    private val diag: DiagModulos? = null
) {
    private val codigos = HashMap<String, String>()
    private val disparos = HashMap<String, Int>()
    private val errores = HashMap<String, Int>()
    private val veredictos = HashMap<String, String>()
    private val alcances = HashMap<String, Alcance>()
    private val ancladas = HashMap<String, String>()
    private val pendientes = HashMap<String, String>()
    private val uiModelos = HashMap<String, UiModelDeclarado>()
    private val datosPaquetes = HashMap<String, Map<String, String>>()
    private val sustitutosPaquetes = HashMap<String, Map<String, RecursoSustituto>>()
    private val estilosPaquetes = HashMap<String, RecursoEstilo>()
    private val scriptsPaquetes = HashMap<String, RecursoScript>()
    private val cosmeticosPaquetes = HashMap<String, RecursoCosmeticos>()
    private val veredictosDisparo = LinkedHashMap<String, String>()

    /** Alcance de un modulo: global o solo estos origenes (ADR-005). */
    data class Alcance(
        val global: Boolean,
        val origenes: Set<String>,
        val excepto: Set<String> = emptySet()
    ) {
        /** T054 v1: la excepcion veta el origen ANTES del alcance. */
        fun aplica(origen: String): Boolean =
            !excepto.contains(origen) && (global || origenes.contains(origen))
    }

    init {
        if (almacen != null) cargar()
        if (claves != null) cargarClaves()
    }

    /** Fichas de todos los modulos instalados, en orden de instalacion. */
    @Synchronized
    fun fichas(): List<FichaModulo> {
        val lista = ArrayList<FichaModulo>()
        for (id in registry.installedIds()) {
            val m = registry.manifestOf(id) ?: continue
            val sesion = runtime.sessionOf(id)
            val fallos = errores[id] ?: 0
            val estado = when {
                sesion == null -> EstadoModulo.DESACTIVADO
                fallos > 0 -> EstadoModulo.ERROR
                else -> EstadoModulo.ACTIVO
            }
            val alcance = alcances[id] ?: Alcance(true, emptySet())
            lista.add(
                FichaModulo(
                    id = id,
                    version = m.version,
                    entry = m.entry,
                    name = if (m.name.isEmpty()) id else m.name,
                    description = m.description,
                    categories = m.categories,
                    capabilities = m.capabilities,
                    permissions = m.permissions,
                    estado = estado,
                    alcanceGlobal = alcance.global,
                    alcanceOrigenes = ArrayList(alcance.origenes),
                    alcanceExcepciones = ArrayList(alcance.excepto),
                    hooksStartup = sesion?.hookCount(PluginEvent.STARTUP) ?: 0,
                    hooksNavigate = sesion?.hookCount(PluginEvent.NAVIGATE) ?: 0,
                    hooksRequest = sesion?.hookCount(PluginEvent.REQUEST) ?: 0,
                    disparos = disparos[id] ?: 0,
                    errores = fallos,
                    ultimoVeredicto = veredictos[id] ?: ""
                )
            )
        }
        return lista
    }

    /** Ficha de un modulo o null si no esta instalado. */
    @Synchronized
    fun ficha(id: String): FichaModulo? = fichas().find { it.id == id }

    /** Instala el demo embebido (v2, ADR-005): observa, no bloquea. */
    @Synchronized
    fun instalarDemo(): Outcome<Unit> = instalar(DEMO_MANIFIESTO, DEMO_ENTRY)

    /**
     * Instala un manifiesto con su entry y lo activa; si la carga
     * falla, retira el registro (nada queda a medias).
     */
    @Synchronized
    fun instalar(manifestTexto: String, codigo: String): Outcome<Unit> {
        val instalado = registry.install(manifestTexto)
        if (instalado is Outcome.Failure) return Outcome.failure(instalado.error)
        val mod = (instalado as? Outcome.Success)?.value as? InstalledModule
        if (mod == null) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "instalacion sin modulo")
            )
        }
        val carga = runtime.load(mod.id, codigo)
        if (carga is Outcome.Failure) {
            registry.uninstall(mod.id)
            return Outcome.failure(carga.error)
        }
        codigos[mod.id] = codigo
        return Outcome.success(Unit)
    }

    /** Previa del paquete .niamod (T095, ADR-005): el usuario ve TODO
     *  (id, permisos, huella del paquete y de la clave, estado de
     *  confianza, permisos nuevos en update) ANTES de aprobar. */
    @Synchronized
    fun previsualizarPaquete(bytes: ByteArray): Outcome<PreviaPaquete> {
        return when (val p = Niamod.inspeccionar(bytes)) {
            is Outcome.Failure -> Outcome.failure(p.error)
            is Outcome.Success -> Outcome.success(previa(p.value)
            )
        }
    }

    private fun previa(p: PaqueteNiamod): PreviaPaquete {
        val id = p.manifest.id
        val anclada = ancladas[id]
        val estado = when {
            anclada == null -> EstadoClave.NUEVA_AUTORIA
            p.huellaClave == anclada -> EstadoClave.MISMA_CLAVE
            pendientes[id] == p.huellaClave -> EstadoClave.ROTACION_PENDIENTE
            else -> EstadoClave.CAMBIO_MANUAL
        }
        val actuales = if (registry.isInstalled(id)) {
            registry.manifestOf(id)?.permissions ?: emptySet()
        } else {
            emptySet()
        }
        val nuevos = ArrayList<String>()
        for (perm in p.manifest.permissions) {
            if (!actuales.contains(perm)) nuevos.add(perm)
        }
        return PreviaPaquete(
            id, p.manifest.name, p.manifest.version, p.manifest.entry,
            p.manifest.permissions, p.manifest.categories, p.manifest.capabilities,
            p.huellaPaquete, p.huellaClave, p.nextHuella,
            registry.isInstalled(id), nuevos, estado,
            p.uiModel.ajustes.size, p.uiModel.acciones.size, p.uiModel.paneles.size,
            p.archivosDatos, p.bytesDatos
        )
    }

    /**
     * Instala un paquete .niamod validado por Niamod (T095, ADR-005):
     * instalar = anclar la huella sha256(SPKI) del autor con la
     * aprobacion explicita que EL VIO (la aprobacion debe citar la
     * huella real: una aprobacion de otra huella se rechaza); update
     * firmado por la clave anclada; rotacion de UN paso via next-key;
     * sin next-key, un cambio de clave exige reaprobacion manual
     * JAMAS silenciosa; permisos nuevos reconfirmados. Si el modulo
     * estaba activo el update usa el rollback de T030 (si falla, el
     * modulo viejo revive); si estaba desactivado, el registro se
     * sustituye sin sesion (sigue desactivado, nada a medias).
     */
    @Synchronized
    fun instalarPaquete(bytes: ByteArray, aprobacion: AprobacionPaquete): Outcome<Unit> {
        val p = when (val r = Niamod.inspeccionar(bytes)) {
            is Outcome.Failure -> return Outcome.failure(r.error)
            is Outcome.Success -> r.value
        }
        val id = p.manifest.id
        val anclada = ancladas[id]
        if (anclada == null) {
            if (aprobacion.huellaAprobada != p.huellaClave) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.DENIED, "instalar exige aprobar la huella del autor")
                )
            }
        } else if (p.huellaClave != anclada &&
            pendientes[id] != p.huellaClave &&
            aprobacion.huellaAprobada != p.huellaClave
        ) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.DENIED, "cambio de clave exige reaprobacion manual")
            )
        }
        val esUpdate = registry.isInstalled(id)
        if (esUpdate) {
            val actuales = registry.manifestOf(id)?.permissions ?: emptySet()
            val nuevos = ArrayList<String>()
            for (perm in p.manifest.permissions) {
                if (!actuales.contains(perm)) nuevos.add(perm)
            }
            if (!nuevos.isEmpty() && !aprobacion.aceptaNuevosPermisos) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.DENIED, "permisos nuevos exigen reconfirmacion")
                )
            }
            if (runtime.sessionOf(id) != null) {
                val upd = runtime.update(
                    id, p.manifiestoTexto, p.entryTexto,
                    EntornoModulo(config, p.uiModel, p.datos, diag)
                )
                if (upd is Outcome.Failure) return Outcome.failure(upd.error)
            } else {
                val viejo = registry.manifestOf(id)
                if (viejo == null) {
                    return Outcome.failure(
                        ErrorInfo(ErrorCode.INVALID_INPUT, "modulo sin manifiesto: " + id)
                    )
                }
                val textoViejo = runtime.manifestTexto(viejo)
                registry.uninstall(id)
                val instalado = registry.install(p.manifiestoTexto)
                if (instalado is Outcome.Failure) {
                    registry.install(textoViejo)
                    return Outcome.failure(instalado.error)
                }
            }
        } else {
            val instalado = registry.install(p.manifiestoTexto)
            if (instalado is Outcome.Failure) return Outcome.failure(instalado.error)
            val carga = runtime.load(
                id, p.entryTexto, EntornoModulo(config, p.uiModel, p.datos, diag)
            )
            if (carga is Outcome.Failure) {
                registry.uninstall(id)
                return Outcome.failure(carga.error)
            }
        }
        codigos[id] = p.entryTexto
        uiModelos[id] = p.uiModel
        datosPaquetes[id] = p.datos
        sustitutosPaquetes[id] = p.sustitutos
        if (p.estilo != null) {
            estilosPaquetes[id] = p.estilo
        } else {
            estilosPaquetes.remove(id)
        }
        if (p.script != null) {
            scriptsPaquetes[id] = p.script
        } else {
            scriptsPaquetes.remove(id)
        }
        if (p.cosmeticos != null) {
            cosmeticosPaquetes[id] = p.cosmeticos
        } else {
            cosmeticosPaquetes.remove(id)
        }
        ancladas[id] = p.huellaClave
        if (p.nextHuella != null && p.nextHuella != p.huellaClave) {
            pendientes[id] = p.nextHuella
        } else {
            pendientes.remove(id)
        }
        guardarClaves()
        return Outcome.success(Unit)
    }

    /** Activa un modulo instalado (exige entry conocida del host). */
    @Synchronized
    fun activar(id: String): Outcome<Unit> {
        val codigo = codigos[id]
        if (codigo == null) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "sin entry conocida para el modulo: " + id)
            )
        }
        val carga = runtime.load(
            id, codigo,
            EntornoModulo(
                config,
                uiModelos[id] ?: UiModelDeclarado(emptyList(), emptyList(), emptyList()),
                datosPaquetes[id] ?: emptyMap(),
                diag
            )
        )
        if (carga is Outcome.Failure) return Outcome.failure(carga.error)
        return Outcome.success(Unit)
    }

    /** Desactiva un modulo: el fire posterior cae en DENIED (T030). */
    @Synchronized
    fun desactivar(id: String): Outcome<Unit> = runtime.unload(id)

    /** C11 v2 (R0260, RES-0116): registro declarado ui-model del
     *  modulo o null si no declara (parseado y validado en la
     *  instalacion del paquete; el demo embebido no declara). */
    @Synchronized
    fun uiModelDe(id: String): UiModelDeclarado? = uiModelos[id]

    /** C3 v1 (R0271, RES-0119): recurso de sustitucion del paquete
     *  instalado o null (sin modulo, sin recurso o desinstalado); lo
     *  resuelve el filtro de red ANTES de servir la sustitucion. */
    @Synchronized
    fun recursoSustituible(id: String, nombre: String): RecursoSustituto? =
        sustitutosPaquetes[id]?.get(nombre)

    /** C6 v1 (R0276, RES-0120): hoja de estilos del paquete instalado
     *  o null (sin modulo, sin hoja declarada o desinstalado). */
    @Synchronized
    fun hojaEstilos(id: String): RecursoEstilo? = estilosPaquetes[id]

    /**
     * C6 v1 (R0276, RES-0120): hoja del documento tras el disparo de
     * navegacion: gana el primer modulo en ORDEN DE INSTALACION cuyo
     * veredicto fue "estilos" en el ULTIMO disparo con alcance en el
     * origen (api-version 2 con hoja declarada); api-version 1 o sin
     * hoja se ignoran (abstencion) y los que pierden reciben
     * diagnostico "no disponible" (solo con EventContext, C10).
     * UNA hoja por documento.
     */
    @Synchronized
    fun hojaParaNavegacion(origen: String, ctxDiag: EventContext? = null): RecursoEstilo? {
        var ganadora: RecursoEstilo? = null
        for (id in registry.installedIds()) {
            val alcance = alcances[id] ?: Alcance(true, emptySet())
            if (!alcance.aplica(origen)) continue
            if (veredictosDisparo[id] != "estilos") continue
            val manifest = registry.manifestOf(id) ?: continue
            if (manifest.apiVersion < 2) continue
            val hoja = estilosPaquetes[id] ?: continue
            if (ganadora == null) {
                ganadora = hoja
            } else if (ctxDiag != null && diag != null) {
                diag.evento(
                    id, EventLevel.WARNING,
                    "estilos no disponible: ya hay otra hoja para este documento",
                    ctxDiag
                )
            }
        }
        return ganadora
    }

    /** C5 v1 (R0280, RES-0121): script de documento del paquete instalado
     *  o null (sin modulo, sin script declarado o desinstalado). */
    @Synchronized
    fun scriptDeModulo(id: String): RecursoScript? = scriptsPaquetes[id]

    /** T053 v1 (R0289, RES-0123): lista cosmetica del paquete instalado
     *  o null (sin modulo, sin cosmeticos declarados o desinstalado). */
    @Synchronized
    fun recursoCosmeticos(id: String): RecursoCosmeticos? = cosmeticosPaquetes[id]

    /**
     * C5 v1 (R0280, RES-0121): script del documento tras el disparo de
     * navegacion: gana el primer modulo en ORDEN DE INSTALACION cuyo
     * veredicto fue "script" en el ULTIMO disparo con alcance en el
     * origen (api-version 2 con script declarado); api-version 1 o sin
     * script se ignoran (abstencion) y los que pierden reciben
     * diagnostico "no disponible" (solo con EventContext, C10).
     * UN script por documento: vive y muere con la navegacion.
     */
    @Synchronized
    fun scriptParaNavegacion(origen: String, ctxDiag: EventContext? = null): RecursoScript? {
        var ganador: RecursoScript? = null
        for (id in registry.installedIds()) {
            val alcance = alcances[id] ?: Alcance(true, emptySet())
            if (!alcance.aplica(origen)) continue
            if (veredictosDisparo[id] != "script") continue
            val manifest = registry.manifestOf(id) ?: continue
            if (manifest.apiVersion < 2) continue
            val script = scriptsPaquetes[id] ?: continue
            if (ganador == null) {
                ganador = script
            } else if (ctxDiag != null && diag != null) {
                diag.evento(
                    id, EventLevel.WARNING,
                    "script no disponible: ya hay otro script para este documento",
                    ctxDiag
                )
            }
        }
        return ganador
    }

    /**
     * T053 v1 (R0289, RES-0123): lista cosmetica del documento tras el
     * disparo de navegacion: gana el primer modulo en ORDEN DE
     * INSTALACION cuyo veredicto fue "cosmeticos" en el ULTIMO disparo
     * con alcance en el origen (api-version 2 con cosmeticos
     * declarados); api-version 1 o sin cosmeticos se ignoran
     * (abstencion) y los que pierden reciben diagnostico
     * "no disponible" (solo con EventContext, C10). UNA lista por
     * documento: vive y muere con la navegacion.
     */
    @Synchronized
    fun cosmeticosParaNavegacion(origen: String, ctxDiag: EventContext? = null): RecursoCosmeticos? {
        var ganador: RecursoCosmeticos? = null
        for (id in registry.installedIds()) {
            val alcance = alcances[id] ?: Alcance(true, emptySet())
            if (!alcance.aplica(origen)) continue
            if (veredictosDisparo[id] != "cosmeticos") continue
            val manifest = registry.manifestOf(id) ?: continue
            if (manifest.apiVersion < 2) continue
            val lista = cosmeticosPaquetes[id] ?: continue
            if (ganador == null) {
                ganador = lista
            } else if (ctxDiag != null && diag != null) {
                diag.evento(
                    id, EventLevel.WARNING,
                    "cosmeticos no disponible: ya hay otra lista para este documento",
                    ctxDiag
                )
            }
        }
        return ganador
    }

    /** Retira el modulo entero: sesion, registro, alcance y contadores. */
    @Synchronized
    fun desinstalar(id: String): Outcome<Unit> {
        runtime.unload(id)
        val fuera = registry.uninstall(id)
        if (fuera is Outcome.Failure) return Outcome.failure(fuera.error)
        codigos.remove(id)
        disparos.remove(id)
        errores.remove(id)
        veredictos.remove(id)
        alcances.remove(id)
        uiModelos.remove(id)
        datosPaquetes.remove(id)
        sustitutosPaquetes.remove(id)
        estilosPaquetes.remove(id)
        scriptsPaquetes.remove(id)
        cosmeticosPaquetes.remove(id)
        veredictosDisparo.remove(id)
        guardar()
        return Outcome.success(Unit)
    }

    /** Limita el modulo a un origen mas (alcance del HOST, ADR-005). */
    @Synchronized
    fun soloEnEsteSitio(id: String, origen: String): Outcome<Unit> {
        if (!registry.isInstalled(id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id)
            )
        }
        val actual = alcances[id]
        val nuevos = LinkedHashSet(actual?.origenes ?: emptySet())
        nuevos.add(origen)
        alcances[id] = Alcance(false, nuevos)
        guardar()
        return Outcome.success(Unit)
    }

    /** Devuelve el modulo al alcance global (T054: conserva las
     *  excepciones de sitio anotadas). */
    @Synchronized
    fun hacerGlobal(id: String): Outcome<Unit> {
        if (!registry.isInstalled(id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id)
            )
        }
        alcances[id] = Alcance(true, emptySet(), alcances[id]?.excepto ?: emptySet())
        guardar()
        return Outcome.success(Unit)
    }

    /** T054 v1 (R0284, RES-0122): anota una EXCEPCION de origen en un
     *  modulo de alcance global (ADR-005): el modulo se abstiene en
     *  ese origen en onNavigate y en el filtro de red
     *  (estaExceptuado). En alcance SITIO se rechaza (INVALID_INPUT):
     *  quitar el origen de la lista ya existe. */
    @Synchronized
    fun exceptuarSitio(id: String, origen: String): Outcome<Unit> {
        if (!registry.isInstalled(id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id)
            )
        }
        val actual = alcances[id] ?: Alcance(true, emptySet())
        if (!actual.global) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "exceptuar exige alcance global: " + id)
            )
        }
        val nuevos = LinkedHashSet(actual.excepto)
        nuevos.add(origen)
        alcances[id] = Alcance(true, actual.origenes, nuevos)
        guardar()
        return Outcome.success(Unit)
    }

    /** T054 v1: retira la excepcion de origen (no-op si no estaba). */
    @Synchronized
    fun permitirSitio(id: String, origen: String): Outcome<Unit> {
        if (!registry.isInstalled(id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id)
            )
        }
        val actual = alcances[id] ?: return Outcome.success(Unit)
        val nuevos = LinkedHashSet(actual.excepto)
        nuevos.remove(origen)
        alcances[id] = Alcance(actual.global, actual.origenes, nuevos)
        guardar()
        return Outcome.success(Unit)
    }

    /** T054 v1: consulta de excepcion para el filtro de red (corre
     *  en hilos de red); sin alcance anotado no hay excepcion. */
    @Synchronized
    fun estaExceptuado(id: String, origen: String): Boolean =
        alcances[id]?.excepto?.contains(origen) == true

    /**
     * Notifica onNavigate a los modulos ACTIVOS con alcance en el
     * origen (fail-open, ADR-005): cuenta disparos y errores y guarda
     * el ultimo veredicto truncado; JAMAS lanza. C10 v2 (R0267,
     * RES-0118): ctxDiag opcional es el EventContext real de la
     * navegacion; sin contexto el diagnostico del modulo se abstiene.
     */
    @Synchronized
    fun dispararNavegar(origen: String, url: String, ctxDiag: EventContext? = null): ResumenDisparos {
        var disparados = 0
        var conErrores = 0
        veredictosDisparo.clear()
        for (id in registry.installedIds()) {
            val sesion = runtime.sessionOf(id) ?: continue
            val alcance = alcances[id] ?: Alcance(true, emptySet())
            if (!alcance.aplica(origen)) continue
            disparos[id] = (disparos[id] ?: 0) + 1
            disparados += 1
            when (val r = sesion.fire(PluginEvent.NAVIGATE, url, ctxDiag)) {
                is Outcome.Success -> {
                    veredictos[id] = truncar(r.value)
                    veredictosDisparo[id] = r.value.trim()
                }
                is Outcome.Failure -> {
                    errores[id] = (errores[id] ?: 0) + 1
                    conErrores += 1
                }
            }
        }
        return ResumenDisparos(disparados, conErrores)
    }

    private fun truncar(s: String): String = if (s.length > 80) s.substring(0, 80) else s

    /** Persistencia best-effort del alcance; JAMAS rompe el gestor. */
    private fun guardar() {
        if (almacen == null) return
        try {
            val sb = StringBuilder()
            for ((id, a) in alcances) {
                sb.append(id).append('\t')
                if (a.global) {
                    if (a.excepto.isEmpty()) {
                        sb.append("GLOBAL")
                    } else {
                        sb.append("GLOBAL_EXCEPTO").append('\t')
                            .append(a.excepto.joinToString("|"))
                    }
                } else {
                    sb.append("SITIO").append('\t').append(a.origenes.joinToString("|"))
                }
                sb.append('\n')
            }
            almacen.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun cargar() {
        if (almacen == null || !almacen.exists()) return
        try {
            for (linea in almacen.readText().lines()) {
                val t = linea.trim()
                if (t.isEmpty()) continue
                val p = t.split('\t')
                if (p.size < 2) continue
                when (p[1]) {
                    "GLOBAL" -> alcances[p[0]] = Alcance(true, emptySet())
                    "SITIO" -> {
                        val origenes = if (p.size >= 3) {
                            p[2].split('|').filter { it.isNotEmpty() }.toSet()
                        } else {
                            emptySet()
                        }
                        alcances[p[0]] = Alcance(false, origenes)
                    }
                    "GLOBAL_EXCEPTO" -> {
                        val excepto = if (p.size >= 3) {
                            p[2].split('|').filter { it.isNotEmpty() }.toSet()
                        } else {
                            emptySet()
                        }
                        alcances[p[0]] = Alcance(true, emptySet(), excepto)
                    }
                    else -> {} // linea corrupta: se ignora
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Persistencia best-effort del anillo de confianza (T095,
     *  ADR-005): id, huella anclada y pendiente de rotacion; lineas
     *  corruptas se ignoran; desinstalar NO borra el anillo (el autor
     *  ya aprobado no exige reaprobacion al reinstalar). */
    private fun guardarClaves() {
        if (claves == null) return
        try {
            val sb = StringBuilder()
            for ((id, h) in ancladas) {
                sb.append(id).append('\t').append(h)
                val pend = pendientes[id]
                if (pend != null) sb.append('\t').append(pend)
                sb.append('\n')
            }
            claves.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun cargarClaves() {
        if (claves == null || !claves.exists()) return
        try {
            for (linea in claves.readText().lines()) {
                val t = linea.trim()
                if (t.isEmpty()) continue
                val p = t.split('\t')
                if (p.size < 2) continue
                if (!ModuleSchema.validId(p[0])) continue
                if (p[1].length != 64) continue
                ancladas[p[0]] = p[1]
                if (p.size >= 3 && p[2].length == 64) pendientes[p[0]] = p[2]
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Demo embebido (T094, ADR-005): observa sin bloquear nada. */
        const val DEMO_MANIFIESTO =
            "id = demo.observador\n" +
            "version = 2\n" +
            "entry = demo-observador.js\n" +
            "name = Observador demo\n" +
            "description = Observa navegaciones sin bloquear nada (demo del gestor)\n" +
            "category = background\n" +
            "capabilities = observar\n" +
            "api-version = 1\n"

        const val DEMO_ENTRY =
            "// demo.observador (T094, ADR-005): observa, no bloquea ni modifica.\n" +
            "nia.onStartup(function (url) { return \"observa\"; });\n" +
            "nia.onNavigate(function (url) { return \"observa\"; });\n"
    }
}
