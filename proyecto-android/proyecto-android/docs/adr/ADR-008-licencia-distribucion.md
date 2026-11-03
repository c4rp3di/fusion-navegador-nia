# ADR-008 — Licencia, distribución, claves e identidad

**Estado:** ACORDADO (R0003) · **Decisión del usuario** · **Base:** NIA-MAESTRO

- **Código cerrado.** Sin licencia open source ni permiso de
  redistribución.
- **Distribución:** solo APK con instalación manual. Sin Google Play
  (cuenta de desarrollador y revisión de Google fuera de alcance).
- **Claves de firma de release:** fuera del repositorio, custodia
  exclusiva del usuario. El repo solo firma debug.
- **Identidad provisional:** nombre "Navegador NIA", applicationId
  `app.nia.navegador`. Cambiables antes de la primera release; después
  Android los trata como identidad de actualización.
- Pagos/monederos: no requisito de aceptación; no se prohíben tiendas
  ni APIs generales (ADR-007).
