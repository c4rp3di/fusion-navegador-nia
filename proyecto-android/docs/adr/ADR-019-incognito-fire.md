# ADR-019 — Incógnito y Fire: política de datos

**Estado:** ACEPTADO (R0100, RES-0054) · **Base:** NIA-MAESTRO

- Problema: T044 (modo incógnito) y T045 (Fire) necesitan la política de
  borrado, las confirmaciones y la decisión de si las descargas y los
  favoritos creados en incógnito persisten.
- Incógnito (T044, D-001): perfil temporal aislado EN MEMORIA — cookies
  y storage solo en memoria, sin historial y sin escritura de particiones
  a disco mientras dure la sesión (particiones de T033 en RAM); al
  cerrar, borrado total del perfil temporal probado con datos reales en
  partición aislada (criterio transversal R0092).
- Persistencia en incógnito (decisión): las DESCARGAS iniciadas en
  incógnito PERSISTEN (el usuario las dirigió explícitamente a un destino
  SAF) y los FAVORITOS creados en incógnito PERSISTEN (acción explícita),
  pero SIN historial de origen; la UI lo avisa al abrir la ventana.
- Fire (T045, D-002): dos niveles. «Sesión» (cookies de sesión, caché y
  storage temporal) es el borrado por defecto con aviso previo; «Total»
  (historial, almacenamiento persistente, cookies persistentes y datos
  de PWA) EXIGE confirmación explícita con el detalle de lo que se
  borra; los MARCADORES nunca se borran por Fire salvo selección
  explícita del usuario.
- Límites: sin promesa de anonimato (D-001: el incógnito aísla del
  dispositivo, no de la red); el borrado es por contexto/partición y
  nunca toca otras particiones.
- Pruebas: borrado con datos reales en particiones aisladas, aislamiento
  entre perfil incógnito y normal (dos top-sites no comparten área) y
  aviso de persistencia de descargas/favoritos. Dueño: asistente único
  (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T044/T045 y tras el primer reality smoke
  con particiones (T067).
