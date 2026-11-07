package nia.engine.diag

import nia.contracts.TestStatus

/**
 * T108 v1 (RES-0103): capacidades del build instalado con los estados
 * del harness (T004): PASS/NOT_RUN/FAIL/BLOCKED/UNSUPPORTED, alcance
 * (ci/dispositivo/sitio real) y evidencia corta separados.
 *
 * NADA se inventa: sin registro embebido todo queda NOT_RUN, un
 * registro ilegible NO se maquilla y actualizar() JAMAS sobrescribe
 * un resultado ya medido (la UI no cambia resultados por optimismo).
 * PASS de un subset no significa web completa.
 */
data class Capacidad(
    val nombre: String,
    val estado: TestStatus,
    val alcance: String,
    val evidencia: String
)

object Capacidades {

    /** Alcances de evidencia separados (aceptacion T108). */
    const val ALCANCE_CI = "ci"
    const val ALCANCE_DISPOSITIVO = "dispositivo"
    const val ALCANCE_SITIO_REAL = "sitio real"
    const val ALCANCE_NINGUNO = "ninguno"

    const val SIN_REGISTRO = "sin registro embebido"
    const val ILEGIBLE = "registro embebido ilegible"
    const val AVISO_SUBSET = "PASS de un subset no significa web completa"

    /** Catalogo REVISADO A MANO (subset de la aceptacion): quien
     *  anade o retira una capacidad actualiza esta lista y el paso
     *  del workflow que escribe el registro embebido. */
    fun catalogo(): List<String> = listOf(
        "http.fetch",
        "html.parser",
        "css.cascade",
        "js.cuotas",
        "cookies.persistencia",
        "marcadores.html"
    )

    /** Estado base honesto: sin medir es NOT_RUN con alcance ninguno. */
    fun base(evidencia: String): List<Capacidad> =
        catalogo().map { Capacidad(it, TestStatus.NOT_RUN, ALCANCE_NINGUNO, evidencia) }

    /** Registro del build: null o vacio es build sin registro, un
     *  registro ilegible NO se maquilla y uno valido llena solo el
     *  catalogo (fuera de catalogo se ignora, conservador). */
    fun registro(texto: String?): List<Capacidad> {
        if (texto.isNullOrEmpty()) {
            return base(SIN_REGISTRO)
        }
        val leidas = parsear(texto) ?: return base(ILEGIBLE)
        val porNombre = leidas.associateBy { it.nombre }
        return catalogo().map { nombre ->
            porNombre[nombre] ?: Capacidad(nombre, TestStatus.NOT_RUN, ALCANCE_NINGUNO, SIN_REGISTRO)
        }
    }

    /** Lineas "nombre=ESTADO#alcance#evidencia"; una linea mala
     *  invalida TODO el registro (nada a medias). */
    private fun parsear(texto: String): List<Capacidad>? {
        val out = ArrayList<Capacidad>()
        val catalogo = catalogo().toSet()
        for (linea in texto.split("\n")) {
            val l = linea.trim()
            if (l.isEmpty()) continue
            val sep = l.indexOf('=')
            if (sep <= 0) return null
            val nombre = l.substring(0, sep)
            if (!catalogo.contains(nombre)) continue
            val partes = l.substring(sep + 1).split("#")
            if (partes.size != 3) return null
            val estado = estadoDe(partes[0]) ?: return null
            val alcance = partes[1]
            val evidencia = partes[2]
            if (alcance.isEmpty() || evidencia.isEmpty()) return null
            out.add(Capacidad(nombre, estado, alcance, evidencia))
        }
        return out
    }

    private fun estadoDe(s: String): TestStatus? = when (s) {
        "PASS" -> TestStatus.PASS
        "NOT_RUN" -> TestStatus.NOT_RUN
        "FAIL" -> TestStatus.FAIL
        "BLOCKED" -> TestStatus.BLOCKED
        "UNSUPPORTED" -> TestStatus.UNSUPPORTED
        else -> null
    }

    /** Anti-optimismo: una medida NUEVA solo llena un NOT_RUN; un
     *  resultado ya medido es INMUTABLE desde la UI. */
    fun actualizar(actuales: List<Capacidad>, nueva: Capacidad): List<Capacidad> =
        actuales.map {
            if (it.nombre == nueva.nombre && it.estado == TestStatus.NOT_RUN && nueva.estado != TestStatus.NOT_RUN) {
                nueva
            } else {
                it
            }
        }

    /** Texto del dialogo: cada capacidad con estado, alcance y
     *  evidencia, conteo por estado y el aviso de subset SIEMPRE. */
    fun resumen(estado: List<Capacidad>): String {
        val lineas = ArrayList<String>()
        lineas.add("capacidades del build: " + estado.size)
        for (c in estado) {
            lineas.add(c.nombre + ": " + c.estado.name + " (" + c.alcance + ") - " + c.evidencia)
        }
        var pass = 0
        var fail = 0
        var notRun = 0
        var blocked = 0
        var unsupported = 0
        for (c in estado) {
            when (c.estado) {
                TestStatus.PASS -> pass += 1
                TestStatus.FAIL -> fail += 1
                TestStatus.NOT_RUN -> notRun += 1
                TestStatus.BLOCKED -> blocked += 1
                TestStatus.UNSUPPORTED -> unsupported += 1
            }
        }
        lineas.add("estados: PASS " + pass + ", FAIL " + fail + ", NOT_RUN " + notRun + ", BLOCKED " + blocked + ", UNSUPPORTED " + unsupported)
        lineas.add(AVISO_SUBSET)
        return lineas.joinToString("\n")
    }
}
