package app.devlas.plugins.thermalprinter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject

/**
 * Transporte USB host (OTG) — habla directo con UsbManager, sin librerías.
 *
 * Selección de interfaz: prefiere la de clase impresora (USB_CLASS_PRINTER);
 * si el dispositivo no la declara (térmicas genéricas con clase vendor-specific),
 * cae a la primera interfaz con endpoint bulk OUT — heurística equivalente a la
 * usada por WebUSB en navegador, validada con hardware real.
 */
class UsbTransport(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "app.devlas.plugins.thermalprinter.USB_PERMISSION"
        private const val CHUNK_SIZE = 4096
        private const val TRANSFER_TIMEOUT_MS = 3000
    }

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun list(): JSArray {
        val result = JSArray()
        val manager = usbManager ?: return result
        for (device in manager.deviceList.values) {
            val obj = JSObject()
            obj.put("transport", "usb")
            obj.put("deviceId", device.deviceId)
            obj.put("vendorId", device.vendorId)
            obj.put("productId", device.productId)
            obj.put("name", device.productName ?: device.deviceName)
            obj.put("canPrint", findPrinterInterface(device) != null)
            obj.put("hasPermission", manager.hasPermission(device))
            result.put(obj)
        }
        return result
    }

    /**
     * Flujo de permiso compatible con Android 12/14+:
     * PendingIntent MUTABLE (el sistema rellena EXTRA_PERMISSION_GRANTED),
     * intent explícito (setPackage) y receiver RECEIVER_NOT_EXPORTED.
     * Ninguno de los plugins de la comunidad revisados hace esto correctamente.
     */
    fun requestPermission(
        vendorId: Int?,
        productId: Int?,
        onResult: (granted: Boolean) -> Unit,
        onError: (code: String, msg: String) -> Unit,
    ) {
        val manager = usbManager ?: return onError("unavailable", "USB no disponible en este dispositivo")
        val device = findDevice(manager, vendorId, productId)
            ?: return onError("not_found", "No se encontró una impresora USB conectada")
        if (manager.hasPermission(device)) return onResult(true)

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, received: Intent) {
                if (received.action != ACTION_USB_PERMISSION) return
                try { context.unregisterReceiver(this) } catch (_: Exception) { /* ya removido */ }
                onResult(received.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        manager.requestPermission(device, pendingIntent)
    }

    /** Stateless: abre → reclama interfaz → escribe chunked → libera → cierra. */
    fun print(vendorId: Int?, productId: Int?, bytes: ByteArray) {
        val manager = usbManager
            ?: throw PrinterException("unavailable", "USB no disponible en este dispositivo")
        val device = findDevice(manager, vendorId, productId)
            ?: throw PrinterException("not_found", "No se encontró una impresora USB conectada")
        if (!manager.hasPermission(device)) {
            throw PrinterException("permission_denied", "Sin permiso USB — llama a requestPermission() primero")
        }
        val (iface, endpoint) = findPrinterInterface(device)
            ?: throw PrinterException("not_found", "El dispositivo no tiene endpoint de salida (bulk OUT)")
        val connection = manager.openDevice(device)
            ?: throw PrinterException("connect_failed", "No se pudo abrir el dispositivo USB")
        try {
            if (!connection.claimInterface(iface, true)) {
                throw PrinterException("connect_failed", "No se pudo reclamar la interfaz USB")
            }
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(CHUNK_SIZE, bytes.size - offset)
                val sent = connection.bulkTransfer(endpoint, bytes, offset, len, TRANSFER_TIMEOUT_MS)
                if (sent < 0) throw PrinterException("write_failed", "bulkTransfer falló en offset $offset")
                offset += sent
            }
        } finally {
            try { connection.releaseInterface(iface) } catch (_: Exception) { /* best effort */ }
            connection.close()
        }
    }

    private fun findDevice(manager: UsbManager, vendorId: Int?, productId: Int?): UsbDevice? {
        val devices = manager.deviceList.values
        if (vendorId != null && productId != null) {
            return devices.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        }
        return devices.firstOrNull { findPrinterInterface(it) != null }
    }

    private fun findPrinterInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        var fallback: Pair<UsbInterface, UsbEndpoint>? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val out = bulkOutEndpoint(iface) ?: continue
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return iface to out
            if (fallback == null) fallback = iface to out
        }
        return fallback
    }

    private fun bulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                return ep
            }
        }
        return null
    }
}
