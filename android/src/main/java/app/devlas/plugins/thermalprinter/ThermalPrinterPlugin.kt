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
import java.util.concurrent.Executors

@CapacitorPlugin(
    name = "ThermalPrinter",
    permissions = [
        Permission(alias = "bluetooth", strings = [Manifest.permission.BLUETOOTH_CONNECT])
    ]
)
class ThermalPrinterPlugin : Plugin() {

    // Un solo hilo: serializa los trabajos (nunca dos impresiones concurrentes al
    // mismo dispositivo) y saca el I/O bloqueante (sockets, bulkTransfer) del main.
    private val executor = Executors.newSingleThreadExecutor()

    private val usb by lazy { UsbTransport(context) }
    private val tcp = TcpTransport()
    private val bluetooth by lazy { BluetoothTransport(context) }

    override fun handleOnDestroy() {
        executor.shutdown()
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
        executor.execute {
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
