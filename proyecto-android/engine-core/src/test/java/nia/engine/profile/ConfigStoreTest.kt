package nia.engine.profile

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas identicas,
 * huella 23.23.6fa98e30): ConfigStore de C9 v1 (R0253, RES-0113) contra
 * disco REAL: limites, TSV write-through, lineas corruptas ignoradas,
 * enabled solo con el "false" exacto y la regla dura de que
 * ConfigStore(null) vive solo en memoria.
 */
class ConfigStoreTest {

    private var secuencia = 0

    private fun dir(): File {
        secuencia += 1
        val d = File(File(System.getProperty("java.io.tmpdir"), "nia-config-store-test"), "caso-" + secuencia)
        d.mkdirs()
        return d
    }

    @Test
    fun escribirYLeerIdaYVuelta() {
        val c = ConfigStore(dir().resolve("modulos-config.tsv"))
        assertTrue(c.escribir("demo.uno", "general.enabled", "false") is Outcome.Success)
        assertEquals("false", c.leer("demo.uno", "general.enabled"))
        assertNull("una clave que no existe es null", c.leer("demo.uno", "otra.clave"))
    }

    @Test
    fun enabledSoloElFalseExactoDeshabilita() {
        val c = ConfigStore(null)
        assertTrue("sin ajustes el modulo queda habilitado", c.enabled("demo.uno"))
        assertTrue(c.escribir("demo.uno", "general.enabled", "true") is Outcome.Success)
        assertTrue(c.enabled("demo.uno"))
        assertTrue(c.escribir("demo.uno", "general.enabled", "FALSE") is Outcome.Success)
        assertTrue("el FALSE mayuscula NO deshabilita", c.enabled("demo.uno"))
        assertTrue(c.escribir("demo.uno", "general.enabled", "false") is Outcome.Success)
        assertFalse("solo el false exacto deshabilita", c.enabled("demo.uno"))
    }

    @Test
    fun clavesYValoresInvalidosSeRechazanSinEscribir() {
        val c = ConfigStore(null)
        assertTrue(c.escribir("demo.uno", "clave-ok", "v") is Outcome.Success)
        for (clave in listOf("con espacio", "", "x".repeat(65))) {
            assertTrue(
                "clave rechazada: " + clave,
                c.escribir("demo.uno", clave, "v") is Outcome.Failure
            )
        }
        assertTrue("id vacio rechazado", c.escribir("", "clave-ok", "v") is Outcome.Failure)
        assertTrue("id con espacio rechazado", c.escribir("id invalido", "clave-ok", "v") is Outcome.Failure)
        assertTrue("valor con tabulador rechazado", c.escribir("demo.uno", "clave-ok", "a\tb") is Outcome.Failure)
        assertTrue("valor con salto rechazado", c.escribir("demo.uno", "clave-ok", "a\nb") is Outcome.Failure)
        assertTrue("valor de 513 chars rechazado", c.escribir("demo.uno", "clave-ok", "x".repeat(513)) is Outcome.Failure)
        assertEquals("los pares validos siguen intactos", 1, c.entradas("demo.uno").size)
    }

    @Test
    fun limiteDeClavesPorModulo() {
        val c = ConfigStore(null)
        for (n in 1..64) {
            assertTrue(c.escribir("demo.uno", "clave-" + n, "v") is Outcome.Success)
        }
        assertTrue("la clave 65 se rechaza", c.escribir("demo.uno", "clave-65", "v") is Outcome.Failure)
        assertTrue("actualizar una existente sigue permitido", c.escribir("demo.uno", "clave-1", "nuevo") is Outcome.Success)
    }

    @Test
    fun limiteDeModulosConAjustes() {
        val c = ConfigStore(null)
        for (n in 1..32) {
            assertTrue(c.escribir("demo." + n, "general.enabled", "true") is Outcome.Success)
        }
        assertTrue("el modulo 33 se rechaza", c.escribir("demo.33", "general.enabled", "true") is Outcome.Failure)
    }

    @Test
    fun actualizarClaveExistenteNoDuplica() {
        val c = ConfigStore(null)
        assertTrue(c.escribir("demo.uno", "clave.ok", "uno") is Outcome.Success)
        assertTrue(c.escribir("demo.uno", "clave.ok", "dos") is Outcome.Success)
        assertEquals(1, c.entradas("demo.uno").size)
        assertEquals("dos", c.leer("demo.uno", "clave.ok"))
    }

    @Test
    fun persisteEntreInstanciasSobreDisco() {
        val archivo = dir().resolve("modulos-config.tsv")
        val c1 = ConfigStore(archivo)
        assertTrue(c1.escribir("demo.uno", "general.enabled", "false") is Outcome.Success)
        assertTrue(c1.escribir("demo.uno", "listas.basica", "on") is Outcome.Success)
        val c2 = ConfigStore(archivo)
        assertEquals("false", c2.leer("demo.uno", "general.enabled"))
        assertEquals("on", c2.leer("demo.uno", "listas.basica"))
        assertFalse("la config recargada deshabilita al modulo", c2.enabled("demo.uno"))
    }

    @Test
    fun lineasCorruptasSeIgnoranAlCargar() {
        val archivo = dir().resolve("modulos-config.tsv")
        archivo.writeText(
            "demo.uno\tgeneral.enabled\tfalse\n" +
                "demo.dos\tclave-sin-valor\n" +
                "linea sin tabulador alguno\n" +
                "demo.tres\tgeneral.enabled\t" + "x".repeat(600) + "\n"
        )
        val c = ConfigStore(archivo)
        assertFalse("la linea valida deshabilita", c.enabled("demo.uno"))
        assertTrue("la de dos campos se ignora", c.enabled("demo.dos"))
        assertTrue("la sin tabuladores se ignora", c.enabled("demo.tres"))
    }

    @Test
    fun borrarYLimpiarCuentanPares() {
        val c = ConfigStore(null)
        c.escribir("demo.uno", "a.b", "1")
        c.escribir("demo.uno", "c.d", "2")
        c.escribir("demo.dos", "e.f", "3")
        assertEquals(2, c.borrar("demo.uno"))
        assertEquals(0, c.borrar("demo.uno"))
        assertEquals(1, c.limpiar())
        assertEquals(0, c.limpiar())
    }

    @Test
    fun limpiarTruncaElArchivoPersistido() {
        val archivo = dir().resolve("modulos-config.tsv")
        val c1 = ConfigStore(archivo)
        c1.escribir("demo.uno", "general.enabled", "false")
        assertEquals(1, c1.limpiar())
        assertEquals("el TSV queda vacio tras limpiar", "", archivo.readText())
        val c2 = ConfigStore(archivo)
        assertTrue("el archivo truncado no revive ajustes", c2.enabled("demo.uno"))
        assertTrue(c2.entradas("demo.uno").isEmpty())
    }

    @Test
    fun sinAlmacenViveSoloEnMemoria() {
        val c = ConfigStore(null)
        assertTrue(c.escribir("demo.uno", "general.enabled", "false") is Outcome.Success)
        assertFalse(c.enabled("demo.uno"))
        assertEquals("false", c.leer("demo.uno", "general.enabled"))
    }

    @Test
    fun valoresConEspaciosYVacioRedondean() {
        val c = ConfigStore(null)
        assertTrue(c.escribir("demo.uno", "nota.libre", "") is Outcome.Success)
        assertTrue(c.escribir("demo.dos", "nota.libre", "hola mundo") is Outcome.Success)
        assertEquals("", c.leer("demo.uno", "nota.libre"))
        assertEquals("hola mundo", c.leer("demo.dos", "nota.libre"))
    }
}
