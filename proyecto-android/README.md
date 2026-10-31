# Navegador NIA — baseline 0.2.0 (código R0002 · revisión R0009)

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
