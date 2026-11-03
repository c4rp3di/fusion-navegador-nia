package nia.engine.config

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T007 v1 (R0036, RES-0023): configuracion versionada con el flujo del
 * maestro: validate -> migrate -> stage -> apply -> lastKnownGood. Una
 * config invalida NO rompe nada: la ultima buena sobrevive y
 * [NiaConfigStore.recover] devuelve valores esenciales sin tema.
 *
 * Formato: lineas "clave = valor", comentarios con # y lineas vacias
 * libres. La clave version es obligatoria y el export es canonico
 * (version primero, resto ordenado) con roundtrip exacto en load.
 *
 * Limitaciones v1: un solo perfil, sin watch de archivo, sin secretos en
 * el schema (las claves v1/v2 no almacenan credenciales) y sin UI.
 */

data class NiaConfig(val version: Int, val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
}

/** Claves del schema v2 y sus defaults. */
object ConfigKeys {
    const val VERSION = "version"
    const val THEME = "theme"
    const val DENSITY = "density"
    const val FONT_SCALE = "fontScale"
    const val HOMEPAGE = "homepage"
    const val JS_ENABLED = "javascript.enabled"
    const val BLOCK_MIXED = "network.blockMixed"

    fun defaults(): Map<String, String> = linkedMapOf(
        VERSION to ConfigSchema.CURRENT_VERSION.toString(),
        THEME to "system",
        DENSITY to "normal",
        FONT_SCALE to "100",
        HOMEPAGE to "about:blank",
        JS_ENABLED to "true",
        BLOCK_MIXED to "true"
    )
}

object ConfigSchema {
    const val CURRENT_VERSION = 2

    val V2_KEYS = setOf(
        ConfigKeys.VERSION, ConfigKeys.THEME, ConfigKeys.DENSITY,
        ConfigKeys.FONT_SCALE, ConfigKeys.HOMEPAGE,
        ConfigKeys.JS_ENABLED, ConfigKeys.BLOCK_MIXED
    )

    /** Validacion de una clave v2 (la version se valida aparte). */
    fun validateValue(key: String, value: String): Boolean {
        return when (key) {
            ConfigKeys.THEME -> value == "light" || value == "dark" || value == "system"
            ConfigKeys.DENSITY -> value == "compact" || value == "normal" || value == "relaxed"
            ConfigKeys.FONT_SCALE -> {
                val n = value.toIntOrNull() ?: return false
                n >= 50 && n <= 300
            }
            ConfigKeys.HOMEPAGE -> value.isNotBlank()
            ConfigKeys.JS_ENABLED, ConfigKeys.BLOCK_MIXED -> value == "true" || value == "false"
            else -> false
        }
    }
}

/** Config cruda recien parseada, sin validar. */
data class RawConfig(val version: Int, val values: Map<String, String>)

object ConfigParser {
    /** Parsea "clave = valor" por lineas; la clave version es obligatoria. */
    fun parse(text: String): Outcome<RawConfig> {
        val values = LinkedHashMap<String, String>()
        var lineasUtiles = 0
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "linea sin clave = valor: " + sanitiza(line))
                )
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key.isEmpty() || value.isEmpty()) {
                return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "clave o valor vacio"))
            }
            if (values.containsKey(key)) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "clave repetida: " + key)
                )
            }
            values[key] = value
            lineasUtiles += 1
        }
        if (lineasUtiles == 0) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "config vacia"))
        }
        val versionRaw = values[ConfigKeys.VERSION]
        if (versionRaw == null) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "falta la clave version"))
        }
        val version = versionRaw.toIntOrNull()
        if (version == null || version < 1) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "version no valida: " + sanitiza(versionRaw))
            )
        }
        return Outcome.success(RawConfig(version, values))
    }

    /** El safeMessage nunca repite contenido del archivo (podria llevar secretos). */
    private fun sanitiza(line: String?): String = (line ?: "").take(20)
}

object ConfigMigrations {
    /** Cadena de migraciones: v1 -> v2 (darkMode/fontSize -> theme/fontScale). */
    fun migrate(raw: RawConfig): Outcome<RawConfig> {
        var current = raw
        while (current.version < ConfigSchema.CURRENT_VERSION) {
            val next = when (current.version) {
                1 -> v1ToV2(current)
                else -> return Outcome.failure(
                    ErrorInfo(
                        ErrorCode.UNSUPPORTED,
                        "version de config no migrable: " + current.version
                    )
                )
            }
            when (next) {
                is Outcome.Success -> current = next.value
                is Outcome.Failure -> return Outcome.failure(next.error)
            }
        }
        if (current.version > ConfigSchema.CURRENT_VERSION) {
            return Outcome.failure(
                ErrorInfo(
                    ErrorCode.UNSUPPORTED,
                    "config de una version futura (maximo " + ConfigSchema.CURRENT_VERSION + ")"
                )
            )
        }
        return Outcome.success(current)
    }

    private fun v1ToV2(raw: RawConfig): Outcome<RawConfig> {
        val out = LinkedHashMap<String, String>()
        out[ConfigKeys.VERSION] = "2"
        val darkMode = raw.values["darkMode"]
        if (darkMode != null) {
            if (darkMode != "true" && darkMode != "false") {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "darkMode debe ser true o false")
                )
            }
            out[ConfigKeys.THEME] = if (darkMode == "true") "dark" else "light"
        }
        val fontSize = raw.values["fontSize"]
        if (fontSize != null) {
            val n = fontSize.toIntOrNull()
            if (n == null) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "fontSize debe ser un entero")
                )
            }
            out[ConfigKeys.FONT_SCALE] = n.toString()
        }
        for ((k, v) in raw.values) {
            if (k == ConfigKeys.VERSION || k == "darkMode" || k == "fontSize") continue
            out[k] = v
        }
        return Outcome.success(RawConfig(2, out))
    }
}

object ConfigValidator {
    /** validate del flujo: claves y valores del schema vigente. */
    fun validate(raw: RawConfig): Outcome<NiaConfig> {
        for ((key, value) in raw.values) {
            if (key == ConfigKeys.VERSION) continue
            if (!ConfigSchema.V2_KEYS.contains(key)) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "clave desconocida: " + key)
                )
            }
            if (!ConfigSchema.validateValue(key, value)) {
                return Outcome.failure(
                    ErrorInfo(ErrorCode.INVALID_INPUT, "valor invalido para " + key)
                )
            }
        }
        return Outcome.success(NiaConfig(raw.version, LinkedHashMap(raw.values)))
    }
}

/** Preview sin aplicar: la config solo comete con NiaConfigStore.apply. */
class StagedConfig internal constructor(val config: NiaConfig) {
    internal var consumed = false
}

class NiaConfigStore(
    private var lastGood: NiaConfig =
        NiaConfig(ConfigSchema.CURRENT_VERSION, ConfigKeys.defaults())
) {
    val lastKnownGood: NiaConfig get() = lastGood

    /** validate -> migrate -> validate; NO aplica. Falla sin tocar lastKnownGood. */
    fun load(text: String): Outcome<NiaConfig> {
        val resultado: Outcome<NiaConfig> = when (val parsed = ConfigParser.parse(text)) {
            is Outcome.Failure -> Outcome.failure(parsed.error)
            is Outcome.Success -> when (val migrado = ConfigMigrations.migrate(parsed.value)) {
                is Outcome.Failure -> Outcome.failure(migrado.error)
                is Outcome.Success -> ConfigValidator.validate(migrado.value)
            }
        }
        return resultado
    }

    /** stage del flujo: preview sin efectos hasta apply. */
    fun stage(text: String): Outcome<StagedConfig> = when (val cfg = load(text)) {
        is Outcome.Failure -> Outcome.failure(cfg.error)
        is Outcome.Success -> Outcome.success(StagedConfig(cfg.value))
    }

    /** apply del flujo: comete la staged como nueva lastKnownGood (una sola vez). */
    fun apply(staged: StagedConfig): Outcome<NiaConfig> {
        if (staged.consumed) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "la staged ya fue aplicada o descartada")
            )
        }
        staged.consumed = true
        lastGood = staged.config
        return Outcome.success(lastGood)
    }

    /** Recovery sin modulos/tema: esenciales con el tema a su valor por defecto. */
    fun recover(): NiaConfig = NiaConfig(ConfigSchema.CURRENT_VERSION, ConfigKeys.defaults())

    /** Export canonico: version primero y resto de claves ordenadas. */
    fun export(config: NiaConfig = lastGood): String {
        val sb = StringBuilder()
        sb.append("# config NIA v" + config.version + "\n")
        sb.append(ConfigKeys.VERSION + " = " + config.version + "\n")
        val claves = config.values.keys.filter { it != ConfigKeys.VERSION }.sorted()
        for (k in claves) {
            sb.append(k + " = " + (config.values[k] ?: "") + "\n")
        }
        return sb.toString()
    }
}
