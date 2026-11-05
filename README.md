# fusion-navegador-nia
APK de «navegador-nia» compilado con Fusion

## NavController puro JVM (T071 v1, R0149, RES-0074)

NavController en :engine-core orquesta la navegacion completa sobre los motores verificados: fetch con redirecciones y cookies por salto, commit de identidad y origen con storage particionado por perfil, subrecursos contables con mixed-content bloqueado, scripts con storage y fetch del perfil, cancelacion y stale por generaciones, back/forward y revision de layout sobre el documento vivo. El shell (T072, ADR-022) lo cablea fuera del hilo de UI. NavControllerTest (14) corre en el workflow contracts-tests (146 fuentes).
