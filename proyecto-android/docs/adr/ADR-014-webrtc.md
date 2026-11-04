# ADR-014 — WebRTC

**Estado:** ACEPTADO (R0095, RES-0052) · **Base:** NIA-MAESTRO

- Posición: **EXCLUIDO** del núcleo v1. Sin RTCPeerConnection,
  getUserMedia ni pilas externas (el veto a motores y pilas externos de
  sustitución del maestro cubre las pilas WebRTC).
- Requisito real evaluado (R0095): fuera de los objetivos. Las
  videollamadas de Gmail son apps separadas; YouTube en vivo funciona
  por HTTP/websocket; GitHub no lo usa en las funciones objetivo.
- Privacidad: getUserMedia (cuando un requisito real lo exija) pasa por
  el modelo de permisos del broker (autorización por contexto asignado,
  sin acceso libre del renderer), igual que el resto de APIs sensibles.
- Ticket: **T058** (P7+): WebRTC por requisito real del corpus;
  PENDIENTE, sin implementación.
