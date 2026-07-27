package app.devlas.plugins.thermalprinter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trabajos entrantes: imprime en la impresora local mapeada y notifica el
 * resultado. Lo implementa el plugin, que es quien tiene los transportes y las
 * filas de serialización por impresora.
 */
interface HostJobHandler {
    /** Imprime en la impresora local mapeada por `printerId`. Lanza PrinterException si falla. */
    fun print(printerId: String, bytes: ByteArray)
    /** Notifica el resultado de cada trabajo, para el log en vivo de Ajustes. */
    fun onJob(printerId: String, ok: Boolean, error: String?, from: String?)
}

/**
 * Servidor de host de impresión: escucha en la LAN, se anuncia por mDNS
 * (`_wbprint._tcp`) y, por cada trabajo entrante, imprime en la impresora local
 * mapeada por `printerId`. Todo nativo, sin volver al WebView, así funciona
 * aunque la app esté en segundo plano.
 *
 * Protocolo: una línea JSON por request. `op:'print'` imprime; `op:'list'`
 * devuelve las impresoras publicadas (para el alta manual por IP del cliente).
 */
class PrintHostServer(
    private val context: Context,
    private val hostId: String,
    private val name: String,
    private val token: String?,
    /** (id, label) de cada impresora publicada, para mDNS y `op:'list'`. */
    private val printerMeta: List<Pair<String, String>>,
    private val handler: HostJobHandler,
) {
    companion object {
        const val SERVICE_TYPE = "_wbprint._tcp."
        const val PROTO = 1
        private const val CONN_READ_TIMEOUT_MS = 20000
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = AtomicInteger(0)
    private val connExecutor = Executors.newCachedThreadPool()
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    @Volatile private var running = false
    var boundPort: Int = 0
        private set

    fun start(port: Int) {
        val sock = ServerSocket(port)
        serverSocket = sock
        boundPort = sock.localPort
        running = true
        acceptThread = Thread { acceptLoop(sock) }.also { it.start() }
        registerMdns(boundPort)
    }

    fun stop() {
        running = false
        try { unregisterMdns() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        connExecutor.shutdownNow()
    }

    fun clientCount(): Int = clients.get()

    // ── Accept loop ─────────────────────────────────────────────────────────

    private fun acceptLoop(sock: ServerSocket) {
        while (running) {
            val client = try {
                sock.accept()
            } catch (e: IOException) {
                if (running) continue else break
            }
            connExecutor.execute { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        clients.incrementAndGet()
        val from = socket.inetAddress?.hostAddress
        try {
            socket.soTimeout = CONN_READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val out = socket.getOutputStream()

            val line = reader.readLine() ?: return
            val req = try {
                JSONObject(line)
            } catch (e: Exception) {
                writeError(out, "invalid_data", "JSON inválido"); return
            }

            when (req.optString("op", "print")) {
                "list" -> writeList(out)
                "print" -> handlePrint(req, out, from)
                else -> writeError(out, "invalid_data", "op desconocido")
            }
        } catch (e: Exception) {
            // Conexión caída antes de poder responder — nada que hacer.
        } finally {
            clients.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handlePrint(req: JSONObject, out: OutputStream, from: String?) {
        val printerId = req.optString("printerId")

        if (!token.isNullOrEmpty() && req.optString("token") != token) {
            handler.onJob(printerId, false, "PIN incorrecto", from)
            writeError(out, "permission_denied", "PIN incorrecto")
            return
        }

        val data = req.optString("data")
        if (printerId.isEmpty() || data.isEmpty()) {
            writeError(out, "invalid_data", "Falta printerId o data")
            return
        }

        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            writeError(out, "invalid_data", "data no es base64 válido")
            return
        }

        try {
            handler.print(printerId, bytes)
            handler.onJob(printerId, true, null, from)
            writeOk(out)
        } catch (e: PrinterException) {
            handler.onJob(printerId, false, e.message, from)
            writeError(out, e.code, e.message ?: "Error al imprimir")
        }
    }

    // ── Respuestas ────────────────────────────────────────────────────────

    private fun writeLine(out: OutputStream, obj: JSONObject) {
        out.write((obj.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun writeOk(out: OutputStream) = writeLine(out, JSONObject().put("ok", true))

    private fun writeError(out: OutputStream, code: String, msg: String) =
        writeLine(out, JSONObject().put("ok", false).put("code", code).put("error", msg))

    private fun writeList(out: OutputStream) {
        val arr = JSONArray()
        for ((id, label) in printerMeta) arr.put(JSONObject().put("id", id).put("label", label))
        writeLine(
            out,
            JSONObject()
                .put("ok", true)
                .put("hostId", hostId)
                .put("name", name)
                .put("proto", PROTO)
                .put("printers", arr),
        )
    }

    // ── mDNS advertise ──────────────────────────────────────────────────────

    private fun registerMdns(port: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "WB $name"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", hostId)
            setAttribute("name", name)
            setAttribute("proto", PROTO.toString())
            setAttribute("n", printerMeta.size.toString())
            // Las impresoras publicadas van en TXT (2 roles entran de sobra). El
            // cliente igual puede pedir `op:'list'` si necesita la lista fresca.
            printerMeta.forEachIndexed { i, (id, label) -> setAttribute("p$i", "$id|$label") }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {}
        }
        regListener = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            regListener = null
        }
    }

    private fun unregisterMdns() {
        val m = nsd ?: return
        val l = regListener ?: return
        m.unregisterService(l)
        regListener = null
    }
}
