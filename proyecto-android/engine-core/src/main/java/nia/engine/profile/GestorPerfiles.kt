package nia.engine.profile

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T112 v3 (R0394, RES-0157): registro de perfiles nombrados del
 * navegador (X03 del Delta P9): puro JVM, sin Android. El host monta
 * GestorPerfiles(dirDePerfiles) y llama crear/listar/borrar/existe;
 * el DIRECTORIO de cada perfil lo decide y lo aisla el host
 * (perfiles/<nombre>): este gestor SOLO decide que nombres son
 * validos y cuantos viven, como BackupsPerfil decide de copias.
 *
 * Formato perfiles.tsv version 1, un archivo, tabulado: cabecera
 * "nia-perfiles<TAB>1" y una linea "P<TAB><nombre>" por perfil.
 * Escritura atomica tmp+rename; una linea corrupta se ignora y no
 * rompe el listado (corrupcion recuperable). Los NOMBRES son
 * [a-z0-9-] de 1 a 16: jamas una ruta, jamas contenido del perfil.
 *
 * Exclusiones v3: el activo lo decide el host (archivo propio del
 * shell), sin renombrar perfiles, sin import/export del registro y
 * sin tests nuevos (convencion del tiron).
 */
class GestorPerfiles(private val dir: File) {

    /** Perfiles validos en orden alfabetico; los corruptos JAMAS
     * aparecen (no rompen el listado). */
    fun listar(): List<String> {
        val res = ArrayList<String>()
        val f = File(dir, "perfiles.tsv")
        val contenido = try {
            f.readText()
        } catch (ilegible: Exception) {
            return res
        }
        val lineas = contenido.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-perfiles\t1") return res
        for (i in 1 until lineas.size) {
            if (lineas[i].isEmpty()) continue
            val partes = lineas[i].split('\t')
            if (partes.size == 2 && partes[0] == "P" && NOMBRE_RE.matches(partes[1])) {
                if (!res.contains(partes[1])) res.add(partes[1])
            }
        }
        res.sort()
        return res
    }

    /** Cuantos perfiles validos hay ahora mismo. */
    fun total(): Int = listar().size

    /** El nombre esta en el registro? */
    fun existe(nombre: String): Boolean = listar().contains(nombre)

    /**
     * Crea un perfil en el registro: nombre valido, sin duplicados
     * y dentro de la cuota; reescribe el archivo completo de forma
     * atomica. El directorio lo crea el host.
     */
    fun crear(nombre: String): Outcome<String> {
        if (!NOMBRE_RE.matches(nombre)) return fallo("nombre de perfil no valido")
        if (existe(nombre)) return fallo("perfil ya existe")
        if (total() >= MAX_PERFILES) return fallo("demasiados perfiles")
        val lista = ArrayList(listar())
        lista.add(nombre)
        return persistir(lista, nombre)
    }

    /** Retira un perfil del registro (el directorio lo borra el
     * host con confirmacion explicita). */
    fun borrar(nombre: String): Outcome<Unit> {
        if (!NOMBRE_RE.matches(nombre)) return fallo("nombre de perfil no valido")
        if (!existe(nombre)) return fallo("perfil no existe")
        val lista = ArrayList(listar())
        lista.remove(nombre)
        return persistir(lista, Unit)
    }

    /** Reescritura atomica completa del registro (tmp+rename). */
    private fun <T> persistir(lista: List<String>, exito: T): Outcome<T> {
        val sb = StringBuilder()
        sb.append("nia-perfiles\t1\n")
        for (nombre in lista.sorted()) {
            sb.append("P\t").append(nombre).append('\n')
        }
        val f = File(dir, "perfiles.tsv")
        val tmp = File(dir, "perfiles.tsv.tmp")
        try {
            dir.mkdirs()
            tmp.writeText(sb.toString())
        } catch (noEscribible: Exception) {
            tmp.delete()
            return fallo("disco no escribible")
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return fallo("no se pudo escribir el registro")
        }
        return Outcome.success(exito)
    }

    private fun <T> fallo(mensaje: String): Outcome<T> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, mensaje))

    companion object {
        /** Cuota: perfiles vivos como mucho. */
        const val MAX_PERFILES = 6

        /** Nombre de perfil: [a-z0-9-] de 1 a 16. */
        val NOMBRE_RE = Regex("^[a-z0-9-]{1,16}$")
    }
}
