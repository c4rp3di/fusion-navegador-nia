package nia.contracts

/**
 * Versión compartida de los contratos (T002). Cambios incompatibles => bump
 * de MAJOR y RFC con consumidores; adiciones compatibles => MINOR.
 */
object ContractsVersion {
    const val MAJOR = 0
    const val MINOR = 2
    const val PATCH = 0

    override fun toString(): String = "$MAJOR.$MINOR.$PATCH"
}
