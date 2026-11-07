package app.devlas.plugins.thermalprinter

import app.devlas.plugins.thermalprinter.BleProfile.Characteristic
import app.devlas.plugins.thermalprinter.BleProfile.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleProfileTest {

    private fun ch(uuid: String, write: Boolean = false, noResp: Boolean = false) =
        Characteristic(uuid, write, noResp)

    @Test
    fun normalizaUuidCortos() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleProfile.normalize("FFE0"))
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleProfile.normalize("0000ffe0"))
    }

    @Test
    fun prefiereUartConocidoSobreOtroServicio() {
        val services = listOf(
            Service("0000abcd-0000-1000-8000-00805f9b34fb", listOf(ch("1", noResp = true))),
            Service(BleProfile.shortUuid(0xffe0), listOf(ch(BleProfile.shortUuid(0xffe1), write = true))),
        )
        val c = BleProfile.pickChannel(services)!!
        assertEquals(BleProfile.shortUuid(0xffe0), c.serviceUuid)
        assertEquals(false, c.withoutResponse)
    }

    @Test
    fun respetaElOrdenDePreferenciaDeServiciosConocidos() {
        val services = listOf(
            Service(BleProfile.shortUuid(0xff00), listOf(ch("a", noResp = true))),
            Service("49535343-fe7d-4ae5-8fa9-9fafd205e455", listOf(ch("b", noResp = true))),
        )
        assertEquals("49535343-fe7d-4ae5-8fa9-9fafd205e455", BleProfile.pickChannel(services)!!.serviceUuid)
    }

    @Test
    fun dentroDelServicioPrefiereSinRespuesta() {
        val services = listOf(
            Service(BleProfile.shortUuid(0xfff0), listOf(ch("a", write = true), ch("b", noResp = true))),
        )
        val c = BleProfile.pickChannel(services)!!
        assertEquals("b", c.characteristicUuid)
        assertEquals(true, c.withoutResponse)
    }

    @Test
    fun sinUartConocidoUsaCualquierEscribibleNoEstandar() {
        val services = listOf(
            Service(BleProfile.shortUuid(0x1800), listOf(ch("name", write = true))),
            Service("12345678-0000-1000-8000-00805f9b34fb", listOf(ch("w", noResp = true))),
        )
        assertEquals("12345678-0000-1000-8000-00805f9b34fb", BleProfile.pickChannel(services)!!.serviceUuid)
    }

    @Test
    fun nuncaEscribeEnServiciosDelSistema() {
        val services = listOf(
            Service(BleProfile.shortUuid(0x1800), listOf(ch("name", write = true))),
            Service(BleProfile.shortUuid(0x180a), listOf(ch("fw", noResp = true))),
        )
        assertNull(BleProfile.pickChannel(services))
    }

    @Test
    fun sinCaracteristicaEscribibleDevuelveNull() {
        val services = listOf(Service(BleProfile.shortUuid(0xffe0), listOf(ch("r"))))
        assertNull(BleProfile.pickChannel(services))
    }

    @Test
    fun trozoSegunMtuConTopeYPiso() {
        assertEquals(20, BleProfile.chunkSize(23))
        assertEquals(180, BleProfile.chunkSize(247))
        assertEquals(20, BleProfile.chunkSize(5))
        assertEquals(97, BleProfile.chunkSize(100))
    }

    @Test
    fun esperaFinalCreceConElTrabajoConTopes() {
        assertEquals(500L, BleProfile.drainMs(100))
        assertEquals(5000L, BleProfile.drainMs(1_000_000))
        assertEquals(1000L, BleProfile.drainMs(8000))
    }

    @Test
    fun cuentaDeTrozos() {
        assertEquals(0, BleProfile.chunkCount(0, 20))
        assertEquals(1, BleProfile.chunkCount(20, 20))
        assertEquals(2, BleProfile.chunkCount(21, 20))
    }
}
