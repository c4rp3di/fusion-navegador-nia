# Matriz P7 — YouTube (T039 v1, R0120, RES-0063)

Fecha de la matriz: 2026-11-04 · Método: corpus enlatado servido por un
transporte falso SIN red, ejecutado con el pipeline completo del motor
(parse HTML → DOM → JS con bindings → fetch → storage particionado →
layout → paint), con los subtítulos WebVTT del corpus parseados con el
parser v1 (T036/T049). La red real, la reproducción con codecs de
plataforma y las sesiones del usuario quedan para el smoke real (T067).

## Acciones objetivo (según ADR-015)

| ID | Sitio | Acción | Estado | Nota |
| --- | --- | --- | --- | --- |
| yt-busqueda | youtube.com | Búsqueda de videos por query | COMPLETA | resultados del buscador contados desde la API del corpus |
| yt-watch | youtube.com | Página de visualización con tabla de relacionados | COMPLETA | relacionados del video renderizados como tabla |
| yt-comentarios | youtube.com | Comentarios cargados por fetch | COMPLETA | comentarios insertados en el DOM desde la API del corpus |
| yt-subs | youtube.com | Pista de subtítulos WebVTT descargada y parseada | COMPLETA | cues de la pista WebVTT contados con el parser de subtítulos v1 |
| yt-historial | youtube.com | Historial de reproducción persistido | COMPLETA | videos vistos guardados vía localStorage |
| yt-login | youtube.com | Login con form y POST de sesión | COMPLETA | form con usuario y clave que postea la sesión |
| yt-grid | youtube.com | Portada con tabla de videos | COMPLETA | videos de la portada renderizados como tabla |
| yt-mas-tarde | youtube.com | Lista de ver más tarde persistida | COMPLETA | videos guardados y re-leídos vía localStorage |
| yt-play-seek | youtube.com | Reproducción con play y seek | LÍMITE | LÍMITE 2026-11-04: exige HTMLMediaElement en el DOM, excluido de T036 v2; pendiente del elemento multimedia del DOM con los codecs de plataforma (ADR-015) |
| yt-calidad | youtube.com | Cambio de calidad del video | LÍMITE | LÍMITE 2026-11-04: el cambio de calidad exige cambiar el flujo del elemento multimedia, sin HTMLMediaElement en el DOM v1 |
| yt-protegido | youtube.com | Contenido protegido con DRM | EXCLUIDA | EXCLUIDA 2026-11-04: protegido aparte según ADR-015; descomposición solo cuando la matriz confirme el requisito (T059) |
| yt-directos | youtube.com | Directos y casting | EXCLUIDA | EXCLUIDA 2026-11-04: directos y casting aparte del objetivo; exigen red real y reloj continuo, smoke real T067 |

## Resumen

`ok=8 limites=2 excluidas=2` — verificado por YoutubeMatrixTest (15) en el
workflow contracts-tests. La matriz v1 ejecuta las COMPLETAS contra el
corpus y declara los LÍMITES fechados (play/seek y calidad, a la espera
del elemento multimedia del DOM); protegido (DRM) y directos/casting van
«aparte» según ADR-015 y el objetivo explícito. La reevaluación de WASM
tras la matriz (T056) mantiene ADR-012: ninguna función objetivo lo
requiere.
