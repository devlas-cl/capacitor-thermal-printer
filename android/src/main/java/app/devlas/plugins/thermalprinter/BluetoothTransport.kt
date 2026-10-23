package app.devlas.plugins.thermalprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.io.IOException
import java.util.UUID

/**
 * Transporte Bluetooth clásico (SPP/RFCOMM) — el perfil de las térmicas BT
 * (PT-210, MTP-II, Rongta, Xprinter…). La impresora debe estar emparejada
 * desde Ajustes del sistema; aquí solo se listan los dispositivos vinculados.
 *
 * El permiso BLUETOOTH_CONNECT (API 31+) lo gestiona ThermalPrinterPlugin
 * vía el alias de permisos de Capacitor antes de llegar acá.
 */
class BluetoothTransport(private val context: Context) {

    companion object {
        /** UUID estándar del Serial Port Profile. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        /**
         * Escritura pausada: CHUNK_SIZE bytes y luego CHUNK_GAP_MS. SPP no nos
         * da control de flujo visible, y las térmicas baratas pierden bytes o
         * abortan una imagen (`GS v 0`) cuando el flujo les llena el buffer o se
         * corta a la mitad. Visto en Botánico (TELHOME 80 mm, 2026-10): la imagen
         * de la boleta salió como metros de texto. 512 B cada 20 ms son ~25 KB/s:
         * una boleta raster de 150 KB tarda ~6 s.
         */
        private const val CHUNK_SIZE = 512
        private const val CHUNK_GAP_MS = 20L
        /** Una impresora recién conectada puede perder los primeros bytes. */
        private const val SETTLE_AFTER_CONNECT_MS = 150L
        /**
         * Espera después del último byte antes de cerrar, para que el cierre no
         * corte la cola que sigue en la radio y en el buffer de la impresora.
         * Crece con el trabajo: cerrar 2 s después de una imagen de 150 KB (el
         * tope anterior) la cortaba.
         */
        private const val DRAIN_MIN_MS = 500L
        private const val DRAIN_MAX_MS = 5000L
        private const val DRAIN_BYTES_PER_MS = 32L
        /** Corto a propósito: una impresora que no soporta DLE EOT nunca responde. */
        private const val STATUS_READ_TIMEOUT_MS = 800L
    }

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission") // verificado por el plugin antes de llamar
    fun list(): JSArray {
        val result = JSArray()
        val a = adapter ?: throw PrinterException("unavailable", "Bluetooth no disponible en este dispositivo")
        for (device in a.bondedDevices) {
            val obj = JSObject()
            obj.put("transport", "bluetooth")
            obj.put("address", device.address)
            obj.put("name", device.name ?: device.address)
            result.put(obj)
        }
        return result
    }

    /**
     * Consulta de estado. Devuelve los bytes crudos de las cuatro consultas,
     * con `null` donde la impresora no contestó — ver PrinterStatus.
     *
     * SPP no expone timeout de lectura por socket, así que se acota el bloqueo
     * esperando a que haya bytes disponibles en vez de leer a ciegas: sin eso,
     * una impresora que no soporta DLE EOT colgaría el hilo para siempre.
     */
    @SuppressLint("MissingPermission")
    fun status(address: String): List<Int?> {
        val a = adapter ?: throw PrinterException("unavailable", "Bluetooth no disponible en este dispositivo")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw PrinterException("not_found", "Dirección Bluetooth inválida: $address")
        }
        val selectors = listOf(
            PrinterStatus.N_PRINTER,
            PrinterStatus.N_OFFLINE,
            PrinterStatus.N_ERROR,
            PrinterStatus.N_PAPER,
        )
        try {
            a.cancelDiscovery()
            device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                socket.connect()
                val out = socket.outputStream
                val input = socket.inputStream

                return selectors.map { n ->
                    out.write(PrinterStatus.query(n))
                    out.flush()
                    val deadline = System.currentTimeMillis() + STATUS_READ_TIMEOUT_MS
                    while (input.available() == 0 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20)
                    }
                    if (input.available() > 0) input.read() else null
                }
            }
        } catch (e: SecurityException) {
            throw PrinterException("permission_denied", "Sin permiso BLUETOOTH_CONNECT")
        } catch (e: IOException) {
            throw PrinterException("connect_failed", "No se pudo consultar $address: ${e.message}")
        }
    }

    /** Stateless: conecta → escribe chunked → flush → cierra. */
    @SuppressLint("MissingPermission")
    fun print(address: String, bytes: ByteArray) {
        val a = adapter ?: throw PrinterException("unavailable", "Bluetooth no disponible en este dispositivo")
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw PrinterException("not_found", "Dirección Bluetooth inválida: $address")
        }
        try {
            a.cancelDiscovery() // discovery activo degrada la conexión SPP
            device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                socket.connect()
                Thread.sleep(SETTLE_AFTER_CONNECT_MS)
                val out = socket.outputStream
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(CHUNK_SIZE, bytes.size - offset)
                    out.write(bytes, offset, len)
                    out.flush()
                    offset += len
                    if (offset < bytes.size) Thread.sleep(CHUNK_GAP_MS)
                }
                // ponytail: conexión por trabajo (~1-2s de latencia BT por ticket);
                // pool keep-alive si la latencia molesta en caja.
                Thread.sleep((bytes.size / DRAIN_BYTES_PER_MS).coerceIn(DRAIN_MIN_MS, DRAIN_MAX_MS))
            }
        } catch (e: SecurityException) {
            throw PrinterException("permission_denied", "Sin permiso BLUETOOTH_CONNECT")
        } catch (e: IOException) {
            throw PrinterException("connect_failed", "No se pudo imprimir en $address: ${e.message}")
        }
    }
}
