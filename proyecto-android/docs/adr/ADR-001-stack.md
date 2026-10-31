# ADR-001 — Stack y tooling

**Estado:** ACORDADO (R0003; ajuste de DSL en R0004; versiones revisadas en R0005) · **Base:** NIA-MAESTRO

- Kotlin Android + core **Kotlin/JVM puro** (módulo `:contracts` no
  importa Android). Los contratos no dependen de implementaciones.
- Gradle con **Groovy DSL (`.gradle`)**: el pipeline de compilación de
  Fusion espera Groovy; el importador avisa de `.kts`. Se reevaluará si
  Fusion pasa a soportar Kotlin DSL sin fricción.
- **Canvas** (`android.graphics.Canvas`) como primer backend de dibujo
  propio. Sin WebView/GeckoView/Chromium/WebKit ni JS externo, sin
  fallback oculto.
- Intérprete JS **sin JIT** en las fases iniciales.
- Pocos módulos Gradle con paquetes lógicos: `:contracts`, `:app`
  (baseline); después `:engine-core`, `:harness`.
- Versiones (revisadas en R0005): **Kotlin 1.9.24, AGP 8.5.0, Gradle 8.9,
  JVM 17** — la combinación que el pipeline del usuario ya compiló con
  éxito. El salto inicial a Kotlin 2.0.21/AGP 8.7.3 (R0004) falló en
  "Compilar APK" sin log accesible; la subida a 2.x/8.7+ se reevaluará
  con ADR propio cuando el CI esté verde.
- Cambios Rust/C++/GPU requieren ADR propio con mediciones, no decisión
  individual.
