package app.devlas.plugins.thermalprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transporte Bluetooth Low Energy (GATT) para termicas que no hablan SPP.
 *
 * Mismo contrato que los demas transportes: bytes crudos, sin estado (conecta,
 * escribe, cierra). Las reglas de que caracteristica usar y como trocear viven
 * en [BleProfile] (puras y probadas); esta clase solo hace el I/O de Android.
 *
 * La direccion es la MAC que reporta [scan]. Las BLE normalmente no estan
 * emparejadas en el sistema, por eso aqui hay escaneo y en SPP solo listado.
 *
 * Estado en tiempo real (`DLE EOT`): necesitaria una caracteristica de
 * notificacion y cada fabricante la expone distinto; por ahora `status`
 * responde "no lo se" (`supported: false`), que el contrato ya contempla.
 */
class BleTransport(private val context: Context) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 2_000L
        private const val DISCOVER_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        /** Una impresora recien conectada puede perder los primeros bytes. */
        private const val SETTLE_AFTER_CONNECT_MS = 150L
        private const val BUSY_RETRY_MS = 10L
        private const val BUSY_RETRIES = 50
        const val DEFAULT_SCAN_MS = 5_000
    }

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Escanea durante [timeoutMs] y devuelve lo que anuncia un nombre. Los
     * anuncios sin nombre son ruido (beacons, relojes) y no se pueden elegir
     * a ciegas, asi que se omiten.
     */
    @SuppressLint("MissingPermission") // verificado por el plugin antes de llamar
    fun scan(timeoutMs: Int): JSArray {
        val a = adapter ?: throw PrinterException("unavailable", "Bluetooth no disponible en este dispositivo")
        if (!a.isEnabled) throw PrinterException("unavailable", "El Bluetooth esta apagado")
        val scanner = a.bluetoothLeScanner
            ?: throw PrinterException("unavailable", "El Bluetooth esta apagado")

        val found = LinkedHashMap<String, JSObject>()
        val failure = java.util.concurrent.atomic.AtomicInteger(0)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name.isNullOrBlank()) return
                synchronized(found) {
                    found[result.device.address] = JSObject()
                        .put("transport", "ble")
                        .put("address", result.device.address)
                        .put("name", name)
                        .put("rssi", result.rssi)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                failure.set(errorCode)
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            Thread.sleep(timeoutMs.toLong())
        } catch (e: SecurityException) {
            throw PrinterException("permission_denied", "Sin permiso para buscar dispositivos Bluetooth")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        if (failure.get() != 0) {
            throw PrinterException("unavailable", "No se pudo escanear Bluetooth (codigo ${failure.get()})")
        }

        val result = JSArray()
        synchronized(found) {
            // Mas cercano primero: casi siempre es la impresora que el usuario tiene al lado.
            found.values.sortedByDescending { it.getInt("rssi") }.forEach { result.put(it) }
        }
        return result
    }

    /** Sin notificaciones no hay lectura de estado: cuatro "no respondio". */
    fun status(@Suppress("UNUSED_PARAMETER") address: String): List<Int?> = listOf(null, null, null, null)

    /** Stateless: conecta, descubre, escribe troceado, espera la cola y cierra. */
    @SuppressLint("MissingPermission")
    fun print(address: String, bytes: ByteArray) {
        val a = adapter ?: throw PrinterException("unavailable", "Bluetooth no disponible en este dispositivo")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw PrinterException("not_found", "Direccion Bluetooth invalida: $address")
        }
        val session = GattSession(context)
        try {
            session.connect(device)
            val mtu = session.negotiateMtu()
            val services = session.discoverServices()
            val channel = BleProfile.pickChannel(
                services.map { s ->
                    BleProfile.Service(
                        s.uuid.toString(),
                        s.characteristics.map { c ->
                            BleProfile.Characteristic(
                                c.uuid.toString(),
                                canWrite = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                                canWriteNoResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                            )
                        },
                    )
                },
            ) ?: throw PrinterException(
                "write_failed",
                "La impresora no expone una caracteristica de escritura BLE conocida",
            )
            val characteristic = session.characteristic(channel)
                ?: throw PrinterException("write_failed", "No se encontro la caracteristica de escritura")

            val chunk = BleProfile.chunkSize(mtu)
            val total = BleProfile.chunkCount(bytes.size, chunk)
            var offset = 0
            var n = 0
            while (offset < bytes.size) {
                val len = minOf(chunk, bytes.size - offset)
                session.write(characteristic, bytes.copyOfRange(offset, offset + len), channel.withoutResponse)
                offset += len
                n++
                // Sin respuesta no hay confirmacion de la impresora: se le da aire.
                if (channel.withoutResponse && n < total) Thread.sleep(BleProfile.CHUNK_GAP_MS)
            }
            Thread.sleep(BleProfile.drainMs(bytes.size))
        } catch (e: SecurityException) {
            throw PrinterException("permission_denied", "Sin permiso Bluetooth")
        } finally {
            session.close()
        }
    }

    /** Una conexion GATT con sus esperas. Los callbacks llegan en otro hilo. */
    @SuppressLint("MissingPermission")
    private class GattSession(private val context: Context) {
        private var gatt: BluetoothGatt? = null

        private val connected = CountDownLatch(1)
        private val mtuDone = CountDownLatch(1)
        private val servicesDone = CountDownLatch(1)
        @Volatile private var writeLatch = CountDownLatch(0)

        @Volatile private var dropped = false
        @Volatile private var dropStatus = 0
        @Volatile private var mtu = BleProfile.DEFAULT_MTU
        @Volatile private var servicesOk = false
        @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.countDown()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    // Cualquier corte libera todas las esperas: nadie se queda colgado.
                    dropped = true
                    dropStatus = status
                    connected.countDown()
                    mtuDone.countDown()
                    servicesDone.countDown()
                    writeLatch.countDown()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                mtuDone.countDown()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesOk = status == BluetoothGatt.GATT_SUCCESS
                servicesDone.countDown()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                writeStatus = status
                writeLatch.countDown()
            }
        }

        fun connect(device: BluetoothDevice) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw PrinterException("connect_failed", "No se pudo iniciar la conexion BLE con ${device.address}")
            if (!connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || dropped) {
                throw PrinterException(
                    "connect_failed",
                    "No se pudo conectar por BLE con ${device.address} (estado $dropStatus). Revisa que la impresora este encendida y cerca.",
                )
            }
            Thread.sleep(SETTLE_AFTER_CONNECT_MS)
            // Intervalo de conexion corto: mas rendimiento para el ticket.
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }

        /** Pide el MTU mas alto; si la impresora no negocia, sigue con el minimo (23). */
        fun negotiateMtu(): Int {
            if (gatt?.requestMtu(BleProfile.REQUESTED_MTU) == true) {
                mtuDone.await(MTU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            return mtu
        }

        fun discoverServices(): List<android.bluetooth.BluetoothGattService> {
            val g = gatt ?: throw PrinterException("connect_failed", "Sin conexion BLE")
            if (!g.discoverServices()) {
                throw PrinterException("connect_failed", "No se pudieron descubrir los servicios BLE")
            }
            if (!servicesDone.await(DISCOVER_TIMEOUT_MS, TimeUnit.MILLISECONDS) || dropped || !servicesOk) {
                throw PrinterException("connect_failed", "La impresora BLE no respondio al descubrimiento de servicios")
            }
            return g.services
        }

        fun characteristic(channel: BleProfile.Channel): BluetoothGattCharacteristic? =
            gatt?.getService(UUID.fromString(channel.serviceUuid))
                ?.getCharacteristic(UUID.fromString(channel.characteristicUuid))

        /** Escribe un trozo y espera a que el sistema lo entregue a la radio. */
        fun write(c: BluetoothGattCharacteristic, data: ByteArray, withoutResponse: Boolean) {
            val g = gatt ?: throw PrinterException("write_failed", "Sin conexion BLE")
            val type = if (withoutResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            var attempts = 0
            while (true) {
                if (dropped) throw PrinterException("write_failed", "Se perdio la conexion BLE durante la impresion")
                writeLatch = CountDownLatch(1)
                writeStatus = BluetoothGatt.GATT_SUCCESS
                if (start(g, c, data, type)) break
                // Otra operacion en curso: se reintenta un rato antes de rendirse.
                if (++attempts > BUSY_RETRIES) {
                    throw PrinterException("write_failed", "La impresora BLE no acepto datos")
                }
                Thread.sleep(BUSY_RETRY_MS)
            }
            if (!writeLatch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || dropped) {
                throw PrinterException("write_failed", "La impresora BLE dejo de responder")
            }
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                throw PrinterException("write_failed", "Error de escritura BLE (estado $writeStatus)")
            }
        }

        @Suppress("DEPRECATION")
        private fun start(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray, type: Int): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = data
                g.writeCharacteristic(c)
            }

        fun close() {
            val g = gatt ?: return
            runCatching { g.disconnect() }
            // disconnect() es asincrono: cerrar de inmediato puede dejar la radio ocupada.
            runCatching { Thread.sleep(100) }
            runCatching { g.close() }
            gatt = null
        }
    }
}
