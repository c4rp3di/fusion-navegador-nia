package nia.engine.media

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T050 v2 (R0373, RES-0150): persistencia de la cuenta y la sesion
 * de OpenSubtitles para T104 (OpenSubtitles UI). Politica de
 * secretos EXPLICITA, la que exige T104: el TSV vive SOLO en el
 * almacenamiento privado del host (filesDir), JAMAS en un export, un
 * backup ni un log; los safeMessage de los errores NUNCA contienen
 * apiKey, usuario, clave ni token (solo nombres de campo); la carga
 * es todo-o-nada (un archivo corrupto o fuera de limites responde
 * null sin tocar nada) y la escritura es atomica tmp+rename.
 * borrar() cierra la sesion y la OLVIDA del disco.
 *
 * Formato version 1: "nia-os-sesion<TAB>1" mas lineas "K<TAB><apiKey>"
 * (obligatoria), "U<TAB><usuario>", "P<TAB><clave>" y "T<TAB><token>",
 * opcionales, con el escape de barra, tab, salto y retorno.
 *
 * Exclusiones v2: sin cifrado propio (el almacen es privado del
 * dispositivo; si T104 exige keystore lo monta el shell), sin
 * multiples cuentas y sin UI (T104).
 */
class OpenSubtitlesSesion(private val archivo: File) {

    /** Cuenta restaurada: config viva y su token de sesion. */
    data class CuentaGuardada(
        val config: OpenSubtitlesConfig,
        val bearer: String?
    )

    /** Guarda cuenta y token; un campo fuera de limites no escribe NADA. */
    fun guardar(config: OpenSubtitlesConfig, bearer: String?): Outcome<Unit> {
        if (config.apiKey.length > MAX_CAMPO) {
            return fallo("apiKey fuera de limites")
        }
        if (config.username != null && config.username.length > MAX_CAMPO) {
            return fallo("usuario fuera de limites")
        }
        if (config.password != null && config.password.length > MAX_CAMPO) {
            return fallo("clave fuera de limites")
        }
        if (bearer != null && bearer.length > MAX_CAMPO) {
            return fallo("token fuera de limites")
        }
        val sb = StringBuilder()
        sb.append("nia-os-sesion\t1\n")
        sb.append("K\t").append(escapa(config.apiKey)).append('\n')
        if (config.username != null) {
            sb.append("U\t").append(escapa(config.username)).append('\n')
        }
        if (config.password != null) {
            sb.append("P\t").append(escapa(config.password)).append('\n')
        }
        if (bearer != null) {
            sb.append("T\t").append(escapa(bearer)).append('\n')
        }
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        try {
            archivo.parentFile?.mkdirs()
            tmp.writeText(sb.toString())
        } catch (noEscribible: Exception) {
            tmp.delete()
            return fallo("disco no escribible")
        }
        if (!tmp.renameTo(archivo)) {
            tmp.delete()
            return fallo("no se pudo guardar la sesion")
        }
        return Outcome.success(Unit)
    }

    /** Cuenta restaurada o null si no hay sesion valida en disco. */
    fun cargar(): CuentaGuardada? {
        val texto = try {
            archivo.readText()
        } catch (ilegible: Exception) {
            return null
        }
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-os-sesion\t1") return null
        var apiKey: String? = null
        var usuario: String? = null
        var clave: String? = null
        var token: String? = null
        for (i in 1 until lineas.size) {
            if (lineas[i].isEmpty()) continue
            val partes = lineas[i].split('\t')
            if (partes.size != 2) return null
            val r = desescapa(partes[1])
            if (!r.second) return null
            val campo = r.first
            when (partes[0]) {
                "K" -> {
                    if (apiKey != null) return null
                    apiKey = campo
                }
                "U" -> {
                    if (usuario != null) return null
                    usuario = campo
                }
                "P" -> {
                    if (clave != null) return null
                    clave = campo
                }
                "T" -> {
                    if (token != null) return null
                    token = campo
                }
                else -> return null
            }
        }
        if (apiKey == null) return null
        val config = try {
            OpenSubtitlesConfig(apiKey, usuario, clave)
        } catch (invalida: IllegalArgumentException) {
            return null
        }
        return CuentaGuardada(config, token)
    }

    /** Borra la sesion del disco (cerrar sesion y olvidar). */
    fun borrar(): Outcome<Unit> {
        if (!archivo.isFile) return Outcome.success(Unit)
        return if (archivo.delete()) {
            Outcome.success(Unit)
        } else {
            fallo("no se pudo borrar la sesion")
        }
    }

    private fun fallo(mensaje: String): Outcome<Unit> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, mensaje))

    private fun escapa(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch == '\\') sb.append("\\\\")
            else if (ch == '\t') sb.append("\\t")
            else if (ch == '\n') sb.append("\\n")
            else if (ch == '\r') sb.append("\\r")
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun desescapa(s: String): Pair<String, Boolean> {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') {
                sb.append(ch)
                i += 1
                continue
            }
            if (i + 1 >= s.length) return Pair(sb.toString(), false)
            when (s[i + 1]) {
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> return Pair(sb.toString(), false)
            }
            i += 2
        }
        return Pair(sb.toString(), true)
    }

    companion object {
        /** Longitud maxima de cada campo guardado. */
        const val MAX_CAMPO = 256
    }
}
