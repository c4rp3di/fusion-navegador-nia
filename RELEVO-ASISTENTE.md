# RELEVO — Asistente único del proyecto

**Fecha:** 2026-11-14  
**Decisión:** el usuario (`c4rp3di`), autoridad del proyecto, encomienda la gestión técnica day-to-day del Navegador NIA al asistente de turno (Vibe Code), con continuidad para futuros sustitutos.  
**Registro en el maestro vivo:** pendiente de transcribir por el usuario/asistente de turno en la siguiente revisión del maestro (la copia viva vive en el knowledge del usuario; este archivo es su espejo de gobernanza en el repo).

## Reglas de gestión (heredadas del protocolo del maestro)

1. **Un incremento a la vez.** Nada de abrir dos tareas en paralelo.
2. **Contrato firmado contra el código REAL** leído del repo antes de implementar (lección R0264: el gemelo TS no firma tipos de Kotlin).
3. **Gemelo TS verde antes del push**; un commit por entrega; verificación byte-exacta post-push.
4. **El usuario conserva el veto** sobre alcance, diseño y producto (R0002). La confirmación de CI (🟢/🔴) es del usuario.
5. **Ante CI rojo: pedir el log ANTES de investigar** (R0197).
6. **Espejos del maestro al repo solo a petición** (R0200). No tocar la numeración divergente de `ESTADO-registro-3.md` (R0297/R0298 propios del espejo; ver R0299) salvo orden expresa.
7. No arrancar otra tarea tras informar; esperar elección del usuario.
8. Exclusiones honestas siempre: nada se finge soportado; los límites se documentan.

## Estado al tomar el mando

- `main` @ `5dfff5a` (espejo del maestro a R0459, 2026-11-14). CI `contracts-tests` VERDE sobre `9c50051` (run 43632055011).
- Motor: 2.562 tests. Smoke de dispositivo T067-A/B superados (R0220).
- Arco T111 v1–v10 VERIFICADO; TK-111-6 (@font-face/@import) CERRADO v1 (R0459, RES-0178).
- PR #1: actualización de los espejos derivados (`ESTADO.md`, resúmenes, README del proyecto) — solo documentación.

## Cola de trabajo propuesta (el usuario elige; no arrancar sin elección)

1. **Fix de concurrencia JS entre pestañas:** `JsQuotas` y `keepAliveRunners` son estado global y `poolNavegacion` tiene 2 hilos (`BrowserActivity.kt:600`): cuotas begin/end cruzadas, abortos espurios y scripts sin límites con dos pestañas cargando a la vez. Mayor impacto, riesgo silencioso.
2. **Cookies SameSite en navegación top-level:** `navigateInternal` usa siempre `TOP_LEVEL_CROSS_SITE` (`NavController.kt:421`): las cookies `SameSite=Strict` nunca se envían, ni same-site. Rompe logins.
3. **`fetch()` JS sin URLs relativas:** `JsFetchApi` no resuelve contra la base del documento (`UrlParser.parse` exige esquema absoluto): `fetch("/api/x")` falla. Impacto alto en SPAs.
4. Resto de tickets abiertos de la matriz T111 (timers/render, foco/IME/scroll, navegación SPA completa).
5. **T110** (memoria/scroll/paint con presupuestos medidos) y después **P8** (T040–T043: auditoría sandbox, fuzzing, rendimiento, release/firma).

Otros hallazgos de la revisión de 2026-11-14 (menores): CSP no aplicada en subframes, `page.url` desfasado tras pushState post-carga, fuga acotada de `signals` en `JsFetchApi`.

## Para futuros sustitutos

- Leer, en este orden: `ESTADO.md` (archivo estructural + delta R0459), `ESTADO-registro-4.md` (cronología vigente R0299–R0459), `ESTADO-IMPLEMENTACION-REAL.md` (qué está verificado/parcial/pendiente) y este archivo.
- Las LECCIONES acumuladas del maestro son vinculantes (p. ej. parchear por índice, gates por delta, escapes de Kotlin desde el gemelo).
- La fuente de verdad es el maestro vivo del usuario; todo lo de este repo son espejos. Ante duda, preguntar al usuario y no inventar estado.
