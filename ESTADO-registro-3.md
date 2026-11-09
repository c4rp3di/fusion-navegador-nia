# ESTADO — Continuación de la cronología del Navegador NIA (3)

Continuación del registro distribuido del maestro del proyecto. Cubre desde R0295 y añade la revisión de reparación de CI iniciada tras R0296. La confirmación de cierre de R0297 queda pendiente del resultado de GitHub Actions comunicado por el usuario.

| Revisión | Fecha | Autor | Base | Cambio | Aceptación |
|---|---|---|---|---|---|
| R0295 | 2026-11-09 | Asistente único (Vibe) | R0294 | PUBLICACIÓN de espejos del maestro a petición del usuario; ESTADO.md hasta R0294, ESTADO-registro-1.md R0001–R0238 y ESTADO-registro-2.md R0239–R0294. | Publicación reflejada en main; los espejos no incorporan por sí mismos revisiones posteriores. |
| R0296 | 2026-11-09 | Asistente único (Vibe) | R0295 | ENTREGA T094 v5, RES-0124: panel DevTools declarativo y persistencia de contadores del filtro. Código en commit d092229f5565e98014a83a4753d79c2c7090ca23. | CI contracts-tests run #372 ROJO: 2537 tests completados, 1 fallo: PluginNetFilterTest.bloqueoMarcaPendientesYGuardarEscribeTsv, aserción de recarga en la línea 647. No declarar T094 v5 verificada. |
| R0297 | 2026-11-09 | Asistente único (Vibe) | R0296 | DIAGNÓSTICO Y REPARACIÓN PROPUESTA de RES-0124: el test guarda el TSV y luego llama a reiniciarContadores() con el archivo todavía enlazado; ese método persiste correctamente el vaciado, por lo que la recarga posterior devuelve 0 y no 1. Ajuste propuesto solo en PluginNetFilterTest.kt: desvincular la persistencia antes de limpiar el estado en memoria y volver a vincular el TSV para comprobar la recarga. No cambiar semántica de producción ni el test independiente que verifica que reiniciarContadores persiste el archivo vacío. | Pendiente de aplicar el cambio, revisar y confirmar el siguiente run de CI. Hasta que el usuario confirme 🟢, R0297 y T094 v5 no están cerradas ni verificadas. |

## Evidencia y límites de R0297

- Run fallido: [contracts-tests #372](https://github.com/c4rp3di/fusion-navegador-nia/actions/runs/42685280763).
- Archivo afectado: `proyecto-android/engine-core/src/test/java/nia/engine/modules/PluginNetFilterTest.kt`, prueba `bloqueoMarcaPendientesYGuardarEscribeTsv`.
- Causa: `reiniciarContadores()` limpia contadores y escribe el TSV vacío cuando hay un archivo configurado; el test debe desactivar temporalmente el archivo para aislar la limpieza en memoria de la comprobación de persistencia.
- Cambios de producción: ninguno propuesto.
- Cierre: pendiente de modificación puntual, revisión del resultado y confirmación del usuario.
