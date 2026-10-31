# Navegador NIA — baseline 0.2.0 (código R0002 · revisión R0016)

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
engine-core/        módulo Kotlin JVM puro (motor: URL/Origin/Site, tokenizer HTML + tests)
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
