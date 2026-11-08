package nia.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.Outcome

/**
 * Espejo Kotlin del gemelo TS validado offline (2 corridas
 * identicas, huella 27.27.4127f74b): registro declarativo ui-model de
 * C11 v2 (R0260, RES-0116) - parser JSON minimo del host, schema
 * uimodel/1 con secciones ajustes/acciones/paneles, validacion
 * todo-o-nada FAIL-CLOSED (ids, tipos, claves exactas, saneo y
 * recorte de titulos, limites por archivo) y la fusion que
 * rechaza duplicados ENTRE archivos y el limite global.
 */
class UiModelModulosTest {

    private fun ok(o: Outcome<UiModelDeclarado>): UiModelDeclarado {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as UiModelDeclarado
    }

    private fun fallo(o: Outcome<*>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    private fun ajusteJson(
        id: String,
        titulo: String,
        tipo: String = "bool",
        defecto: String = "true"
    ): String =
        "{\"id\":\"" + id + "\",\"titulo\":\"" + titulo +
            "\",\"tipo\":\"" + tipo + "\",\"defecto\":" + defecto + "}"

    @Test
    fun parseaAjustesAccionesYPaneles() {
        val ui = ok(
            UiModelModulos.parse(
                "{\"ajustes\":[" + ajusteJson("filtrar.subrecursos", "Filtrar subrecursos") + "]," +
                    "\"acciones\":[{\"id\":\"actualizar\",\"titulo\":\"Actualizar ahora\"}]," +
                    "\"paneles\":[{\"id\":\"principal\",\"titulo\":\"Panel principal\"}]}"
            )
        )
        assertEquals(1, ui.ajustes.size)
        assertEquals("filtrar.subrecursos", ui.ajustes[0].id)
        assertEquals("Filtrar subrecursos", ui.ajustes[0].titulo)
        assertEquals(true, ui.ajustes[0].defecto)
        assertEquals(1, ui.acciones.size)
        assertEquals("actualizar", ui.acciones[0].id)
        assertEquals(1, ui.paneles.size)
        assertEquals("principal", ui.paneles[0].id)
        assertFalse(ui.vacio)
    }

    @Test
    fun raizVaciaDeclaraNada() {
        val ui = ok(UiModelModulos.parse("{}"))
        assertTrue(ui.vacio)
        assertTrue(ui.ajustes.isEmpty())
        assertTrue(ui.acciones.isEmpty())
        assertTrue(ui.paneles.isEmpty())
    }

    @Test
    fun seccionVaciaDeclaraNada() {
        val ui = ok(UiModelModulos.parse("{\"paneles\":[]}"))
        assertTrue(ui.vacio)
    }

    @Test
    fun ajusteConDefectoFalse() {
        val ui = ok(
            UiModelModulos.parse(
                "{\"ajustes\":[" + ajusteJson("modo.base", "Modo base", defecto = "false") + "]}"
            )
        )
        assertEquals(false, ui.ajustes[0].defecto)
    }

    @Test
    fun escapeUnicodeEnTitulo() {
        val ui = ok(
            UiModelModulos.parse(
                "{\"ajustes\":[" + ajusteJson("caf.ok", "Caf\\u00e9 listo") + "]}"
            )
        )
        assertEquals("Caf\u00e9 listo", ui.ajustes[0].titulo)
    }

    @Test
    fun tituloLargoSeRecorta() {
        val largo = "x".repeat(70)
        val ui = ok(
            UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("corto", largo) + "]}")
        )
        assertEquals(UiModelModulos.MAX_TITULO, ui.ajustes[0].titulo.length)
    }

    @Test
    fun tituloConTabYSaltoSeSanea() {
        val ui = ok(
            UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("saneo", "a\tb\nc") + "]}")
        )
        assertEquals("a b c", ui.ajustes[0].titulo)
    }

    @Test
    fun seccionDesconocidaRechazada() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{\"informes\":[]}")).code
        )
    }

    @Test
    fun raizNoObjetoRechazada() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("[1]")).code
        )
    }

    @Test
    fun jsonRotoRechazado() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{\"ajustes\":[")).code
        )
    }

    @Test
    fun basuraTrasLaRaizRechazada() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{} colado")).code
        )
    }

    @Test
    fun tipoNoBoolRechazado() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(
                UiModelModulos.parse(
                    "{\"ajustes\":[" + ajusteJson("entero.x", "Entero", tipo = "int") + "]}"
                )
            ).code
        )
    }

    @Test
    fun defectoNoBooleanoRechazado() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(
                UiModelModulos.parse(
                    "{\"ajustes\":[" + ajusteJson("defecto.malo", "Malo", defecto = "\"si\"") + "]}"
                )
            ).code
        )
    }

    @Test
    fun claveExtraRechazada() {
        val crudo = ("{\"ajustes\":[{\"id\":\"x.y\",\"titulo\":\"T\",\"tipo\":\"bool\"," +
            "\"defecto\":true,\"extra\":1}]}")
        assertEquals(ErrorCode.INVALID_INPUT, fallo(UiModelModulos.parse(crudo)).code)
    }

    @Test
    fun idInvalidoRechazado() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(
                UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("malo id", "Malo") + "]}")
            ).code
        )
    }

    @Test
    fun idDuplicadoRechazado() {
        val dos = ajusteJson("repetido.a", "Uno") + "," + ajusteJson("repetido.a", "Dos")
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{\"ajustes\":[" + dos + "]}")).code
        )
    }

    @Test
    fun demasiadosAjustesRechazados() {
        val sb = StringBuilder()
        for (n in 1..17) {
            if (n > 1) sb.append(',')
            sb.append(ajusteJson("tope." + n, "T" + n))
        }
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{\"ajustes\":[" + sb + "]}")).code
        )
    }

    @Test
    fun accionSinArregloRechazada() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.parse("{\"acciones\":{}}")).code
        )
    }

    @Test
    fun tituloVacioRechazado() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(
                UiModelModulos.parse("{\"acciones\":[{\"id\":\"a.b\",\"titulo\":\"\"}]}")
            ).code
        )
    }

    @Test
    fun fusionarConcatenaSinDuplicados() {
        val a = ok(UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("uno.a", "A") + "]}"))
        val b = ok(
            UiModelModulos.parse(
                "{\"ajustes\":[" + ajusteJson("dos.b", "B") + "]," +
                    "\"acciones\":[{\"id\":\"act.b\",\"titulo\":\"Act B\"}]}"
            )
        )
        val fusion = ok(UiModelModulos.fusionar(listOf(a, b)))
        assertEquals(2, fusion.ajustes.size)
        assertEquals(1, fusion.acciones.size)
    }

    @Test
    fun fusionarRechazaDuplicadosEntreArchivos() {
        val a = ok(UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("misma.id", "A") + "]}"))
        val b = ok(UiModelModulos.parse("{\"ajustes\":[" + ajusteJson("misma.id", "B") + "]}"))
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.fusionar(listOf(a, b))).code
        )
    }

    @Test
    fun fusionarRespetaElLimiteGlobal() {
        val mitad = StringBuilder()
        for (n in 1..9) {
            if (n > 1) mitad.append(',')
            mitad.append(ajusteJson("global." + n, "G" + n))
        }
        val texto = "{\"ajustes\":[" + mitad + "]}"
        val a = ok(UiModelModulos.parse(texto))
        val b = ok(UiModelModulos.parse(texto.replace("global.", "otro.")))
        assertEquals(
            ErrorCode.INVALID_INPUT,
            fallo(UiModelModulos.fusionar(listOf(a, b))).code
        )
    }
}
