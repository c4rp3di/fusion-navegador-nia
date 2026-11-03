package nia.engine.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import nia.contracts.ErrorCode
import nia.contracts.ErrorInfo
import nia.contracts.IpcEnvelope
import nia.contracts.Outcome

import nia.engine.broker.Broker
import nia.engine.broker.BrokerOps

class ModuleRegistryTest {

    private fun <T> ok(o: Outcome<T>): T {
        assertTrue(o is Outcome.Success)
        return (o as Outcome.Success).value as T
    }

    private fun fallo(o: Outcome<*>): ErrorInfo {
        assertTrue(o is Outcome.Failure)
        return (o as Outcome.Failure).error
    }

    private fun texto(e: IpcEnvelope): String = String(e.payload ?: ByteArray(0), Charsets.UTF_8)

    private val bueno = "# modulo de ejemplo" + "\n" +
        "id = demo.modulo" + "\n" +
        "version = 1" + "\n" +
        "entry = main.js" + "\n" +
        "permissions = storage.read, network.fetch" + "\n"

    @Test fun instalaModuloValido() {
        val registry = ModuleRegistry()
        val mod = ok(registry.install(bueno))
        assertEquals("demo.modulo", mod.id)
        assertEquals(1, mod.manifest.version)
        assertEquals("main.js", mod.manifest.entry)
        assertTrue(mod.manifest.permissions.contains("storage.read"))
        assertTrue(mod.manifest.permissions.contains("network.fetch"))
        assertTrue(registry.isInstalled("demo.modulo"))
        assertEquals(listOf("demo.modulo"), registry.installedIds())
    }

    @Test fun comentariosYLineasVaciasIgnorados() {
        val texto = "\n" + "# comentario" + "\n" + "\n" +
            "id = demo.modulo" + "\n" + "version = 1" + "\n" + "entry = main.js" + "\n"
        val registry = ModuleRegistry()
        ok(registry.install(texto))
        assertTrue(registry.isInstalled("demo.modulo"))
    }

    @Test fun manifiestoVacioRechazado() {
        val texto = "# solo comentarios" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun claveDesconocidaRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "shell = true" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun idIlegalRechazado() {
        val texto = "id = Demo_Modulo!" + "\n" + "version = 1" + "\n" + "entry = main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun idDemasiadoLargoRechazado() {
        val idLargo = "a".repeat(65)
        val texto = "id = " + idLargo + "\n" + "version = 1" + "\n" + "entry = main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun faltaIdRechazado() {
        val texto = "version = 1" + "\n" + "entry = main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun versionInvalidaRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = una" + "\n" + "entry = main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun versionFuturaRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 2" + "\n" + "entry = main.js" + "\n"
        assertEquals(ErrorCode.UNSUPPORTED, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun faltaEntryRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun entryConPathTraversalRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = ../main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun entryAbsolutaRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = /main.js" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun permisoNoPermitidoRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = shell.exec" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun permisoDePestanaDenegado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = tab.navigate" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun permisoRepetidoRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" +
            "permissions = storage.read, storage.read" + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun permisoVacioRechazado() {
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = storage.read," + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun manifiestoDemasiadoGrandeRechazado() {
        val texto = "id = " + "a".repeat(5000) + "\n"
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().install(texto)).code)
    }

    @Test fun idDuplicadoRechazado() {
        val registry = ModuleRegistry()
        ok(registry.install(bueno))
        assertEquals(ErrorCode.INVALID_INPUT, fallo(registry.install(bueno)).code)
    }

    @Test fun uninstallRetira() {
        val registry = ModuleRegistry()
        ok(registry.install(bueno))
        ok(registry.uninstall("demo.modulo"))
        assertTrue(!registry.isInstalled("demo.modulo"))
        assertEquals(0, registry.installedIds().size)
        assertEquals(ErrorCode.INVALID_INPUT, fallo(registry.uninstall("demo.modulo")).code)
    }

    @Test fun uninstallDeNoInstaladoRechazado() {
        assertEquals(ErrorCode.INVALID_INPUT, fallo(ModuleRegistry().uninstall("nada.modulo")).code)
    }

    @Test fun sinPermisosInstalaSinContexto() {
        val broker = Broker()
        val registry = ModuleRegistry(broker)
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" + "entry = main.js" + "\n"
        val mod = ok(registry.install(texto))
        assertTrue(mod.renderer == null)
        assertEquals("main.js", registry.manifestOf("demo.modulo")?.entry)
    }

    @Test fun moduloSoloHaceLoConcedido() {
        val broker = Broker()
        val registry = ModuleRegistry(broker)
        val texto = "id = demo.modulo" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = storage.read" + "\n"
        val mod = ok(registry.install(texto))
        val r = mod.renderer ?: throw AssertionError("el modulo debe tener contexto")
        ok(r.handshake())
        assertEquals("ack", texto(ok(r.request(BrokerOps.STORAGE_READ))))
        assertEquals(ErrorCode.DENIED, fallo(r.request(BrokerOps.SNAPSHOT)).code)
        assertEquals(ErrorCode.DENIED, fallo(r.request(BrokerOps.NETWORK_FETCH)).code)
    }

    @Test fun uninstallCierraContextoDelBroker() {
        val broker = Broker()
        val registry = ModuleRegistry(broker)
        val mod = ok(registry.install(bueno))
        val r = mod.renderer ?: throw AssertionError("el modulo debe tener contexto")
        ok(r.handshake())
        ok(registry.uninstall("demo.modulo"))
        assertEquals(ErrorCode.DENIED, fallo(r.request(BrokerOps.STORAGE_READ)).code)
    }

    @Test fun dosModulosAislados() {
        val broker = Broker()
        val registry = ModuleRegistry(broker)
        val a = ok(registry.install("id = a.mod" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = storage.read" + "\n"))
        val b = ok(registry.install("id = b.mod" + "\n" + "version = 1" + "\n" +
            "entry = main.js" + "\n" + "permissions = network.fetch" + "\n"))
        val ra = a.renderer ?: throw AssertionError("a debe tener contexto")
        val rb = b.renderer ?: throw AssertionError("b debe tener contexto")
        ok(ra.handshake())
        ok(rb.handshake())
        assertEquals(ErrorCode.DENIED, fallo(ra.request(BrokerOps.NETWORK_FETCH)).code)
        assertEquals(ErrorCode.DENIED, fallo(rb.request(BrokerOps.STORAGE_READ)).code)
    }
}
