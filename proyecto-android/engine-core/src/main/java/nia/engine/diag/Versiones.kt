package nia.engine.diag

/**
 * T107 v1 (RES-0101): versiones y componentes del navegador NIA.
 * TODO es metadata del ARTEFACTO INSTALADO: la version de la app la
 * lee el shell del PackageManager (real, nunca supuesta), el sha del
 * build viaja EMBEBIDO en el APK (asset build-info.txt que escribe el
 * workflow; un build local sin el asset declara "no disponible" en
 * vez de fingir) y los contratos traen su ContractsVersion real.
 * NADA de estado remoto: esta pieza no reporta nada que no este en
 * el binario que el usuario tiene instalado.
 */
data class Componente(
    val nombre: String,
    val estado: String,
    val version: String?
)

/**
 * Mapa de componentes de la aceptacion T107 (lexer/parser/DOM/CSS/
 * layout/paint/JS/debugger/fetch/cookies/storage/media/torrent/subs/
 * workers/matrices: 16 en total, 11 disponibles y 5 que aun no
 * existen) REVISADO A MANO: quien anade o retira un motor actualiza
 * esta lista y la REVISION. El estado es honesto: un motor que aun
 * no existe se declara NO_DISPONIBLE, nunca se disfraza.
 */
object Versiones {

    /** Revision manual del mapa de componentes (cronica del cambio). */
    const val REVISION = "R0215"

    const val DISPONIBLE = "disponible"
    const val NO_DISPONIBLE = "no disponible"

    fun componentes(): List<Componente> = listOf(
        Componente("lexer", DISPONIBLE, null),
        Componente("parser", DISPONIBLE, null),
        Componente("DOM", DISPONIBLE, null),
        Componente("CSS", DISPONIBLE, null),
        Componente("layout", DISPONIBLE, null),
        Componente("paint", DISPONIBLE, null),
        Componente("JS", DISPONIBLE, null),
        Componente("debugger", DISPONIBLE, null),
        Componente("fetch", DISPONIBLE, null),
        Componente("cookies", DISPONIBLE, null),
        Componente("storage", DISPONIBLE, null),
        Componente("media", NO_DISPONIBLE, null),
        Componente("torrent", NO_DISPONIBLE, null),
        Componente("subs", NO_DISPONIBLE, null),
        Componente("workers", NO_DISPONIBLE, null),
        Componente("matrices", NO_DISPONIBLE, null)
    )

    /** Texto del dialogo: version de app, sha del build, contracts y
     *  componentes con revision (4 + 16 = 20 lineas); TODO del
     *  artefacto, NADA remoto. */
    fun resumen(appVersion: String, gitSha: String?, contractsVersion: String): String {
        val lineas = ArrayList<String>()
        lineas.add("navegador " + appVersion)
        if (gitSha.isNullOrEmpty()) {
            lineas.add("build: no disponible (build local sin build-info)")
        } else {
            lineas.add("build: " + gitSha)
        }
        lineas.add("contracts: " + contractsVersion)
        lineas.add("revision componentes: " + REVISION)
        for (c in componentes()) {
            lineas.add(c.nombre + ": " + c.estado)
        }
        return lineas.joinToString("\n")
    }
}
