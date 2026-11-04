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
 *
 * T035 v1 (R0088, RES-0049): botones Abrir y Guardar con el Storage Access
 * Framework, sin permisos de almacenamiento. Abrir (ACTION_OPEN_DOCUMENT
 * text/html) lee el archivo y PaintView lo parsea con el motor (los <style>
 * del documento se suman al CSS de la demo); Guardar (ACTION_CREATE_DOCUMENT)
 * escribe el HTML fuente cargado, no el DOM mutado (exclusion v1).
 */
class MainActivity : Activity() {

    private lateinit var frame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        frame.addView(PaintView(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val devtools = Button(this)
        devtools.text = "DevTools"
        val demo = Button(this)
        demo.text = "Fetch demo"
        val abrir = Button(this)
        abrir.text = "Abrir"
        val guardar = Button(this)
        guardar.text = "Guardar"
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        buttons.addView(devtools)
        buttons.addView(demo)
        buttons.addView(abrir)
        buttons.addView(guardar)
        frame.addView(buttons, params)
        devtools.setOnClickListener {
            startActivity(Intent(this, DevToolsActivity::class.java))
        }
        demo.setOnClickListener {
            startActivity(Intent(this, FetchDemoActivity::class.java))
        }
        abrir.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("text/html")
            startActivityForResult(i, REQUEST_ABRIR)
        }
        guardar.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.setType("text/html")
            i.putExtra(Intent.EXTRA_TITLE, "pagina.html")
            startActivityForResult(i, REQUEST_GUARDAR)
        }
        setContentView(frame)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        val view = frame.getChildAt(0) as? PaintView ?: return
        if (requestCode == REQUEST_ABRIR && resultCode == RESULT_OK) {
            val html = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: java.io.IOException) {
                null
            } ?: return
            view.load(html)
        }
        if (requestCode == REQUEST_GUARDAR && resultCode == RESULT_OK) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(view.currentHtml()) }
            } catch (e: java.io.IOException) {
            }
        }
    }

    companion object {
        private const val REQUEST_ABRIR = 41
        private const val REQUEST_GUARDAR = 42
    }
}
