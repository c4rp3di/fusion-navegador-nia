package nia.engine.settings

import java.io.File

/**
 * T093 v1 (R0179, RES-0083): tipos de valor que soporta el armazon
 * v1 de ajustes: BOOL exacto, TEXTO acotado, ENTERO en rango y
 * ELECCION de lista cerrada.
 */
enum class TipoOpcion { BOOL, TEXTO, ENTERO, ELECCION }

/**
 * Una opcion de ajuste (T093 v1): SIEMPRE tipificada y validada.
 * La opcion NO disponible NUNCA cambia (enlaza capacidad real o
 * declara su estado honesto) y la secreta JAMAS viaja en el
 * export.
 */
class OpcionAjuste(
    val id: String,
    val seccion: String,
    val titulo: String,
    val descripcion: String,
    val tipo: TipoOpcion,
    val defecto: String,
    val minimo: Long = 0L,
    val maximo: Long = 0L,
    val elecciones: List<String> = emptyList(),
    val disponible: Boolean = true,
    val secreta: Boolean = false
)

/**
 * SettingsManager puro JVM (T093 v1, subhito armazon de Ajustes
 * modulares SIN ciclos): registro tipificado con validacion
 * estricta (id [a-z0-9.-], defecto y valores SIEMPRE validados
 * contra el tipo: BOOL exacto, ENTERO en [minimo, maximo],
 * ELECCION de lista cerrada y TEXTO no vacio, sin tabuladores
 * ni saltos, hasta 64), busqueda case-insensitive por id,
 * seccion, titulo y descripcion que ABRE la opcion, secciones en
 * orden de registro, restablecer por opcion y restablecer todo
 * (cuenta solo las que estaban fuera del defecto).
 *
 * La opcion NO disponible JAMAS cambia (asignar responde false) y
 * la secreta NUNCA sale en el export. Export en tabulado
 * version 1: "nia-settings\t1" + lineas "S\t<id>\t<valor>" SOLO
 * de las configurables (no secretas NI no disponibles); import
 * SOLO aplica si TODO parsea (clave desconocida, secreta, no
 * disponible, version distinta o valor invalido dejan el estado
 * vivo intacto) y aplica solo las lineas presentes.
 *
 * Persistencia: registrar() NO pisa el archivo (el host registra
 * sus opciones y despues recarga con recargar(): un archivo
 * corrupto se ignora y quedan los defectos); cada cambio
 * SI guarda.
 */
class SettingsManager(private val archivo: File? = null) {

    private val opciones = LinkedHashMap<String, OpcionAjuste>()
    private val valores = LinkedHashMap<String, String>()

    /** Registra una opcion tipificada; false si el id ya existe o
     * algo del esquema no vale (id, titulo, seccion, defecto). */
    fun registrar(op: OpcionAjuste): Boolean {
        if (opciones.containsKey(op.id)) {
            return false
        }
        if (!idValido(op.id)) {
            return false
        }
        if (op.titulo.isEmpty() || op.seccion.isEmpty()) {
            return false
        }
        if (op.descripcion.length > MAX_CAMPO) {
            return false
        }
        if (!validaValor(op, op.defecto)) {
            return false
        }
        opciones[op.id] = op
        valores[op.id] = op.defecto
        return true
    }

    /** Cuantas opciones hay registradas. */
    fun contar(): Int = opciones.size

    /** La opcion registrada con ese id (null si no existe). */
    fun opcion(id: String): OpcionAjuste? = opciones[id]

    /** El valor actual (null si el id no esta registrado). */
    fun valor(id: String): String? = valores[id]

    /** Cambia el valor SIEMPRE que sea valido y la opcion este
     * disponible; false en cualquier otro caso (sin tocar nada). */
    fun asignar(id: String, valor: String): Boolean {
        val op = opciones[id] ?: return false
        if (!op.disponible) {
            return false
        }
        if (!validaValor(op, valor)) {
            return false
        }
        valores[id] = valor
        guardar()
        return true
    }

    /** Busca por id, seccion, titulo o descripcion,
     * case-insensitive; buscar abre la opcion. */
    fun buscar(q: String): List<OpcionAjuste> {
        val n = q.lowercase()
        return opciones.values.filter {
            it.id.lowercase().contains(n) ||
                it.seccion.lowercase().contains(n) ||
                it.titulo.lowercase().contains(n) ||
                it.descripcion.lowercase().contains(n)
        }
    }

    /** Las secciones en orden de registro, sin repetidas. */
    fun secciones(): List<String> {
        val vista = ArrayList<String>()
        for (op in opciones.values) {
            if (!vista.contains(op.seccion)) {
                vista.add(op.seccion)
            }
        }
        return vista
    }

    /** Vuelve una opcion a su defecto; false si no existe. */
    fun restablecer(id: String): Boolean {
        val op = opciones[id] ?: return false
        val previo = valores[id]
        valores[id] = op.defecto
        if (previo != op.defecto) {
            guardar()
        }
        return true
    }

    /** Restablece TODO; devuelve cuantas estaban fuera de su
     * defecto (y volvieron). */
    fun restablecerTodo(): Int {
        var n = 0
        for ((id, op) in opciones) {
            if (valores[id] != op.defecto) {
                n += 1
            }
            valores[id] = op.defecto
        }
        if (n > 0) {
            guardar()
        }
        return n
    }

    /** Exporta el estado (version 1): SOLO las configurables; las
     * secretas y las no disponibles JAMAS viajan. */
    fun exportar(): String {
        val sb = StringBuilder()
        sb.append("nia-settings\t1")
        for ((id, valor) in valores) {
            val op = opciones[id]
            if (op == null || op.secreta || !op.disponible) {
                continue
            }
            sb.append('\n')
            sb.append("S\t")
            sb.append(id)
            sb.append('\t')
            sb.append(valor)
        }
        return sb.toString()
    }

    /** Importa un export: SOLO aplica lineas si TODO parsea; una
     * clave desconocida, secreta o no disponible, una version
     * distinta o un valor invalido dejan el estado vivo intacto. */
    fun importar(texto: String): Boolean {
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-settings\t1") {
            return false
        }
        val tmp = LinkedHashMap<String, String>()
        for (i in 1 until lineas.size) {
            val p = lineas[i].split('\t')
            if (p.size != 3 || p[0] != "S") {
                return false
            }
            if (tmp.containsKey(p[1])) {
                return false
            }
            val op = opciones[p[1]] ?: return false
            if (op.secreta || !op.disponible) {
                return false
            }
            if (!validaValor(op, p[2])) {
                return false
            }
            tmp[p[1]] = p[2]
        }
        for ((id, v) in tmp) {
            valores[id] = v
        }
        guardar()
        return true
    }

    /** Recarga el archivo tras registrar las opciones del host: un
     * archivo corrupto se ignora y quedan los defectos. */
    fun recargar(): Boolean {
        val texto = leer() ?: return false
        return importar(texto)
    }

    companion object {
        const val MAX_CAMPO = 64

        private fun idValido(id: String): Boolean {
            if (id.isEmpty() || id.length > MAX_CAMPO) {
                return false
            }
            for (ch in id) {
                val ok = ch == '.' || ch == '-' ||
                    (ch >= 'a' && ch <= 'z') ||
                    (ch >= '0' && ch <= '9')
                if (!ok) {
                    return false
                }
            }
            return true
        }

        private fun validaValor(op: OpcionAjuste, valor: String): Boolean {
            if (valor.length > MAX_CAMPO) {
                return false
            }
            for (ch in valor) {
                if (ch == '\t' || ch == '\n' || ch == '\r') {
                    return false
                }
            }
            when (op.tipo) {
                TipoOpcion.BOOL -> return valor == "true" || valor == "false"
                TipoOpcion.ENTERO -> {
                    if (op.minimo > op.maximo) {
                        return false
                    }
                    val n = valor.toLongOrNull() ?: return false
                    return n >= op.minimo && n <= op.maximo
                }
                TipoOpcion.ELECCION -> return op.elecciones.contains(valor)
                TipoOpcion.TEXTO -> return valor.isNotEmpty()
            }
        }
    }

    private fun guardar() {
        val f = archivo ?: return
        try {
            f.writeText(exportar())
        } catch (e: Exception) {
            // Sin disco: los ajustes siguen vivos en memoria.
        }
    }

    private fun leer(): String? {
        val f = archivo ?: return null
        if (!f.isFile) {
            return null
        }
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }
}
