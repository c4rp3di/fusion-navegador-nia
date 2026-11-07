package nia.engine.net

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * T101 v1 (RES-0099): fila presentable de una cookie para el gestor
 * del shell. Nada se inventa: cada campo sale de la Cookie REAL del
 * CookieJar. Los SECRETOS VAN OCULTOS por defecto (valor ****) y el
 * valor solo aparece con consentimiento explicito (mostrar=true).
 * Expiracion honesta: sesion (sin Expires/Max-Age), caducada
 * (centinela -1 del Max-Age<=0 o vencida) o fecha UTC legible.
 * Particion: las cookies viven SIN particiones (T033); la fila lo
 * dice honesto en vez de fingir un aislamiento que no existe.
 */
data class FilaCookie(
    val nombre: String,
    val valor: String,
    val dominio: String,
    val ruta: String,
    val hostOnly: Boolean,
    val expira: String,
    val sameSite: String,
    val httpOnly: Boolean,
    val secure: Boolean,
    val particion: String
)

/** T101 v1: presentacion pura de las cookies REALES del jar. */
object GestorCookies {

    const val SIN_PARTICION = "sin particion (T033)"

    /** Valor oculto por defecto; solo con consentimiento explicito. */
    fun valorPresentable(valor: String, mostrar: Boolean): String = if (mostrar) valor else "****"

    /** Sesion / caducada / fecha UTC yyyy-MM-dd HH:mm:ss'Z'. */
    fun expiraTexto(expiresAtMillis: Long?, ahoraMillis: Long): String {
        if (expiresAtMillis == null) return "sesion"
        if (expiresAtMillis <= 0 || expiresAtMillis <= ahoraMillis) return "caducada"
        return FORMATO_UTC.format(Instant.ofEpochMilli(expiresAtMillis))
    }

    /** Sin atributo SameSite el motor lo trata como lax (T026 v2). */
    fun sameSiteTexto(sameSite: String?): String = sameSite ?: "lax"

    /** Fila presentable de una cookie REAL. */
    fun filaDe(cookie: Cookie, mostrar: Boolean, ahoraMillis: Long): FilaCookie = FilaCookie(
        cookie.name,
        valorPresentable(cookie.value, mostrar),
        cookie.domain,
        cookie.path,
        cookie.hostOnly,
        expiraTexto(cookie.expiresAtMillis, ahoraMillis),
        sameSiteTexto(cookie.sameSite),
        cookie.httpOnly,
        cookie.secure,
        SIN_PARTICION
    )

    /** Filas de TODO el jar, en el orden real del almacen. */
    fun filas(jar: CookieJar, mostrar: Boolean, ahoraMillis: Long): List<FilaCookie> =
        jar.all().map { filaDe(it, mostrar, ahoraMillis) }

    /** Linea de dialogo: identidad + expiracion + banderas REALES. */
    fun texto(f: FilaCookie): String {
        val base = f.nombre + " = " + f.valor + " @ " + f.dominio + f.ruta + " | " +
            f.expira + " | " + f.sameSite +
            (if (f.hostOnly) " | host-only" else " | domain")
        return base + (if (f.httpOnly) " | httpOnly" else "") + (if (f.secure) " | secure" else "")
    }

    private val FORMATO_UTC = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
}
