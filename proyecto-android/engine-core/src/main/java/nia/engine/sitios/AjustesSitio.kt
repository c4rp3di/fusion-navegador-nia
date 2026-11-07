package nia.engine.sitios

import java.io.File
import java.util.Locale

/**
 * T080 v1 (RES-0097): preferencias POR SITIO: zoom visual, tamano
 * de texto y modo escritorio. La identidad del sitio es la clave
 * de ORIGEN (scheme://host:puerto efectivo) que calcula el shell
 * con UrlParser/Origin del motor: NUNCA la URL completa. Zoom y
 * texto son INDEPENDIENTES (el zoom re-maqueta el viewport y el
 * texto escala la base tipografica del CSS) y el modo escritorio
 * cambia el viewport logico de base (720px en vez de 360px) con
 * el UA real del transporte SIN TOCAR: no se simulan capacidades
 * ausentes. Limites honestos: zoom 0.5..3.0, texto 0.75..1.5,
 * MAX_SITIOS entradas con expulsion del mas viejo (re-fijar
 * rejuvenece) y los defectos JAMAS se persisten (fijar un
 * defecto borra la entrada).
 */
data class PrefSitio(
    val zoom: Double = 1.0,
    val texto: Double = 1.0,
    val escritorio: Boolean = false
)

/** Constantes y validacion pura de las preferencias por sitio. */
object AjustesSitio {
    const val ZOOM_MIN = 0.5
    const val ZOOM_MAX = 3.0
    const val TEXTO_MIN = 0.75
    const val TEXTO_MAX = 1.5
    const val MAX_SITIOS = 100
    const val MAX_CLAVE = 200

    /** Zoom clampado al rango honesto del sitio. */
    fun limitarZoom(z: Double): Double = z.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** Tamano de texto clampado al rango honesto del sitio. */
    fun limitarTexto(t: Double): Double = t.coerceIn(TEXTO_MIN, TEXTO_MAX)

    /** Cierto si la preferencia es la de fabrica. */
    fun esDefecto(p: PrefSitio): Boolean = p == PrefSitio()

    /** Viewport logico de base: 720 en escritorio, 360 en movil. */
    fun viewportBase(escritorio: Boolean): Double = if (escritorio) 720.0 else 360.0

    /** Serializacion determinista (punto decimal, Locale.US). */
    internal fun numero(x: Double): String = String.format(Locale.US, "%.3f", x)
}

/**
 * Registro de preferencias por sitio con persistencia por perfil
 * (patron de SettingsManager): export/import tabulado version 1
 * ("nia-sitios<TAB>1" + lineas "S<TAB>clave<TAB>zoom<TAB>texto
 * <TAB>escritorio"), TODO-o-NADA al importar (un fallo no toca el
 * estado vivo), escape de tab/backslash/saltos en la clave y
 * archivo opcional: sin disco el estado sigue vivo en memoria.
 */
class SitiosManager(private val archivo: File? = null) {

    private val prefs = LinkedHashMap<String, PrefSitio>()

    /** Cuantos sitios tienen preferencias guardadas. */
    fun total(): Int = prefs.size

    /** Las claves guardadas, en orden de uso (la mas vieja primero). */
    fun claves(): List<String> = ArrayList(prefs.keys)

    /** Preferencia del sitio o la de fabrica si no hay ninguna. */
    fun obtener(clave: String): PrefSitio = prefs[clave] ?: PrefSitio()

    /**
     * Guarda la preferencia del sitio (clamped); un defecto BORRA
     * la entrada (nunca se persisten defectos). Re-fijar rejuvenece
     * el sitio y al pasar de MAX_SITIOS se expulsa al mas viejo.
     */
    fun fijar(clave: String, pref: PrefSitio): Boolean {
        if (clave.isEmpty() || clave.length > AjustesSitio.MAX_CLAVE) return false
        val p = PrefSitio(
            AjustesSitio.limitarZoom(pref.zoom),
            AjustesSitio.limitarTexto(pref.texto),
            pref.escritorio
        )
        if (AjustesSitio.esDefecto(p)) {
            if (prefs.remove(clave) != null) guardar()
            return true
        }
        prefs.remove(clave)
        prefs[clave] = p
        while (prefs.size > AjustesSitio.MAX_SITIOS) {
            prefs.remove(prefs.keys.first())
        }
        guardar()
        return true
    }

    /** Borra las preferencias del sitio; false si no habia. */
    fun borrar(clave: String): Boolean {
        val had = prefs.remove(clave) != null
        if (had) guardar()
        return had
    }

    /** Borra TODO; devuelve cuantos sitios habia. */
    fun borrarTodo(): Int {
        val n = prefs.size
        if (n > 0) {
            prefs.clear()
            guardar()
        }
        return n
    }

    /** Export tabulado version 1 (solo sitios con preferencias). */
    fun exportar(): String {
        val sb = StringBuilder()
        sb.append("nia-sitios\t1")
        for ((clave, p) in prefs) {
            sb.append('\n')
            sb.append("S\t")
            sb.append(escapa(clave))
            sb.append('\t')
            sb.append(AjustesSitio.numero(p.zoom))
            sb.append('\t')
            sb.append(AjustesSitio.numero(p.texto))
            sb.append('\t')
            sb.append(if (p.escritorio) "true" else "false")
        }
        return sb.toString()
    }

    /**
     * Importa un export TODO-o-NADA: cabecera, escape, rango,
     * duplicados o escritorio invalido dejan el estado vivo
     * intacto; aplica solo si TODO parsea.
     */
    fun importar(texto: String): Boolean {
        val lineas = texto.split('\n')
        if (lineas.isEmpty() || lineas[0] != "nia-sitios\t1") return false
        val tmp = LinkedHashMap<String, PrefSitio>()
        for (i in 1 until lineas.size) {
            val partes = lineas[i].split('\t')
            if (partes.size != 5 || partes[0] != "S") return false
            val rClave = desescapa(partes[1])
            if (!rClave.second) return false
            val clave = rClave.first
            if (tmp.containsKey(clave)) return false
            if (clave.isEmpty() || clave.length > AjustesSitio.MAX_CLAVE) return false
            val zoom = partes[2].toDoubleOrNull()
            val textoV = partes[3].toDoubleOrNull()
            if (zoom == null || textoV == null) return false
            if (zoom.isNaN() || zoom.isInfinite() || textoV.isNaN() || textoV.isInfinite()) return false
            if (zoom < AjustesSitio.ZOOM_MIN || zoom > AjustesSitio.ZOOM_MAX) return false
            if (textoV < AjustesSitio.TEXTO_MIN || textoV > AjustesSitio.TEXTO_MAX) return false
            if (partes[4] != "true" && partes[4] != "false") return false
            tmp[clave] = PrefSitio(zoom, textoV, partes[4] == "true")
        }
        prefs.clear()
        prefs.putAll(tmp)
        guardar()
        return true
    }

    /** Recarga el archivo: un corrupto se ignora (quedan defectos). */
    fun recargar(): Boolean {
        val texto = leer() ?: return false
        return importar(texto)
    }

    private fun guardar() {
        val f = archivo ?: return
        try {
            f.writeText(exportar())
        } catch (e: Exception) {
            // Sin disco: las preferencias siguen vivas en memoria.
        }
    }

    private fun leer(): String? {
        val f = archivo ?: return null
        if (!f.isFile) return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

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
}
