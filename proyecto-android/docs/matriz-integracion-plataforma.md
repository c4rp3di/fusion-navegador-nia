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

## v2 (R0405, RES-0161): cierre de brechas pequenas sobre 9d2c0b72

T111 v2 (contrato R0404, entrega R0405): re-auditoria de las superficies
que v1 dejo pendientes (layout fino, parser Css.kt completo, workers y
storage, bindings de media) con conteos medidos sobre el canonico, DOS
brechas pequenas cerradas en el MISMO commit (decision del usuario:
informe + arreglos pequenos en la misma tanda; todas las superficies
por igual sin priorizar) y el resto honesto en tickets.

Lectura medida en 9d2c0b72 ANTES del fix (deltas contra 2d419193 de v1):
nav/NavController.kt 458 l (+9 desde v1: cuotas de script T100 y puente
media T092 v2), nav/NavControllerTest.kt 352 l (14 tests; NINGUN
fixture usa URLs relativas de subrecurso, <base> ni charset no UTF-8:
los fixes no tocan las fijaciones del gemelo), url/UrlParser.kt 424 l
(normaliza . y .. en normalizePath; referencias relativas fuera de
alcance, la resolucion vive en NavController), dom/DomDocument.kt
305 l (findFirst/attributeOf soportan el <base> sin traversal nuevo),
html/HtmlTreeBuilder.kt 1098 l (base/meta/source/track void en head,
iframe rawtext), js/JsDomBindings.kt 856 l, media/MediaPuente.kt
50 l, css/Css.kt 459 l, layout/Layout.kt 810 l,
workers/VisorWorkers.kt 169 l, storage: IndexedDb.kt 13103 B,
Storage.kt 4948 B, StoragePersistence.kt 10109 B.

Brechas cerradas en esta entrega (NavController.kt):

1. TK-111-4 CERRADO para el DOCUMENTO: charset por cabecera
   Content-Type y, si no, primer <meta ... charset=...> de los
   primeros 2048 caracteres (escaneo simple sin excluir comentarios);
   nombre desconocido o ausente -> UTF-8 como antes. Los SUBRECURSOS
   siguen en UTF-8 fijo: nuevo TK-111-10.
2. URL base CERRADA en lo esencial: script/img/link stylesheet se
   resuelven contra la base efectiva (href del primer <base> si
   parsea; si no, la URL de la pagina): absolutas, //host, /ruta,
   ?query y rutas relativas con . y .. normalizadas al parsear la
   candidata. En 9d2c0b72 relativas de documento y //host devolvian
   null y el subrecurso se ignoraba en silencio.

Deltas de veredicto contra v1 (evidencia medida en 9d2c0b72):

- HTMLMediaElement hacia el backend: NO -> PARCIAL. TK-111-1 CERRADO
  por T092 v2 (R0358, RES-0145): MediaPuente.kt 50 l con
  reproducir/pausar/posicionMs/duracionMs/volumen/velocidad/buscar;
  JsDomBindings.kt 683-692 expone video/audio (play/pause/paused/
  currentTime) SOLO con puente (nada fingido); el shell lo cablea
  (BrowserActivity usa PuenteMedia). Quedan ended/timeupdate
  persistentes y UN medio activo (exclusiones del puente v2).
- Scripts module / async/defer: SIN CAMBIOS. 0 hits de module/defer
  en JsDomBindings 856 l re-verificado; TK-111-2 sigue.
- Historia / SPA: SIN CAMBIOS. 0 hits de pushState/popstate/
  hashchange en JsDomBindings 856 l; TK-111-3 sigue.
- Formularios: PARCIAL con matiz. JsDomBindings 665: form.submit()
  existe y DISPARA el evento submit sincrono al elemento, pero NO
  navega ni hace peticion real (0 multipart en JsFetchApi);
  TK-111-8 sigue.
- Foco / IME / scroll: SIN CAMBIOS. 0 hits de focus/activeElement
  en JsDomBindings 856 l.
- Layout fino (nuevo en v2): PARCIAL. Layout.kt 810 l: caja
  rectangular, tablas v3, sin float/flex/grid (v4), medicion
  monoespaciada (avance = fontSize/2), 0 hits de img/bidi/canvas:
  sin imagenes en layout ni bidi (TK-111-5 sigue; nuevo TK-111-11).
- Parser Css.kt (nuevo en v2): SOPORTADO con limites. Css.kt 459 l:
  selectores tipo/universal/clase/id/atributo, combinadores
  descendiente/hijo/+/~, pseudo-clases estaticas, agrupacion por
  comas; @-rules SOLO @media (skipAtRule para el resto): 0 hits de
  @import y font-face; TK-111-6 sigue.
- Workers / storage: SIN CAMBIOS. VisorWorkers.kt 169 l (T102 v1,
  RES-0110) espejo JVM de JsWorkers/JsServiceWorkers; storage
  particionado T033 con persistencia propia.

Tickets actualizados:

- TK-111-1 CERRADO (T092 v2, R0358, RES-0145); los eventos
  persistentes (ended/timeupdate) quedan como exclusion del puente v2.
- TK-111-4 CERRADO para el documento; NUEVO TK-111-10: charset de
  subrecursos (script/stylesheet) y BOM, hoy UTF-8 fijo en fetchSide.
- TK-111-2/3/5/6/7/8/9 SIGUEN (re-verificados con conteos en esta v2).
- NUEVO TK-111-11: bidi/RTL en layout y pintado (medicion
  monoespaciada sin direccion; UrlParser ya declara IDNA sin bidi).

Exclusiones honestas de v2:

- El scan de meta charset es simple (primeros 2048 caracteres, sin
  excluir comentarios ni BOM ni http-equiv completo): va con TK-111-10.
- La resolucion relativa cubre el walk de LOADED (script/img/link
  stylesheet); iframe/font/media siguen sin pedirse.
- Los fixes se verifican SOLO por CI del conteo 2562 (sin tests
  nuevos, protocolo del maestro); los fixtures upstream de fallo
  causal pertenecen a las versiones de cierre de brechas grandes.
- El shell se describe por entregas verificadas (T035 IME, T034
  workers, T092 v2 puente); MainActivity/PaintView no se auditaron
  linea a linea en v2.

## v9 (R0453, RES-0177): submit de formularios TK-111-8

T111 v9 (contrato y entrega en R0453): TK-111-8 CERRADO v1 sobre
fb92d6cb. nav/FormSubmit.kt NUEVO (calculo PURO: action resuelto
contra la URL de la pagina como resolveUrl, metodo GET/POST,
controles con name: input de texto/hidden/password/... con value,
checkbox/radio solo con checked, textarea con su texto profundo;
codificacion application/x-www-form-urlencoded UTF-8, espacio como
+). NavController gana enviarFormulario(metodo, url, cuerpo): el
POST viaja como cuerpo de la peticion con Content-Type urlencoded y
una redireccion a GET suelta el cuerpo (RedirectPolicy manda).
Shell: toque largo dentro de un formulario ofrece Enviar
formulario (mismo camino explicito que los enlaces, R0189) y
navega por el camino del omnibox.

Exclusiones v1: multipart/form-data y text/plain NO envian (null
honesto; multipart en v2 con JsFetchApi), select sin recoger, sin
file, action sin <base> (se resuelve contra la pagina),
form.submit() desde JS sigue siendo SOLO el evento submit
(navegacion programatica en v2: re-entrancia con el event loop de
documento).
