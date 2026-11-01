package nia.contracts

/**
 * Capacidad declarada del núcleo (T004): lo soportado y lo no probado, con
 * estado de prueba real. Nunca declarar PASS sin evidencia (sección 1.5).
 */
data class Capability(
    val id: String,
    val status: TestStatus,
    val detail: String? = null
) {
    init {
        require(id.isNotBlank()) { "id de capacidad obligatorio" }
    }
}

object Capabilities {

    /** Capacidades de la baseline P0: solo PASS lo que tiene tests en verde. */
    fun baseline(): List<Capability> = listOf(
        Capability("contracts.ids", TestStatus.PASS, "IdsTest en verde (workflow contracts-tests)"),
        Capability("contracts.errors", TestStatus.PASS, "ErrorsTest en verde"),
        Capability("contracts.handles", TestStatus.PASS, "HandlesTest en verde"),
        Capability("contracts.cancellation", TestStatus.PASS, "CancellationTest en verde"),
        Capability("contracts.ipc", TestStatus.PASS, "IpcTest en verde"),
        Capability("contracts.harness", TestStatus.PASS, "HarnessTest en verde"),
        Capability("app.first-draw", TestStatus.PASS, "APK instalada con dibujo propio, sin WebView (2026-10-30)"),
        Capability("engine.url", TestStatus.PASS, "T010 (R0014): parser URL/Origin/Site con IDNA/punycode (RFC 3492); UrlParserTest y OriginSiteTest en el workflow contracts-tests"),
        Capability("engine.html", TestStatus.PASS, "T012 (R0017): tokenizer HTML + tree builder v1 (subconjunto WHATWG, modos de insercion con recovery tipificado) en :engine-core; HtmlTokenizerTest y HtmlTreeBuilderTest en el workflow contracts-tests"),
        Capability("engine.dom", TestStatus.PASS, "T013 (R0018): DOM propio con handles/generacion, mutaciones con errores tipificados y snapshots con revision monotona en :engine-core; DomDocumentTest en el workflow contracts-tests"),
        Capability("engine.css", TestStatus.PASS, "T014 (R0019) + v2 (R0023): CSS en :engine-core con parser de selectores (tipo/universal/clase/id/atributo, combinadores descendente, hijo y hermanos adyacente/general, pseudo-clases estaticas :first-child/:last-child/:only-child/:nth-child(an+b)), especificidad, cascada con orden y herencia, shorthands margin/padding/border-width (1-4 valores TRBL) y unidades relativas em/rem/% resueltas a px; CssParserTest y CssCascadeTest en el workflow contracts-tests"),
        Capability("engine.layout", TestStatus.PASS, "T015 (R0020) + v2 (R0023): layout en :engine-core con formato de bloques, inline con envoltura greedy, bloques anonimos, alturas de linea y alineacion heredada; v2 anade margin/padding/border por bloque (modelo border-box, sin margin collapsing), unidades em/rem contra la fuente y % contra el ancho del contenedor; LayoutTest en el workflow contracts-tests"),
        Capability("engine.paint", TestStatus.PASS, "T016 (R0021) + v2 (R0023): pintura en :engine-core (nia/engine/paint): display list de ops DrawRect/DrawText en orden documental sobre el layout real, hitTest topmost por punto; v2 anade hasta 4 tiras de borde por elemento (border-color o color heredado) tras el fondo; PaintView (app) renderiza la escena del pipeline completo y resuelve toques con hitTest del motor; PaintTest en el workflow contracts-tests"),
        Capability("engine.js", TestStatus.PASS, "T018 v1 (R0024) + v2 (R0025) + v3 (R0026): lexer ECMAScript en :engine-core (nia/engine/js) con tokens con spans de fuente (v3 anade el puntuador '?.', consumido por el parser como optional chaining), templates con sustituciones y anidados, regex frente a division, numeros (hex/octal/binario/BigInt/legacy) y errores tipificados con linea/columna; parser ECMAScript v1 (R0025) + v2 (R0026): AST con spans y errores tipificados con linea/columna, recursive descent con precedencias, sentencias (var/let/const, funciones con defaults, if/else, while, do-while, for/for-in/for-of, break/continue con label, throw, try/catch/finally, switch, bloque, labels, clase y expresion) y expresiones (asignacion compuesta, condicional, logicos, bitwise, igualdad, relacionales, shifts, **, unarios, update pre/post, new, call/member/index con optional chaining '?.', literales, array y object con getters/setters, paren, coma, arrows y expresiones de clase) con recuperacion a nivel de sentencia; runtime/evaluacion ECMAScript v1 (R0027, T019) sobre el AST v2: valores (undefined/null/boolean/number sobre Double/string/objetos con prototipos y accessors/arrays con length/funciones con closure/clases/RegExp como valor) y conversiones espejo de ECMAScript (ToBoolean, ToNumber con trim y hex, jsNumToString sobre Double.toString, ToInt32/ToUint32 para bitwise, ToPrimitive), entornos en cadena con hoisting (var a la funcion, function declarations, let/const de bloque), closures y this por llamada (receptor en metodos, nuevo en constructores, lexico en arrows, undefined en llamadas sueltas), completions normal/break/continue/return con labels, try/catch/finally donde el finally reemplaza, switch con === y fallthrough, for-in de claves propias y for-of de arrays/strings, objetos con getters/setters y spread, funciones y arrows con defaults y spread en llamadas, clases completas (constructor por defecto que delega en super con los argumentos, metodos, get/set, claves computed, static, extends como cadena de prototipos), new/instanceof/in/delete, optional chaining con cortocircuito nullish, coercion completa de operadores y errores tipificados como objetos Error (el resultado del programa es el completion value del spec (la ultima sentencia de nivel superior con valor, incluidos try/catch y switch, estilo REPL) y los throws sin capturar se reportan como uncaught); builtins v1 (R0028, T020): Math (PI/E, abs/ceil/floor/round/trunc/sign/sqrt/pow/min-max con semantica NaN, log/log2/log10/exp/random), Number (callable y con isInteger/isFinite/isNaN solo sobre numbers, parseInt/parseFloat, EPSILON, MAX/MIN_SAFE_INTEGER), globales de conversion (String/Number/Boolean, parseInt con trim/signo/0x/radix 2-36, parseFloat con prefijo y exponentes, isNaN/isFinite coercivos), Object.keys (claves propias en orden de insercion, accesores incluidos; arrays devuelven indices), Array.isArray y JSON.parse/stringify (subconjunto sin replacer/reviver/indent; parse tipifica SyntaxError; stringify omite funciones/clases/nativas, undefined se omite en objetos y null en arrays), sobre JsNative con call y props; JsLexerTest (35), JsParserTest (114) y JsInterpreterTest (226) en el workflow contracts-tests"),
        Capability("engine.network", TestStatus.NOT_RUN, "P2 sin iniciar")
    )
}
