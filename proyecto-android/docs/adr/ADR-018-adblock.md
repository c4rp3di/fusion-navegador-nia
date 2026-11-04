# ADR-018 — Adblock: listas, actualización y reglas cosméticas

**Estado:** ACEPTADO (R0100, RES-0054) · **Base:** NIA-MAESTRO

- Problema: T051 (adblock core), T052 (hook de red) y T053 (reglas
  cosméticas) necesitan el formato de las listas, la política de
  actualización y el momento de aplicación de las reglas cosméticas.
- Formato de listas v1: **sintaxis EasyList** en subconjunto práctico:
  filtros por dominio (||), excepciones (@@), separadores (^) y
  wildcards (*) con opciones básicas; SIN regex complejas ni snippet
  filters (#$#) en v1 (degradación documentada, sin fingir soporte).
- Actualización: bajo demanda del usuario y periódica, SIEMPRE vía el
  FetchManager del broker (nunca red directa del módulo) con ETag/
  If-None-Match y cuota de tamaño por lista; los fallos de red no borran
  la lista vigente.
- Reglas de red (T052): la decisión Allow/Block se toma ANTES de la
  petición, desde el broker, por contexto (particiones de T026).
- Momento de reglas cosméticas (decisión que faltaba): DESPUÉS del
  primer layout — el matching de selectores del motor CSS (T014)
  identifica los elementos y se neutralizan con un re-layout acotado;
  el costo se mide contra el baseline de rendimiento (R0099) y, si el
  re-layout resultara caro, este punto se revisa.
- Reutilización: la app existente del usuario se reutiliza SOLO tras la
  auditoría de T055 (licencias, origen, versión, permisos y separación
  de lógica portable); mismo criterio de dependencias que ADR-016.
- Límites (D-004): sin VPN, sin MITM, sin CA de NetLens; sin bloqueo de
  primera parte sin regla explícita.
- Pruebas: fixtures de listas (regla, excepción, opciones), matching en
  Kotlin puro, excepciones por sitio, actualización con ETag y cuotas.
  Dueño: asistente único (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T051-T053 y tras la auditoría T055.
