# ADR-011 — Plataforma Android

**Estado:** ACORDADO (R0003; ajuste de targetSdk en R0004; compileSdk alineado en R0005) · **Base:** NIA-MAESTRO

- **minSdk 26** (Android 8.0): cubre ~95% de dispositivos activos.
- **targetSdk/compileSdk 34** en la baseline R0005: par
  AGP 8.5.0/Gradle 8.9 compilado con éxito por el pipeline del usuario.
  El compileSdk 35 de R0004 se queda a la espera de que el stack lo
  soporte sin fricción; la subida a 35/36 se reevaluará con ADR cuando
  el CI esté verde.
- Dispositivos: teléfonos y tablets ARM64.
- Teclado externo contemplado (DevTools y comandos lo aprovechan).
