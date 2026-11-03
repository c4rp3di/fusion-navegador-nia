package app.nia.navegador

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * NIA (T016, R0021): la actividad delega en PaintView, que renderiza la
 * escena del motor propio (HTML -> CSS -> layout -> display list) y resuelve
 * toques con hitTest del motor. Sin WebView y sin layout XML.
 *
 * R0041 (T017 v2): boton DevTools que abre DevToolsActivity, el panel del
 * inspector propio (arbol con nodeIds, estilos computados y causas).
 *
 * R0062 (T027 v2): boton Fetch demo que abre FetchDemoActivity, la demo
 * interactiva de forms/fetch con cancelacion.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.addView(PaintView(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val devtools = Button(this)
        devtools.text = "DevTools"
        val demo = Button(this)
        demo.text = "Fetch demo"
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        buttons.addView(devtools)
        buttons.addView(demo)
        frame.addView(buttons, params)
        devtools.setOnClickListener {
            startActivity(Intent(this, DevToolsActivity::class.java))
        }
        demo.setOnClickListener {
            startActivity(Intent(this, FetchDemoActivity::class.java))
        }
        setContentView(frame)
    }
}
