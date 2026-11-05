package nia.engine.errors

import nia.contracts.ErrorCode
import nia.engine.inspector.SecretRedactor

/**
 * T083 v1 (R0157, RES-0076): paginas de error del shell sobre los codigos
 * cerrados de :contracts (maestro, seccion 4): cada ErrorCode tipifica su
 * pagina (titulo, mensaje seguro y accion) SIN inventar codigos ad-hoc. El
 * detalle del motor y la URL de la pagina comprometida se muestran SIEMPRE
 * redactados con el SecretRedactor verificado (T008): utiles y seguros por
 * construccion.
 *
 * Cancelaciones y handles caducados NO generan pagina de error: el usuario
 * no pierde la pagina util que esta leyendo (CANCELLED y STALE_HANDLE
 * devuelven null). TLS NUNCA ofrece recargar como recuperacion: aceptar un
 * certificado invalido no es una opcion del motor (fila T083 del maestro).
 *
 * Exclusiones v1: sin reintentos automaticos (el usuario decide con la
 * accion ofrecida), sin cuotas de reintento y sin pintar la pagina de
 * error dentro del motor (el shell decide como pintar los textos).
 */
object ErrorPages {

    /** Accion del usuario que resuelve la pagina de error. */
    enum class Accion { NINGUNA, VOLVER, RECARGAR }

    /** Pagina de error lista para pintar: TODO su texto es seguro. */
    data class ErrorPage(
        val titulo: String,
        val mensaje: String,
        val detalleRedactado: String,
        val urlRedactada: String?,
        val accion: Accion
    )

    /** (titulo, mensaje, accion) por codigo; SOLO para la tabla interna. */
    private data class Plantilla(
        val titulo: String,
        val mensaje: String,
        val accion: Accion
    )

    /**
     * Pagina de error de un fallo de navegacion; null cuando el fallo NO
     * debe reemplazar la pagina util (cancelacion del usuario o handle
     * caducado). [detalle] es el mensaje crudo del motor: se redacta aqui.
     */
    fun desdeFallo(code: ErrorCode, detalle: String, url: String?): ErrorPage? {
        val p = when (code) {
            ErrorCode.CANCELLED -> return null
            ErrorCode.STALE_HANDLE -> return null
            ErrorCode.UNSUPPORTED -> Plantilla(
                "Esquema no soportado",
                "El navegador no sabe abrir esta direccion.",
                Accion.VOLVER
            )
            ErrorCode.INVALID_INPUT -> Plantilla(
                "Direccion no valida",
                "La direccion no produce una URL navegable.",
                Accion.VOLVER
            )
            ErrorCode.DENIED -> Plantilla(
                "Acceso denegado",
                "La navegacion fue rechazada por una regla.",
                Accion.VOLVER
            )
            ErrorCode.ORIGIN_VIOLATION -> Plantilla(
                "Regla de origen",
                "La operacion rompe la regla de origen de la pagina.",
                Accion.NINGUNA
            )
            ErrorCode.DEADLINE -> Plantilla(
                "Tiempo agotado",
                "La navegacion tarda mas de lo permitido.",
                Accion.RECARGAR
            )
            ErrorCode.QUOTA -> Plantilla(
                "Limite alcanzado",
                "La navegacion agoto un limite del motor.",
                Accion.RECARGAR
            )
            ErrorCode.PROCESS_GONE -> Plantilla(
                "Proceso detenido",
                "El proceso que servia la pagina se detuvo.",
                Accion.RECARGAR
            )
            ErrorCode.NETWORK -> Plantilla(
                "Sin red",
                "La red no responde o la direccion no se resuelve.",
                Accion.RECARGAR
            )
            ErrorCode.TLS -> Plantilla(
                "Conexion no segura",
                "El certificado del sitio no es de confiar; recargar no lo arregla.",
                Accion.VOLVER
            )
            ErrorCode.PARSE -> Plantilla(
                "Pagina ilegible",
                "La respuesta no se pudo interpretar como pagina.",
                Accion.VOLVER
            )
            ErrorCode.INTERNAL -> Plantilla(
                "Fallo interno",
                "El motor fallo de forma inesperada.",
                Accion.RECARGAR
            )
        }
        return ErrorPage(
            p.titulo,
            p.mensaje,
            SecretRedactor.redactText(detalle),
            if (url == null) null else SecretRedactor.redactUrl(url),
            p.accion
        )
    }
}
