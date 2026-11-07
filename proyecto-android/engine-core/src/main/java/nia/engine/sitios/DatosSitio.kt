package nia.engine.sitios

import nia.engine.net.CookieJar
import nia.engine.net.HttpCache

/**
 * T085 v1 (RES-0098): DATOS del sitio: conteo y borrado por origen
 * sobre los motores REALES del perfil. Nada se fabrica: los conteos
 * salen de los almacenes vivos y el borrado toca SOLO el origen
 * pedido (los demas origenes quedan intactos). Las cookies son del
 * host por domain-match RFC 6265 5.1.3 (una cookie Domain=example.com
 * pertenece tambien a sub.example.com) y el cache es del origen
 * (scheme, host, puerto efectivo). Exclusiones honestas v1: el
 * storage particionado y el IndexedDb (particiones (topSite,
 * origin)) NO entran en el borrado por origen (llegan con su API
 * de particiones) y las cuotas visibles son las de sus particiones
 * propias; las preferencias zoom/texto/escritorio viven aparte
 * (SitiosManager, T080).
 */
data class ConteoSitio(val cookies: Int, val cache: Int)

/** Operaciones de datos por sitio sobre los motores reales. */
object DatosSitio {

    /** Conteo REAL del sitio: cookies del host + entradas de cache
     *  del origen (scheme, host, puerto efectivo). */
    fun conteo(jar: CookieJar, cache: HttpCache, scheme: String, host: String, port: Int): ConteoSitio =
        ConteoSitio(jar.contarDe(host), cache.contarDe(scheme, host, port))

    /** Borra SOLO los datos del sitio (cookies del host + cache del
     *  origen); devuelve el conteo de lo borrado, todo real. */
    fun borrar(jar: CookieJar, cache: HttpCache, scheme: String, host: String, port: Int): ConteoSitio =
        ConteoSitio(jar.borrarDe(host), cache.borrarDe(scheme, host, port))
}
