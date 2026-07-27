package app.devlas.plugins.thermalprinter

import android.Manifest
import android.content.Context
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
        // El túnel se serializa por (host, impresora remota): dos trabajos a la
        // misma impresora del mismo host no se solapan; a hosts distintos, sí.
        "tunnel" -> "tunnel:${call.getString("host")}:${call.getInt("port") ?: TunnelTransport.DEFAULT_PORT}:${call.getString("printerId")}"
        else -> "invalid"
    }

    private val usb by lazy { UsbTransport(context) }
    private val tcp = TcpTransport()
    private val bluetooth by lazy { BluetoothTransport(context) }
    private val discovery by lazy { TcpDiscovery(context) }
    private val tunnel = TunnelTransport()
    private val tunnelDiscovery by lazy { TunnelDiscovery(context) }

    // Modo host: servidor activo (o null), su id, y el mapeo id -> destino local
    // que es la fuente de verdad de a dónde imprime cada trabajo entrante.
    private var host: PrintHostServer? = null
    private var currentHostId: String? = null
    private val hostPrinters = ConcurrentHashMap<String, JSObject>()

    // Escaneo de red aparte del pool de impresión: no compite con los trabajos
    // en curso y un descubrimiento lento no retiene ninguna impresión.
    private val discoveryExecutor = Executors.newSingleThreadExecutor()

    override fun handleOnDestroy() {
        host?.stop()
        host = null
        executors.values.forEach { it.shutdown() }
        executors.clear()
        discoveryExecutor.shutdown()
    }

    // ── discover ─────────────────────────────────────────────────────────

    /**
     * Descubre impresoras de red en la LAN (mDNS + barrido de subred). Es el
     * equivalente de red a `list('usb')`: el POS enumera lo que ve para que el
     * dashboard lo ofrezca sin escribir IPs a mano.
     */
    @PluginMethod
    fun discover(call: PluginCall) {
        if (call.getString("transport") != "tcp") {
            call.reject("discover sólo soporta transport 'tcp'", "invalid_transport")
            return
        }
        val timeoutMs = call.getInt("timeoutMs") ?: 4000
        discoveryExecutor.execute {
            try {
                call.resolve(JSObject().put("devices", discovery.discover(timeoutMs)))
            } catch (e: Exception) {
                call.reject(e.message ?: "Error descubriendo impresoras de red", "unavailable")
            }
        }
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
            // El túnel no toca hardware local: el permiso lo resuelve el host.
            "tunnel" -> call.resolve(JSObject().put("granted", true))
            else -> call.reject("transport debe ser 'usb', 'tcp', 'bluetooth' o 'tunnel'", "invalid_transport")
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
                    "tunnel" -> {
                        val tHost = call.getString("host")
                            ?: throw PrinterException("connect_failed", "Falta 'host' para transporte tunnel")
                        val printerId = call.getString("printerId")
                            ?: throw PrinterException("not_found", "Falta 'printerId' para transporte tunnel")
                        // Se reenvía el base64 original tal cual — el host lo imprime
                        // en su impresora local mapeada por printerId.
                        tunnel.print(tHost, call.getInt("port") ?: TunnelTransport.DEFAULT_PORT, printerId, call.getString("token"), data)
                    }
                    else -> throw PrinterException("invalid_transport", "transport debe ser 'usb', 'tcp', 'bluetooth' o 'tunnel'")
                }
                call.resolve()
            } catch (e: PrinterException) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject(e.message ?: "Error de impresión", "write_failed")
            }
        }
    }

    // ── requestPermission: tunnel nunca requiere permiso del SO ──────────────

    // (el `when` de requestPermission agrega el caso 'tunnel' más arriba)

    // ── Modo host ────────────────────────────────────────────────────────────

    @PluginMethod
    fun startPrintHost(call: PluginCall) {
        val name = call.getString("name") ?: "Caja"
        val port = call.getInt("port") ?: TunnelTransport.DEFAULT_PORT
        val token = call.getString("token")
        val hostId = call.getString("hostId") ?: "wbhost-${System.currentTimeMillis()}"
        val printersArr = call.getArray("printers")
        if (printersArr == null) {
            call.reject("Falta 'printers'", "invalid_data")
            return
        }

        hostPrinters.clear()
        val meta = mutableListOf<Pair<String, String>>()
        try {
            for (i in 0 until printersArr.length()) {
                val p = JSObject.fromJSONObject(printersArr.getJSONObject(i))
                val id = p.getString("id") ?: continue
                val label = p.getString("label") ?: id
                val target = JSObject.fromJSONObject(p.getJSONObject("target"))
                hostPrinters[id] = target
                meta.add(id to label)
            }
        } catch (e: Exception) {
            call.reject("'printers' mal formado: ${e.message}", "invalid_data")
            return
        }

        host?.stop()

        val server = PrintHostServer(context, hostId, name, token, meta, object : HostJobHandler {
            override fun print(printerId: String, bytes: ByteArray) {
                val target = hostPrinters[printerId]
                    ?: throw PrinterException("not_found", "Impresora '$printerId' no publicada")
                // Va por la misma fila que las impresiones locales a ese destino,
                // así un trabajo remoto y uno local nunca se entrelazan en el papel.
                // Se usa execute + latch (no submit) para evitar la ambigüedad
                // Runnable/Callable de Kotlin con lambdas que devuelven Unit.
                val error = arrayOfNulls<Throwable>(1)
                val latch = java.util.concurrent.CountDownLatch(1)
                executorFor(targetKeyForTarget(target)).execute {
                    try {
                        printToTarget(target, bytes)
                    } catch (t: Throwable) {
                        error[0] = t
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await()
                error[0]?.let { t ->
                    if (t is PrinterException) throw t
                    throw PrinterException("write_failed", t.message ?: "Error al imprimir")
                }
            }

            override fun onJob(printerId: String, ok: Boolean, error: String?, from: String?) {
                val ev = JSObject().put("printerId", printerId).put("ok", ok)
                if (error != null) ev.put("error", error)
                if (from != null) ev.put("from", from)
                notifyListeners("printHostJob", ev)
            }
        })

        try {
            server.start(port)
        } catch (e: Exception) {
            call.reject(e.message ?: "No se pudo iniciar el modo host", "unavailable")
            return
        }

        host = server
        currentHostId = hostId
        call.resolve(
            JSObject()
                .put("hostId", hostId)
                .put("host", localIp() ?: "")
                .put("port", server.boundPort),
        )
    }

    @PluginMethod
    fun stopPrintHost(call: PluginCall) {
        host?.stop()
        host = null
        currentHostId = null
        hostPrinters.clear()
        call.resolve()
    }

    @PluginMethod
    fun printHostStatus(call: PluginCall) {
        val h = host
        val res = JSObject().put("running", h != null)
        if (h != null) {
            res.put("hostId", currentHostId ?: "")
            res.put("host", localIp() ?: "")
            res.put("port", h.boundPort)
            res.put("clients", h.clientCount())
        }
        call.resolve(res)
    }

    @PluginMethod
    fun discoverHosts(call: PluginCall) {
        val timeoutMs = call.getInt("timeoutMs") ?: 4000
        discoveryExecutor.execute {
            try {
                call.resolve(JSObject().put("hosts", tunnelDiscovery.discover(timeoutMs)))
            } catch (e: Exception) {
                call.reject(e.message ?: "Error descubriendo hosts", "unavailable")
            }
        }
    }

    // ── Helpers del modo host ────────────────────────────────────────────────

    /** Clave de serialización de un destino publicado (mismo formato que targetKey). */
    private fun targetKeyForTarget(t: JSObject): String = when (t.getString("transport")) {
        "usb" -> {
            val vid = intOrNull(t, "vendorId")
            val pid = intOrNull(t, "productId")
            if (vid != null && pid != null) "usb:$vid:$pid" else "usb:auto"
        }
        "tcp" -> "tcp:${t.getString("host")}:${intOrNull(t, "port") ?: 9100}"
        "bluetooth" -> "bt:${t.getString("address")}"
        else -> "invalid"
    }

    /** Imprime en el destino local publicado. Se ejecuta dentro de su fila. */
    private fun printToTarget(t: JSObject, bytes: ByteArray) {
        when (t.getString("transport")) {
            "usb" -> usb.print(intOrNull(t, "vendorId"), intOrNull(t, "productId"), bytes)
            "tcp" -> {
                val h = t.getString("host")
                    ?: throw PrinterException("connect_failed", "Falta 'host' en destino publicado")
                tcp.print(h, intOrNull(t, "port") ?: 9100, bytes)
            }
            "bluetooth" -> {
                val addr = t.getString("address")
                    ?: throw PrinterException("not_found", "Falta 'address' en destino publicado")
                bluetooth.print(addr, bytes)
            }
            else -> throw PrinterException("invalid_transport", "Transporte inválido en destino publicado")
        }
    }

    private fun intOrNull(o: JSObject, key: String): Int? =
        if (o.has(key)) o.getInt(key) else null

    /** IP WiFi del dispositivo, para reportar en qué dirección quedó escuchando. */
    @Suppress("DEPRECATION")
    private fun localIp(): String? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }
}
