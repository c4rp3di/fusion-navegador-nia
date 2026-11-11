package nia.engine.profile

import java.io.File

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T112 v1 (R0369, RES-0149): backups del perfil NORMAL versionados
 * y atomicos SIN secretos (X03 del Delta P9). El gestor NO decide
 * solo que es un secreto: el host pasa SOLO areas de la lista
 * permitida (marcadores, historial, ajustes...) y cualquier area
 * ajena es INVALID_INPUT sin escribir NADA: cero privado por
 * construccion, no por promesa. Cookies, storage, IndexedDb y
 * valores secretos JAMAS deben entrar aqui.
 *
 * Formato version 1, un archivo por backup, tabulado y escapado
 * como BookmarkManager: "nia-backup<TAB>1" + "V<TAB><version>" y una
 * linea "C<TAB><area><TAB><contenido>" por area. Escritura
 * atomica: tmp+rename (un crash nunca deja un backup a medias
 * visible).
 *
 * Cuota: MAX_BACKUPS backups vivos; crear() rota el mas viejo.
 * Corrupcion recuperable: listar() ignora lo que no parsea y
 * borrar() puede erradicar lo que quede; restaurar() valida TODO
 * antes de tocar disco y escribe cada area como <area>.tsv en un
 * directorio de staging: JAMAS escribe sobre el estado vivo del
 * perfil (aplicarlo es decision del host y del usuario).
 *
 * Exclusiones v1: armazon motor SIN cableado en el shell ni menu
 * de Ajustes (v2), sin import desde SAF (v2+), sin cifrado propio
 * (los secretos no viajan, no se cifran aqui) y sin tests nuevos
 * (convencion del tiron: el smoke de Actions compila el motor).
 *
 * T112 v4 (R0407, RES-0162): texto(nombre) expone el texto canonico
 * para que el host lo exporte por SAF a donde el usuario elija.
 */
data class BackupInfo(
    val nombre: String,
    val version: Long,
    val areas: List<String>,
    val bytes: Long
)

/**
 * Gestor de backups del perfil normal (T112 v1): puro JVM, sin
 * Android. El host monta BackupsPerfil(dirDeBackups,
 * areasPermitidas) y llama crear/listar/restaurar/borrar. El
 * INCOGNITO no debe montar ninguno: nada suyo llega a disco.
 */
class BackupsPerfil(
    private val dir: File,
    private val permitidas: Set<String>
) {

    /** Cuantos backups validos hay ahora mismo. */
    fun total(): Int = listar().size

    /** Backups validos en orden de version; los corruptos JAMAS
     * aparecen (no rompen el listado). */
    fun listar(): List<BackupInfo> {
        val res = ArrayList<BackupInfo>()
        val archivos = dir.listFiles() ?: return res
        for (f in archivos) {
            val info = leerInfo(f)
            if (info != null) res.add(info)
        }
        res.sortBy { it.version }
        return res
    }

    /**
     * Crea un backup versionado con las areas dadas: todas deben
     * estar en la lista permitida o no se escribe NADA. Pasar la
     * cuota rota el mas viejo.
     */
    fun crear(areas: Map<String, String>): Outcome<BackupInfo> {
        if (areas.isEmpty()) return fallo("sin areas")
        if (areas.size > MAX_AREAS) return fallo("demasiadas areas")
        for ((area, contenido) in areas) {
            if (!AREA_RE.matches(area)) {
                return fallo("nombre de area no valido: " + sanitiza(area))
            }
            if (!permitidas.contains(area)) {
                return fallo("area no permitida: " + sanitiza(area))
            }
            if (contenido.length > MAX_AREA) {
                return fallo("area demasiado grande: " + sanitiza(area))
            }
        }
        val version = mayorVersion() + 1L
        val texto = serializar(version, areas)
        val tmp = File(dir, nombreDe(version) + ".tmp")
        val destino = File(dir, nombreDe(version))
        try {
            dir.mkdirs()
            tmp.writeText(texto)
        } catch (noEscribible: Exception) {
            tmp.delete()
            return fallo("disco no escribible")
        }
        if (!tmp.renameTo(destino)) {
            tmp.delete()
            return fallo("no se pudo mover el backup")
        }
        rotar()
        return Outcome.success(
            BackupInfo(nombreDe(version), version, areas.keys.sorted(), texto.length.toLong())
        )
    }

    /**
     * Restaura un backup a staging: valida TODO antes de escribir y
     * cada area queda como <area>.tsv dentro de staging (atomico por
     * archivo). El estado vivo del perfil NO se toca: aplicar el
     * staging es decision del host, despues de que el usuario mire.
     */
    fun restaurar(nombre: String, staging: File): Outcome<Unit> {
        if (!NOMBRE_RE.matches(nombre)) return fallo("nombre no valido")
        val origen = File(dir, nombre)
        if (!origen.isFile) return fallo("backup no existe")
        val contenido = try {
            origen.readText()
        } catch (ilegible: Exception) {
            return fallo("backup ilegible")
        }
        val parse = parsear(contenido) ?: return fallo("backup corrupto")
        try {
            staging.mkdirs()
        } catch (noEscribible: Exception) {
            return fallo("disco no escribible")
        }
        for ((area, texto) in parse.second) {
            if (!AREA_RE.matches(area)) return fallo("backup corrupto")
            val destino = File(staging, area + ".tsv")
            val tmp = File(staging, area + ".tsv.tmp")
            try {
                tmp.writeText(texto)
            } catch (noEscribible: Exception) {
                tmp.delete()
                return fallo("disco no escribible")
            }
            if (!tmp.renameTo(destino)) {
                tmp.delete()
                return fallo("no se pudo escribir el staging")
            }
        }
        return Outcome.success(Unit)
    }

    /** Borra un backup por nombre; sirve tambien para erradicar
     * archivos corruptos que listar() no muestra. */
    fun borrar(nombre: String): Outcome<Unit> {
        if (!NOMBRE_RE.matches(nombre)) return fallo("nombre no valido")
        val f = File(dir, nombre)
        if (!f.isFile) return fallo("backup no existe")
        return if (f.delete()) Outcome.success(Unit) else fallo("no se pudo borrar")
    }

    /**
     * T112 v3 (R0394, RES-0157): importa una copia de un archivo
     * externo (SAF): TODO-o-NADA (parsear) y reescritura CANONICA con
     * version NUEVA (mayorVersion+1): el nombre del archivo externo
     * JAMAS decide la version ni entra al registro. Atomica y con
     * rotacion como crear; el tope de 2 MiB rechaza abuso y
     * corrupcion sin llenar la memoria.
     */
    fun importar(contenido: String): Outcome<BackupInfo> {
        if (contenido.isEmpty()) return fallo("copia vacia")
        if (contenido.length > MAX_IMPORTAR) return fallo("copia demasiado grande")
        val parse = parsear(contenido) ?: return fallo("copia no valida")
        val areas = parse.second
        val version = mayorVersion() + 1L
        val texto = serializar(version, areas)
        val tmp = File(dir, nombreDe(version) + ".tmp")
        val destino = File(dir, nombreDe(version))
        try {
            dir.mkdirs()
            tmp.writeText(texto)
        } catch (noEscribible: Exception) {
            tmp.delete()
            return fallo("disco no escribible")
        }
        if (!tmp.renameTo(destino)) {
            tmp.delete()
            return fallo("no se pudo mover el backup")
        }
        rotar()
        return Outcome.success(
            BackupInfo(nombreDe(version), version, areas.keys.sorted(), texto.length.toLong())
        )
    }

    /**
     * T112 v4 (R0407, RES-0162): texto CANONICO de un backup para
     * EXPORTARLO (SAF): valida TODO-o-NADA (parsear) y re-serializa
     * con la MISMA version y el orden canonico; un backup corrupto
     * JAMAS sale del disco. El contenido viaja SOLO al destino que
     * el usuario elige: ni estado ni traza lo repiten.
     */
    fun texto(nombre: String): Outcome<String> {
        if (!NOMBRE_RE.matches(nombre)) return fallo("nombre no valido")
        val f = File(dir, nombre)
        if (!f.isFile) return fallo("backup no existe")
        val contenido = try {
            f.readText()
        } catch (ilegible: Exception) {
            return fallo("backup ilegible")
        }
        val parse = parsear(contenido) ?: return fallo("backup corrupto")
        return Outcome.success(serializar(parse.first, parse.second))
    }

    /** Info del archivo o null si no es un backup valido. */
    private fun leerInfo(f: File): BackupInfo? {
        val m = NOMBRE_RE.find(f.name) ?: return null
        val version = m.groupValues[1].toLongOrNull() ?: return null
        if (!f.isFile) return null
        val contenido = try {
            f.readText()
        } catch (ilegible: Exception) {
            return null
        }
        val parse = parsear(contenido) ?: return null
        if (parse.first != version) return null
        return BackupInfo(f.name, version, parse.second.keys.sorted(), f.length())
    }

    /** Version mayor entre los backups validos (0 sin ninguno). */
    private fun mayorVersion(): Long {
        var mayor = 0L
        for (info in listar()) {
            if (info.version > mayor) mayor = info.version
        }
        return mayor
    }

    /** Borra los mas viejos hasta bajar de la cuota. */
    private fun rotar() {
        val lista = listar()
        if (lista.size <= MAX_BACKUPS) return
        for (i in 0 until (lista.size - MAX_BACKUPS)) {
            File(dir, lista[i].nombre).delete()
        }
    }

    private fun nombreDe(version: Long): String = "backup-" + version + ".tsv"

    /** Serializa version 1: cabecera, version y una linea C por area. */
    private fun serializar(version: Long, areas: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("nia-backup\t1\n")
        sb.append("V\t").append(version).append('\n')
        for (area in areas.keys.sorted()) {
            sb.append("C\t").append(escapa(area)).append('\t')
            sb.append(escapa(areas.getValue(area))).append('\n')
        }
        return sb.toString()
    }

    /** Parsea TODO o nada: version y areas validas o null. */
    private fun parsear(texto: String): Pair<Long, LinkedHashMap<String, String>>? {
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-backup\t1") return null
        var version = -1L
        val areas = LinkedHashMap<String, String>()
        for (i in 1 until lineas.size) {
            if (lineas[i].isEmpty()) continue
            val partes = lineas[i].split('\t')
            if (partes[0] == "V" && partes.size == 2) {
                val v = partes[1].toLongOrNull() ?: return null
                if (v < 1L || version != -1L) return null
                version = v
            } else if (partes[0] == "C" && partes.size == 3) {
                if (version < 1L) return null
                val rArea = desescapa(partes[1])
                if (!rArea.second) return null
                val area = rArea.first
                if (!AREA_RE.matches(area)) return null
                if (!permitidas.contains(area)) return null
                if (areas.containsKey(area)) return null
                val rTexto = desescapa(partes[2])
                if (!rTexto.second) return null
                areas[area] = rTexto.first
            } else {
                return null
            }
        }
        if (version < 1L || areas.isEmpty()) return null
        return Pair(version, areas)
    }

    private fun <T> fallo(mensaje: String): Outcome<T> =
        Outcome.failure(ErrorInfo(ErrorCode.INVALID_INPUT, mensaje))

    /** El safeMessage nunca repite contenido crudo del perfil. */
    private fun sanitiza(texto: String?): String = (texto ?: "").take(20)

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
        /** Cuota: backups vivos como mucho. */
        const val MAX_BACKUPS = 5

        /** Tope de areas por backup. */
        const val MAX_AREAS = 8

        /** Tope de caracteres por area (256 KiB). */
        const val MAX_AREA = 262144

        /** Areas: [a-z0-9-] de 1 a 24 (nombre de archivo seguro). */
        private val AREA_RE = Regex("^[a-z0-9-]{1,24}$")

        /** Tope de caracteres de una copia importada (2 MiB). */
        const val MAX_IMPORTAR = 2097152

        /** Nombre de backup: backup-<n>.tsv. */
        private val NOMBRE_RE = Regex("^backup-(\\d{1,9})\\.tsv$")
    }
}
