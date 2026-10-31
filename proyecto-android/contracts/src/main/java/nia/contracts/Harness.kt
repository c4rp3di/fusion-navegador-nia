package nia.contracts

/**
 * Estados de prueba del maestro (sección 1.5). El harness nunca inventa PASS:
 * lo que no se ejecuta queda NOT_RUN (o UNSUPPORTED si la plataforma no
 * puede correrlo), nunca en verde (T004).
 */
enum class TestStatus { NOT_RUN, PASS, FAIL, BLOCKED, UNSUPPORTED }

/**
 * Resultado de una suite con cada cuenta separada (T004): passed/failed/skipped
 * son casos ejecutados; [blocked] y [unsupported] marcan casos que el entorno
 * o la plataforma no pueden ejecutar.
 */
data class SuiteResult(
    val suite: String,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val blocked: Int = 0,
    val unsupported: Int = 0
) {
    init {
        require(suite.isNotBlank()) { "suite es obligatorio" }
        require(passed >= 0 && failed >= 0 && skipped >= 0) { "contadores negativos" }
        require(blocked >= 0 && unsupported >= 0) { "contadores negativos" }
    }

    /** Estado global de la suite: FAIL manda; sin casos ejecutados es NOT_RUN. */
    val status: TestStatus
        get() = when {
            failed > 0 -> TestStatus.FAIL
            blocked > 0 -> TestStatus.BLOCKED
            unsupported > 0 -> TestStatus.UNSUPPORTED
            passed + skipped > 0 -> TestStatus.PASS
            else -> TestStatus.NOT_RUN
        }

    /** Resumen de una línea con cada estado separado (aceptación T004). */
    fun summary(): String =
        "$suite: ${status.name} (PASS $passed · FAIL $failed · SKIPPED $skipped · BLOCKED $blocked · UNSUPPORTED $unsupported)"
}

/**
 * Registro del harness (T004): acumula suites y renderiza un reporte con los
 * estados separados, pensado para logs de CI. [overall] es el peor estado
 * presente; nunca PASS si algo quedó sin correr.
 */
object HarnessReport {
    private val suites = mutableListOf<SuiteResult>()

    @Synchronized
    fun add(result: SuiteResult) {
        suites.add(result)
    }

    @Synchronized
    fun clear() = suites.clear()

    @Synchronized
    fun all(): List<SuiteResult> = suites.toList()

    @Synchronized
    fun overall(): TestStatus =
        if (suites.isEmpty()) TestStatus.NOT_RUN
        else unrank(suites.minOf { rank(it.status) })

    @Synchronized
    fun report(): String = buildString {
        appendLine("HARNESS NIA · suites ${suites.size}")
        for (s in suites) appendLine(s.summary())
        append("GLOBAL: ${overall().name}")
    }

    private fun rank(s: TestStatus): Int = when (s) {
        TestStatus.FAIL -> 0
        TestStatus.BLOCKED -> 1
        TestStatus.UNSUPPORTED -> 2
        TestStatus.NOT_RUN -> 3
        TestStatus.PASS -> 4
    }

    private fun unrank(r: Int): TestStatus = when (r) {
        0 -> TestStatus.FAIL
        1 -> TestStatus.BLOCKED
        2 -> TestStatus.UNSUPPORTED
        3 -> TestStatus.NOT_RUN
        else -> TestStatus.PASS
    }
}
