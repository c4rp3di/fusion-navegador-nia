# Matriz de transportes reales (T113 v1)

T113 v1 (R0410, RES-0163): auditoria X04 del Delta P9 sobre las
fuentes REALES del motor y del shell en el sha 46e5781d (canonico
verificado, motor 2562 tests). REGLA X04: nada se anuncia como
cliente real funcionante sin transporte REAL en el producto; los
transportes inyectados por el harness son guiones de CI, JAMAS
producto. Metodo: lectura directa con conteos medidos (jamas de
memoria) y un ticket por cada bloqueo REAL.

Alcance auditado (21 fuentes medidas en 46e5781d):
net/HttpTransport.kt (174 l), net/Fetch.kt (331), net/HttpCache.kt
(228), net/RedirectPolicy.kt (68), net/RequestFilter.kt (74),
net/EstadoRed.kt (78), net/SiteSecurity.kt (109), net/CookieJar.kt
(292), net/GestorCookies.kt (78), net/Cors.kt (160),
torrent/Torrent.kt (374), torrent/TorrentNet.kt (362),
torrent/TorrentUdp.kt (127), torrent/TorrentDht.kt (180),
torrent/TorrentStream.kt (86), download/DownloadEngine.kt (458),
media/MediaEngine.kt (222), media/OpenSubtitles.kt (534),
shell: PlatformTorrent.kt (75), PlatformDownloadTransport.kt
(99), MainActivity.kt (1289, regiones guiones 763-893),
BrowserActivity.kt (6825, regiones descargas/torrents).

## Veredictos

- SOPORTADO: ruta real, verificada y cableada en el producto.
- PARCIAL: ruta real con una brecha documentada.
- NO: no existe en el producto; el bloqueo abre un ticket.

## Matriz

| Bloque | Veredicto | Evidencia (sha 46e5781d) | Brecha |
|---|---|---|---|
| HTTP cliente (navegacion) | SOPORTADO | HttpTransport.kt 174 l: HttpURLConnection real con timeouts connect/read 10 s (38-39), TLS por HttpsURLConnection con rechazo SSLException -> TLS_INVALID (9-10, 58), cancel real token -> connection.disconnect() (64-65), instanceFollowRedirects false (59) | - |
| Redirecciones | SOPORTADO | RedirectPolicy.kt 68 l: 301/302/303/307/308, tope 20 saltos, POST -> GET solo en 301/302, degradacion https -> http prohibida (56-57) | - |
| Fallos tipificados / DNS | SOPORTADO | Fetch.kt 331 l: TransportFailure TLS_INVALID/DNS/NETWORK/TOO_LARGE (89) con mapa honesto (237-239); generaciones con cancel Replace real (110, 133); DNS del sistema via HttpURLConnection, sin resolutor propio (documentado, no prometido) | - |
| Tope de cuerpo | SOPORTADO | Fetch.kt: CuerpoExcedido -> TOO_LARGE sobre maxBodyBytes del adaptador | - |
| Cache HTTP | PARCIAL | HttpCache.kt 228 l: revalidacion ETag/If-None-Match medida (144-145) | sin Cache-Control (no-store/no-cache/max-age) medido: TK-113-2 |
| Detalle TLS (certificado) | PARCIAL | SiteSecurity.kt 109 l define TlsInfo emisor/sujeto/validez (21-43) "SOLO cuando el adaptador los expone"; Fetch.kt 85 fija seguridad: TlsInfo? = null | HttpTransport JAMAS llena TlsInfo: el panel no muestra emisor ni validez (TK-113-1) |
| HTTP/2 y HTTP/3 | NO | la pila de HttpTransport/PlatformDownloadTransport/PlatformTorrent es HttpURLConnection (HTTP/1.1); 0 hits de alt-svc/h2 en el repo | TK-113-3: solo con ADR explicito, JAMAS prometiendo stacks inexistentes |
| Proxy | NO | 0 hits de proxy en el paquete net/ del motor | TK-113-4 |
| Cookies y CORS | SOPORTADO | CookieJar.kt 292 l RFC 6265 con SameSite strict/none/lax; Cors.kt 160 l subconjunto Fetch (preflight, cabeceras prohibidas); GestorCookies.kt 78 l con valores ocultos por defecto | persistencia en disco PENDIENTE conocida de T026/T033 (sin ticket nuevo) |
| Descargas | SOPORTADO | DownloadEngine.kt 458 l: estados QUEUED..FAILED, retry SOLO NETWORK/DNS (173-174), cancel por CancellationTokenSource; shell con maxConcurrent 2 y maxRetries 1 (BrowserActivity 504) | - |
| Reanudacion con Range | SOPORTADO | PlatformDownloadTransport.kt 99 l REAL: Range bytes=startByte- (49), estado 200/206 validado (66-70), startAt honesto solo con 206 (71), total = startByte + restLength solo con 206 (73), timeouts 10 s (33-34), cancel -> disconnect (51-52), TLS/DNS tipificados (58, 61) | - |
| Torrent peers TCP | SOPORTADO | PlatformTorrent.kt 75 l: PlatformPeerTransport sobre java.net.Socket con InetSocketAddress (51-62: connect 20 s, soTimeout 20 s, close real); TorrentNet.kt 362 l verifica el handshake contra infohash (278-283); piezas verificadas SHA-1 (Torrent.kt 374 l) | - |
| Torrent tracker HTTP | SOPORTADO | PlatformTrackerTransport sobre HttpURLConnection (28-39, 15 s); magnet real con metadatos ut_metadata de peers reales en la UI (BrowserActivity 3496+); cuota honesta: UNA bajada a la vez, sin siembra, piezas en memoria | - |
| Torrent tracker UDP (BEP-15) | NO | TorrentUdp.kt 127 l: nucleo puro con UdpTransport inyectable; sin adaptador DatagramSocket para torrent en el producto (el unico DatagramSocket del repo vive en CastingDlna.kt, SSDP del casting T066); MainActivity 851-867 monta un GUION con respuestas enlatadas de harness | TK-113-5 |
| DHT (BEP-5) | NO | TorrentDht.kt 180 l: Kademlia pura con DhtTransport inyectable; sin adaptador real; MainActivity 872-884 GUION de harness | TK-113-5 |
| Streaming de torrent | SOPORTADO | TorrentStream.kt 86 l: lectura por rangos SOLO desde piezas COMPLETAS verificadas (isRangeReady 80) | - |
| Media remota | SOPORTADO | MediaEngine.kt 222 l: backend inyectable; fuentes Remote http/https y Local file/content (5-7, 180); reproduccion real por MediaPlayer del shell | - |
| Subtitulos OpenSubtitles | SOPORTADO | OpenSubtitles.kt 534 l: API REST v1 (48) SOBRE FetchManager (transporte inyectable: HttpTransport en dispositivo), User-Agent propio (232, 288), probarConexion (145) | - |
| Estado de red y redaccion | SOPORTADO | EstadoRed.kt 78 l: linea de UNA pestana JAMAS mezclada con otra, URL redactada con SecretRedactor (57) | - |

## Tickets por bloqueo real

- TK-113-1: exponer el certificado TLS real (emisor/sujeto/ventana
  de validez) desde HttpTransport hacia TlsInfo; hoy el panel de
  identidad solo dice "Conexion cifrada" sin detalles.
- TK-113-2: parseo de Cache-Control (no-store/no-cache/max-age)
  en HttpCache para respetar la directiva del servidor.
- TK-113-3: HTTP/2/HTTP/3 SOLO con ADR explicito (stack propio o
  Cronet); esta matriz JAMAS promete stacks inexistentes.
- TK-113-4: soporte de proxy HTTP(S) del sistema con exclusiones
  documentadas.
- TK-113-5: adaptador UDP real (DatagramSocket) para trackers
  BEP-15 y DHT BEP-5 con cancelacion/cierre y consentimiento;
  hasta entonces la UI solo anuncia trackers HTTP (como hoy).

## Exclusiones honestas de v1

- Los guiones de MainActivity (763-893) son harness de CI, NO
  producto: los transportes inyectados alli JAMAS se anuncian
  como cliente real funcionante (cotejo X04).
- DNS: resolucion del sistema via HttpURLConnection con fallo
  tipificado; sin DoH ni resolutor propio (X05 los evaluara como
  producto).
- MainActivity y BrowserActivity no se auditaron linea a linea
  completos: solo las regiones de guiones torrent (763-893) y de
  descargas/torrents (504, 3496-3660, 3912-4090).
- El ensayo de fixture propio en red y dispositivo (aceptacion
  X04) pertenece a las versiones de cierre (v2+), no a esta linea
  base.
- Cuota honesta del torrent documentada en la UI (T103): UNA
  bajada, sin siembra, piezas en memoria sin escritura automatica
  a disco.
