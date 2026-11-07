# ADR-005 — Módulos instalables: contenedor .niamod, confianza y runtime

**Estado:** PROPUESTO (requisito de R0003; enmienda .niamod del Delta P9, R0137) — redactado en T096 v1 SIN APROBAR: hasta la decisión del usuario, .niamod NO está aprobado · **Base:** NIA-MAESTRO

- Problema: los módulos instalables son requisito acordado y el motor ya tiene TRES contratos verificados: el registro declarativo de T009 (manifiesto «clave = valor», id [a-z0-9.-] ≤ 64, entry relativa sin «..» ≤ 256, permisos solo storage.read/network.fetch, manifiesto ≤ 4096, versión futura → UNSUPPORTED), el runtime restringido de T029 (entry JS interpretado con cuotas de plugin 100.000 pasos / 10.000 allocs, sin acceso host libre: solo el objeto nia con onStartup/onNavigate y onRequest tras network.fetch) y la revocación/rollback de T030 (unload → DENIED, update con rollback automático sin recompilar). Falta la decisión de contenedor y confianza: cómo se distribuye, instala, firma, actualiza y revoca un paquete real.
- Reconciliación (lo que este ADR NO rompe): el manifiesto sigue siendo «clave = valor» y el ModuleRegistry de T009 se usa TAL CUAL; el entry sigue interpretado con cuotas de plugin (T029) y la revocación/update con rollback siguen como en T030. Las cuotas de plugin son INDEPENDIENTES de las cuotas JS del shell (T100): los ajustes del usuario no aplican a módulos y un módulo JAMÁS puede pedir más cuota (no existe API para ello). Los tests de T009/T029/T030 siguen pasando sin cambios: este documento no modifica código.
- Contenedor .niamod (PROPUESTA): un .niamod es un ZIP (deflate o stored, java.util.zip de la plataforma, cero dependencias) con estructura fija: manifest.txt (el manifiesto de T009), entry.js (el entry declarado, única lógica ejecutable del paquete) y datos/… de SOLO LECTURA sin lógica (p. ej. listas EasyList para ADR-018). En api-version 1 el módulo NO tiene API para leer datos/: quien los consume es el host (el matcher de T051/ADR-018 es código del host; el módulo embarca datos y lógica JS acotada). El instalador rechaza entradas no declaradas, duplicadas, con symlink o fuera de regla.
- Schema del manifiesto v2 (migración compatible de v1): versión 2 añade claves OPCIONALES sobre las cuatro de v1 — name ≤ 64, description ≤ 256, api-version (entero, por defecto 1), category (lista combinable de background, contextual, panel), capabilities (subconjunto de observar, bloquear, modificar), author-key (SPKI base64) y next-key (SPKI base64, rotación). Los builds actuales ya responden UNSUPPORTED a una versión futura (implementado y testeado en T009); CURRENT_VERSION sube a 2 cuando T094/T095 lo implementen. Instalar HOY con texto de manifiesto directo al registry sigue válido: .niamod es una capa de contenedor y confianza ENCIMA (T095 desempaqueta, valida y llama al registry sin cambios).
- Límites ANTES del pre-parser (orden de validación; «sin agotar memoria» de T095): (1) solo con la central directory del ZIP, sin leer contenidos: entradas ≤ 64, contenedor ≤ 4 MiB, cada entrada ≤ 2 MiB, total descomprimido ≤ 8 MiB, ratio por entrada ≤ 100:1 (zipbomb), sin symlinks, nombres relativos sin «..» (validEntry de T009 extendida al árbol); (2) manifest.txt extraído con tope 4096 (T009), todavía sin parsear; (3) hash SHA-256 por archivo con lecturas acotadas (stream con tope, nunca un byte[] grande antes del tope) y CRC verificado; (4) canonicalización y verificación de firma; (5) recién entonces parseo del manifiesto (T009), coherencia capabilities/permissions y api-version soportada (futura → UNSUPPORTED); (6) pre-parse del entry (JsParser.parse de T029); (7) staging y activación con el update/rollback de T030. Nada se parsea ni evalúa antes de pasar 1-4.
- Hash, firma, canonicalización, confianza (trust), rotación y revocación: la canonical niamod/1 (UTF-8, LF) es «niamod/1» + sha256(manifest.txt) + por cada archivo excepto manifest.txt en orden lexicográfico: nombre + sha256. La huella del paquete es sha256 del canónico y el instalador la muestra al usuario. La firma es ECDSA P-256 (SHA256withECDSA de java.security, disponible desde minSdk 26 sin dependencias; Ed25519 sería más pequeña pero su disponibilidad en minSdk 26 no está garantizada). Instalar = anclar: el usuario ve la huella sha256(SPKI) de la clave de autor y aprueba explícitamente, por id de módulo (SAF es el primer instalador, ADR-028; la cadena de confianza NO se difiere). Update: firmado por la clave anclada. Rotación de UN paso: un paquete firmado por la clave actual puede declarar next-key; el host la registra y el update siguiente puede usarla y re-anclar; sin next-key firmado, un cambio de clave exige reaprobación manual — nunca silencioso. Revocación LOCAL: uninstall/disable (fire posterior → DENIED, T030 implementado); SIN revocación online, sin CRL, sin phone-home; una lista de bloqueo local importable es futuro de api-version y NO se promete. Un paquete sin author-key (el paquete de prueba de este documento) lo rechaza el instalador normal: vale SOLO como fixture de validación de T095, que genera además el paquete firmado de CI con la clave de test.
- Capabilities diferenciadas (reconciliadas con v1): observar = onStartup/onNavigate, eventos de solo lectura, ya implementado en T029 sin permiso extra. Bloquear = onRequest, decisión Allow/Block ANTES de la petición desde el broker (ADR-018/T052); en v1 su puerta ES el permiso network.fetch (T029 lo exige): declarar capabilities = bloquear sin network.fetch es paquete INCOHERENTE → INVALID_INPUT. Modificar = reescritura de contenido (reglas cosméticas de T053/ADR-018: después del primer layout, vía el motor CSS del host); NO existe en el runtime v1: declararlo con un host que no lo soporta → UNSUPPORTED, sin fingir soporte. Las capabilities clasifican; los permissions conceden; ninguna capability concede lo que el api-version del host no implemente.
- Categorías combinables (declarativas, para el gestor de T094): background (engancha hooks de navegación sin UI), contextual (aparece en «En este sitio», activación por sitio que guarda el HOST, no el módulo) y panel (aporta panel al cajón de paneles; SIN toolbar propia ni floating buttons, regla dura del Delta P9). Combinables; la declaración no concede nada por sí misma.
- Hooks sync/async, orden y Fail-open/Fail-closed (reconciliado con T030): v1 es SINCRÓNICO y el valor de una Promise se ignora (exclusión documentada de T029); async con timeout medido sería api-version futura y NO se promete. Dentro de un módulo los hooks corren en orden de registro y el ÚLTIMO decide (implementado en T030). Entre módulos el orden es el de instalación, estable y SIEMPRE el mismo; para bloquear el veredicto es el MÁS restrictivo: si algún módulo bloquea, se bloquea. Errores tipificados y sin crudo del plugin (implementado). Fail-open para NAVEGAR: un módulo roto (hook que lanza, aborto por cuotas) JAMÁS rompe la navegación, su veredicto cuenta como abstención y queda marcado con error visible en el gestor (T094), que ofrece desactivarlo. Fail-closed para la INTEGRIDAD: firma mal, hash mal, límite mal o incoherencia → NO se instala, cero tolerancia.
- Vía de ejecución y la regla dura: los matchers pesados son DATOS más código del HOST (el matcher EasyList es del host, ADR-018); la lógica propia del módulo es JS interpretado acotado. NO cargar Kotlin/DEX/JAR/SO arbitrario: poner el Kotlin existente dentro de un ZIP .niamod NO lo ejecuta; el host JAMÁS classload, refleja ni ejecuta bytecode nativo o JVM; el único ejecutable del paquete es el entry interpretado con cuotas.
- Paquete de prueba «demo.observador» (fixture reproducible; contenido exacto, UTF-8 y LF, con salto final):

```
id = demo.observador
version = 2
entry = entry.js
permissions = storage.read
name = Observador demo
description = Paquete de prueba del ADR-005: observa navegaciones sin bloquear nada
category = background
capabilities = observar
api-version = 1
```

```
// demo.observador (ADR-005): observa sin bloquear ni modificar.
nia.onStartup(function (url) { return "startup: " + url; });
nia.onNavigate(function (url) { return "navigate: " + url; });
```

```
Dato de solo lectura del paquete de prueba demo.observador (ADR-005).
En api-version 1 el modulo NO puede leer este archivo: solo el host lo consume.
```

  Canónico (la huella del paquete es sha256 del canónico):

```
niamod/1
manifest-sha256=d3e964afa179398d6c366a8f8755ac23dd845e9b10d544a22a68b19a06967ad0
file=datos/ejemplo.txt sha256=d26438487843783299ce7d15a0e5d0d9446d277b7876e03a1f1ad66e137c5d9d
file=entry.js sha256=d178576f5b657fe7fdec0e6e7aa9c95e1c290d692028aead5754a6582083ae48
```

  Huella del paquete: f365ee4632cc2209d668eba5fe212a2494e85b64e0f5fbdac7f98720eefb8529. T095 materializa el paquete como fixture de CI, verifica estos hashes y genera el paquete firmado con la clave de test.
- Límites de esta propuesta: sin marketplace (ADR-028 difiere; SAF basta), sin actualización silenciosa sin clave anclada, sin hooks async, sin hook de contenido en v1, sin API para pedir más cuotas, sin ejecutar nada que no sea JS interpretado. La integración del carril AntiAds sigue DIFERIDA (decisión R0220) hasta verificar el navegador base en dispositivo: este documento no integra nada.
- Pruebas: T095 instala fixtures maliciosos (traversal, zipbomb de ratio, entrada gigante, duplicados, symlink, firma alterada → fallo, hash alterado → fallo, capabilities incoherentes, api-version futura y versión de manifiesto futura → UNSUPPORTED) sin agotar memoria; permisos nuevos en update reconfirmados; plugin demostrable instala sin rebuild; el paquete de prueba reproduce los hashes de este documento. El gestor T094 muestra estados (activo, error con conteo, desactivado) y retira efectos/recursos al revocar; la integración UI no prueba la sandbox. Dueño: asistente único (Vibe) con aprobación del usuario.
- Fecha de revisión: al abrir T094/T095 (gestor e instalador sobre este formato) y al cerrar la decisión del usuario sobre .niamod.
