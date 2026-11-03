package nia.contracts

/** Propietario del handle: la autorización es por contexto asignado, no por origin declarado en el mensaje. */
enum class HandleOwner { BROWSER, NETWORK, DOCUMENTS, JS, MODULES, INSPECTOR, CONFIG }

/**
 * Handle(owner, generation, id) del maestro: caduca cuando su recurso
 * cambia de generación (p. ej. al navegar). `T` solo tipa el handle al
 * recurso esperado; el id opaco viaja en [id].
 */
data class Handle<T>(
    val owner: HandleOwner,
    val generation: Long,
    val id: String
) {
    init {
        require(generation > 0) { "generation debe ser > 0" }
        require(id.isNotBlank()) { "id obligatorio" }
    }

    /** Stale si la generación viva del recurso ya no es la del handle. */
    fun isStale(currentGeneration: Long): Boolean = currentGeneration != generation
}

/**
 * Contador de generaciones de UN recurso (pestaña, documento, contexto).
 * Cada recurso real tendrá su propio reloj; `next()` emite generaciones
 * válidas y `invalidate()` caduca las vivas.
 */
class GenerationClock {
    private var currentGen = 0L

    @Synchronized fun current(): Long = currentGen

    @Synchronized fun next(): Long {
        currentGen += 1
        return currentGen
    }

    /** Caduca los handles de la generación actual (navegación, cierre, crash). */
    @Synchronized fun invalidate(): Long {
        currentGen += 1
        return currentGen
    }
}

/**
 * Registro mínimo de emisión/validación de handles para un recurso.
 * `validate` devuelve Outcome: StaleHandle se maneja como flujo normal,
 * no como excepción.
 */
class HandleRegistry {
    private val clock = GenerationClock()

    fun <T> issue(owner: HandleOwner, id: String): Handle<T> =
        Handle(owner, clock.next(), id)

    fun currentGeneration(): Long = clock.current()

    fun invalidateAll(): Long = clock.invalidate()

    fun <T> validate(handle: Handle<T>): Outcome<Handle<T>> =
        if (handle.isStale(clock.current())) {
            Outcome.failure(
                ErrorInfo(
                    ErrorCode.STALE_HANDLE,
                    "el recurso cambió de generación; pide un handle nuevo"
                )
            )
        } else {
            Outcome.success(handle)
        }
}
