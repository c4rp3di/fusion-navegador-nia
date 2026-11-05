package nia.engine.themes

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T031 v1 (R0133, RES-0069, ADR-018): temas declarativos con recovery
 * de tema roto. Un tema es un texto de lineas "clave = valor" (id,
 * name y los seis colores del schema cerrado) que NUNCA entra roto al
 * registro: el parse falla tipificado antes de tocar nada. claro y
 * oscuro son builtin protegidos (no se instalan sobre sus ids ni se
 * desinstalan), activate recuerda el ultimo bueno y uninstall del
 * tema activo hace el recovery del acceptance: cae al ultimo bueno
 * que siga instalado y, si ya no queda, al suelo claro.
 *
 * Exclusiones v1: paneles y comandos del titulo de la tarea quedan
 * para v2, sin persistencia del tema activo (llega con la config de
 * T007 al cablear el renderer) y sin UI de temas.
 */

/** Tema inmutable instalado en el registro. */
data class Theme(val id: String, val name: String, val colors: Map<String, String>)

/** Schema cerrado de temas, como el de la config de T007. */
object ThemeSchema {
    const val MAX_NAME = 64
    val COLOR_KEYS = listOf("bg", "surface", "fg", "accent", "warn", "error")
    val RESERVED = setOf("claro", "oscuro")
    private val ID_RE = Regex("^[a-z0-9.-]{1,64}$")
    private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")

    fun idValida(id: String): Boolean = ID_RE.matches(id)

    fun colorValido(color: String): Boolean = COLOR_RE.matches(color)
}

/** Parser de temas: lineas "clave = valor", comentarios # y vacias libres. */
object ThemeParser {

    /** Parsea y valida un tema; el fallo es INVALID_INPUT tipificado. */
    fun parse(texto: String): Outcome<Theme> {
        val valores = LinkedHashMap<String, String>()
        var lineasUtiles = 0
        for (lineaCruda in texto.lines()) {
            val linea = lineaCruda.trim()
            if (linea.isEmpty() || linea.startsWith("#")) continue
            val eq = linea.indexOf('=')
            if (eq <= 0) {
                return fallo("linea sin clave = valor: " + sanitiza(linea))
            }
            val clave = linea.substring(0, eq).trim()
            val valor = linea.substring(eq + 1).trim()
            if (clave.isEmpty() || valor.isEmpty()) {
                return fallo("clave o valor vacio")
            }
            if (valores.containsKey(clave)) {
                return fallo("clave repetida: " + clave)
            }
            valores[clave] = valor
            lineasUtiles += 1
        }
        if (lineasUtiles == 0) return fallo("tema vacio")
        val id = valores["id"] ?: return fallo("falta la clave id")
        if (!ThemeSchema.idValida(id)) return fallo("id no valida: " + sanitiza(id))
        val name = valores["name"] ?: return fallo("falta la clave name")
        if (name.length > ThemeSchema.MAX_NAME) return fallo("name no valida: " + sanitiza(name))
        for (clave in valores.keys) {
            if (clave != "id" && clave != "name" && !ThemeSchema.COLOR_KEYS.contains(clave)) {
                return fallo("clave desconocida: " + clave)
            }
        }
        val colores = LinkedHashMap<String, String>()
        for (clave in ThemeSchema.COLOR_KEYS) {
            val valor = valores[clave] ?: return fallo("falta el color: " + clave)
            if (!ThemeSchema.colorValido(valor)) return fallo("color invalido: " + clave)
            colores[clave] = valor
        }
        return Outcome.success(Theme(id, name, colores))
    }

    private fun fallo(mensaje: String): Outcome<Theme> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, mensaje))

    /** El safeMessage nunca repite contenido crudo del tema. */
    private fun sanitiza(texto: String?): String = (texto ?: "").take(20)
}

/**
 * Registro de temas con el activo y su recovery: un tema roto nunca
 * entra (el parse falla antes) y la unica rotura posible en vivo -
 * la desaparicion del activo - cae al ultimo bueno con claro de suelo.
 */
class ThemeManager {

    private val temas = LinkedHashMap<String, Theme>()
    private var activo: Theme = CLARO
    private var lastGood: Theme = CLARO

    init {
        temas[CLARO.id] = CLARO
        temas[OSCURO.id] = OSCURO
    }

    /** Tema activo (claro por defecto). */
    val active: Theme get() = activo

    /** Ids instalados en orden: builtin primero, customs despues. */
    fun list(): List<String> = ArrayList(temas.keys)

    /** Tema instalado por id o null si no esta. */
    fun themeOf(id: String): Theme? = temas[id]

    /**
     * Instala un tema declarativo: el id reservado de un builtin se
     * rechaza y un duplicado REEMPLAZA la definicion (en caliente si
     * es la del activo). Un tema roto nunca entra: el fallo del parse
     * se propaga sin tocar el registro.
     */
    fun install(texto: String): Outcome<Theme> {
        val parseado = ThemeParser.parse(texto)
        if (parseado is Outcome.Failure) return parseado
        val tema = (parseado as Outcome.Success).value
        if (ThemeSchema.RESERVED.contains(tema.id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "tema reservado: " + tema.id)
            )
        }
        temas[tema.id] = tema
        if (activo.id == tema.id) activo = tema
        return Outcome.success(tema)
    }

    /**
     * Desinstala un tema: los builtin estan protegidos y el activo
     * hace RECOVERY al ultimo bueno que siga instalado (claro como
     * suelo). El success lleva el tema activo tras la operacion.
     */
    fun uninstall(id: String): Outcome<Theme> {
        val tema = temas[id]
        if (tema == null) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "tema no instalado: " + id)
            )
        }
        if (ThemeSchema.RESERVED.contains(id)) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "tema reservado: " + id)
            )
        }
        temas.remove(id)
        if (activo.id == id) {
            activo = if (temas.containsKey(lastGood.id)) lastGood else CLARO
            if (lastGood.id == id) lastGood = CLARO
        }
        return Outcome.success(activo)
    }

    /** Activa un tema instalado y recuerda el anterior como ultimo bueno. */
    fun activate(id: String): Outcome<Theme> {
        val tema = temas[id]
        if (tema == null) {
            return Outcome.failure(
                ErrorInfo(ErrorCode.INVALID_INPUT, "tema no instalado: " + id)
            )
        }
        lastGood = activo
        activo = tema
        return Outcome.success(tema)
    }

    /** Suelo duro de recovery: vuelve a claro y lo endurece. */
    fun recover(): Theme {
        activo = CLARO
        lastGood = CLARO
        return activo
    }

    companion object {
        /** Builtin claro: el suelo del recovery. */
        val CLARO: Theme = Theme(
            "claro", "Claro",
            linkedMapOf(
                "bg" to "#ffffff", "surface" to "#f5f5f5", "fg" to "#1a1a1a",
                "accent" to "#0066cc", "warn" to "#b45309", "error" to "#b91c1c"
            )
        )

        /** Builtin oscuro. */
        val OSCURO: Theme = Theme(
            "oscuro", "Oscuro",
            linkedMapOf(
                "bg" to "#0d1117", "surface" to "#161b22", "fg" to "#e6edf3",
                "accent" to "#2f81f7", "warn" to "#d29922", "error" to "#f85149"
            )
        )
    }
}
