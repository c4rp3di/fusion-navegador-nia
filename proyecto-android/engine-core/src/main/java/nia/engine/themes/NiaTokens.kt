package nia.engine.themes

/**
 * T069 v1 (R0144, RES-0073, ADR-021): tokens del shell sobre el schema
 * cerrado de ThemeManager (T031 v1). Los SEIS colores semanticos del
 * tema (bg/surface/fg/accent/warn/error en #rrggbb) son la unica fuente
 * de color del shell: aqui se resuelven por modo (claro/oscuro/auto, el
 * auto sigue al uiMode del sistema), se derivan los pares de rol y se
 * verifica el contraste AA (WCAG 2.1) de los temas builtin; las
 * familias NO color (tipografia, espaciado, radios y elevacion) son
 * escalas fijas que el shell consume directamente. Sin dependencias y
 * sin UI: los componentes de app/ui solo leen valores.
 */
object NiaTokens {

    /** Modo de tema del shell (ADR-021): AUTO sigue al sistema. */
    enum class Modo { CLARO, OSCURO, AUTO }

    /** Tokens de color resueltos: los seis del schema, ya en modo activo. */
    data class Colores(
        val bg: String,
        val surface: String,
        val fg: String,
        val accent: String,
        val warn: String,
        val error: String
    )

    /** Rol tipografico: escala en sp con peso por rol. */
    data class Rol(val nombre: String, val sp: Int, val negrita: Boolean)

    /** Familia tipografica del shell (descendente, 5 roles). */
    val TIPOGRAFIA: List<Rol> = listOf(
        Rol("display", 24, true),
        Rol("titulo", 20, true),
        Rol("seccion", 16, true),
        Rol("cuerpo", 14, false),
        Rol("nota", 12, false)
    )

    /** Escala de espaciado en dp (paso 4, ascendente). */
    val ESPACIADO: List<Int> = listOf(2, 4, 8, 12, 16, 24, 32)

    /** Radios de esquina en dp (ascendente). */
    val RADIOS: List<Int> = listOf(2, 4, 8, 12, 16)

    /** Niveles de elevacion en dp (ascendente). */
    val ELEVACION: List<Int> = listOf(0, 2, 4, 8)

    /** Umbrales AA de WCAG 2.1: texto normal y componentes esenciales. */
    const val AA_TEXTO = 4.5
    const val AA_COMPONENTE = 3.0

    /** Resuelve el modo efectivo: AUTO sigue al uiMode del sistema. */
    fun resolverModo(modo: Modo, sistemaOscuro: Boolean): Modo =
        if (modo == Modo.AUTO) {
            if (sistemaOscuro) Modo.OSCURO else Modo.CLARO
        } else {
            modo
        }

    /** Tokens de color del builtin segun el modo resuelto. */
    fun builtin(modo: Modo, sistemaOscuro: Boolean): Colores =
        coloresDe(
            if (resolverModo(modo, sistemaOscuro) == Modo.OSCURO) ThemeManager.OSCURO
            else ThemeManager.CLARO
        )

    /** Tokens de color del tema ACTIVO del manager (custom instalado). */
    fun delActivo(manager: ThemeManager): Colores = coloresDe(manager.active)

    /** Los seis colores de un tema (orden del schema, suelo claro). */
    fun coloresDe(tema: Theme): Colores = Colores(
        color(tema, "bg"),
        color(tema, "surface"),
        color(tema, "fg"),
        color(tema, "accent"),
        color(tema, "warn"),
        color(tema, "error")
    )

    /** Color del schema con suelo del builtin claro. */
    private fun color(tema: Theme, clave: String): String =
        tema.colors[clave] ?: ThemeManager.CLARO.colors[clave] ?: "#000000"

    /** Luminancia relativa WCAG 2.1 de un color #rrggbb. */
    fun luminancia(hex: String): Double {
        val canales = canales(hex)
        return 0.2126 * lineal(canales[0]) +
            0.7152 * lineal(canales[1]) +
            0.0722 * lineal(canales[2])
    }

    /** Ratio de contraste WCAG 2.1 entre dos colores (rango 1..21). */
    fun contraste(a: String, b: String): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        val mayor = maxOf(la, lb)
        val menor = minOf(la, lb)
        return (mayor + 0.05) / (menor + 0.05)
    }

    /** Par de rol del shell con su umbral AA (ADR-021). */
    data class Par(
        val nombre: String,
        val frente: String,
        val fondo: String,
        val umbral: Double
    ) {
        fun ratio(): Double = contraste(frente, fondo)

        fun cumple(): Boolean = ratio() >= umbral
    }

    /** Pares del catalogo: texto sobre superficie y fondo (4.5:1) y
     * accent/warn/error como componentes esenciales (3:1). */
    fun pares(c: Colores): List<Par> = listOf(
        Par("fg/superficie", c.fg, c.surface, AA_TEXTO),
        Par("fg/fondo", c.fg, c.bg, AA_TEXTO),
        Par("accent/fondo", c.accent, c.bg, AA_COMPONENTE),
        Par("warn/fondo", c.warn, c.bg, AA_COMPONENTE),
        Par("error/fondo", c.error, c.bg, AA_COMPONENTE)
    )

    /** Verdadero si TODOS los pares del catalogo cumplen AA. */
    fun esAA(c: Colores): Boolean = pares(c).all { it.cumple() }

    /** Catalogo comprobable: cada token del shell con nombre estable. */
    fun catalogo(c: Colores): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("color.bg" to c.bg)
        out.add("color.surface" to c.surface)
        out.add("color.fg" to c.fg)
        out.add("color.accent" to c.accent)
        out.add("color.warn" to c.warn)
        out.add("color.error" to c.error)
        for (rol in TIPOGRAFIA) {
            out.add("tipo." + rol.nombre to rol.sp.toString() + "sp")
        }
        for ((i, e) in ESPACIADO.withIndex()) {
            out.add("espacio." + i to e.toString() + "dp")
        }
        for ((i, r) in RADIOS.withIndex()) {
            out.add("radio." + i to r.toString() + "dp")
        }
        for ((i, n) in ELEVACION.withIndex()) {
            out.add("elevacion." + i to n.toString() + "dp")
        }
        return out
    }

    private fun canales(hex: String): IntArray {
        val limpio = hex.removePrefix("#")
        return intArrayOf(
            Integer.parseInt(limpio.substring(0, 2), 16),
            Integer.parseInt(limpio.substring(2, 4), 16),
            Integer.parseInt(limpio.substring(4, 6), 16)
        )
    }

    private fun lineal(canal: Int): Double {
        val c = canal / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
}
