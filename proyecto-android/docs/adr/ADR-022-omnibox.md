# ADR-022 — Omnibox: URL y búsqueda con parser y heurísticas explícitas

**Estado:** ACEPTADO (R0154, RES-0075) · **Base:** NIA-MAESTRO

- Problema: T072 cablea BrowserActivity con un omnibox que recibe texto
  ambiguo y el navegador debe decidir si navega o busca sin adivinar en
  silencio ni delegar la decisión a intentos opacos. La filosofía del
  proyecto (ligero, honesto, sin peso heredado) exige reglas
  deterministas, documentadas y testeables en CI, apoyadas en el parser
  de URLs ya verificado (T012) en lugar de una segunda heurística
  paralela.
- Opciones evaluadas: (1) probar a navegar y caer a búsqueda si algo
  falla (mágica, imposible de auditar y confusa: un fallo TLS no es una
  consulta); (2) navegar solo con esquema explícito y buscar todo lo
  demás (conservadora pero hostil: obliga a escribir el esquema para
  cualquier sitio); (3) parser propio más heurísticas explícitas:
  navegar cuando el texto parece un dominio y caer a búsqueda SOLO si
  la navegación falla de DNS, con el resto resolviendo en búsqueda.
  Decisión del usuario (R0154): **opción 3**.
- Resolución del input, en orden, la primera regla que aplica, todo
  testeable en JVM puro: (1) vacío o espacios, sin acción; (2) esquema
  explícito http/https, navegar tal cual con UrlParser (los errores de
  parseo se avisan en el omnibox, nunca búsqueda silenciosa); (3)
  esquema explícito NO soportado (about:, file:, chrome:, javascript:,
  data:, view-source:), aviso honesto de esquema no soportado, sin
  fingir navegación ni búsqueda; (4) sin esquema pero parece dominio
  (host con al menos un punto, localhost o IP literal, sin espacios),
  navegar con https implícito; (5) todo lo demás, búsqueda en el motor
  por defecto con la query codificada.
- Fallback DNS: si la regla 4 navegó y el fallo es de DNS (host
  inexistente), el omnibox ofrece lanzar la búsqueda con el texto
  original tras confirmación del usuario: no se reintenta en silencio
  ni se convierte automáticamente en búsqueda; cualquier otro fallo
  (TLS, red, HTTP) muestra su error honesto (T083 amplía la página de
  error).
- Búsqueda: el motor por defecto de v1 es DuckDuckGo HTML
  (https://html.duckduckgo.com/html/?q=), resultados sin JS y afines al
  motor propio; la elección de motor llega con los ajustes (T054/T094)
  y queda como decisión de UI pendiente, no de protocolo. Una búsqueda
  es una navegación normal del NavController: misma identidad, origen,
  TLS y cookies que cualquier página; sin motor propio de resultados ni
  parseo especial.
- Sugerencias: SIEMPRE locales (historial y marcadores del perfil
  visible), sin red ni autocompletado remoto; en incógnito no se
  sugiere historial (ADR-019); el orden es coincidencia exacta,
  prefijo y más recientes, con tope pequeño y sin telemetría. El
  omnibox muestra el esquema y origen reales de la URL final tras las
  redirecciones (T071 ya expone origen y sitio).
- Límites: sin predicciones, sin múltiples motores simultáneos, sin
  comandos especiales del omnibox y sin búsqueda por voz o imágenes en
  v1; la heurística de parece-dominio es deliberadamente simple (punto,
  localhost o IP) y sus casos van fechados en la tabla de tests;
  punycode/IDN llega con la normalización de URLs si T083/T084 la
  exigen.
- Pruebas: tabla de resolución del input en JVM puro con un caso por
  regla (esquema soportado, esquema no soportado, dominio implícito,
  localhost, IP, query con espacios, query sin punto), sugerencias
  locales contra un perfil real (y su silencio en incógnito) y fallback
  DNS simulado con transporte falso; el smoke T067-A mide la aceptación
  de T072: URL https real pintada con motor propio, enlaces y
  subrecursos en alcance, back/forward/reload/stop, fallo DNS/TLS y
  origen correcto. Dueño: asistente único (Vibe) con aprobación del
  usuario.
- Fecha de revisión: al abrir T072 v1 (el código del omnibox sobre estas
  reglas) y al cerrar T067-A (smoke del primer APK utilizable de P9-A).
