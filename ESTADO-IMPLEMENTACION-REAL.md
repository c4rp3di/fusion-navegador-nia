# Estado de implementación — verificado, parcial y pendiente

**Fecha de corte:** 2026-11-09  
**Repositorio:** [`c4rp3di/fusion-navegador-nia`](https://github.com/c4rp3di/fusion-navegador-nia)  
**Commit de `main` observado:** `d092229f5565e98014a83a4753d79c2c7090ca23`.  
**Fuente documental principal:** `ESTADO.md` y su cronología hasta R0294. El commit observado anuncia R0296/T094 v5, posterior al último registro incluido en esos espejos.

> «Verificada» aquí significa que el documento registra la aceptación y la evidencia correspondiente para una versión/alcance concreto. No equivale a conformidad completa con estándares web, a validación de todos los flujos en un dispositivo o sitio real, ni a que una tarea amplia esté terminada si su registro la mantiene `EN_CURSO`.

## 1. Con cierre registrado para alcances concretos

El maestro registra como verificadas o cerradas, entre otras, las bases de contratos/harness y los hitos de URL, HTML, DOM, CSS, layout y pintura (T001–T005 y T010–T016); versiones importantes del intérprete y del entorno JS (T018, T021, T023, T025); red, forms y storage (T026–T027, T033–T035); y entregas de privacidad, multimedia, torrent y subtítulos (T044–T050, en las versiones enumeradas en el maestro).

También constan hitos del shell como ADR-021/T068, componentes T069, NavController T071, BrowserActivity/omnibox T072, páginas de error T083 e identidad/TLS T084. Esto acredita entregas delimitadas, no que todas las funciones del navegador estén integradas o listas para uso diario.

**Hitos recientes con evidencia concreta en el registro:**

- **T053 v1 — reglas cosméticas de adblock:** R0294 la marca verificada y RES-0123 liberada; se registra confirmación de CI verde en el commit `24859cd1` (run #371).
- **T054 v1 — UI y ajustes de adblock:** R0288 la marca verificada y RES-0122 liberada, según la confirmación de CI registrada.
- **T094 v5 — panel declarativo de DevTools y persistencia de contadores:** aparece en el último commit observado, pero la ejecución más reciente de `contracts-tests` es el run #372 y terminó en **failure**. No considerarlo verificado basándose en el historial anterior; hace falta revisar el run y corregir o confirmar el resultado posterior.

## 2. Parcial, en curso o limitado a una versión

El propio registro mantiene varias tareas como `EN_CURSO` o describe entregas v1 con límites explícitos. Entre ellas están T009 (módulos declarativos), T019–T020 (runtime e intrínsecos JS), T022/T024 (bindings y APIs DOM), T028 (debugger), T031 (temas/configuración), T036 (multimedia), T038–T039 (matrices de compatibilidad), T046–T050 (descargas/torrent/streaming/subtítulos), T051–T055 (adblock), T060 (marcadores) y T094 (integración de módulos/UI, con actividad reciente).

La lectura correcta es **«hay una versión o parte implementada»**, no «la capacidad completa está cerrada». Por ejemplo, las matrices T038/T039 catalogan acciones y límites; no son prueba de que GitHub, Gmail o YouTube funcionen de extremo a extremo en el navegador.

## 3. Pendiente en el plan maestro

El registro identifica tareas aún pendientes, entre ellas:

- **Seguridad y robustez:** auditoría independiente de sandbox/IPC/plugins (T040), fuzzing continuo (T041), mediciones de rendimiento repetibles (T042) y preparación de release/firma/update (T043).
- **PWA y shell avanzado:** instalación/apertura/gestión de PWA y persistencia asociada (T061–T064), además de varias tareas P9 para gestos, selección/búsqueda de texto, permisos, compartir, descargas, ajustes y diagnóstico.
- **Integración de plataforma:** multimedia en segundo plano/casting y lifecycle avanzado (T065–T066, T109); integración real HTMLMediaElement y auditoría de transportes reales (T111 y T113); perfiles e integridad de persistencia (T112).
- **Otras evaluaciones:** WebGL/GPU, WebRTC y DRM figuran condicionados o pendientes según sus ADRs y necesidades del corpus; no son capacidades que deban presumirse implementadas.

La lista completa y las dependencias están en la tabla de tareas de `ESTADO.md`; este resumen no reemplaza ese registro.

## 4. Qué falta para afirmar «navegador funcional» con confianza

1. Resolver y documentar el resultado del run #372 sobre el commit actual; distinguir el resultado de tests de motor del smoke de compilación Android.
2. Ejecutar y registrar los smoke tests de realidad/dispositivo y sus medidas, como propone T067; identificar APK/commit probado y casos concretos.
3. Verificar los flujos P9 integrados de extremo a extremo (navegar, historial/pestañas, UI, errores, permisos y datos), no solo cada clase por separado.
4. Probar objetivos reales con cuentas de laboratorio y límites fechados, sin convertir una matriz de acciones o fixtures en una afirmación de compatibilidad general.
5. Completar auditorías de seguridad, aislamiento, red y persistencia antes de manejar cuentas o datos sensibles.

## 5. Desajustes documentales a revisar

- `ESTADO.md` llega a R0294 y el historial de `main` ya incluye R0296/T094 v5; publicar un nuevo espejo cuando corresponda y reflejar el resultado del run #372.
- `proyecto-android/README.md` declara revisión R0160, muy anterior al maestro; actualizar el encabezado o explicar que es una referencia histórica.
- La tabla de fases de `ESTADO.md` marca `NO_INICIADA` para fases pese a los hitos verificados que el propio documento registra. Aclarar si es una tabla sin actualizar o corregirla siguiendo el protocolo de revisiones.
- Mantener separados los estados `ENTREGADA`, `CI PASS`, `VERIFICADA`, `INTEGRADA` y `probada en dispositivo/sitio real`; describen evidencias diferentes.

## Conclusión

Hay una cantidad sustancial de implementación y pruebas automatizadas documentadas, pero la evidencia disponible no justifica llamar al producto un navegador completo o compatible de forma amplia. La señal inmediata es el run #372 fallido; después, la prioridad de evaluación es la integración P9 y la validación real en dispositivo/sitios, acompañada por las auditorías de seguridad y rendimiento pendientes.

## Lectura relacionada

- [Resumen ejecutivo](RESUMEN-EJECUTIVO.md)
- [Archivo maestro de estado](ESTADO.md)
- [Cronología reciente](ESTADO-registro-2.md)
- [Histórico de reservas](ESTADO-reservas-1.md)
