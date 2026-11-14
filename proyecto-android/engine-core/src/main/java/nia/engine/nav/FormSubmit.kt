package nia.engine.nav

import nia.engine.dom.DomDocument
import nia.engine.dom.DomException
import nia.engine.dom.DomHandle
import nia.engine.html.DomNode
import nia.engine.url.Url
import nia.engine.url.UrlParseException
import nia.engine.url.UrlParser

/**
 * Submit de formularios (T111 v9 / TK-111-8, R0453, RES-0177): calculo
 * PURO del envio de un <form> del documento vivo. El shell lo invoca
 * desde el menu de toque largo (mismo camino explicito que los
 * enlaces, R0189) y navega con NavController.enviarFormulario; el
 * motor JAMAS navega por su cuenta.
 *
 * Semantica v1: action resuelto contra la URL de la pagina (absoluta
 * http/https, //host, /ruta, ?query y relativa con . y .., espejo de
 * resolveUrl); method GET (defecto y para cualquier valor no POST) o
 * POST; controles con name no vacio y sin disabled en ORDEN
 * DOCUMENTAL: input de texto y derivados con el value del atributo
 * (vacios incluidos), checkbox/radio SOLO con checked (value o "on"),
 * textarea con su TEXTO PROFUNDO; codificacion
 * application/x-www-form-urlencoded sobre UTF-8 (espacio como +,
 * reservados %XX mayusculas). GET: la query del action se REEMPLAZA
 * y el fragmento se conserva; POST: la url queda intacta y el cuerpo
 * viaja aparte.
 *
 * Exclusiones v1: enctype distinto de application/x-www-form-urlencoded
 * (multipart/form-data, text/plain) NO envia (null honesto; multipart
 * en v2 con JsFetchApi); select sin recoger; input type=file/submit/
 * button/reset/image fuera; sin <base> para el action (se resuelve
 * contra la URL de la pagina); sin navegacion desde JS (form.submit()
 * de JsDomBindings sigue siendo SOLO el evento submit: la navegacion
 * programatica exige re-entrancia con el event loop de documento, v2).
 */
object FormSubmit {

    /** Envio calculado: metodo, url final y cuerpo (null en GET). */
    data class FormEnvio(val metodo: String, val url: String, val cuerpo: String?)

    /**
     * Prepara el envio del formulario; null si no se puede enviar
     * (url de pagina invalida, action solo #fragmento, enctype no
     * soportado).
     */
    fun preparar(doc: DomDocument, form: DomHandle, urlPagina: String): FormEnvio? {
        val page: Url = try {
            UrlParser.parse(urlPagina)
        } catch (e: UrlParseException) {
            return null
        }
        val action = atributo(doc, form, "action")
        val destino: String
        if (action == null || action.trim().isEmpty()) {
            destino = urlPagina
        } else {
            destino = resolver(page, action.trim()) ?: return null
        }
        val m = atributo(doc, form, "method")?.trim()?.uppercase()
        val metodo = if (m == "POST") "POST" else "GET"
        val enctype = atributo(doc, form, "enctype")?.trim()?.lowercase()
        if (enctype != null && enctype != "application/x-www-form-urlencoded") {
            return null
        }
        val pares = ArrayList<String>()
        recoger(doc, form, pares)
        val datos = pares.joinToString("&")
        return if (metodo == "GET") {
            FormEnvio("GET", conQuery(destino, datos), null)
        } else {
            FormEnvio("POST", destino, datos)
        }
    }

    /** Atributo de un elemento; null si el nodo no es Element. */
    private fun atributo(doc: DomDocument, h: DomHandle, nombre: String): String? {
        val n = doc.nodeOf(h)
        return (n as? DomNode.Element)?.attributes?.get(nombre)
    }

    /**
     * Resolucion del action contra la pagina (espejo de resolveUrl de
     * NavController): absoluta, #fragmento (null), //host, ?query,
     * /ruta y relativa con . y .. normalizados al parsear.
     */
    private fun resolver(page: Url, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.startsWith("#")) return null
        val origen = page.scheme + "://" + page.host +
            (if (page.port != null) ":" + page.port else "")
        if (t.startsWith("//")) return page.scheme + ":" + t
        if (t.startsWith("?")) return origen + page.path + t
        if (t.startsWith("/")) return origen + t
        val corte = page.path.lastIndexOf('/')
        val dir = if (corte <= 0) "/" else page.path.substring(0, corte + 1)
        return try {
            UrlParser.parse(origen + dir + t).toString()
        } catch (e: UrlParseException) {
            null
        }
    }

    /** Recoge los controles con name del subarbol en orden documental. */
    private fun recoger(doc: DomDocument, h: DomHandle, pares: MutableList<String>) {
        for (k in hijosDe(doc, h)) {
            val n = doc.nodeOf(k)
            if (n is DomNode.Element) {
                val tag = n.name.lowercase()
                val nombre = n.attributes["name"]
                if (!nombre.isNullOrEmpty() && !n.attributes.containsKey("disabled")) {
                    val valor: String? = when (tag) {
                        "input" -> valorInput(n)
                        "textarea" -> textoProfundo(doc, k)
                        else -> null
                    }
                    if (valor != null) {
                        pares.add(codificar(nombre) + "=" + codificar(valor))
                    }
                }
            }
            recoger(doc, k, pares)
        }
    }

    /** Valor de un input: texto y derivados con value (defecto ""),
     * checkbox/radio SOLO con checked (value o "on"); los que no
     * envian (submit/button/reset/file/image) devuelven null. */
    private fun valorInput(n: DomNode.Element): String? {
        val tipo = (n.attributes["type"] ?: "text").trim().lowercase()
        if (tipo == "checkbox" || tipo == "radio") {
            if (!n.attributes.containsKey("checked")) return null
            val v = n.attributes["value"]
            if (v != null) return v
            return "on"
        }
        if (tipo == "submit" || tipo == "button" || tipo == "reset" || tipo == "file" || tipo == "image") {
            return null
        }
        val v = n.attributes["value"]
        if (v != null) return v
        return ""
    }

    /** Texto profundo: la data de TODOS los Text del subarbol. */
    private fun textoProfundo(doc: DomDocument, h: DomHandle): String {
        val sb = StringBuilder()
        val n = doc.nodeOf(h)
        if (n is DomNode.Text) {
            sb.append(n.data)
        }
        for (k in hijosDe(doc, h)) {
            sb.append(textoProfundo(doc, k))
        }
        return sb.toString()
    }

    /** Hijos de un handle; vacio si el nodo no admite hijos. */
    private fun hijosDe(doc: DomDocument, h: DomHandle): List<DomHandle> {
        return try {
            doc.childrenOf(h)
        } catch (e: DomException) {
            emptyList<DomHandle>()
        }
    }

    /** application/x-www-form-urlencoded: UTF-8, espacio como +,
     * sin escapar A-Za-z0-9 - . _ ~ y el resto %XX mayusculas. */
    private fun codificar(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57) || c == 45 || c == 46 || c == 95 || c == 126) {
                sb.append(c.toChar())
            } else if (c == 32) {
                sb.append('+')
            } else {
                sb.append('%')
                sb.append(digito(c / 16))
                sb.append(digito(c % 16))
            }
        }
        return sb.toString()
    }

    /** Digito hexadecimal mayuscula. */
    private fun digito(v: Int): Char {
        if (v < 10) return (48 + v).toChar()
        return (55 + v).toChar()
    }

    /** GET: la query del destino se REEMPLAZA por los datos y el
     * fragmento se conserva. */
    private fun conQuery(base: String, datos: String): String {
        val q = base.indexOf('?')
        val hash = base.indexOf('#')
        var corte = base.length
        if (q >= 0 && (hash < 0 || q < hash)) corte = q
        else if (hash >= 0) corte = hash
        val sinQuery = base.substring(0, corte)
        val frag = if (hash >= 0) base.substring(hash) else ""
        return sinQuery + "?" + datos + frag
    }
}
