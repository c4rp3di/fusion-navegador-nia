package nia.engine.bookmarks

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * T060 v1 (R0135, RES-0070): marcadores y accesos rapidos con
 * persistencia por perfil. El estado NUNCA entra roto: importar
 * parsea todo el archivo a estructuras temporales y solo hace el
 * swap cuando la ultima linea es valida (un archivo corrupto no
 * toca el estado vivo, como en StoragePersistence). Las carpetas
 * cuelgan de root (builtin protegida: no se renombra ni se borra),
 * el ciclo de carpetas se rechaza tipificado y los pins solo
 * aceptan marcadores.
 *
 * Exclusiones v1: sin barra de favoritos ni UI (llegan con el
 * shell de navegacion), sin cableado del ciclo de vida con el
 * perfil (el renderer llamara exportar/importar) y sin favicons.
 */

/** Marcador inmutable; carpeta es el id de la carpeta contenedora. */
data class Bookmark(val id: String, val titulo: String, val url: String, val carpeta: String)

/** Carpeta inmutable; padre es el id de la carpeta contenedora. */
data class Carpeta(val id: String, val nombre: String, val padre: String)

/**
 * Registro de marcadores con carpetas anidadas, orden por carpeta,
 * busqueda case-insensitive y accesos rapidos, con export/import
 * tabulado por perfil. Los ids se generan (bm.N y cf.N) con
 * defensa anti-colision y los fallos son INVALID_INPUT tipificados
 * cuyo safeMessage nunca repite contenido crudo.
 */
class BookmarkManager {

    private val items = LinkedHashMap<String, Bookmark>()
    private val carpetas = LinkedHashMap<String, Carpeta>()
    private val orden = LinkedHashMap<String, MutableList<String>>()
    private val pins = ArrayList<String>()
    private var nextBm = 1
    private var nextCf = 1

    init {
        carpetas["root"] = Carpeta("root", "Raiz", "root")
        orden["root"] = ArrayList()
    }

    /** Total de marcadores instalados. */
    val total: Int get() = items.size

    /** Marcador por id o null si no esta. */
    fun marcadorDe(id: String): Bookmark? = items[id]

    /** Carpeta por id o null si no esta. */
    fun carpetaDe(id: String): Carpeta? = carpetas[id]

    /** Crea un marcador en una carpeta instalada. */
    fun add(titulo: String, url: String, carpeta: String = "root"): Outcome<Bookmark> {
        if (!tituloOk(titulo)) return fallo("titulo no valido: " + sanitiza(titulo))
        if (!URL_RE.matches(url)) return fallo("url no valida: " + sanitiza(url))
        if (!carpetas.containsKey(carpeta)) return fallo("carpeta no existe: " + sanitiza(carpeta))
        var id = "bm." + nextBm
        while (items.containsKey(id) || carpetas.containsKey(id)) {
            nextBm += 1
            id = "bm." + nextBm
        }
        nextBm += 1
        val bm = Bookmark(id, titulo, url, carpeta)
        items[id] = bm
        orden.getOrPut(carpeta) { ArrayList() }.add(id)
        return Outcome.success(bm)
    }

    /** Crea una carpeta colgada de un padre instalado. */
    fun addCarpeta(nombre: String, padre: String = "root"): Outcome<Carpeta> {
        if (!tituloOk(nombre)) return fallo("nombre no valido: " + sanitiza(nombre))
        if (!carpetas.containsKey(padre)) return fallo("carpeta padre no existe: " + sanitiza(padre))
        var id = "cf." + nextCf
        while (carpetas.containsKey(id) || items.containsKey(id)) {
            nextCf += 1
            id = "cf." + nextCf
        }
        nextCf += 1
        val cf = Carpeta(id, nombre, padre)
        carpetas[id] = cf
        orden.getOrPut(id) { ArrayList() }
        orden.getOrPut(padre) { ArrayList() }.add(id)
        return Outcome.success(cf)
    }

    /** Renombra un marcador instalado. */
    fun rename(id: String, titulo: String): Outcome<Bookmark> {
        val bm = items[id]
        if (bm == null) return fallo("marcador no existe: " + sanitiza(id))
        if (!tituloOk(titulo)) return fallo("titulo no valido: " + sanitiza(titulo))
        val nuevo = bm.copy(titulo = titulo)
        items[id] = nuevo
        return Outcome.success(nuevo)
    }

    /** Renombra una carpeta instalada que no sea root. */
    fun renombrarCarpeta(id: String, nombre: String): Outcome<Carpeta> {
        val cf = carpetas[id]
        if (cf == null) return fallo("carpeta no existe: " + sanitiza(id))
        if (id == "root") return fallo("carpeta reservada: root")
        if (!tituloOk(nombre)) return fallo("nombre no valido: " + sanitiza(nombre))
        val nuevo = cf.copy(nombre = nombre)
        carpetas[id] = nuevo
        return Outcome.success(nuevo)
    }

    /** Cierto si candidata cuelga de de (o es la misma). */
    fun esDescendiente(candidata: String, de: String): Boolean {
        var actual: String? = candidata
        while (actual != null && actual != "root") {
            if (actual == de) return true
            actual = carpetas[actual]?.padre
        }
        return false
    }

    /**
     * Mueve un marcador o carpeta a otra carpeta en un indice
     * (clamp 0..size); en la misma carpeta reordena y una carpeta
     * dentro de si misma o de su descendencia es un ciclo.
     */
    fun move(id: String, nuevaCarpeta: String, indice: Int): Outcome<Unit> {
        val bm = items[id]
        val cf = carpetas[id]
        if (bm == null && cf == null) return fallo("elemento no existe: " + sanitiza(id))
        if (cf != null && id == "root") return fallo("carpeta reservada: root")
        if (!carpetas.containsKey(nuevaCarpeta)) {
            return fallo("carpeta no existe: " + sanitiza(nuevaCarpeta))
        }
        if (cf != null && esDescendiente(nuevaCarpeta, id)) {
            return fallo("ciclo de carpetas: " + sanitiza(id))
        }
        val carpetaVieja = bm?.carpeta ?: (cf as Carpeta).padre
        val dest = orden.getOrPut(nuevaCarpeta) { ArrayList() }
        if (carpetaVieja != nuevaCarpeta) {
            quitarDeOrden(id, carpetaVieja)
            if (bm != null) {
                items[id] = bm.copy(carpeta = nuevaCarpeta)
            } else {
                carpetas[id] = (cf as Carpeta).copy(padre = nuevaCarpeta)
            }
        } else {
            val pos = dest.indexOf(id)
            if (pos >= 0) dest.removeAt(pos)
        }
        val i = if (indice < 0) 0 else if (indice > dest.size) dest.size else indice
        dest.add(i, id)
        return Outcome.success(Unit)
    }

    /** Borra un marcador (desfijandolo) o una carpeta ya vacia. */
    fun delete(id: String): Outcome<Unit> {
        val bm = items[id]
        val cf = carpetas[id]
        if (bm == null && cf == null) return fallo("elemento no existe: " + sanitiza(id))
        if (cf != null && id == "root") return fallo("carpeta reservada: root")
        if (cf != null) {
            val hijosCf = orden[id]
            if (hijosCf != null && hijosCf.isNotEmpty()) {
                return fallo("carpeta no vacia: " + sanitiza(id))
            }
            quitarDeOrden(id, cf.padre)
            carpetas.remove(id)
            orden.remove(id)
            return Outcome.success(Unit)
        }
        val marcador = bm ?: return fallo("elemento no existe: " + sanitiza(id))
        quitarDeOrden(id, marcador.carpeta)
        items.remove(id)
        val pos = pins.indexOf(id)
        if (pos >= 0) pins.removeAt(pos)
        return Outcome.success(Unit)
    }

    /** Ids de los hijos de una carpeta, en orden. */
    fun hijos(carpeta: String): Outcome<List<String>> {
        if (!carpetas.containsKey(carpeta)) return fallo("carpeta no existe: " + sanitiza(carpeta))
        return Outcome.success(ArrayList(orden[carpeta] ?: ArrayList()))
    }

    /** Marcadores cuyo titulo o url contiene la consulta, sin mayusculas. */
    fun buscar(q: String): List<Bookmark> {
        val nq = q.lowercase()
        val res = ArrayList<Bookmark>()
        for (bm in items.values) {
            if (bm.titulo.lowercase().contains(nq) || bm.url.lowercase().contains(nq)) {
                res.add(bm)
            }
        }
        return res
    }

    /** Fija un marcador como acceso rapido (sin duplicados). */
    fun pin(id: String): Outcome<Unit> {
        val bm = items[id]
        if (bm == null && carpetas.containsKey(id)) {
            return fallo("solo se fijan marcadores: " + sanitiza(id))
        }
        if (bm == null) return fallo("marcador no existe: " + sanitiza(id))
        if (pins.contains(id)) return fallo("marcador ya fijado: " + sanitiza(id))
        pins.add(id)
        return Outcome.success(Unit)
    }

    /** Quita un marcador de los accesos rapidos. */
    fun unpin(id: String): Outcome<Unit> {
        val bm = items[id]
        if (bm == null) return fallo("marcador no existe: " + sanitiza(id))
        val pos = pins.indexOf(id)
        if (pos < 0) return fallo("marcador no fijado: " + sanitiza(id))
        pins.removeAt(pos)
        return Outcome.success(Unit)
    }

    /** Marcadores fijados, en orden de fijado. */
    fun accesosRapidos(): List<Bookmark> = pins.mapNotNull { items[it] }

    /** Texto tabulado por perfil (cabecera nia-bookmarks 1). */
    fun exportar(): String {
        val lineas = ArrayList<String>()
        lineas.add("nia-bookmarks\t1")
        for (cf in carpetas.values) {
            lineas.add("C\t" + escapa(cf.id) + "\t" + escapa(cf.padre) + "\t" + escapa(cf.nombre))
        }
        for (bm in items.values) {
            lineas.add(
                "M\t" + escapa(bm.id) + "\t" + escapa(bm.carpeta) + "\t" +
                    escapa(bm.titulo) + "\t" + escapa(bm.url)
            )
        }
        for ((carpeta, lista) in orden) {
            if (lista.isEmpty()) continue
            val partes = ArrayList<String>()
            partes.add("O")
            partes.add(escapa(carpeta))
            for (hijo in lista) partes.add(escapa(hijo))
            lineas.add(partes.joinToString("\t"))
        }
        if (pins.isNotEmpty()) {
            val partes = ArrayList<String>()
            partes.add("P")
            for (p in pins) partes.add(escapa(p))
            lineas.add(partes.joinToString("\t"))
        }
        val sb = StringBuilder()
        for (linea in lineas) {
            sb.append(linea)
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Importa un texto exportado: parsea TODO a temporales antes del
     * swap (un fallo no toca el estado vivo), valida ids, padres ya
     * declarados, escapes, orden completo por carpeta y pins; los
     * contadores de ids se recomputan del import.
     */
    fun importar(texto: String): Outcome<Unit> {
        val lineas = texto.split("\n")
        if (lineas[0] != "nia-bookmarks\t1") return fallo("encabezado no valido")
        val tItems = LinkedHashMap<String, Bookmark>()
        val tCarpetas = LinkedHashMap<String, Carpeta>()
        tCarpetas["root"] = Carpeta("root", "Raiz", "root")
        val tOrden = LinkedHashMap<String, MutableList<String>>()
        tOrden["root"] = ArrayList()
        val tPins = ArrayList<String>()
        val hijosReales = LinkedHashMap<String, MutableList<String>>()
        var rootDeclarada = false
        for (li in 1 until lineas.size) {
            val linea = lineas[li]
            if (linea.isEmpty()) continue
            val partes = linea.split("\t")
            val tipo = partes[0]
            if (tipo == "C") {
                if (partes.size != 4) return fallo("campos invalidos en linea " + (li + 1))
                val rId = desescapa(partes[1])
                val rPadre = desescapa(partes[2])
                val rNombre = desescapa(partes[3])
                if (!rId.second || !rPadre.second || !rNombre.second) {
                    return fallo("escape invalido en linea " + (li + 1))
                }
                val id = rId.first
                val padre = rPadre.first
                val nombre = rNombre.first
                if (!ID_RE.matches(id)) return fallo("id no valida: " + sanitiza(id))
                if (id == "root") {
                    if (rootDeclarada) return fallo("id repetida: root")
                    if (padre != "root") return fallo("carpeta reservada: root")
                    if (!tituloOk(nombre)) return fallo("nombre no valido: " + sanitiza(nombre))
                    tCarpetas["root"] = Carpeta("root", nombre, "root")
                    rootDeclarada = true
                    continue
                }
                if (tCarpetas.containsKey(id) || tItems.containsKey(id)) {
                    return fallo("id repetida: " + sanitiza(id))
                }
                if (!tCarpetas.containsKey(padre)) {
                    return fallo("carpeta padre no existe: " + sanitiza(padre))
                }
                if (!tituloOk(nombre)) return fallo("nombre no valido: " + sanitiza(nombre))
                tCarpetas[id] = Carpeta(id, nombre, padre)
                if (!tOrden.containsKey(id)) tOrden[id] = ArrayList()
                apuntar(hijosReales, padre, id)
            } else if (tipo == "M") {
                if (partes.size != 5) return fallo("campos invalidos en linea " + (li + 1))
                val rId = desescapa(partes[1])
                val rCarpeta = desescapa(partes[2])
                val rTitulo = desescapa(partes[3])
                val rUrl = desescapa(partes[4])
                if (!rId.second || !rCarpeta.second || !rTitulo.second || !rUrl.second) {
                    return fallo("escape invalido en linea " + (li + 1))
                }
                val id = rId.first
                val carpeta = rCarpeta.first
                val titulo = rTitulo.first
                val url = rUrl.first
                if (!ID_RE.matches(id)) return fallo("id no valida: " + sanitiza(id))
                if (tCarpetas.containsKey(id) || tItems.containsKey(id)) {
                    return fallo("id repetida: " + sanitiza(id))
                }
                if (!tCarpetas.containsKey(carpeta)) {
                    return fallo("carpeta no existe: " + sanitiza(carpeta))
                }
                if (!tituloOk(titulo)) return fallo("titulo no valido: " + sanitiza(titulo))
                if (!URL_RE.matches(url)) return fallo("url no valida: " + sanitiza(url))
                tItems[id] = Bookmark(id, titulo, url, carpeta)
                apuntar(hijosReales, carpeta, id)
            } else if (tipo == "O") {
                if (partes.size < 2) return fallo("campos invalidos en linea " + (li + 1))
                val rCarpeta = desescapa(partes[1])
                if (!rCarpeta.second) return fallo("escape invalido en linea " + (li + 1))
                val carpeta = rCarpeta.first
                if (!tCarpetas.containsKey(carpeta)) {
                    return fallo("carpeta no existe: " + sanitiza(carpeta))
                }
                val hijos = ArrayList<String>()
                for (k in 2 until partes.size) {
                    val rH = desescapa(partes[k])
                    if (!rH.second) return fallo("escape invalido en linea " + (li + 1))
                    val h = rH.first
                    if (!tItems.containsKey(h) && !tCarpetas.containsKey(h)) {
                        return fallo("hijo no existe: " + sanitiza(h))
                    }
                    if (hijos.contains(h)) return fallo("hijo repetido: " + sanitiza(h))
                    val bmH = tItems[h]
                    val cfH = tCarpetas[h]
                    if (bmH != null && bmH.carpeta != carpeta) {
                        return fallo("hijo ajeno a la carpeta: " + sanitiza(h))
                    }
                    if (cfH != null && cfH.padre != carpeta) {
                        return fallo("hijo ajeno a la carpeta: " + sanitiza(h))
                    }
                    hijos.add(h)
                }
                tOrden[carpeta] = hijos
            } else if (tipo == "P") {
                if (partes.size < 2) return fallo("campos invalidos en linea " + (li + 1))
                for (k in 1 until partes.size) {
                    val rP = desescapa(partes[k])
                    if (!rP.second) return fallo("escape invalido en linea " + (li + 1))
                    val p = rP.first
                    if (tCarpetas.containsKey(p) || !tItems.containsKey(p)) {
                        return fallo("pin no valido: " + sanitiza(p))
                    }
                    if (tPins.contains(p)) return fallo("pin repetido: " + sanitiza(p))
                    tPins.add(p)
                }
            } else {
                return fallo("linea desconocida: " + sanitiza(tipo))
            }
        }
        for (idCarpeta in tCarpetas.keys) {
            val reales = (hijosReales[idCarpeta] ?: ArrayList()).sorted().joinToString(",")
            val declarados = (tOrden[idCarpeta] ?: ArrayList()).sorted().joinToString(",")
            if (reales != declarados) return fallo("orden incompleto: " + sanitiza(idCarpeta))
        }
        items.clear()
        items.putAll(tItems)
        carpetas.clear()
        carpetas.putAll(tCarpetas)
        orden.clear()
        orden.putAll(tOrden)
        pins.clear()
        pins.addAll(tPins)
        var maxBm = 0
        var maxCf = 0
        for (id in items.keys) {
            val m = BM_NUM_RE.find(id)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (n > maxBm) maxBm = n
            }
        }
        for (id in carpetas.keys) {
            val m = CF_NUM_RE.find(id)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (n > maxCf) maxCf = n
            }
        }
        nextBm = maxBm + 1
        nextCf = maxCf + 1
        return Outcome.success(Unit)
    }

    private fun quitarDeOrden(id: String, carpeta: String) {
        val lista = orden[carpeta] ?: return
        val pos = lista.indexOf(id)
        if (pos >= 0) lista.removeAt(pos)
    }

    private fun apuntar(
        hijosReales: LinkedHashMap<String, MutableList<String>>,
        carpeta: String,
        id: String
    ) {
        val lista = hijosReales.getOrPut(carpeta) { ArrayList() }
        lista.add(id)
    }

    private fun tituloOk(t: String): Boolean = t.isNotEmpty() && t.length <= MAX_CAMPO

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
        const val MAX_CAMPO = 64
        private val ID_RE = Regex("^[a-z0-9.-]{1,64}$")
        private val URL_RE = Regex("^https?://\\S+$")
        private val BM_NUM_RE = Regex("^bm\\.(\\d+)$")
        private val CF_NUM_RE = Regex("^cf\\.(\\d+)$")
    }
}
