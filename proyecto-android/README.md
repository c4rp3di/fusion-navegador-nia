# Navegador NIA — baseline 0.2.0 (código R0002 · revisión R0025)

Navegador Android con motor HTML/CSS/layout y JavaScript **propios** (sin
WebView, GeckoView, Chromium, WebKit ni JS externo). Este ZIP corrige la
baseline inicial (código R0001) tras los fallos de compilación en GitHub
Actions diagnosticados en las revisiones R0005–R0007 del plan maestro (NIA-MAESTRO):

- **contracts-tests** ejecutaba `gradle` en la raíz del repo, donde no hay
  proyecto (16 runs fallidos). Ahora usa `working-directory: proyecto-android`.
- **Build APK Nativo** fallaba en "Compilar APK": el stack moderno
  (Kotlin 2.0.21 / AGP 8.7.3 / compileSdk 35) no estaba verificado en este
  pipeline. Se alinea con la combinación que el pipeline ya compiló con
  éxito: Kotlin 1.9.24 · AGP 8.5.0 · compileSdk 34 · Gradle 8.9.
- La dependencia de tests usa coordenadas explícitas
  (`org.jetbrains.kotlin:kotlin-test-junit`) en vez de `kotlin('test')`,
  que es sintaxis del DSL Kotlin, no de Groovy.
- `buildTypes` se declara una sola vez en `app/build.gradle`.
- **R0007**: el primer `:contracts:compileKotlin` real (run #350) destapó dos bugs del código, nunca compilado antes: entradas de enum sin calificar en `when` y fábricas `Outcome.success`/`Outcome.failure` ausentes. Corregidos en `Errors.kt`.
- **R0009 (T004/T005)**: harness de resultados (`Harness.kt`, estados NOT_RUN/PASS/FAIL/BLOCKED/UNSUPPORTED separados), registro de capacidades declaradas (`Capabilities.kt`), tests del harness (`HarnessTest.kt`), verificación de roundtrip de fuentes tras la importación y XML de tests publicado como artefacto del workflow. `ContractsVersion` pasa a 0.2.0 (adición compatible).
- **R0011**: corrección en `HarnessTest.kt` (línea 36): la llamada de prueba pasaba solo `passed` y el constructor exige `failed`/`skipped` (sin valor por defecto), lo que rompía `:contracts:compileTestKotlin`. El resto de archivos no cambia.
- **R0012 (T010 inicio)**: nuevo módulo `:engine-core` (Kotlin JVM puro, sin Android) con parseo de URL absoluta según un subconjunto práctico del algoritmo WHATWG: esquemas especiales (http/https/ws/wss/ftp/file), userinfo, host (dominio ASCII, IPv4, IPv6), puerto con normalización de defectos, normalización de rutas (`.`/`..`), query y fragmento. Además `Origin` (tupla u opaco; nunca la URL como identidad) y `Site` (dominio registrable con tabla de sufijos embebida — aproximación al PSL, no usar aún para decisiones de seguridad). Tests: `UrlParserTest` y `OriginSiteTest`. El workflow ejecuta `:engine-core:test` y el paso de roundtrip vigila también las fuentes del módulo nuevo.
- **R0014 (T010 completo)**: IDNA/punycode en el parser de URL (RFC 3492, solo encode): los dominios con caracteres no-ASCII se normalizan a minúsculas Unicode y cada etiqueta se convierte a su forma `xn--` (p. ej. `españa.com` → `xn--espaa-rta.com`). Limitaciones documentadas: sin normalización NFC ni validación bidi/de-hyfen. `Capabilities` declara `engine.url` en PASS con los tests del workflow (21 + 9).
- **R0015**: la aserción de `HarnessTest.kt` que exigía NOT_RUN para toda capability del motor se sustituye por su forma general: una capability del motor en PASS exige evidencia declarada (detalle no vacío). Compatible con `engine.url` PASS.
- **R0016 (T012 v1)**: tokenizer HTML (subconjunto práctico de la tokenization WHATWG, spec 13.2.5) en `:engine-core` (`nia/engine/html`): tokens Data/StartTag/EndTag/Comment/Doctype/Eof con errores de parseo tipificados; nombres de etiqueta y atributo en minúsculas ASCII, atributos con comillas dobles/simples/sin comillas (duplicados: gana el primero), self-closing, comentarios incluidos `--!>` y cierres abruptos, doctype con nombre + forceQuirks (ids PUBLIC/SYSTEM consumidos, no guardados), RAWTEXT/RCDATA aproximado para `script/style/textarea/title/xmp/iframe/noembed/noframes` y el quirk documentado del spec (`<a href = x>` abre un atributo llamado `=`). Casos malformados cubiertos: `<` suelto, EOF dentro de etiquetas, `</>`, comentarios sin cerrar, doctype sin nombre, NUL → U+FFFD. Limitaciones v1: sin character references (`&` pasa literal), CDATA degradado a comentario (como en HTML), atributos de etiquetas de cierre descartados. 42 tests (`HtmlTokenizerTest`); `Capabilities` declara `engine.html` en PASS.
- **R0017 (T012 v2)**: tree builder HTML v1 en `:engine-core` (`nia/engine/html`): DOM propio (`DomNode`: Document/Doctype/Comment/Text/Element con atributos preservando el orden de llegada) y construccion del arbol con los modos de insercion INITIAL, BEFORE_HTML, BEFORE_HEAD, IN_HEAD, AFTER_HEAD, IN_BODY, AFTER_BODY y AFTER_AFTER_BODY (reprocess y errores tipificados): cierre implicito de `p` por bloques y headings, `li`/`dd`/`dt` con scope, `genericEnd` con `mismatched-end-tag`, fusion de atributos en `html`/`body` duplicados, comentarios segun posicion y `eof-in-body`. `DomPretty` serializa el arbol (snapshots de tests). 45 tests (`HtmlTreeBuilderTest`); `Capabilities` amplia `engine.html` a tokenizer + tree builder; el workflow vigila 27 fuentes. Limitaciones v1: sin modos de tabla, `template`, `frameset` ni adoption agency; `hr` no cierra `p`.
- **R0018 (T013 v1)**: DOM propio en `:engine-core` (`nia/engine/dom`, `DomDocument.kt`): el árbol de `HtmlTreeBuilder` se registra con ids estables en pre-orden y se muta con handles (`DomHandle`: documento + generación + nodo). `invalidate()` caduca los handles (representa navegar; código `stale-handle`) y `removeChild` desregistra el subárbol completo (`node-not-found` en los handles internos). Mutaciones tipificadas: `appendChild`/`insertBefore`/`removeChild`, `setText` (solo `Text`), `setAttribute` con validación de nombre (`invalid-attribute-name`; sobrescribir conserva el orden) y `removeAttribute` (`attribute-not-found`). La revisión solo sube con mutaciones exitosas y `snapshot()` es inmutable (generación + revisión + árbol serializado con `DomPretty`). 31 tests (`DomDocumentTest`); `Capabilities` declara `engine.dom` en PASS; el workflow vigila 29 fuentes. Limitaciones v1: sin eventos/bindings (T024), sin snapshots incrementales (T017) y los textos añadidos no se funden automáticamente.
- **R0019 (T014 v1)**: CSS propio en `:engine-core` (`nia/engine/css`, `Css.kt` + `CssCascade.kt`): parser de hojas de estilo con selectores de tipo, universal, clase, id y atributo (`[a]`, `[a=v]`, `[a~=v]`), combinadores descendente e hijo y agrupación por comas; los pseudo-selectores y @-rules producen errores tipificados (`unsupported-pseudo`, `unsupported-at-rule`) y se omiten (limitación v1). La cascada ordena por especificidad (ids, clases/atributos, tipos) y orden de aparición; los estilos computados aplican defaults, herencia (color y tipografía bajan del padre; `background-color` no) y las declaraciones ganadoras. 36 tests (`CssParserTest` + `CssCascadeTest`); `Capabilities` declara `engine.css` en PASS; el workflow vigila 33 fuentes. Limitaciones v1: sin selectores de hermanos, pseudo-clases, @-rules, origenes múltiples ni !important.
- **R0020 (T015 v1)**: layout propio en `:engine-core` (`nia/engine/layout`, `Layout.kt` + `LayoutTest.kt`): formato de bloques con apilado vertical (cada bloque ocupa el ancho del contenedor), contenido inline con envoltura greedy por palabras (el hueco entre palabras usa la fuente de la palabra siguiente) y bloques anónimos para contenido mixto; alturas de línea `line-height` en px o normal = 1.2x la fuente (por línea se usa el máximo); alineación heredada left/center/right; medición monoespaciada (avance = fontSize/2) y overflow permitido (limitaciones v1); cajas consultables por nodo: `boxOf` (bloques: su caja; inline y texto: envolvente de fragmentos) y `fragmentsOf` (una caja por palabra y línea). 28 tests espejo del gemelo TS validado offline sobre la cascada CSS real; `Capabilities` declara `engine.layout` en PASS; el workflow vigila 35 fuentes. Limitaciones v1: sin margin/padding/border, width/height fijados, float/flex/tablas, unidades relativas ni múltiples contextos de formato.
- **R0021 (T016 v1)**: pintura propia en `:engine-core` (`nia/engine/paint`, `Paint.kt` + `PaintTest.kt`): display list de operaciones `DrawRect` (fondos con `background-color` no transparente) y `DrawText` (una op por palabra con la caja del fragmento del layout y el color/tamaño heredados del contexto) en orden documental (pre-orden) sobre el árbol, la cascada y el layout reales; `PaintScene.hitTest` resuelve un punto al op topmost (bordes exclusivos). Primer adaptador Android del motor: `PaintView` (`:app`) ejecuta el pipeline completo (parse HTML → CSS → layout → escena) en su init, dibuja la escena escalada en `Canvas` y convierte el toque a coordenadas de documento para resolverlo con `hitTest` del motor (resaltado del op ganador); `MainActivity` delega en `PaintView`. 20 tests espejo del gemelo TS validado offline; `Capabilities` declara `engine.paint` en PASS; el workflow vigila 38 fuentes. Limitaciones v1: sin z-index/stacking contexts, sin imágenes, sin scroll ni entrada de texto.
- **R0023 (CSS/layout v2, RES-0010)**: extensión del motor CSS/layout/paint en `:engine-core`: selectores de hermanos adyacente (`+`) y general (`~`) y pseudo-clases estáticas (`:first-child`, `:last-child`, `:only-child`, `:nth-child(an+b)` 1-based con `odd`/`even` y coeficiente negativo); shorthands `margin`/`padding`/`border-width` con 1-4 valores expandidos TRBL en cascada (valores no numéricos como `auto` se descartan); unidades relativas resueltas a px en cascada (`em` y `%` de `font-size` contra el padre, `rem` contra la fuente del primer elemento, `em`/`rem` de `line-height`/`letter-spacing`/`margin`/`padding`/`border-width` contra la propia fuente; `line-height` numérica = N×fuente; los `%` de margin/padding/border se quedan como `N%` y los resuelve layout contra el ancho del contenedor); layout v2 coloca cada bloque hijo con margin/padding/border (modelo border-box: la caja devuelta incluye padding y borde, los margins quedan fuera; sin margin collapsing) y paint v2 dibuja hasta 4 tiras de borde por elemento (arriba/abajo a lo ancho, izquierda/derecha entre las horizontales) con `border-color` o el color heredado. Los 4 archivos de test se amplían con espejos del gemelo TS validado offline (parser, cascada, layout, paint). `PaintView` (app) usa la demo v2 (padding de body, bordes de h1 y tarjeta, `#card p:first-child`, `p+p`). Sin cambios de workflow (38 fuentes). Limitaciones v2: sin margin collapsing, width/height fijados, float/flex/tablas, pseudo-clases dinámicas (`:hover`), @-rules, orígenes múltiples ni !important.
- **R0024 (T018 v1, RES-0011)**: primer paso del motor JavaScript propio: lexer ECMAScript v1 en `:engine-core` (`nia/engine/js`, `JsToken.kt` + `JsLexer.kt`). Tokens con spans de fuente `[start, end)` para diagnostico preciso: puntuadores hasta `>>>=`, keywords estrictas (`let`/`static`/`of`/`get`/`set`/`async` quedan como identificadores: lo decide el parser futuro), identificadores ASCII con escapes `\uXXXX`, numeros (decimales con separador `_`, exponentes, hex/octal/binario, BigInt, octal legacy tipificado), strings con escapes (`\xHH`, `\uHHHH`, `\u{...}` hasta 6 hex, continuaciones de linea, escapes octales no soportados con error), templates con sustituciones (`tmplHead`/`tmplMiddle`/`tmplTail`) y anidados mediante pila de profundidad de llaves, literales RegExp frente a division (heuristica por token previo; `)`, `]`, `}` asumen division) y errores tipificados con linea/columna 1-based con recuperacion (el lexico continua tras el error). 35 tests espejo del gemelo TS validado offline (`JsLexerTest`); `Capabilities` declara `engine.js` en PASS; el workflow vigila 41 fuentes. Limitaciones v1: sin parser/AST (T018 continua), identificadores ASCII + `\uXXXX` (sin Unicode ID_Start completo), regex vs division por heuristica y sin quirks de comentarios HTML.
- **R0025 (T018 v2, RES-0012)**: parser ECMAScript v1 en `:engine-core` (`nia/engine/js`, `JsAst.kt` + `JsParser.kt`): recursive descent con precedencias sobre el token stream del lexer, AST con spans `[start, end)` y errores tipificados con linea/columna 1-based, recuperacion a nivel de sentencia (skip hasta `;` o `}`, avance garantizado). Sentencias: vacia, bloque, `var`/`let`/`const`, funcion declarada (parametros con defaults), `return`, `if`/`else`, `while`, `do-while`, `for` (3 clausulas + `for-in`/`for-of`), `break`/`continue`, `throw`, `try/catch/finally`, `switch` y expresion. Expresiones: asignacion (todos los compuestos), condicional, `??`/`||`/`&&`, bitwise, igualdad, relacionales, shifts, `**`, unarios, update pre/post, `new`, call/member/index, literales (numero/BigInt/string/template con sustituciones parseadas/regex/bool/null/this), array y object literal (kv/string/number/computed/shorthand/metodo/spread), paren y coma; el `in` del for-init se parsea como identificador (flag `noIn`, espejo de la gramatica [In] de ECMA). Fix del lexer descubierto por el gemelo del parser: token `**` ausente en `PUNCTS` (antes `2**3` lexaba como `*` `*`). 71 tests espejo del gemelo TS validado offline (`JsParserTest`); `Capabilities` amplia `engine.js` con el parser; el workflow vigila 44 fuentes. Limitaciones v1: sin clases, async/await, generators, arrows, destructuring, labels, `with`, modulos, getters/setters, optional chaining ni ASI (puntos y coma obligatorios; error no fatal `missing-semicolon`).

Incluye:

- **T002** — Módulo `:contracts` (Kotlin JVM puro): ids tipados,
  `Outcome`/`ErrorInfo` con códigos cerrados y retryabilidad, handles con
  generación (`Handle`, `GenerationClock`, `HandleRegistry`), cancelación
  cooperativa (`CancellationTokenSource`, deadlines con reloj inyectable) y
  sobres IPC versionados (`IpcEnvelope`). Tests con kotlin-test.
- **T003 (inicio)** — `:app` mínima: `MainActivity` con primer dibujo
  propio sobre `android.graphics.Canvas`. Sin WebView ni layout XML.

## Estructura

```
settings.gradle / build.gradle / gradle.properties   raíz Gradle (Groovy DSL)
contracts/          módulo Kotlin JVM puro (contratos + tests)
engine-core/        módulo Kotlin JVM puro (motor: URL/Origin/Site, tokenizer + tree builder HTML, DOM con mutaciones y snapshots, CSS con selectores/cascada/herencia, layout block/inline, paint display list, lexer + parser ECMAScript + tests)
app/                aplicación Android mínima
docs/adr/           ADR-001 (stack), ADR-008 (licencia), ADR-011 (plataforma)
.github/workflows/  contracts-tests.yml (CI: tests + APK debug)
```

## Importar en Fusion

Abrir → **Desde el dispositivo (.zip)** → selecciona este archivo.
Fusion lo detecta como proyecto Android (`AndroidManifest.xml` + `.kt` +
`.gradle`). Los archivos raíz, `contracts/` y `docs/` se conservan;
el workflow queda registrado en el panel de workflows.

## Compilar y probar

- **APK**: compila desde Fusion vía GitHub como de costumbre (el editor
  genera su workflow de APK nativa; usa Gradle 8.9 automáticamente).
- **Tests del módulo `:contracts`**: workflow `contracts-tests`
  (`.github/workflows/contracts-tests.yml`) — ejecuta
  `gradle :contracts:test` dentro de `proyecto-android/` y también un
  smoke build del APK debug, subiendo el APK como artefacto.

## Stack (ADR-001 / ADR-011, revisión R0005)

Kotlin 1.9.24 · AGP 8.5.0 · Gradle 8.9 · Groovy DSL · minSdk 26 (Android
8.0) · targetSdk/compileSdk 34 · JVM 17 · paquete `app.nia.navegador`
(provisional, ADR-008).

## Licencia (ADR-008)

Código cerrado. Sin permiso de redistribución. Distribución: APK manual
(sin Play Store). Las claves de firma de release NO se suben al repo:
el usuario las custodia fuera del proyecto.
