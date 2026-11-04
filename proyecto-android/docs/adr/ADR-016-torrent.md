# ADR-016 — Cliente torrent, red y exposición local del streaming

**Estado:** ACEPTADO (R0100, RES-0054) · **Base:** NIA-MAESTRO

- Problema: T047/T048 requieren el protocolo BitTorrent (magnet/.torrent,
  DHT, peers, piezas y verificación de hashes); implementarlo desde cero
  es caro y arriesgado, y la elección de cliente condiciona red,
  permisos y tamaño del APK.
- Opciones evaluadas: (1) cliente propio en Kotlin puro; (2) biblioteca
  externa auditada; (3) binario controlado descargado. Decisión del
  usuario (R0100): **biblioteca externa auditada**, priorizando robustez
  y funcionalidad sobre mantener cero dependencias a cualquier coste.
- Auditoría obligatoria antes de seleccionar la candidata (apertura de
  T047): licencia de la biblioteca, de su wrapper y de TODAS sus
  dependencias transitivas (compatibles con código cerrado y distribución
  APK manual, ADR-008); compatibilidad con el flujo Android/Fusion
  (JVM/Android, minSdk 26); tamaño del APK, memoria y consumo (contra el
  baseline de los presupuestos medibles R0099).
- Arquitectura obligatoria: el protocolo vive DETRÁS de un adaptador
  reemplazable (frontera TorrentClient inyectable, como FetchTransport o
  MediaBackend); permisos, almacenamiento y límites de red/sembrado los
  controla SIEMPRE el navegador vía broker (T006), nunca el módulo por
  su cuenta.
- Respaldo: si NINGUNA candidata cumple la auditoría, se presentan los
  resultados y las alternativas ANTES de decidir un cliente propio.
- Exposición local del streaming (T048): archivo parcial reproducible
  servido por un servidor local en loopback gestionado por el broker,
  puerto efímero, SOLO durante la reproducción, con cancelación y
  limpieza al desconectar; sin exposición pública ni persistente.
- Límites (D-003): módulo OPCIONAL, sin buscador de torrents, sin
  recomendaciones ni promoción, sin garantía de códecs; riesgos de
  exposición de IP, batería y almacenamiento gestionados con límites,
  cuotas, validación de rutas y borrado seguro; neutralidad del
  protocolo.
- Pruebas: fixtures .torrent/magnet contra el adaptador con fakes;
  auditoría de permisos por capability; medición de APK/memoria contra el
  baseline. Dueño: asistente único (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T047 (selección de la candidata) y en el
  primer reality smoke con torrent activo (T067).
