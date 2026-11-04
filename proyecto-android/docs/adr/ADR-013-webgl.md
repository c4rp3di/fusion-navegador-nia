# ADR-013 — WebGL

**Estado:** ACEPTADO (R0095, RES-0052) · **Base:** NIA-MAESTRO

- Posición: **EXCLUIDO** del núcleo v1. El pipeline de render propio es
  HTML/CSS/layout/paint sobre Canvas 2D (T016); no hay contexto GL ni
  bindings WebGL para el JS del documento.
- Requisito real evaluado (R0095): ninguno de los tres objetivos exige
  WebGL para las funciones objetivo. YouTube lo usa para efectos
  decorativos de fondo del reproductor (no bloqueantes); GitHub y Gmail
  no lo requieren para las funciones objetivo.
- Respuesta honesta: getContext("webgl") y variantes no exponen API
  ficticia (sin anunciar soporte, criterio de capacidades del maestro).
- La aceleración GPU del compositor propio es un cambio que exige ADR
  propio con mediciones (ADR-001); no se decide aquí.
- Ticket: **T057** (P6+): evaluación de WebGL/GPU compositing por
  rendimiento medido (P8); PENDIENTE, sin implementación.
