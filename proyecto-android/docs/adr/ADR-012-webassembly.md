# ADR-012 — WebAssembly (WASM)

**Estado:** ACEPTADO (R0095, RES-0052) · reevaluado R0122 (T056, RES-0064) · **Base:** NIA-MAESTRO

- Posición: **EXCLUIDO** del núcleo NIA y del intérprete JS propio en v1.
  El diseño del motor (intérprete ECMAScript sin JIT con cuotas e
  interrupción de T021) no incluye una unidad de ejecución WASM, y
  añadirla es un cambio de motor de ejecución que exige ADR propio con
  mediciones (ADR-001).
- Requisito real evaluado (R0095, reevaluado R0122 con la evidencia de
  las matrices P7): las funciones objetivo de GitHub (repo, archivos,
  issues, diffs), Gmail (bandeja, hilos, búsqueda, redacción) y YouTube
  (búsqueda, watch, comentarios, subs, historial, login, portada,
  ver-más-tarde) **no requieren WASM**. La matriz GitHub/Gmail (T038,
  R0118/R0119, docs/matriz-p7-github-gmail.md) ejecutó sus 8 acciones
  COMPLETAS con el pipeline puro del motor (parse HTML, DOM, JS con
  bindings, fetch, storage particionado y layout/paint) y la matriz
  YouTube (T039, R0120/R0121, docs/matriz-p7-youtube.md) ejecutó sus 8
  COMPLETAS igual (incluida la pista WebVTT parseada con el parser v1):
  16 de 16 acciones completadas SIN WASM, con límites fechados por
  resaltado de sintaxis, contenteditable y HTMLMediaElement del DOM y
  exclusiones por sesión, red, protegido (DRM «aparte» según ADR-015)
  y directos — ninguna por WASM. Donde la web lo usa (compresión de
  imágenes, ofimática, criptografía de clientes) son optimizaciones o
  funciones fuera de los objetivos, no requisitos.
- Degradación honesta: las páginas cuyo único camino sea WASM degradan;
  el intérprete no finge soporte (sin anunciar API inexistente, criterio
  de capacidades del maestro). Si un objetivo del proyecto exige WASM
  como único camino para una función requerida, se abre ticket propio
  (T056) y este ADR se revisa.
- Ticket: **T056** (P6+): evaluación de requisito real de WASM tras la
  matriz T038/T039; CERRADO (R0122): la reevaluación con la evidencia
  de ambas matrices CONFIRMA la posición; WASM sigue EXCLUIDO. El
  ticket se reabre solo si el smoke real (T067) o un objetivo nuevo
  exige WASM como único camino.
