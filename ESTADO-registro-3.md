# ESTADO — Continuación de la cronología del Navegador NIA (3)

Continuación del registro distribuido del maestro del proyecto. Cubre desde R0295 y añade la revisión de reparación de CI iniciada tras R0296. La confirmación de cierre de R0297 queda pendiente del resultado de GitHub Actions comunicado por el usuario.

| Revisión | Fecha | Autor | Base | Cambio | Aceptación |
|---|---|---|---|---|---|
| R0295 | 2026-11-09 | Asistente único (Vibe) | R0294 | PUBLICACIÓN de espejos del maestro a petición del usuario; ESTADO.md hasta R0294, ESTADO-registro-1.md R0001–R0238 y ESTADO-registro-2.md R0239–R0294. | Publicación reflejada en main; los espejos no incorporan por sí mismos revisiones posteriores. |
| R0296 | 2026-11-09 | Asistente único (Vibe) | R0295 | ENTREGA T094 v5, RES-0124: panel DevTools declarativo y persistencia de contadores del filtro. Código en commit d092229f5565e98014a83a4753d79c2c7090ca23. | CI contracts-tests run #372 ROJO: 2537 tests completados, 1 fallo: PluginNetFilterTest.bloqueoMarcaPendientesYGuardarEscribeTsv, aserción de recarga en la línea 647. No declarar T094 v5 verificada. |
| R0297 | 2026-11-09 | Asistente único (Vibe) | R0296 | DIAGNÓSTICO Y REPARACIÓN PROPUESTA de RES-0124: se aisló la recarga de persistencia del vaciado de memoria del contador, ajustando el orden del test. | Pendiente de verificar en CI. |
| R0298 | 2026-11-09 | Asistente único (Vibe) | R0297 | NUEVO ROJO en CI sobre el fix de R0297: el compilador reporteda `Type mismatch: inferred type is Map<..., ...> but List<PanelDeclarado> was expected` en `PluginNetFilterTest.kt`. Causa raíz: se pasaba `emptyMap()` donde `UiModelDeclarado` requiere tres listas (`List<AjusteDeclarado>`, `List<AccionDeclarada>`, `List<PanelDeclarado>`). Se corrige con `emptyList()` en las dos instancias de `EntornoModulo(..., UiModelDeclarado(...), ...)`. | Pendiente de nuevo run tras aplicar el arreglo. |

## Evidencia y límites de R0298

- Run fallido: [contracts-tests #374](https://github.com/c4rp3di/fusion-navegador-nia/actions/runs/42689167127).
- Error exacto: `Type mismatch: inferred type is Map<???, ???> but List<PanelDeclarado> was expected`.
- Fichero afectado: `proyecto-android/engine-core/src/test/java/nia/engine/modules/PluginNetFilterTest.kt`.
- Corrección: `UiModelDeclarado(emptyList(), emptyList(), emptyList())` para las dos instancias donde el constructor del test inyectaba un entorno módulo con lista de paneles vacía.
- Cierre: pendiente del siguiente run de CI tras el fix puntual.
