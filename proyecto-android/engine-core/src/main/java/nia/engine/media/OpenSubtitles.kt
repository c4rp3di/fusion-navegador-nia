package nia.engine.media

import nia.contracts.CancellationToken
import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome
import nia.engine.net.FetchManager
import nia.engine.net.FetchResponse

/**
 * T050 v1 (R0116, RES-0061, ADR-017): proveedor OpenSubtitles de
 * nucleo puro JVM. OpenSubtitlesClient habla con la API REST v1
 * (api.opensubtitles.com/api/v1) SOBRE el FetchManager de T011: el
 * transporte es inyectable (HttpTransport en el dispositivo, un stub
 * en CI), la cancelacion es cooperativa y los errores se mapean a
 * los codigos cerrados de :contracts.
 *
 * Cuenta configurable (ADR-017): apiKey SIEMPRE; usuario y clave son
 * OPCIONALES y habilitan el login POST /login que entrega el token
 * Bearer de la sesion (perezoso, UNA sola vez por cliente). Las
 * credenciales NUNCA salen del proceso: no se persisten, no se
 * loguean y los safeMessage de los errores NO las contienen (tests
 * incluidos).
 *
 * JSON minimo propio (sin dependencias nuevas): entiende objetos,
 * arrays, strings con escapes (incluido el unicode de 4 hex),
 * numeros, booleanos y null; una respuesta ilegible es un fallo
 * PARSE, no una excepcion suelta.
 *
 * Exclusiones v1: busqueda por nombre (sin moviehash de video), sin
 * paginacion, sin subidas, sin persistencia de la sesion (el token
 * vive en el cliente) y sin UI de ajustes de la cuenta (T054). Los
 * estados del proveedor se mapean: 401 y 403 DENIED, 429 QUOTA y el
 * resto no-2xx NETWORK.
 */

/** Cuenta del proveedor: apiKey obligatoria, usuario y clave opcional. */
data class OpenSubtitlesConfig(
    val apiKey: String,
    val username: String? = null,
    val password: String? = null,
    val baseUrl: String = "https://api.opensubtitles.com/api/v1",
    val userAgent: String = "Navegador NIA v1"
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey obligatoria" }
    }

    /** Cuenta con usuario y clave: habilita el login Bearer. */
    val conCuenta: Boolean
        get() = username != null && password != null
}

/** Un resultado de busqueda: un archivo de subtitulos del proveedor. */
data class OpenSubtitlesResult(
    val id: String,
    val nombre: String,
    val idioma: String,
    val fileId: Long
)

/** Pista descargada: nombre de archivo y contenido ya decodificado. */
data class OpenSubtitlesPista(
    val nombre: String,
    val contenido: String
)

class OpenSubtitlesClient(
    private val config: OpenSubtitlesConfig,
    private val http: FetchManager
) {

    private var bearer: String? = null

    /** Sesion iniciada (login con cuenta hecho). */
    fun conectado(): Boolean = bearer != null

    /** Busca subtitulos por consulta e idiomas (codigos ISO; vacio = todos). */
    fun buscar(
        consulta: String,
        idiomas: List<String> = emptyList(),
        token: CancellationToken? = null
    ): Outcome<List<OpenSubtitlesResult>> {
        if (consulta.isBlank()) {
            return Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, "consulta vacia"))
        }
        val cuenta = asegurarCuenta(token)
        if (cuenta != null) return cuenta
        val url = StringBuilder(config.baseUrl)
            .append("/subtitles?query=")
            .append(codificar(consulta))
        if (idiomas.isNotEmpty()) {
            url.append("&languages=")
            url.append(idiomas.joinToString(",") { codificar(it) })
        }
        val request = http.newRequest("GET", url.toString(), cabeceras())
        return when (val r = http.fetch(request, token)) {
            is Outcome.Failure -> r
            is Outcome.Success -> analizarBusqueda(r.value)
        }
    }

    /** Descarga el archivo (POST /download y GET del link firmado). */
    fun descargar(fileId: Long, token: CancellationToken? = null): Outcome<OpenSubtitlesPista> {
        val cuenta = asegurarCuenta(token)
        if (cuenta != null) return cuenta
        val cuerpo = "{\"file_id\":" + fileId + "}"
        val cab = cabeceras() + mapOf("Content-Type" to "application/json")
        val request = http.newRequest(
            "POST",
            config.baseUrl + "/download",
            cab,
            cuerpo.toByteArray(Charsets.UTF_8)
        )
        return when (val r = http.fetch(request, token)) {
            is Outcome.Failure -> r
            is Outcome.Success -> analizarDescarga(r.value, token)
        }
    }

    /** Login perezoso UNA vez si hay cuenta configurada; null = seguir. */
    private fun asegurarCuenta(token: CancellationToken?): Outcome<Nothing>? {
        if (!config.conCuenta || bearer != null) return null
        val usuario = config.username
        val clave = config.password
        if (usuario == null || clave == null) return null
        val cuerpo = "{\"username\":\"" + escaparJson(usuario) +
            "\",\"password\":\"" + escaparJson(clave) + "\"}"
        val cab = cabeceras() + mapOf("Content-Type" to "application/json")
        val request = http.newRequest(
            "POST",
            config.baseUrl + "/login",
            cab,
            cuerpo.toByteArray(Charsets.UTF_8)
        )
        return when (val r = http.fetch(request, token)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val fallo = mapearEstado(r.value.status)
                if (fallo != null) return fallo
                val raiz = try {
                    JsonMinimo.parse(String(r.value.body, Charsets.UTF_8))
                } catch (_: JsonInvalido) {
                    return Outcome.failure(ErrorInfo(ErrorCode.PARSE, "respuesta ilegible"))
                }
                val t = JsonMinimo.texto(JsonMinimo.campo(raiz, "token"))
                if (t.isNullOrBlank()) {
                    Outcome.failure(ErrorInfo(ErrorCode.PARSE, "sesion sin token"))
                } else {
                    bearer = t
                    null
                }
            }
        }
    }

    /** Estado HTTP no-2xx a error cerrado; null = respuesta usable. */
    private fun mapearEstado(status: Int): Outcome<Nothing>? = when (status) {
        in 200..299 -> null
        401, 403 -> Outcome.failure(ErrorInfo(ErrorCode.DENIED, "credenciales rechazadas"))
        429 -> Outcome.failure(ErrorInfo(ErrorCode.QUOTA, "limite de peticiones"))
        else -> Outcome.failure(ErrorInfo(ErrorCode.NETWORK, "proveedor no disponible"))
    }

    /** Cabeceras comunes; Authorization solo con sesion iniciada. */
    private fun cabeceras(): Map<String, String> {
        val base = LinkedHashMap<String, String>()
        base["Api-Key"] = config.apiKey
        base["User-Agent"] = config.userAgent
        base["Accept"] = "application/json"
        val b = bearer
        if (b != null) base["Authorization"] = "Bearer " + b
        return base
    }

    private fun analizarBusqueda(respuesta: FetchResponse): Outcome<List<OpenSubtitlesResult>> {
        val fallo = mapearEstado(respuesta.status)
        if (fallo != null) return fallo
        val raiz = try {
            JsonMinimo.parse(String(respuesta.body, Charsets.UTF_8))
        } catch (_: JsonInvalido) {
            return Outcome.failure(ErrorInfo(ErrorCode.PARSE, "respuesta ilegible"))
        }
        val datos = JsonMinimo.lista(JsonMinimo.campo(raiz, "data"))
        val resultados = ArrayList<OpenSubtitlesResult>()
        for (item in datos) {
            val atributos = JsonMinimo.campo(item, "attributes") ?: continue
            val id = JsonMinimo.texto(JsonMinimo.campo(item, "id")) ?: continue
            val archivos = JsonMinimo.lista(JsonMinimo.campo(atributos, "files"))
            if (archivos.isEmpty()) continue
            val fileId = JsonMinimo.entero(JsonMinimo.campo(archivos[0], "file_id")) ?: continue
            val nombre = JsonMinimo.texto(JsonMinimo.campo(atributos, "release"))
            val idioma = JsonMinimo.texto(JsonMinimo.campo(atributos, "language"))
            resultados.add(
                OpenSubtitlesResult(
                    id,
                    nombre ?: "sin release",
                    idioma ?: "?",
                    fileId
                )
            )
        }
        return Outcome.success(resultados)
    }

    private fun analizarDescarga(
        respuesta: FetchResponse,
        token: CancellationToken?
    ): Outcome<OpenSubtitlesPista> {
        val fallo = mapearEstado(respuesta.status)
        if (fallo != null) return fallo
        val raiz = try {
            JsonMinimo.parse(String(respuesta.body, Charsets.UTF_8))
        } catch (_: JsonInvalido) {
            return Outcome.failure(ErrorInfo(ErrorCode.PARSE, "respuesta ilegible"))
        }
        val link = JsonMinimo.texto(JsonMinimo.campo(raiz, "link"))
        if (link.isNullOrBlank()) {
            return Outcome.failure(ErrorInfo(ErrorCode.PARSE, "descarga sin link"))
        }
        val nombre = JsonMinimo.texto(JsonMinimo.campo(raiz, "file_name"))
        val pedido = http.newRequest(
            "GET",
            link,
            mapOf("User-Agent" to config.userAgent)
        )
        return when (val r = http.fetch(pedido, token)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val fallo2 = mapearEstado(r.value.status)
                if (fallo2 != null) return fallo2
                Outcome.success(
                    OpenSubtitlesPista(
                        nombre ?: "subtitulos.srt",
                        String(r.value.body, Charsets.UTF_8)
                    )
                )
            }
        }
    }

    /** Codificacion de query param: no reservados tal cual y %XX UTF-8. */
    private fun codificar(raw: String): String {
        val fuera = StringBuilder()
        for (c in raw) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~') {
                fuera.append(c)
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xff
                    fuera.append('%')
                    fuera.append(HEX[v / 16])
                    fuera.append(HEX[v % 16])
                }
            }
        }
        return fuera.toString()
    }

    /** Escapes minimos de un valor dentro de un string JSON. */
    private fun escaparJson(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/** Hexadecimal mayuscula para los escapes de query param. */
private val HEX = "0123456789ABCDEF"

/** JSON ilegal: el parser minimo no lo pudo leer. */
private class JsonInvalido(mensaje: String) : Exception(mensaje)

/**
 * JSON minimo propio (T050 v1): objetos, arrays, strings con escapes
 * (incluido el unicode de 4 hex), numeros, booleanos y null. Sin
 * dependencias nuevas; solo para las respuestas del proveedor.
 */
private object JsonMinimo {

    sealed class Valor
    object Nulo : Valor()
    object Verdadero : Valor()
    object Falso : Valor()
    data class Texto(val valor: String) : Valor()
    data class Numero(val valor: Double) : Valor()
    data class Arreglo(val items: List<Valor>) : Valor()
    data class Objeto(val campos: Map<String, Valor>) : Valor()

    fun parse(texto: String): Valor {
        val lector = Lector(texto)
        lector.espacios()
        val v = lector.valor()
        lector.espacios()
        if (!lector.fin()) throw JsonInvalido("sobras al final")
        return v
    }

    fun campo(v: Valor?, nombre: String): Valor? {
        if (v is Objeto) return v.campos[nombre]
        return null
    }

    fun texto(v: Valor?): String? = (v as? Texto)?.valor

    fun entero(v: Valor?): Long? {
        val n = v as? Numero ?: return null
        return n.valor.toLong()
    }

    fun lista(v: Valor?): List<Valor> {
        if (v is Arreglo) return v.items
        return emptyList()
    }

    private fun llaveAbierta(c: Char): Boolean = c == '{'
    private fun llaveCerrada(c: Char): Boolean = c == '}'
    private fun corcheteAbierto(c: Char): Boolean = c == '['
    private fun corcheteCerrado(c: Char): Boolean = c == ']'

    private class Lector(private val texto: String) {
        private var i = 0

        fun fin(): Boolean = i >= texto.length

        fun espacios() {
            while (i < texto.length && texto[i].isWhitespace()) i += 1
        }

        fun valor(): Valor {
            if (fin()) throw JsonInvalido("fin inesperado")
            val c = texto[i]
            return when {
                llaveAbierta(c) -> objeto()
                corcheteAbierto(c) -> Arreglo(items())
                c == '"' -> Texto(cadena())
                c == 't' -> literal("true", Verdadero)
                c == 'f' -> literal("false", Falso)
                c == 'n' -> literal("null", Nulo)
                c == '-' || c in '0'..'9' -> Numero(numero())
                else -> throw JsonInvalido("valor inesperado")
            }
        }

        private fun objeto(): Valor {
            i += 1
            val campos = LinkedHashMap<String, Valor>()
            espacios()
            if (!fin() && llaveCerrada(texto[i])) {
                i += 1
                return Objeto(campos)
            }
            while (true) {
                espacios()
                if (fin() || texto[i] != '"') throw JsonInvalido("clave esperada")
                val clave = cadena()
                espacios()
                if (fin() || texto[i] != ':') throw JsonInvalido("dos puntos esperados")
                i += 1
                espacios()
                campos[clave] = valor()
                espacios()
                if (fin()) throw JsonInvalido("objeto sin cerrar")
                val c = texto[i]
                if (c == ',') {
                    i += 1
                } else if (llaveCerrada(c)) {
                    i += 1
                    return Objeto(campos)
                } else {
                    throw JsonInvalido("coma o cierre esperados")
                }
            }
        }

        private fun items(): List<Valor> {
            i += 1
            val items = ArrayList<Valor>()
            espacios()
            if (!fin() && corcheteCerrado(texto[i])) {
                i += 1
                return items
            }
            while (true) {
                items.add(valor())
                espacios()
                if (fin()) throw JsonInvalido("arreglo sin cerrar")
                val c = texto[i]
                if (c == ',') {
                    i += 1
                } else if (corcheteCerrado(c)) {
                    i += 1
                    return items
                } else {
                    throw JsonInvalido("arreglo sin cerrar")
                }
            }
        }

        private fun cadena(): String {
            i += 1
            val fuera = StringBuilder()
            while (true) {
                if (fin()) throw JsonInvalido("cadena sin cerrar")
                val c = texto[i]
                if (c == '"') {
                    i += 1
                    return fuera.toString()
                }
                if (c == '\\') {
                    i += 1
                    if (fin()) throw JsonInvalido("escape sin cerrar")
                    when (val e = texto[i]) {
                        '"' -> fuera.append('"')
                        '\\' -> fuera.append('\\')
                        '/' -> fuera.append('/')
                        'b' -> fuera.append('\b')
                        'f' -> fuera.append('\u000C')
                        'n' -> fuera.append('\n')
                        'r' -> fuera.append('\r')
                        't' -> fuera.append('\t')
                        'u' -> {
                            if (i + 4 >= texto.length) throw JsonInvalido("unicode corto")
                            val hex = texto.substring(i + 1, i + 5)
                            val punto = try {
                                hex.toInt(16)
                            } catch (_: NumberFormatException) {
                                throw JsonInvalido("unicode ilegal")
                            }
                            fuera.append(punto.toChar())
                            i += 4
                        }
                        else -> throw JsonInvalido("escape ilegal")
                    }
                    i += 1
                } else {
                    fuera.append(c)
                    i += 1
                }
            }
        }

        private fun numero(): Double {
            val inicio = i
            if (i < texto.length && texto[i] == '-') i += 1
            while (i < texto.length && (texto[i] in '0'..'9' || texto[i] == '.' ||
                    texto[i] == 'e' || texto[i] == 'E' ||
                    texto[i] == '+' || texto[i] == '-')) {
                i += 1
            }
            val crudo = texto.substring(inicio, i)
            return try {
                crudo.toDouble()
            } catch (_: NumberFormatException) {
                throw JsonInvalido("numero ilegal")
            }
        }

        private fun literal(palabra: String, v: Valor): Valor {
            if (!texto.startsWith(palabra, i)) throw JsonInvalido("literal ilegal")
            i += palabra.length
            return v
        }
    }
}
