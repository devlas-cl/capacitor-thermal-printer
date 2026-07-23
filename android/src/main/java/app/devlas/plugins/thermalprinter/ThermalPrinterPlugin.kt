package app.devlas.plugins.thermalprinter

import android.Manifest
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@CapacitorPlugin(
    name = "ThermalPrinter",
    permissions = [
        Permission(alias = "bluetooth", strings = [Manifest.permission.BLUETOOTH_CONNECT])
    ]
)
class ThermalPrinterPlugin : Plugin() {

    // Una fila por impresora de destino, no una global.
    //
    // Cada executor es de un solo hilo, asi que dos trabajos al MISMO destino
    // siguen serializados (nunca se entrelazan dos tickets en el mismo papel) y
    // el I/O bloqueante (sockets, bulkTransfer) sigue fuera del main thread.
    // Pero destinos distintos ya no se bloquean entre si: con un executor global,
    // una impresora caida retenia a todas las demas durante su CONNECT_TIMEOUT_MS
    // — la comanda a una cocina apagada demoraba la boleta del cliente.
    private val executors = ConcurrentHashMap<String, ExecutorService>()

    private fun executorFor(target: String): ExecutorService =
        executors.getOrPut(target) { Executors.newSingleThreadExecutor() }

    /**
     * Clave de serializacion: identifica la impresora fisica de destino.
     *
     * Devuelve una clave de descarte para llamadas mal formadas (transporte
     * desconocido, falta host/address) — esas fallan igual mas abajo con su
     * error especifico, solo necesitan alguna fila donde correr.
     */
    private fun targetKey(call: PluginCall): String = when (call.getString("transport")) {
        "usb" -> {
            val vid = call.getInt("vendorId")
            val pid = call.getInt("productId")
            // Sin vendorId/productId, UsbTransport usa "la primera impresora USB
            // detectada". No se puede saber cual es sin resolverla, asi que todas
            // las llamadas ambiguas comparten una fila.
            if (vid != null && pid != null) "usb:$vid:$pid" else "usb:auto"
        }
        "tcp" -> "tcp:${call.getString("host")}:${call.getInt("port") ?: 9100}"
        "bluetooth" -> "bt:${call.getString("address")}"
        else -> "invalid"
    }

    private val usb by lazy { UsbTransport(context) }
    private val tcp = TcpTransport()
    private val bluetooth by lazy { BluetoothTransport(context) }

    override fun handleOnDestroy() {
        executors.values.forEach { it.shutdown() }
        executors.clear()
    }

    // ── list ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun list(call: PluginCall) {
        when (call.getString("transport")) {
            "usb" -> call.resolve(JSObject().put("devices", usb.list()))
            "bluetooth" -> {
                if (getPermissionState("bluetooth") != PermissionState.GRANTED) {
                    requestPermissionForAlias("bluetooth", call, "bluetoothListCallback")
                    return
                }
                resolveBluetoothList(call)
            }
            else -> call.reject("transport debe ser 'usb' o 'bluetooth'", "invalid_transport")
        }
    }

    @PermissionCallback
    private fun bluetoothListCallback(call: PluginCall) {
        if (getPermissionState("bluetooth") == PermissionState.GRANTED) {
            resolveBluetoothList(call)
        } else {
            call.reject("Permiso Bluetooth denegado", "permission_denied")
        }
    }

    private fun resolveBluetoothList(call: PluginCall) {
        try {
            call.resolve(JSObject().put("devices", bluetooth.list()))
        } catch (e: PrinterException) {
            call.reject(e.message, e.code)
        }
    }

    // ── requestPermission ────────────────────────────────────────────────

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        when (call.getString("transport")) {
            "usb" -> usb.requestPermission(
                vendorId = call.getInt("vendorId"),
                productId = call.getInt("productId"),
                onResult = { granted -> call.resolve(JSObject().put("granted", granted)) },
                onError = { code, msg -> call.reject(msg, code) },
            )
            "bluetooth" -> {
                if (getPermissionState("bluetooth") == PermissionState.GRANTED) {
                    call.resolve(JSObject().put("granted", true))
                } else {
                    requestPermissionForAlias("bluetooth", call, "bluetoothPermissionCallback")
                }
            }
            "tcp" -> call.resolve(JSObject().put("granted", true))
            else -> call.reject("transport debe ser 'usb', 'tcp' o 'bluetooth'", "invalid_transport")
        }
    }

    @PermissionCallback
    private fun bluetoothPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().put("granted", getPermissionState("bluetooth") == PermissionState.GRANTED))
    }

    // ── status ───────────────────────────────────────────────────────────

    /**
     * Estado en tiempo real (papel, tapa, errores) via `DLE EOT`.
     *
     * Va por la misma fila que las impresiones al mismo destino: consultar el
     * estado mientras se escribe un ticket mezclaria los bytes en la linea.
     */
    @PluginMethod
    fun status(call: PluginCall) {
        val transport = call.getString("transport")
        executorFor(targetKey(call)).execute {
            try {
                val raw: List<Int?> = when (transport) {
                    "usb" -> usb.status(call.getInt("vendorId"), call.getInt("productId"))
                    "tcp" -> {
                        val host = call.getString("host")
                            ?: throw PrinterException("connect_failed", "Falta 'host' para transporte tcp")
                        tcp.status(host, call.getInt("port") ?: 9100)
                    }
                    "bluetooth" -> {
                        val address = call.getString("address")
                            ?: throw PrinterException("not_found", "Falta 'address' para transporte bluetooth")
                        bluetooth.status(address)
                    }
                    else -> throw PrinterException("invalid_transport", "transport debe ser 'usb', 'tcp' o 'bluetooth'")
                }
                call.resolve(PrinterStatus.toJs(raw[0], raw[1], raw[2], raw[3]))
            } catch (e: PrinterException) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject(e.message ?: "Error consultando estado", "write_failed")
            }
        }
    }

    // ── print ────────────────────────────────────────────────────────────

    @PluginMethod
    fun print(call: PluginCall) {
        val data = call.getString("data")
        if (data.isNullOrEmpty()) {
            call.reject("Falta 'data' (bytes ESC/POS en base64)", "invalid_data")
            return
        }
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            call.reject("'data' no es base64 válido", "invalid_data")
            return
        }
        val transport = call.getString("transport")
        executorFor(targetKey(call)).execute {
            try {
                when (transport) {
                    "usb" -> usb.print(call.getInt("vendorId"), call.getInt("productId"), bytes)
                    "tcp" -> {
                        val host = call.getString("host")
                            ?: throw PrinterException("connect_failed", "Falta 'host' para transporte tcp")
                        tcp.print(host, call.getInt("port") ?: 9100, bytes)
                    }
                    "bluetooth" -> {
                        val address = call.getString("address")
                            ?: throw PrinterException("not_found", "Falta 'address' para transporte bluetooth")
                        bluetooth.print(address, bytes)
                    }
                    else -> throw PrinterException("invalid_transport", "transport debe ser 'usb', 'tcp' o 'bluetooth'")
                }
                call.resolve()
            } catch (e: PrinterException) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject(e.message ?: "Error de impresión", "write_failed")
            }
        }
    }
}
