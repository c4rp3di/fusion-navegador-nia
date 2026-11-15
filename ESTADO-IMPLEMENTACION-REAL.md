# Estado de implementación — verificado, parcial y pendiente

**Fecha de corte:** 2026-11-14  
**Repositorio:** [`c4rp3di/fusion-navegador-nia`](https://github.com/c4rp3di/fusion-navegador-nia)  
**Commit de `main` observado:** `5dfff5afe697c5eaa971aeef7a70891f9d809780` (espejo del maestro a R0459).  
**Fuente documental principal:** `ESTADO-registro-4.md` (cronología R0299–R0459) y `ESTADO.md` (snapshot R0294 + delta R0459).

> «Verificada» aquí significa que el documento registra la aceptación y la evidencia correspondiente para una versión/alcance concreto. No equivale a conformidad completa con estándares web, a validación de todos los flujos en un dispositivo o sitio real, ni a que una tarea amplia esté terminada si su registro la mantiene `EN_CURSO`.

## 1. Con cierre registrado para alcances concretos

El maestro registra como verificadas o cerradas, entre otras, las bases de contratos/harness y los hitos de URL, HTML, DOM, CSS, layout y pintura (T001–T005 y T010–T016); versiones importantes del intérprete y del entorno JS (T018, T021, T023, T025); red, forms y storage (T026–T027, T033–T035); y entregas de privacidad, multimedia, torrent y subtítulos (T044–T050, en las versiones enumeradas en el maestro).

**Hitos verificados en el periodo R0299–R0459 (detalle en `ESTADO-registro-4.md`):**

- **T111 v1–v10 — integración de plataforma web, TK-111-6 CERRADO v1 (R0459):** matriz de auditoría con evidencia sobre fuentes reales, charset del documento y subrecursos con BOM, URLs relativas con base, scripts async/defer/module, bidi/RTL, pushState/replaceState con verificación de origen, event loop de documento vivo, pintura de imágenes, submit de formularios GET/POST y @font-face/@import. CI verde sobre `9c50051`.
- **T011 v5/v6 (R0306/R0310):** cancelación real de peticiones en vuelo al invalidar (RES-0125) y tope de cuerpo en respuestas HTTP (RES-0126); cierran los puntos 1 y 3.2 del informe externo recibido en R0301.
- **T026 v3/v4 (R0313/R0317):** presupuesto LRU de HttpCache (RES-0127) y Set-Cookie múltiples (RES-0128).
- **T071 v2–v2f (R0418–R0438):** frame tree de iframes, sandbox, scripts en subframes, about:blank sintético, iframes anidados y CSP por directiva (RES-0165–RES-0170).
- **T065/T066 (R0368–R0403):** audio focus, media session/foreground service y casting DLNA con subtítulos (RES-0147, RES-0154, RES-0159).
- **T092 (R0357/R0360):** player standalone y puente HTMLMediaElement contra el backend real (RES-0144/RES-0145).
- **T093 v5–v10 (R0331–R0348):** ajustes con secciones Red, Media y Perfiles honestas, tema claro/oscuro/auto y buscador real.
- **T094 v5 (R0300):** panel DevTools declarativo y contadores persistentes del filtro (RES-0124); el run #372 rojo quedó resuelto con el fix `26da043e`.
- **T103–T105 (R0351–R0400):** pantalla torrents con transportes reales, UI OpenSubtitles con búsqueda por hash y streaming torrent con prioridad de piezas (RES-0142, RES-0151, RES-0156, RES-0158).
- **T109 v1–v3 (R0363–R0390):** restauración de sesión tras proceso muerto, política de media y PiP (RES-0146/RES-0148/RES-0155).
- **T112 v1–v4 (R0372–R0409):** gestor de backups versionados y atómicos, sección Copias, perfiles nombrados e import/export por SAF (RES-0149/RES-0152/RES-0157/RES-0162).
- **T113 v1/v2 (R0412/R0415):** matriz de transportes reales y certificado TLS conservado en cache (RES-0163/RES-0164).

Esto acredita entregas delimitadas con CI verde y confirmación del usuario; no que todas las funciones del navegador estén integradas o listas para uso diario.

## 2. Parcial, en curso o limitado a una versión

El propio registro mantiene varias tareas como `EN_CURSO` o describe entregas con límites explícitos. Entre ellas están T009 (módulos declarativos), T019–T020 (runtime e intrínsecos JS), T022/T024 (bindings y APIs DOM), T028 (debugger), T031 (temas/configuración), T036 (multimedia), T038–T039 (matrices de compatibilidad), T046–T050 (descargas/torrent/streaming/subtítulos), T051–T055 (adblock), T060 (marcadores), T087/T088 (share/VIEW: v1 verificada, pendiente el rol de navegador por defecto y smoke) y T111 (arco de compatibilidad en marcha).

La lectura correcta es **«hay una versión o parte implementada»**, no «la capacidad completa está cerrada». Por ejemplo, las matrices T038/T039 catalogan acciones y límites; no son prueba de que GitHub, Gmail o YouTube funcionen de extremo a extremo en el navegador.

## 3. Pendiente en el plan maestro

El registro identifica tareas aún pendientes, entre ellas:

- **Seguridad y robustez (P8):** auditoría independiente de sandbox/IPC/plugins (T040), fuzzing continuo (T041), mediciones de rendimiento repetibles (T042) y preparación de release/firma/update (T043).
- **Rendimiento:** memoria/scroll/paint con presupuestos medidos (T110).
- **PWA y shell avanzado:** instalación/apertura/gestión de PWA y persistencia asociada (T061–T064, T091), accesibilidad y temas (T070), shortcuts/widget/tile (T097).
- **Condicionados:** WebGL/GPU (T057), WebRTC (T058) y DRM (T059) figuran condicionados o pendientes según sus ADRs y necesidades del corpus; no son capacidades que deban presumirse implementadas.
- **De la auditoría externa (R0301/R0302):** los puntos ya cerrados están en la lista de verificados; los restantes (p. ej. Thread por navegación sin tope en el shell) siguen en cola como incrementos del carril red/shell.

La lista completa y las dependencias están en la tabla de tareas de `ESTADO.md` (snapshot R0294 + delta R0459); este resumen no reemplaza ese registro.

## 4. Qué falta para afirmar «navegador funcional» con confianza

1. Cerrar el arco T111 de compatibilidad con sitios reales y registrar los fallos causales con fixtures upstream.
2. Ejecutar y registrar los smoke tests de realidad/dispositivo de los hitos recientes (APK/commit probado y casos concretos), siguiendo el método T067.
3. Verificar los flujos P9 integrados de extremo a extremo (navegar, historial/pestañas, UI, errores, permisos y datos), no solo cada clase por separado.
4. Medir rendimiento con presupuestos aceptados (T110) antes de interpretar la cantidad de tests como fluidez.
5. Completar auditorías de seguridad, aislamiento, red y persistencia (T040–T043) antes de manejar cuentas o datos sensibles.

## 5. Desajustes documentales a revisar

- `ESTADO.md` publica tablas y fichas al snapshot R0294 con un delta de estados hasta R0459; la re-publicación íntegra del espejo queda a petición (R0200).
- `ESTADO-registro-3.md` usa numeración DIVERGENTE (sus R0297/R0298 no son los del maestro) y su narrativa del fix de T094 v5 difiere de la del maestro (R0299: el fix válido fue `26da043e`); reconciliar solo a petición.
- `proyecto-android/README.md` declara revisión R0160; sus notas son una referencia histórica y el estado vigente vive en el maestro y sus registros.
- Mantener separados los estados `ENTREGADA`, `CI PASS`, `VERIFICADA`, `INTEGRADA` y `probada en dispositivo/sitio real`; describen evidencias diferentes.

## Conclusión

Hay una cantidad sustancial de implementación y pruebas automatizadas documentadas, con CI en verde (motor 2.562 tests) y smoke de dispositivo superados; el arco de integración web (T111) avanza rápido sobre sitios reales. La prioridad de evaluación sigue siendo la validación en dispositivo/sitios de los hitos recientes, el rendimiento medido (T110) y las auditorías de seguridad y release (P8) pendientes.

## Lectura relacionada

- [Resumen ejecutivo](RESUMEN-EJECUTIVO.md)
- [Archivo maestro de estado](ESTADO.md)
- [Cronología vigente](ESTADO-registro-4.md)
- [Histórico de reservas](ESTADO-reservas-1.md)
