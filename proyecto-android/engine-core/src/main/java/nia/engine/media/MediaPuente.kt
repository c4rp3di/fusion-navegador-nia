package nia.engine.media

/**
 * T092 v2 (R0358, RES-0145): contrato del puente HTMLMediaElement
 * (TK-111-1 de la matriz de T111). El motor es puro JVM y NO conoce
 * Android: el shell implementa este contrato sobre el backend REAL de
 * plataforma (MediaEngine + PlatformMediaBackend en :app) y
 * JsDomBindings lo consume para exponer video/audio a los scripts de
 * la pagina. SIN puente (null) los elementos media de la pagina NO
 * exponen la API: nada se finge.
 *
 * v2 sincrono: play()/pause() actuan en seguida y los eventos
 * play/pause/seeked se disparan al elemento en el mismo script. Los
 * eventos asincronos (ended, timeupdate, error) requieren el entorno
 * JS persistente y quedan como exclusion honesta (v3). UN medio activo
 * a la vez (como UNA bajada de torrent en T103 v1).
 */
interface MediaPuente {

    /** Arranca (o reanuda) la reproduccion de la URL resuelta. */
    fun reproducir(url: String, esVideo: Boolean)

    /** Pausa el medio activo. */
    fun pausar()

    /** Posicion REAL del medio activo en ms (0 sin medio). */
    fun posicionMs(): Long

    /** Duracion REAL del medio activo en ms (0 sin preparar). */
    fun duracionMs(): Long

    /** Cierto si no hay reproduccion en curso. */
    fun enPausa(): Boolean

    /** Volumen actual 0..1. */
    fun volumen(): Double

    /** Fija el volumen 0..1. */
    fun fijarVolumen(v: Double)

    /** Velocidad actual. */
    fun velocidad(): Double

    /** Fija la velocidad. */
    fun fijarVelocidad(rate: Double)

    /** Salta a la posicion absoluta en ms. */
    fun buscar(ms: Long)
}
