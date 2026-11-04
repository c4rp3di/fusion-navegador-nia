# Matriz P7 — GitHub/Gmail (T038 v1, R0118, RES-0062)

Fecha de la matriz: 2026-11-04 · Método: corpus enlatado servido por un
transporte falso SIN red, ejecutado con el pipeline completo del motor
(parse HTML → DOM → JS con bindings → fetch → storage particionado →
layout → paint). La red real y las sesiones del usuario quedan para el
smoke real (T067).

## Acciones objetivo (según ADR-012)

| ID | Sitio | Acción | Estado | Nota |
| --- | --- | --- | --- | --- |
| gh-repo | github.com | Repo público con tabla de archivos | COMPLETA | lista de archivos del repo renderizada como tabla |
| gh-issue | github.com | Issue con comentarios cargados por fetch | COMPLETA | comentarios insertados en el DOM desde la API del corpus |
| gh-login | github.com | Login con form y POST de sesión | COMPLETA | form con usuario y clave que postea la sesión |
| gm-bandeja | mail.google.com | Bandeja con tabla de hilos | COMPLETA | hilos de la bandeja renderizados como tabla |
| gm-hilo | mail.google.com | Hilo abierto con mensaje por fetch | COMPLETA | mensaje del hilo insertado desde la API del corpus |
| gm-busqueda | mail.google.com | Búsqueda de hilos por query | COMPLETA | resultados del buscador contados desde la API del corpus |
| gm-redaccion | mail.google.com | Redacción con borrador en localStorage | COMPLETA | borrador del cuerpo guardado y re-leído |
| gm-leidos | mail.google.com | Estado leído persistido | COMPLETA | hilos marcados leídos vía localStorage |
| gh-diff | github.com | Diff plano sin resaltado de sintaxis | LÍMITE | LÍMITE 2026-11-04: el diff se muestra como texto plano, sin resaltado de sintaxis |
| gm-composicion-rica | mail.google.com | Composición rich-text | LÍMITE | LÍMITE 2026-11-04: la composición rich-text exige contenteditable, no soportado por el DOM v1 |
| gh-notificaciones | github.com | Notificaciones con sesión real | EXCLUIDA | EXCLUIDA 2026-11-04: exige sesión real y canal de notificaciones; fuera del corpus enlatado (smoke real T067) |
| gm-oauth | mail.google.com | Login real de Google (OAuth) | EXCLUIDA | EXCLUIDA 2026-11-04: el login real exige red y credenciales del usuario; smoke real T067 |

## Resumen

`ok=8 limites=2 excluidas=2` — verificado por SiteMatrixTest (15) en el
workflow contracts-tests. La matriz v1 ejecuta las COMPLETAS contra el
corpus y declara los LÍMITES fechados; las EXCLUIDAS exigen red real o
sesiones del usuario (smoke real T067). La reevaluación de WASM tras la
matriz (T056) mantiene ADR-012: ninguna función objetivo lo requiere.
