# Matriz de integracion de plataforma (T111 v1)

T111 v1 (R0352, RES-0143): auditoria de integracion X01 sobre las fuentes
REALES del motor en el sha 2d419193 (canonico verificado, motor 2562
tests). Metodo: lectura directa de 14 archivos del motor con conteos
medidos (jamas de memoria) y entregas verificadas del shell; veredicto
por bloque y un ticket por cada bloqueo REAL. Esta matriz es la linea
base; el cierre de brechas llega en versiones siguientes de T111.

Alcance auditado (14 archivos del motor en 2d419193):
nav/NavController.kt (449 l), js/JsDomBindings.kt (743),
js/JsEventLoop.kt (213), js/JsRuntime.kt (371), js/JsFetchApi.kt (230),
html/HtmlTreeBuilder.kt (1098), dom/DomDocument.kt (305),
media/MediaEngine.kt (222), paint/Paint.kt (138), css/CssCascade.kt
(423), net/Fetch.kt (331), net/HttpCache.kt (228), net/HttpTransport.kt
(174), url/UrlParser.kt (424).

## Veredictos

- SOPORTADO: comportamiento real, verificado y probado.
- PARCIAL: funciona en el nucleo pero con una brecha documentada.
- NO: no existe; el bloqueo abre un ticket.

## Matriz

| Bloque | Veredicto | Evidencia (sha 2d419193) | Brecha |
|---|---|---|---|
| Scripts classic al cargar | PARCIAL | NavController ejecuta los scripts del documento al cargar via JsEventLoop (sesion mantenida entre pumps, cuotas); JsRuntime tiene flag async para promesas | 0 hits de defer en js/; el orden documental es el unico (TK-111-2) |
| Scripts module | NO | 0 hits de "module" en js/ (JsRuntime, JsEventLoop, NavController) | TK-111-2 |
| Subrecursos img/stylesheet/script | PARCIAL | NavSubresource contable por tipo e iniciador; stylesheet alimenta CssStylesheet (T014/T023); img se pide por el pipeline | Paint.kt kdoc: "sin imagenes"; no se pinta (TK-111-5) |
| stylesheet/link CSS | SOPORTADO | CssCascade v3: herencia, shorthands TRBL, unidades em/rem/%, @media min/max-width contra viewport (R0090, RES-0050) | sin inline styles ni !important (documentado en kdoc) |
| font / @font-face / @import | NO | 0 hits de font-face y @import en css/; font-family default "serif" computado sin cargar nada | TK-111-6 |
| iframe anidado | NO | HtmlTreeBuilder trata iframe como rawtext (solo parseo); sin navegacion por pagina | TK-111-7 |
| URL base / encoding / MIME | PARCIAL | UrlParser: absolutas, subconjunto WHATWG, IDNA punycode; NavController resuelve relativas contra scheme+host+path de la pagina; charset UTF-8 FIJO en 2 sitios (String(bytes, Charsets.UTF_8)) | sin charset por cabecera/meta (TK-111-4); Content-Type solo se fija en la respuesta sustituta (Fetch.kt), sin sniffing |
| Formularios | PARCIAL | JsDomBindings: accessors value de input y form.elements (T027); IME real del shell (T035, BaseInputConnection) | sin submit real ni multipart (0 hits en JsFetchApi) (TK-111-8) |
| Foco / IME / scroll | PARCIAL | IME del shell real (T035); scroll propio con gestos del shell | foco de documento no expuesto a JS |
| Timers | SOPORTADO | JsEventLoop: setTimeout/setInterval/queueMicrotask con sesion mantenida entre pumps | - |
| Eventos DOM | SOPORTADO | JsDomBindings: addEventListener/dispatchEvent con captura y burbujeo (T024) | eventos sinteticos de entrada sin verificar |
| Historia / SPA | PARCIAL | historia propia ArrayList (back/forward por URL) en NavController | 0 hits de pushState/popstate/hashchange: las SPA no escriben la historia (TK-111-3) |
| HTMLMediaElement hacia el backend | NO | kdoc de MediaEngine: "sin audio del DOM ni HTMLMediaElement para JS (T039)"; 0 hits de video/audio en JsDomBindings | TK-111-1 (habilita T092 in-page) |
| MSE | NO | el MediaSource del motor NO es MSE (colision de nombre: es la fuente de medios del visor); sin MediaSource JS | TK-111-9, solo con ADR explicito |
| Shadow DOM / custom elements | NO | 0 hits de attachShadow/shadowRoot/customElements en js/ y html/ | - |
| SVG | NO | 0 hits de svg en html/, css/ y paint/ | - |
| Canvas 2D (JS) | NO | Paint.kt: display list propio (DrawRect/DrawText); sin elemento canvas ni API JS (0 hits) | - |
| UTF-8 / bidi | PARCIAL | UTF-8 fijo en la decodificacion; IDNA simplificado | UrlParser kdoc: "sin normalizacion NFC ni validacion bidi" del RFC 3491 |
| Workers | SOPORTADO | T034 dedicated workers verificado (VisorWorkers en el shell) | - |
| fetch / cookies / CORS / cache / storage | SOPORTADO | FetchManager v6 (cache LRU con presupuesto R0311, filtro v3, respuesta sustituta C3, tope de cuerpo v6); HttpTransport real (HttpURLConnection, TLS siempre validado, sin seguir 3xx); CookieJar/GestorCookies; Cors.kt; particiones storage T033 | - |

## Tickets por bloqueo real

- TK-111-1 (PRIORITARIO): puente HTMLMediaElement basico: exponer
  video/audio al MediaEngine con estados y eventos JS (play, pause,
  timeupdate) hacia el backend. HABILITA la aceptacion in-page de T092
  (player); T109 lifecycle y T112 perfiles dependen de esa cadena.
- TK-111-2: async/defer en scripts del documento y type=module (orden
  de ejecucion real del arbol).
- TK-111-3: pushState/popstate/hashchange para que las SPA escriban la
  historia.
- TK-111-4: charset por cabecera Content-Type y por meta charset del
  documento (hoy UTF-8 fijo).
- TK-111-5: decodificacion y pintura de imagenes en paint/ (DrawImage
  sobre el layout).
- TK-111-6: @font-face y @import en el parser CSS.
- TK-111-7: iframe anidado con navegacion por pagina (motor por frame).
- TK-111-8: submit de formularios (GET/POST con codificacion y
  multipart/form-data en JsFetchApi).
- TK-111-9: MSE solo con ADR explicito (MediaSource JS sobre el buffer
  del motor; el nombre ya esta ocupado por la fuente del visor).

## Exclusiones honestas de v1

- Auditados 14 archivos del motor sobre 2d419193; el shell se describe
  por entregas verificadas (T035 IME, T034 workers, menus y pantallas).
- Quedan por auditar en v2: layout fino, el parser Css.kt completo,
  js/JsWorker y storage.
- Los fixtures upstream (fallo causal reproducible) pertenecen a las
  versiones de cierre de brechas, no a esta linea base.
