package nia.engine.modules

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * C11 v2 (R0260, RES-0116, E4 1/2.5): registro DECLARATIVO de
 * ajustes, acciones y paneles del modulo, consumido por el HOST
 * desde los archivos .json de ui-model del paquete .niamod (T095) contra el gestor
 * existente (T094). El shell tiene catalogo; este incremento define
 * el registro que ese catalogo consume.
 *
 * JSON minimo propio del host (mismo repertorio que el de T050 en
 * media, sin dependencias nuevas): objetos, arreglos, cadenas con
 * escapes (incluido el unicode de 4 hex), numeros, booleanos y
 * null. La validacion es TODO-O-NADA y FAIL-CLOSED (ADR-005): un
 * ui-model ilegible, una seccion desconocida, un tipo mal, un id
 * invalido o duplicado, o un elemento fuera de limites rechazan el
 * PAQUETE ENTERO en la instalacion (un paquete no entra a medias).
 *
 * Schema uimodel/1: la raiz es un objeto con secciones OPCIONALES
 * "ajustes", "acciones" y "paneles" (arreglos de objetos). Un
 * ajuste declara id, titulo, tipo "bool" EXACTO y defecto booleano;
 * una accion o panel declara id y titulo. Las claves de cada objeto
 * son EXACTAS: una clave extra se rechaza. Los titulos se sanean
 * (sin tab ni saltos de linea: una linea del detalle JAMAS se
 * rompe) y se recortan a MAX_TITULO.
 *
 * v2 es DECLARATIVO del lado HOST: el entry NO puede leer ni
 * escribir estos ajustes en api-version 1 (E4; llegara cuando
 * reabra T030); los valores los persiste el host por perfil via
 * C9 (clave "uim.<id>") y el consumo por el hook es un incremento
 * futuro. Las acciones y paneles se registran como catalogo: su
 * EJECUCION necesita la API del modulo (T030) y su superficie llega
 * con los paneles.
 */
data class AjusteDeclarado(
    val id: String,
    val titulo: String,
    val defecto: Boolean
)

data class AccionDeclarada(
    val id: String,
    val titulo: String
)

data class PanelDeclarado(
    val id: String,
    val titulo: String
)

/** Registro ui-model declarado por un paquete (uimodel/1). */
data class UiModelDeclarado(
    val ajustes: List<AjusteDeclarado>,
    val acciones: List<AccionDeclarada>,
    val paneles: List<PanelDeclarado>
) {
    val vacio: Boolean
        get() = ajustes.isEmpty() && acciones.isEmpty() && paneles.isEmpty()
}

object UiModelModulos {

    /** Tope de archivos .json de ui-model por paquete (Niamod lo aplica). */
    const val MAX_ARCHIVOS_UI = 8

    /** Tope de UN archivo ui-model en caracteres (Niamod lo aplica). */
    const val MAX_JSON_CHARS = 65536

    /** Tope de ajustes declarados (por archivo y al fusionar). */
    const val MAX_AJUSTES = 16

    /** Tope de acciones declaradas (por archivo y al fusionar). */
    const val MAX_ACCIONES = 8

    /** Tope de paneles declarados (por archivo y al fusionar). */
    const val MAX_PANELES = 8

    const val MAX_ID = 64
    const val MAX_TITULO = 64

    /**
     * Parsea y valida un archivo .json de ui-model (todo-o-nada);
     * falla con INVALID_INPUT sin estado a medias.
     */
    fun parse(texto: String): Outcome<UiModelDeclarado> {
        if (texto.length > MAX_JSON_CHARS) {
            return invalid("ui-model demasiado grande")
        }
        val raiz = try {
            Json(texto).raiz()
        } catch (mal: JsonIlegible) {
            return invalid("ui-model ilegible: " + (mal.message ?: "json roto"))
        }
        if (raiz !is Map<*, *>) return invalid("ui-model sin objeto raiz")
        for (k in raiz.keys) {
            if (k != "ajustes" && k != "acciones" && k != "paneles") {
                return invalid("seccion desconocida en ui-model: " + k)
            }
        }
        val ajustes = ArrayList<AjusteDeclarado>()
        val acciones = ArrayList<AccionDeclarada>()
        val paneles = ArrayList<PanelDeclarado>()
        val idsAjustes = HashSet<String>()
        val idsAcciones = HashSet<String>()
        val idsPaneles = HashSet<String>()
        val secAjustes = raiz["ajustes"]
        if (secAjustes != null) {
            if (secAjustes !is List<*>) return invalid("seccion ajustes sin arreglo")
            if (secAjustes.size > MAX_AJUSTES) {
                return invalid("demasiados ajustes declarados")
            }
            for (elem in secAjustes) {
                when (val r = ajusteDe(elem, idsAjustes)) {
                    is Outcome.Failure -> return Outcome.failure(r.error)
                    is Outcome.Success -> ajustes.add(r.value as AjusteDeclarado)
                }
            }
        }
        val secAcciones = raiz["acciones"]
        if (secAcciones != null) {
            if (secAcciones !is List<*>) return invalid("seccion acciones sin arreglo")
            if (secAcciones.size > MAX_ACCIONES) {
                return invalid("demasiadas acciones declaradas")
            }
            for (elem in secAcciones) {
                when (val r = tituladoDe(elem, idsAcciones, "accion")) {
                    is Outcome.Failure -> return Outcome.failure(r.error)
                    is Outcome.Success -> {
                        val v = r.value as Pair<*, *>
                        acciones.add(AccionDeclarada(v.first as String, v.second as String))
                    }
                }
            }
        }
        val secPaneles = raiz["paneles"]
        if (secPaneles != null) {
            if (secPaneles !is List<*>) return invalid("seccion paneles sin arreglo")
            if (secPaneles.size > MAX_PANELES) {
                return invalid("demasiados paneles declarados")
            }
            for (elem in secPaneles) {
                when (val r = tituladoDe(elem, idsPaneles, "panel")) {
                    is Outcome.Failure -> return Outcome.failure(r.error)
                    is Outcome.Success -> {
                        val v = r.value as Pair<*, *>
                        paneles.add(PanelDeclarado(v.first as String, v.second as String))
                    }
                }
            }
        }
        return Outcome.success(UiModelDeclarado(ajustes, acciones, paneles))
    }

    /**
     * Fusiona los archivos ui-model de UN paquete (orden determinista
     * del llamador) rechazando ids duplicados ENTRE archivos y los
     * limites globales: la validacion por archivo no los ve.
     */
    fun fusionar(modelos: List<UiModelDeclarado>): Outcome<UiModelDeclarado> {
        val ajustes = ArrayList<AjusteDeclarado>()
        val acciones = ArrayList<AccionDeclarada>()
        val paneles = ArrayList<PanelDeclarado>()
        for (m in modelos) {
            ajustes.addAll(m.ajustes)
            acciones.addAll(m.acciones)
            paneles.addAll(m.paneles)
        }
        if (ajustes.size > MAX_AJUSTES) return invalid("demasiados ajustes declarados")
        if (acciones.size > MAX_ACCIONES) return invalid("demasiadas acciones declaradas")
        if (paneles.size > MAX_PANELES) return invalid("demasiados paneles declarados")
        if (idsDuplicados(ajustes.map { it.id })) {
            return invalid("id de ajuste duplicado entre archivos")
        }
        if (idsDuplicados(acciones.map { it.id })) {
            return invalid("id de accion duplicado entre archivos")
        }
        if (idsDuplicados(paneles.map { it.id })) {
            return invalid("id de panel duplicado entre archivos")
        }
        return Outcome.success(UiModelDeclarado(ajustes, acciones, paneles))
    }

    fun idValido(s: String): Boolean =
        s.isNotEmpty() && s.length <= MAX_ID &&
            s.all { c ->
                c == '.' || c == '-' || c == '_' ||
                    (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
            }

    private fun ajusteDe(elem: Any?, ids: MutableSet<String>): Outcome<AjusteDeclarado> {
        if (elem !is Map<*, *>) return invalid("ajuste sin objeto")
        for (k in elem.keys) {
            if (k != "id" && k != "titulo" && k != "tipo" && k != "defecto") {
                return invalid("clave desconocida en ajuste: " + k)
            }
        }
        val id = elem["id"]
        val titulo = elem["titulo"]
        val tipo = elem["tipo"]
        val defecto = elem["defecto"]
        if (id !is String || !idValido(id)) return invalid("id de ajuste invalido")
        if (titulo !is String) return invalid("titulo de ajuste invalido")
        if (tipo != "bool") return invalid("tipo de ajuste no soportado (solo bool)")
        if (defecto !is Boolean) return invalid("defecto de ajuste no booleano")
        if (!ids.add(id)) return invalid("id de ajuste duplicado: " + id)
        val saneado = saneaTitulo(titulo)
        if (saneado.isEmpty()) return invalid("titulo de ajuste vacio")
        return Outcome.success(AjusteDeclarado(id, saneado, defecto))
    }

    /** Elemento titulado (accion o panel): id y titulo EXACTOS. */
    private fun tituladoDe(
        elem: Any?,
        ids: MutableSet<String>,
        clase: String
    ): Outcome<Pair<String, String>> {
        if (elem !is Map<*, *>) return invalid(clase + " sin objeto")
        for (k in elem.keys) {
            if (k != "id" && k != "titulo") {
                return invalid("clave desconocida en " + clase + ": " + k)
            }
        }
        val id = elem["id"]
        val titulo = elem["titulo"]
        if (id !is String || !idValido(id)) return invalid("id de " + clase + " invalido")
        if (titulo !is String) return invalid("titulo de " + clase + " invalido")
        if (!ids.add(id)) return invalid("id de " + clase + " duplicado: " + id)
        val saneado = saneaTitulo(titulo)
        if (saneado.isEmpty()) return invalid("titulo de " + clase + " vacio")
        return Outcome.success(Pair(id, saneado))
    }

    private fun idsDuplicados(ids: List<String>): Boolean =
        ids.size != ids.toSet().size

    /** Sin tab ni saltos de linea, recortado a MAX_TITULO. */
    private fun saneaTitulo(t: String): String {
        val limpio = StringBuilder()
        for (c in t) {
            if (c == '\t' || c == '\n' || c == '\r') limpio.append(' ') else limpio.append(c)
        }
        return if (limpio.length > MAX_TITULO) limpio.substring(0, MAX_TITULO) else limpio.toString()
    }

    class JsonIlegible(mensaje: String) : Exception(mensaje)

    /** Parser JSON minimo del host (C11 v2; el de media es T050). */
    private class Json(private val texto: String) {
        private var i = 0

        fun raiz(): Any? {
            val v = valor()
            saltar()
            if (!fin()) throw JsonIlegible("basura tras el json")
            return v
        }

        fun valor(): Any? {
            saltar()
            if (fin()) throw JsonIlegible("json vacio")
            val c = texto[i]
            return when {
                c == '{' -> objeto()
                c == '[' -> arreglo()
                c == '"' -> cadena()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                else -> numero()
            }
        }

        private fun objeto(): LinkedHashMap<String, Any?> {
            i += 1
            val fuera = LinkedHashMap<String, Any?>()
            saltar()
            if (!fin() && texto[i] == '}') {
                i += 1
                return fuera
            }
            while (true) {
                saltar()
                if (fin() || texto[i] != '"') throw JsonIlegible("objeto sin clave")
                val clave = cadena()
                saltar()
                if (fin() || texto[i] != ':') throw JsonIlegible("clave sin dos puntos")
                i += 1
                fuera[clave] = valor()
                saltar()
                if (fin()) throw JsonIlegible("objeto sin cerrar")
                when (texto[i]) {
                    ',' -> i += 1
                    '}' -> {
                        i += 1
                        return fuera
                    }
                    else -> throw JsonIlegible("objeto sin cerrar")
                }
            }
        }

        private fun arreglo(): ArrayList<Any?> {
            i += 1
            val items = ArrayList<Any?>()
            saltar()
            if (!fin() && texto[i] == ']') {
                i += 1
                return items
            }
            while (true) {
                items.add(valor())
                saltar()
                if (fin()) throw JsonIlegible("arreglo sin cerrar")
                when (texto[i]) {
                    ',' -> i += 1
                    ']' -> {
                        i += 1
                        return items
                    }
                    else -> throw JsonIlegible("arreglo sin cerrar")
                }
            }
        }

        private fun cadena(): String {
            i += 1
            val fuera = StringBuilder()
            while (true) {
                if (fin()) throw JsonIlegible("cadena sin cerrar")
                val c = texto[i]
                if (c == '"') {
                    i += 1
                    return fuera.toString()
                }
                if (c == '\\') {
                    i += 1
                    if (fin()) throw JsonIlegible("escape sin cerrar")
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
                            if (i + 4 >= texto.length) throw JsonIlegible("unicode corto")
                            val hex = texto.substring(i + 1, i + 5)
                            val punto = try {
                                hex.toInt(16)
                            } catch (_: NumberFormatException) {
                                throw JsonIlegible("unicode ilegal")
                            }
                            fuera.append(punto.toChar())
                            i += 4
                        }
                        else -> throw JsonIlegible("escape ilegal")
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
                throw JsonIlegible("numero ilegal")
            }
        }

        private fun literal(palabra: String, v: Any?): Any? {
            if (!texto.startsWith(palabra, i)) throw JsonIlegible("literal ilegal")
            i += palabra.length
            return v
        }

        private fun saltar() {
            while (!fin() && (texto[i] == ' ' || texto[i] == '\t' ||
                    texto[i] == '\n' || texto[i] == '\r')) {
                i += 1
            }
        }

        private fun fin(): Boolean = i >= texto.length
    }

    private fun <T> invalid(safe: String): Outcome<T> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, safe))
}
