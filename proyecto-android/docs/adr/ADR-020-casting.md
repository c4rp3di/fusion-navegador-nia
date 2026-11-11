# ADR-020 — Casting a Smart TV: protocolos y subtítulos

**Estado:** ACEPTADO (R0100, RES-0054; Enmienda 1 en R0401, RES-0159) · **Base:** NIA-MAESTRO

- Problema: T066 (envío multimedia a Smart TV con subtítulos) necesita
  protocolos objetivo, dispositivos y la política de subtítulos del
  receptor.
- Decisión del usuario (R0100): **ambos por fases**. v1: **DLNA/UPnP AV**
  (MediaRenderer descubierto por SSDP). v2: **Google Cast como
  integración OPCIONAL y separada** — la base del navegador funciona SIN
  Google Play services (APK manual, ADR-008) y la disponibilidad de Cast
  se comprueba en el dispositivo antes de ofrecerse.
- DLNA v1: implementación mantenible y probada; NO se impone JVM puro ni
  cero dependencias si compromete robustez — las dependencias pasan por
  la misma auditoría que ADR-016 (licencia, wrapper, transitivas, tamaño
  de APK, memoria y consumo). ANTES de implementar (apertura de T066) se
  documentan dispositivos objetivo, dependencias, permisos y límites.
- Subtítulos (requisito del reproductor): la pista ELEGIDA acompaña al
  vídeo; enviar WebVTT preferente (conversión de SRT/ASS/SSA solo si no
  pierde información esencial); se PRUEBA visualización, sincronía tras
  seek, cambio de pista y opciones de configuración en CADA receptor
  objetivo; NO se declara compatibilidad general de subtítulos por el
  mero hecho de enviar el vídeo — si el receptor no admite pistas
  externas, incompatibilidad honesta y visible.
- Servidor temporal: para pistas o medios locales, servidor en la
  interfaz LAN — **Enmienda 1 (R0401, RES-0159)**: el loopback original
  es imposible para una TV remota, que no puede descargar del teléfono;
  el endpoint escucha en la IPv4 de la LAN SOLO si el receptor exige
  URL, acotado a la sesión y al dispositivo, sirve EXCLUSIVAMENTE el
  medio y la pista elegidos en rutas con token aleatorio de sesión (sin
  listados ni rutas arbitrarias), con caducidad por inactividad y
  cancelación y limpieza al desconectar (mismo patrón que la exposición
  local del streaming de ADR-016). Las URLs remotas van directas al
  receptor, JAMAS por el servidor.
- Límites: casting de URL/media NO es mirroring de pantalla; DRM,
  autenticación, códecs o restricciones del sitio pueden impedir el
  envío (ADR-015); la transcodificación con subtítulos incrustados queda
  como ruta opcional y costosa, fuera de v1.
- Pruebas: descubrimiento y comandos AVTransport con fakes, envío de
  pista, sincronía tras seek, desconexión limpia y un receptor SIN
  soporte de subtítulos externos mostrando la limitación honesta.
  Dueño: asistente único (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T066 y tras la primera matriz de receptores
  reales.
