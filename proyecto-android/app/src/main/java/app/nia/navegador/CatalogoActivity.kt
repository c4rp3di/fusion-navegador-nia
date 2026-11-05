package app.nia.navegador

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.nia.navegador.ui.NiaCard
import app.nia.navegador.ui.NiaEmptyState
import app.nia.navegador.ui.NiaListItem
import app.nia.navegador.ui.NiaProgressBar
import app.nia.navegador.ui.NiaSection
import app.nia.navegador.ui.NiaToggle
import app.nia.navegador.ui.NiaUi
import nia.engine.themes.NiaTokens

/**
 * T069 v1 (R0144, RES-0073, ADR-021): catalogo comprobable de los
 * componentes propios en app/ui con los tokens del shell: cada pieza
 * consume los SEIS colores del schema via NiaTokens y las escalas
 * fijas (tipografia, espaciado, radios, elevacion); el modo AUTO
 * sigue al uiMode del sistema y el boton alterna claro/oscuro para
 * verificar los pares AA en vivo. Catalogo v1: NiaSection, NiaCard,
 * NiaListItem, NiaToggle, NiaEmptyState y NiaProgressBar; los
 * overlays y los atados a navegacion (NiaMenu, BottomSheet, Dialog,
 * Snackbar, UrlBar, TabStrip, ErrorPage) llegan con el shell de
 * navegacion (T071/T072, ADR-022).
 */
class CatalogoActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private var fijado: NiaTokens.Colores? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        setContentView(scroll)
        scroll.addView(
            raiz,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pintar()
    }

    /** Reconstruye el catalogo: modo AUTO del sistema o el fijado. */
    private fun pintar() {
        val colores = fijado ?: NiaUi.colores(this, NiaTokens.Modo.AUTO)
        raiz.removeAllViews()
        raiz.setBackgroundColor(NiaUi.color(colores.bg))
        val margen = NiaUi.dp(this, NiaTokens.ESPACIADO[4])
        raiz.setPadding(margen, margen, margen, margen)

        val modoBtn = Button(this)
        modoBtn.text = "Alternar claro/oscuro"
        modoBtn.setOnClickListener {
            val oscuro = colores.bg == NiaTokens.builtin(NiaTokens.Modo.OSCURO, true).bg
            fijado = if (oscuro) NiaTokens.builtin(NiaTokens.Modo.CLARO, false)
            else NiaTokens.builtin(NiaTokens.Modo.OSCURO, true)
            pintar()
        }
        raiz.addView(modoBtn)

        val paresAA = TextView(this)
        val builder = StringBuilder()
        for (par in NiaTokens.pares(colores)) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(par.nombre).append(' ')
                .append(String.format("%.2f", par.ratio())).append(' ')
                .append(if (par.cumple()) "AA" else "FALLO")
        }
        builder.append('\n').append("tokens del catalogo: ")
            .append(NiaTokens.catalogo(colores).size)
        paresAA.text = builder.toString()
        paresAA.textSize = NiaTokens.TIPOGRAFIA[4].sp.toFloat()
        paresAA.setTextColor(NiaUi.color(colores.fg))
        raiz.addView(paresAA)

        val seccion = NiaSection(this, colores, "Componentes")
        val card = NiaCard(this, colores)
        val dentro = LinearLayout(this)
        dentro.orientation = LinearLayout.VERTICAL
        val espacio = NiaUi.dp(this, NiaTokens.ESPACIADO[3])
        dentro.setPadding(espacio, espacio, espacio, espacio)
        dentro.addView(
            NiaListItem(this, colores, "Marcador diario", "https://ejemplo.com/diario")
        )
        dentro.addView(NiaToggle(this, colores))
        card.addView(
            dentro,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        seccion.contenedor.addView(card)
        raiz.addView(seccion)

        val barra = NiaProgressBar(this, colores)
        barra.setFraccion(0.6f)
        raiz.addView(
            barra,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NiaUi.dp(this, NiaTokens.ESPACIADO[1])
            )
        )

        val vacio = NiaSection(this, colores, "Estado vacio")
        vacio.contenedor.addView(
            NiaEmptyState(this, colores, "o", "Sin nada aqui", "El catalogo crece con el shell")
        )
        raiz.addView(vacio)
    }
}
