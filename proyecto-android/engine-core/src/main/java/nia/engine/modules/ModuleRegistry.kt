package nia.engine.modules

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

import nia.engine.broker.Broker
import nia.engine.broker.BrokerOps
import nia.engine.broker.Renderer

/**
 * T009 v1 (R0037, RES-0024): paquete declarativo/instalacion. Un modulo se
 * declara con un manifiesto de lineas "clave = valor" (id, version, entry,
 * permissions) y el registro valida TODO antes de instalar: lo que no pasa
 * la validacion es un paquete malicioso y se rechaza con INVALID_INPUT (o
 * UNSUPPORTED si el manifiesto viene de una version futura).
 *
 * Integracion con el broker de T006: instalar adjunta un contexto cuyas
 * operaciones concedidas son EXACTAMENTE los permisos declarados (un
 * modulo nunca toca pestanas: las operaciones tab.* no son declarables) y
 * uninstall cierra ese contexto (los requests posteriores caen en DENIED).
 *
 * Limitaciones v1: sin firma/hash del paquete (el contenido llega aparte en
 * fases posteriores), sin versiones multiples del mismo id (reinstalar
 * exige uninstall primero), sin activacion parcial y sin eventos.
 *
 * T094 v1 (R0226, ADR-005 APROBADO en R0225): schema v2 con claves
 * OPCIONALES name, description, api-version, category y capabilities
 * (ADR-005); una version de manifiesto o una api-version futuras se
 * rechazan con UNSUPPORTED, y capabilities = bloquear exige el
 * permiso network.fetch (paquete incoherente, ADR-005). Los
 * manifiestos v1 siguen validos TAL CUAL.
 *
 * T030 v2 (R0263, RES-0117, E4 C2): api-version = 2 es el contrato
 * del hook CONTEXTO: onRequest recibe el objeto con url, type,
 * initiator y thirdParty y el entry gana nia.ajustes, nia.datos y
 * nia.diag (montados por el gestor). Migracion compatible: los
 * modulos api-version 1 siguen recibiendo SOLO la url y su objeto
 * nia no cambia; una api-version futura se rechaza con UNSUPPORTED.
 *
 * T095 v1 (R0227, ADR-005): claves OPCIONALES author-key y next-key
 * (SPKI base64; next-key exige author-key y debe diferir); el ancla
 * de confianza sha256(SPKI) y la firma ECDSA viven en Niamod y en el
 * gestor (instalar = anclar, ADR-028).
 *
 * C3 v1 (R0271, RES-0119, E4): clave OPCIONAL sustitutos (lista por
 * comas, <= 8 nombres idValido) para la respuesta sustituta; exige
 * la capability modificar y el permiso network.response.
 *
 * C6 v1 (R0276, RES-0120, E4): clave OPCIONAL estilos (UN nombre
 * idValido) para la hoja de estilos del modulo; exige la capability
 * modificar y el permiso document.styles.
 *
 * C5 v1 (R0280, RES-0121, E4): clave OPCIONAL script (UN nombre
 * idValido) para el script de documento del modulo; exige la
 * capability modificar y el permiso document.script. T053 v1
 * (R0289, RES-0123): la clave cosmeticos declara una lista cosmetica
 * EasyList de datos/ (idValido) para el modulo; exige la capability
 * modificar y el permiso document.cosmetic.
 */

data class ModuleManifest(
    val id: String,
    val version: Int,
    val entry: String,
    val permissions: Set<String>,
    val name: String = "",
    val description: String = "",
    val apiVersion: Int = 1,
    val categories: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val authorKey: String = "",
    val nextKey: String = "",
    val sustitutos: List<String> = emptyList(),
    val estilos: String? = null,
    val script: String? = null,
    val cosmeticos: String? = null
)

object ModuleSchema {
    const val CURRENT_VERSION = 2
    const val MANIFEST_MAX_CHARS = 4096
    const val MAX_ID_CHARS = 64
    const val MAX_ENTRY_CHARS = 256
    const val MAX_NAME_CHARS = 64
    const val MAX_DESCRIPTION_CHARS = 256

    /** C3 v1 (R0271, RES-0119): tope de sustitutos declarados. */
    const val MAX_SUSTITUTOS = 8

    /** C3 v1 (R0271, RES-0119): permiso de la respuesta sustituta. */
    const val NETWORK_RESPONSE = "network.response"

    /** C6 v1 (R0276, RES-0120): permiso de la hoja de estilos. */
    const val DOCUMENT_STYLES = "document.styles"

    /** C5 v1 (R0280, RES-0121): permiso del script de documento. */
    const val DOCUMENT_SCRIPT = "document.script"

    /** T053 v1 (R0289, RES-0123): permiso de la lista cosmetica. */
    const val DOCUMENT_COSMETIC = "document.cosmetic"

    val KEYS = setOf(
        "id", "version", "entry", "permissions",
        "name", "description", "api-version", "category", "capabilities",
        "author-key", "next-key", "sustitutos", "estilos", "script", "cosmeticos"
    )

    /** Categorias combinables del gestor (ADR-005, Delta P9). */
    val ALLOWED_CATEGORIES = setOf("background", "contextual", "panel")

    /** Capabilities diferenciadas del gestor (ADR-005, Delta P9). */
    val ALLOWED_CAPABILITIES = setOf("observar", "bloquear", "modificar")

    /**
     * Permisos declarables v1: solo operaciones de datos del broker; las
     * operaciones de pestana (tab.*) nunca se conceden a un modulo.
     */
    val ALLOWED_PERMISSIONS = setOf(
        BrokerOps.STORAGE_READ, BrokerOps.NETWORK_FETCH, NETWORK_RESPONSE, DOCUMENT_STYLES, DOCUMENT_SCRIPT,
        DOCUMENT_COSMETIC
    )

    fun validId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ID_CHARS) return false
        if (id[0] == '.' || id[0] == '-') return false
        val ultimo = id[id.length - 1]
        if (ultimo == '.' || ultimo == '-') return false
        for (c in id) {
            val okChar = (c in 'a'..'z') || (c in '0'..'9') || c == '.' || c == '-'
            if (!okChar) return false
        }
        return true
    }

    fun validEntry(entry: String): Boolean {
        if (entry.isEmpty() || entry.length > MAX_ENTRY_CHARS) return false
        if (entry.startsWith("/")) return false
        if (entry.contains("..")) return false
        for (c in entry) {
            val okChar = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '.' || c == '-' || c == '_' || c == '/'
            if (!okChar) return false
        }
        return true
    }
}

object ModuleParser {
    /** Parseo y validacion del manifiesto; el mensaje nunca repite el crudo. */
    fun parse(text: String): Outcome<ModuleManifest> {
        if (text.length > ModuleSchema.MANIFEST_MAX_CHARS) {
            return invalid("manifiesto demasiado grande")
        }
        val values = LinkedHashMap<String, String>()
        var utiles = 0
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return invalid("linea sin clave = valor")
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return invalid("clave o valor vacio")
            if (!ModuleSchema.KEYS.contains(key)) return invalid("clave desconocida: " + key)
            if (values.containsKey(key)) return invalid("clave repetida: " + key)
            values[key] = value
            utiles += 1
        }
        if (utiles == 0) return invalid("manifiesto vacio")
        val id = values["id"]
        if (id == null) return invalid("falta la clave id")
        if (!ModuleSchema.validId(id)) return invalid("id no valido")
        val versionRaw = values["version"]
        if (versionRaw == null) return invalid("falta la clave version")
        val version = versionRaw.toIntOrNull()
        if (version == null) return invalid("version no valida")
        if (version > ModuleSchema.CURRENT_VERSION) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.UNSUPPORTED, "manifiesto de una version futura")
            )
        }
        val entry = values["entry"]
        if (entry == null) return invalid("falta la clave entry")
        if (!ModuleSchema.validEntry(entry)) return invalid("entry no valida")
        val permissions = LinkedHashSet<String>()
        val permsRaw = values["permissions"]
        if (permsRaw != null) {
            for (token in permsRaw.split(',')) {
                val p = token.trim()
                if (p.isEmpty()) return invalid("permiso vacio")
                if (permissions.contains(p)) return invalid("permiso repetido: " + p)
                if (!ModuleSchema.ALLOWED_PERMISSIONS.contains(p)) {
                    return invalid("permiso no permitido: " + p)
                }
                permissions.add(p)
            }
        }
        val name = values["name"] ?: ""
        if (name.length > ModuleSchema.MAX_NAME_CHARS) return invalid("name demasiado largo")
        val description = values["description"] ?: ""
        if (description.length > ModuleSchema.MAX_DESCRIPTION_CHARS) return invalid("description demasiado larga")
        var apiVersion = 1
        val apiRaw = values["api-version"]
        if (apiRaw != null) {
            val api = apiRaw.toIntOrNull() ?: return invalid("api-version no valida")
            if (api != 1 && api != 2) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.UNSUPPORTED, "api-version no soportada")
                )
            }
            apiVersion = api
        }
        val categories = LinkedHashSet<String>()
        val catRaw = values["category"]
        if (catRaw != null) {
            for (token in catRaw.split(',')) {
                val c = token.trim()
                if (c.isEmpty()) return invalid("categoria vacia")
                if (categories.contains(c)) return invalid("categoria repetida: " + c)
                if (!ModuleSchema.ALLOWED_CATEGORIES.contains(c)) {
                    return invalid("categoria no permitida: " + c)
                }
                categories.add(c)
            }
        }
        val capabilities = LinkedHashSet<String>()
        val capRaw = values["capabilities"]
        if (capRaw != null) {
            for (token in capRaw.split(',')) {
                val c = token.trim()
                if (c.isEmpty()) return invalid("capability vacia")
                if (capabilities.contains(c)) return invalid("capability repetida: " + c)
                if (!ModuleSchema.ALLOWED_CAPABILITIES.contains(c)) {
                    return invalid("capability no permitida: " + c)
                }
                capabilities.add(c)
            }
        }
        if (capabilities.contains("bloquear") && !permissions.contains(BrokerOps.NETWORK_FETCH)) {
            return invalid("bloquear exige el permiso network.fetch")
        }
        val sustitutos = ArrayList<String>()
        val susRaw = values["sustitutos"]
        if (susRaw != null) {
            for (token in susRaw.split(',')) {
                val s = token.trim()
                if (s.isEmpty()) return invalid("sustituto vacio")
                if (sustitutos.contains(s)) return invalid("sustituto repetido: " + s)
                if (!UiModelModulos.idValido(s)) return invalid("sustituto no valido: " + s)
                sustitutos.add(s)
            }
            if (sustitutos.size > ModuleSchema.MAX_SUSTITUTOS) {
                return invalid("demasiados sustitutos")
            }
            if (!capabilities.contains("modificar")) {
                return invalid("sustitutos exige la capability modificar")
            }
            if (!permissions.contains(ModuleSchema.NETWORK_RESPONSE)) {
                return invalid("sustitutos exige el permiso network.response")
            }
        }
        val estilos = values["estilos"]
        if (estilos != null) {
            if (!UiModelModulos.idValido(estilos)) return invalid("estilos no valido: " + estilos)
            if (!capabilities.contains("modificar")) {
                return invalid("estilos exige la capability modificar")
            }
            if (!permissions.contains(ModuleSchema.DOCUMENT_STYLES)) {
                return invalid("estilos exige el permiso document.styles")
            }
        }
        val script = values["script"]
        if (script != null) {
            if (!UiModelModulos.idValido(script)) return invalid("script no valido: " + script)
            if (!capabilities.contains("modificar")) {
                return invalid("script exige la capability modificar")
            }
            if (!permissions.contains(ModuleSchema.DOCUMENT_SCRIPT)) {
                return invalid("script exige el permiso document.script")
            }
        }
        val cosmeticos = values["cosmeticos"]
        if (cosmeticos != null) {
            if (!UiModelModulos.idValido(cosmeticos)) return invalid("cosmeticos no validos: " + cosmeticos)
            if (!capabilities.contains("modificar")) {
                return invalid("cosmeticos exigen la capability modificar")
            }
            if (!permissions.contains(ModuleSchema.DOCUMENT_COSMETIC)) {
                return invalid("cosmeticos exigen el permiso document.cosmetic")
            }
        }
        val authorKey = values["author-key"] ?: ""
        if (authorKey.isNotEmpty() && !spkiValida(authorKey)) {
            return invalid("author-key no es un SPKI base64 valido")
        }
        val nextKey = values["next-key"] ?: ""
        if (nextKey.isNotEmpty() && !spkiValida(nextKey)) {
            return invalid("next-key no es un SPKI base64 valido")
        }
        if (nextKey.isNotEmpty() && authorKey.isEmpty()) {
            return invalid("next-key exige author-key")
        }
        if (nextKey.isNotEmpty() && nextKey == authorKey) {
            return invalid("next-key debe diferir de author-key")
        }
        return Outcome.success(
            ModuleManifest(
                id, version, entry, permissions, name, description, apiVersion,
                categories, capabilities, authorKey, nextKey, sustitutos, estilos, script, cosmeticos
            )
        )
    }

    /** SPKI base64 plausible (T095): decodifica a un DER SEQUENCE de
     *  tamano acotado; el parseo criptografico lo hace Niamod. */
    private fun spkiValida(s: String): Boolean {
        if (s.isEmpty() || s.length > 1024) return false
        return try {
            val dec = java.util.Base64.getDecoder().decode(s)
            dec.isNotEmpty() && dec.size <= 512 && dec[0].toInt() == 0x30
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun invalid(safe: String): Outcome<ModuleManifest> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, safe))
}

/** Modulo instalado: el manifiesto validado y su contexto en el broker. */
class InstalledModule internal constructor(
    val manifest: ModuleManifest,
    internal val renderer: Renderer?
) {
    val id: String get() = manifest.id
}

class ModuleRegistry(private val broker: Broker? = null) {
    private val modules = LinkedHashMap<String, InstalledModule>()

    /** Instala un paquete declarativo: valida TODO y solo entonces registra. */
    fun install(manifestText: String): Outcome<InstalledModule> {
        val manifest = when (val parsed = ModuleParser.parse(manifestText)) {
            is Outcome.Failure -> return Outcome.failure(parsed.error)
            is Outcome.Success -> parsed.value
        }
        if (modules.containsKey(manifest.id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo ya instalado: " + manifest.id)
            )
        }
        val renderer = if (broker == null || manifest.permissions.isEmpty()) {
            null
        } else {
            broker.attachRenderer("module-" + manifest.id, manifest.permissions)
        }
        val installed = InstalledModule(manifest, renderer)
        modules[manifest.id] = installed
        return Outcome.success(installed)
    }

    /** Retira el modulo y cierra su contexto del broker (si lo tenia). */
    fun uninstall(id: String): Outcome<Unit> {
        val installed = modules[id]
        if (installed == null) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "modulo no instalado: " + id)
            )
        }
        modules.remove(id)
        val renderer = installed.renderer
        if (renderer != null) renderer.close()
        return Outcome.success(Unit)
    }

    fun isInstalled(id: String): Boolean = modules.containsKey(id)

    fun installedIds(): List<String> = ArrayList(modules.keys)

    fun manifestOf(id: String): ModuleManifest? {
        val installed = modules[id]
        return installed?.manifest
    }
}
