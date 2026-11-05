# ADR-021 — Diseño del shell: tokens, iconografía, gestos y accesibilidad

**Estado:** ACEPTADO (R0141, RES-0072) · **Base:** NIA-MAESTRO

- Problema: P9 necesita una superficie común de navegador (T069, T071,
  T072) y hasta ahora cada demo traía su propio cromo a mano: sin sistema
  de tokens los colores, radios, espaciados y tipografías se duplican por
  pantalla, el tema declarativo de T031 solo nombra seis colores del
  motor, y la accesibilidad de T070 no tiene criterio aplicable hasta que
  exista una base medible compartida.
- Opciones evaluadas: (1) Material Components con tokens derivados; (2)
  librería UI externa (Compose u otra) con su sistema visual; (3) sistema
  propio de tokens sobre widgets/API de Android. Decisión del usuario
  (R0137): **sistema propio**, sin Material ni librerías UI externas
  nuevas; los widgets y las APIs de plataforma siguen permitidos y el
  cambio no añade ninguna dependencia.
- Tokens sobre ThemeManager (T031 v1): el schema cerrado de SEIS colores
  semánticos (`bg`, `surface`, `fg`, `accent`, `warn`, `error`, en
  `#rrggbb`) es la única fuente de color; el shell deriva de esos seis los
  pares de rol (fondo/texto, superficie/texto, accent sobre fondo) y
  añade las familias no color: tipografía (escala y pesos), espaciado
  (escala en dp), radios y elevación (niveles de sombra). Tres modos:
  claro, oscuro y auto (el auto sigue el uiMode del sistema); los
  builtin claro y oscuro de T031 siguen protegidos y la recuperación de
  tema roto queda intacta.
- Iconografía monolineal: set propio vectorial de un solo trazo con peso
  uniforme, tamaño base 24dp, color heredado de `fg` y variantes de
  estado por opacidad; sin librería de iconos externa.
- Animaciones: 150–250ms para entradas, salidas y cambios de superficie
  con las curvas de plataforma; el movimiento reducido (ajustes de
  accesibilidad del sistema) elimina el desplazamiento y deja solo el
  fundido de opacidad; sin bucles decorativos ni animaciones que
  bloqueen la interacción.
- Gestos y estructura: barra superior única, SIN barra inferior
  permanente; los overlays, el reproductor flotante y las vistas peek
  cubren lo que una barra inferior haría. Los gestos se definen como
  contratos del shell (no por componente), listos para que T071/T072 los
  cableen. Sin splash propio ni onboarding obligatorio: la app abre
  directo al navegador.
- Accesibilidad AA como contrato verificable (lo verifica T070 EN
  DISPOSITIVO): contraste por pares de tokens con criterio aplicable
  (WCAG 2.1 AA: 4.5:1 texto normal, 3:1 texto grande y componentes
  esenciales, medido sobre los seis colores del schema), foco visible,
  roles y contentDescription para TalkBack, fontScale del sistema
  respetado (sp, no px), targets táctiles >= 48dp y alto contraste del
  sistema respetado; el contenido web hereda la base de T035.
- Ligereza: cada superficie se infla bajo demanda (carga diferida de
  T069), sin capas de abstracción sobre los widgets, y las pantallas
  consumen los componentes de T069 con los mismos tokens; el inspector
  de DevTools (T017) queda integrado al shell bajo el nombre de
  producto NavTools.
- Límites: este ADR fija la dirección de diseño del shell, no un documento
  verificado: T069 construye los trece componentes sobre estos tokens y
  T070 verifica el contrato en dispositivo; el omnibox y su barra quedan
  para ADR-022 (T072) y este documento no invade su territorio; el alto
  contraste y el movimiento reducido no se simulan en CI.
- Pruebas: T069 monta un catálogo comprobable de componentes y tokens
  (pares de contraste de los temas builtin verificados en JVM), duración
  de animaciones medida contra 150–250ms y una demo del shell en el
  smoke de P9-A; T070 verifica en dispositivo el resto del contrato de
  accesibilidad. Dueño: asistente único (Vibe) con aprobación del
  usuario.
- Fecha de revisión: al abrir T069 (componentes del shell sobre los
  tokens) y al cerrar T070 (accesibilidad en dispositivo con el criterio
  aplicable verificado).
