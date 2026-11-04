# ADR-016 — Auditoría de bibliotecas torrent (T047 v1, R0108, RES-0058)

Fecha: 2026-11-04 · Dueño: Yo Tuu · Ejecutor: asistente (búsqueda web verificada el 2026-11-04)

## Criterios (ADR-016)

1. Licencias del wrapper y transitivas.
2. Compatibilidad con Android/Fusion: minSdk 26, JVM 17, sin Play Services.
3. Tamaño de APK/memoria/consumo contra el baseline (APK actual sin binarios nativos).
4. Protocolo detrás de adaptador reemplazable y permisos siempre vía broker.

## Candidatas

### jlibtorrent (frostwire/frostwire-jlibtorrent)

- Licencia MIT (wrapper SWIG sobre libtorrent C++).
- Mantenimiento ACTIVO (jlibtorrent 2.0.15.3, octubre 2026).
- Compatibilidad Android: sí (API 21+), pero los binarios nativos SWIG por ABI (arm64-v8a, armeabi-v7a, x86, x86_64) añaden decenas de MB al APK y memoria residente propia.
- Veredicto: NO CUMPLE por tamaño y consumo contra el baseline (criterio 3): el proyecto no tiene nativos y la decisión D-003 exige control de huella.
- https://github.com/frostwire/frostwire-jlibtorrent

### libtorrent4j (aldenml/libtorrent4j)

- Licencia MIT (mismo linaje SWIG sobre libtorrent).
- Mantenimiento ACTIVO (versión 2.1.0-39, febrero 2026).
- Compatibilidad Android: exige Android API >= 28 (actualización de NDK r29 del propio proyecto) contra el minSdk 26 del stack (ADR-001/ADR-011), además de los mismos binarios nativos por ABI.
- Veredicto: NO CUMPLE por compatibilidad (criterio 2) y por tamaño (criterio 3).
- https://github.com/aldenml/libtorrent4j

### ttorrent (mpetazzoni/ttorrent)

- Licencia Apache 2.0, Java puro (sin nativos, tamaño mínimo).
- Implementa BEP-3, BEP-12 (announce-list), BEP-15 (UDP announce), BEP-20, BEP-23; versión 2.0 en master.
- Sin DHT (BEP-5), sin obtención de metadatos magnet (BEP-9/53), sin PEX; mantenimiento esporádico y sin releases estables recientes. Su modelo propio de hilos y red no pasa por el broker del navegador (D-003) y añadiría la primera dependencia externa a un motor que hoy tiene cero.
- Veredicto: NO CUMPLE por mantenimiento y por las piezas que faltan justo para la apertura de magnet del título de T047.
- https://github.com/mpetazzoni/ttorrent

## Decisión

Ninguna candidata cumple los criterios de ADR-016, así que se aplica la cláusula de respaldo decidida por el usuario: **cliente propio**, con todo el protocolo detrás de una fuente de piezas inyectable (`PieceSource`) reemplazable, igual que `FetchTransport` y `MediaBackend`. T047 v1 entrega el núcleo puro JVM (Bencode canónico, metainfo .torrent, magnet, sesión con piezas verificadas con SHA-1, selección de archivos, progreso, pausa, cancelación y borrado); la capa de red (trackers, DHT, peers) va en v2 sobre la misma frontera y el empaquetado como módulo instalable espera a T029/T030.

Reevaluación: cuando el reality smoke (T067) mida el baseline real de APK/memoria/consumo, o si el stack cambia minSdk.
