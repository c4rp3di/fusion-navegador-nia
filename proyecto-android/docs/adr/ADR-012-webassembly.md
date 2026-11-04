# ADR-012 — WebAssembly (WASM)

**Estado:** ACEPTADO (R0095, RES-0052) · **Base:** NIA-MAESTRO

- Posición: **EXCLUIDO** del núcleo NIA y del intérprete JS propio en v1.
  El diseño del motor (intérprete ECMAScript sin JIT con cuotas e
  interrupción de T021) no incluye una unidad de ejecución WASM, y
  añadirla es un cambio de motor de ejecución que exige ADR propio con
  mediciones (ADR-001).
- Requisito real evaluado (R0095): las funciones objetivo de GitHub
  (repo, archivos, issues, diffs), Gmail (bandeja, hilos, búsqueda,
  redacción) y YouTube (búsqueda, reproducción base, audio, seek, subs,
  fullscreen — T039) **no requieren WASM**. Donde la web lo usa
  (compresión de imágenes, ofimática, criptografía de clientes) son
  optimizaciones o funciones fuera de los objetivos, no requisitos.
- Degradación honesta: las páginas cuyo único camino sea WASM degradan;
  el intérprete no finge soporte (sin anunciar API inexistente, criterio
  de capacidades del maestro). Si un objetivo del proyecto exige WASM
  como único camino para una función requerida, se abre ticket propio
  (T056) y este ADR se revisa.
- Ticket: **T056** (P6+): evaluación de requisito real de WASM tras la
  matriz T038/T039; PENDIENTE, sin implementación.
