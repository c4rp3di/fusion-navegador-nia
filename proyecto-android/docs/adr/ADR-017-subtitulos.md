# ADR-017 — Subtítulos: formatos v1, codificación y OpenSubtitles

**Estado:** ACEPTADO (R0100, RES-0054) · **Base:** NIA-MAESTRO

- Problema: T049 (subtítulos locales e incluidos) y T050 (OpenSubtitles)
  necesitan formatos de v1, política de codificación y una API/almacén
  de credenciales definidos antes de reservar.
- Formatos v1: **WebVTT y SRT** (subconjunto práctico: cues con tiempos,
  texto plano y saltos de línea). ASS/SSA queda DIFERIDO a v2 (lectura de
  eventos básicos); los estilos avanzados de ASS están fuera y la
  degradación se documenta.
- Codificación: UTF-8 obligatorio con detección de BOM; si el archivo no
  es UTF-8 válido se intenta CP1252/Latin-1 con AVISO explícito en la UI
  (nunca silencioso y sin prometer fidelidad).
- OpenSubtitles: proveedor EXTERNO OPCIONAL (T050); API REST de
  opensubtitles.com con la API key del usuario; búsqueda por nombre,
  metadatos y hash; las credenciales las custodia el BROKER (T006) en la
  configuración (T007) y NUNCA viajan en exportaciones de configuración
  ni en eventos de DevTools (criterio transversal R0092; redacción en la
  ingesta como el resto de secretos, T008).
- Opciones del reproductor (tamaño, posición, color, fondo/contorno,
  opacidad, tipografía, idioma y pista activa, retardo manual,
  sincronización automática cuando sea viable): preferencias persistentes
  por perfil (T033), aplicadas en reproducción local y, cuando el
  receptor lo permita, durante casting (T066/ADR-020).
- Límites: sin teletexto ni subtítulos forzados de contenedores;
  OpenSubtitles sujeto a sus condiciones de servicio y a la disponibilidad
  del servicio; sin descarga automática sin configurar.
- Pruebas: fixtures de cada formato, round-trip de desfase/sincronización,
  redacción de credenciales en eventos e importaciones. Dueño: asistente
  único (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T049/T050 y cuando el casting (ADR-020)
  exija pistas adicionales.
