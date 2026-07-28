package app.devlas.plugins.thermalprinter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Descubre hosts de impresión en la LAN, con dos técnicas complementarias:
 *
 * 1. **mDNS (NsdManager)** — browse de `_wbprint._tcp`. Lee de los TXT el id,
 *    nombre, versión de protocolo y las impresoras publicadas. Limpio, pero
 *    depende de que el multicast funcione en la red (muchos APs lo filtran).
 * 2. **Barrido de subred** — prueba el puerto de túnel (9110) en cada IP del /24
 *    local; a los que aceptan la conexión les pide `op:'list'` y arma el host
 *    con la respuesta. Cubre las redes donde el mDNS no llega.
 *
 * Se corren en paralelo y se fusionan por `hostId`: si un host aparece por
 * ambas, queda una sola entrada.
 *
 * Igual que TcpDiscovery, sólo tiene sentido en el dispositivo que está EN la
 * LAN: un dashboard remoto no ve la red del local.
 */
class TunnelDiscovery(private val context: Context) {

    companion object {
        private const val PROBE_TIMEOUT_MS = 300
        private const val SCAN_PARALLELISM = 32
    }

    fun discover(timeoutMs: Int): JSArray {
        val hosts = ConcurrentHashMap<String, JSObject>()

        val mdnsThread = Thread { discoverMdns(hosts, timeoutMs) }
        val scanThread = Thread { scanSubnet(hosts, timeoutMs) }
        mdnsThread.start()
        scanThread.start()
        mdnsThread.join((timeoutMs + 500).toLong())
        scanThread.join((timeoutMs + 500).toLong())

        val arr = JSArray()
        for (o in hosts.values) arr.put(o)
        return arr
    }

    // ── mDNS ─────────────────────────────────────────────────────────────

    private fun discoverMdns(hosts: ConcurrentHashMap<String, JSObject>, timeoutMs: Int) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val latch = CountDownLatch(1)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                // resolve() completa host/puerto y los TXT; el found sólo trae nombre.
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val attrs = resolved.attributes ?: emptyMap()
                        fun attr(k: String): String? = attrs[k]?.toString(StandardCharsets.UTF_8)

                        val hostId = attr("id") ?: resolved.serviceName
                        val obj = JSObject()
                        obj.put("hostId", hostId)
                        obj.put("name", attr("name") ?: resolved.serviceName)
                        obj.put("host", host)
                        obj.put("port", if (resolved.port > 0) resolved.port else TunnelTransport.DEFAULT_PORT)
                        obj.put("proto", attr("proto")?.toIntOrNull() ?: 1)

                        val printers = JSArray()
                        val n = attr("n")?.toIntOrNull() ?: 0
                        for (i in 0 until n) {
                            val raw = attr("p$i") ?: continue
                            val sep = raw.indexOf('|')
                            val id = if (sep >= 0) raw.substring(0, sep) else raw
                            val label = if (sep >= 0) raw.substring(sep + 1) else raw
                            printers.put(JSObject().put("id", id).put("label", label))
                        }
                        obj.put("printers", printers)
                        hosts[hostId] = obj
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, err: Int) {}
                })
            }

            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
        }

        try {
            nsd.discoverServices(PrintHostServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            return
        }

        // mDNS es asíncrono: se le da la ventana completa y después se corta.
        latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    // ── Barrido de subred ────────────────────────────────────────────────

    private fun scanSubnet(hosts: ConcurrentHashMap<String, JSObject>, timeoutMs: Int) {
        val base = localSubnetBase() ?: return
        val pool = Executors.newFixedThreadPool(SCAN_PARALLELISM)
        val deadline = System.currentTimeMillis() + timeoutMs

        for (host in 1..254) {
            val ip = "$base.$host"
            pool.execute {
                if (System.currentTimeMillis() > deadline) return@execute
                probeHost(ip, hosts)
            }
        }

        pool.shutdown()
        pool.awaitTermination((timeoutMs + 500).toLong(), TimeUnit.MILLISECONDS)
    }

    /**
     * Conecta al puerto de túnel de `ip`, pide `op:'list'` y, si responde ok,
     * arma la entrada de host. Si no hay hostId en la respuesta, se descarta.
     */
    private fun probeHost(ip: String, hosts: ConcurrentHashMap<String, JSObject>) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, TunnelTransport.DEFAULT_PORT), PROBE_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS

                val req = JSONObject()
                    .put("proto", PrintHostServer.PROTO)
                    .put("op", "list")
                val out = socket.getOutputStream()
                out.write((req.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val line = reader.readLine() ?: return
                val res = JSONObject(line)
                if (!res.optBoolean("ok", false)) return

                val hostId = res.optString("hostId", "")
                if (hostId.isEmpty()) return

                val obj = JSObject()
                obj.put("hostId", hostId)
                obj.put("name", res.optString("name", hostId))
                obj.put("host", ip)
                obj.put("port", TunnelTransport.DEFAULT_PORT)
                obj.put("proto", res.optInt("proto", 1))

                val printers = JSArray()
                val arr = res.optJSONArray("printers")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val id = p.optString("id", "")
                        val label = p.optString("label", id)
                        printers.put(JSObject().put("id", id).put("label", label))
                    }
                }
                obj.put("printers", printers)

                // dedup por hostId: si ya lo trajo mDNS, no lo pisamos.
                hosts.putIfAbsent(hostId, obj)
            }
        } catch (_: Exception) {
            // cerrado / sin ruta / no habla el protocolo — no es un host de impresión
        }
    }

    /** Prefijo /24 de la IP WiFi del dispositivo, ej "192.168.1". Null sin WiFi. */
    @Suppress("DEPRECATION")
    private fun localSubnetBase(): String? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        // ipAddress viene little-endian.
        val a = ip and 0xff
        val b = ip shr 8 and 0xff
        val c = ip shr 16 and 0xff
        return "$a.$b.$c"
    }
}
