package app.nia.navegador

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import nia.contracts.CancellationToken
import nia.engine.dom.DomDocument
import nia.engine.js.JsFetchApi
import nia.engine.js.JsInterpreter
import nia.engine.js.JsLimits
import nia.engine.js.JsStr
import nia.engine.net.FetchRequest
import nia.engine.net.FetchResponse
import nia.engine.net.FetchTransport
import nia.engine.net.TransportResult

/**
 * T027 v2 (R0062, RES-0036): demo interactiva de forms y fetch JS
 * sobre los componentes VERIFICADOS del motor (T027 v1, run #207):
 * JsFetchApi (fetch y AbortController sobre FetchManager), los
 * bindings de formularios (T024) y las promises del interprete
 * (T023). Sin WebView y SIN red en el device: el transporte es un
 * fake determinista que hace eco de la peticion, como en los tests
 * del motor (JsFetchApiTest: demoSubmitFetchRender y
 * demoSubmitFetchCancelado son el molde).
 *
 * Cada pulsacion re-evalua el programa de la demo con el valor del
 * EditText en el value del input del DOM: "Enviar" cubre el flujo
 * submit, fetch y render; "Enviar y cancelar" cubre abort, submit y
 * catch AbortError (la aceptacion de T027: demo interactiva con
 * cancelacion).
 */
class FetchDemoActivity : Activity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 32, 24, 32)

        val query = EditText(this)
        query.hint = "consulta"
        query.setText("hola")
        root.addView(query, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val send = Button(this)
        send.text = "Enviar"
        val cancel = Button(this)
        cancel.text = "Enviar y cancelar"
        row.addView(send, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        output = TextView(this)
        output.typeface = Typeface.MONOSPACE
        output.textSize = 12f
        val scroll = ScrollView(this)
        scroll.addView(output, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)

        send.setOnClickListener { runDemo(query.text.toString(), false) }
        cancel.setOnClickListener { runDemo(query.text.toString(), true) }
    }

    /**
     * Ejecuta la demo de la aceptacion de T027: el abort sucede ANTES
     * del submit (la cancelacion observada descarta la respuesta, la
     * promise se rechaza con AbortError y la peticion no sale del
     * transporte, como en demoSubmitFetchCancelado).
     */
    private fun runDemo(query: String, abort: Boolean) {
        val doc = DomDocument.parse(
            "<form id=\"f\"><input id=\"q\" name=\"q\" value=\"" + escapeHtml(query) + "\"></form>"
        )
        val api = JsFetchApi(EchoTransport())
        val result = JsInterpreter.evaluateWithDom(demoProgram(abort), doc, JsLimits(), api)
        val text = when (val v = result.value) {
            is JsStr -> v.value
            else -> "(sin resultado)"
        }
        output.text = text
    }

    /** El flujo completo de la aceptacion: submit, fetch, render (o abort y catch). */
    private fun demoProgram(abort: Boolean): String {
        val abortLine = if (abort) " c.abort();" else ""
        return "var o = []; var f = document.getElementById('f');" +
            " var c = new AbortController();" +
            " f.addEventListener('submit', function () {" +
            " fetch('http://demo/datos?q=' + document.getElementById('q').value, {signal: c.signal})" +
            " .then(function (r) { return r.text(); })" +
            " .then(function (txt) { o.push('render: ' + txt); return o.join('\\n'); })" +
            " .catch(function (e) { o.push('error: ' + e.name); return o.join('\\n'); }); });" +
            " o.push('enviado: ' + document.getElementById('q').value);" +
            abortLine +
            " f.submit(); o.join('\\n')"
    }

    /** Escape minimo para meter el texto del usuario en el atributo value. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    /** Transporte de demo: eco determinista de la peticion, sin red. */
    private class EchoTransport : FetchTransport {
        val requests = ArrayList<FetchRequest>()
        override fun fetch(request: FetchRequest, token: CancellationToken): TransportResult {
            requests.add(request)
            return TransportResult.Success(
                FetchResponse(200, mapOf("content-type" to "text/plain"), request.url.toByteArray())
            )
        }
    }
}
