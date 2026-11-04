# ADR-015 — DRM, CDM y codecs protegidos (YouTube)

**Estado:** ACEPTADO (R0095, RES-0052) · **Base:** NIA-MAESTRO

- Posición: DRM **fuera del núcleo**. La reproducción base de YouTube
  (T039: search/play/audio/seek/calidad/subs/fullscreen) se sirve con
  codecs de plataforma (android.media.MediaPlayer del sistema, el mismo
  patrón de backend inyectable que T036 v1 con PlatformMediaBackend,
  sin dependencias nuevas) para los formatos que la plataforma decodifica.
- El contenido PROTEGIDO queda «aparte», como define el propio T039;
  NIA no promete reproducción de contenido protegido en v1.
- Cuando se aborde: el CDM sería **MediaDrm de plataforma Android**
  (API de sistema, como MediaPlayer), el nivel que ofrezca el
  dispositivo, sin promesas de calidad; PROHIBIDO empotrar CDM externos
  o binarios sin ADR propio.
- Distribución (riesgo documentado): el provisioning del CDM de
  plataforma puede depender de la vía de distribución (APK manual,
  ADR-008); si una vía exige cambios, se decide en su propio ADR.
- Dependencias externas: solo APIs de plataforma (nivel SDK 26+); si un
  módulo futuro (p. ej. contenedor propio de streaming, T047/T048)
  añade dependencias, pasan por el criterio R0092 de dependencias
  revisadas (licencia, origen, versión y límites declarados).
- Ticket: **T059** (P7): descomposición real del DRM de YouTube
  protegido; se abre SOLO cuando la matriz T039 confirme el requisito;
  PENDIENTE.
