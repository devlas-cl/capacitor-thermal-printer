package app.devlas.plugins.thermalprinter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Descubre impresoras de red en la LAN, con dos técnicas complementarias:
 *
 * 1. **mDNS (NsdManager)** — las impresoras que se anuncian aparecen con su
 *    nombre real. Limpio, pero muchas térmicas baratas no publican servicio.
 * 2. **Barrido de subred** — prueba el puerto 9100 (JetDirect) en cada IP del
 *    /24 local. Cubre las que no anuncian, a costa de tiempo (~254 sondeos).
 *
 * Se corren en paralelo y se fusionan por IP: si una impresora aparece por
 * ambas, gana el nombre de mDNS.
 *
 * Sólo tiene sentido en el dispositivo que está EN la LAN (el POS): un dashboard
 * remoto no puede escanear la red del local. Por eso vive del lado del terminal.
 */
class TcpDiscovery(private val context: Context) {

    companion object {
        /** Servicios mDNS de impresión raw / IPP que nos interesan. */
        private val SERVICE_TYPES = listOf(
            "_pdl-datastream._tcp.",  // raw 9100 — el estándar térmico
            "_printer._tcp.",         // LPD
            "_ipp._tcp.",             // IPP
        )
        private const val RAW_PORT = 9100
        private const val PROBE_TIMEOUT_MS = 300
        private const val SCAN_PARALLELISM = 32
    }

    /** IP → {name?, port} acumulado por ambas técnicas; la clave evita duplicados. */
    private data class Found(var name: String?, val port: Int)

    fun discover(timeoutMs: Int): JSArray {
        val found = ConcurrentHashMap<String, Found>()

        val mdnsThread = Thread { discoverMdns(found, timeoutMs) }
        val scanThread = Thread { scanSubnet(found, timeoutMs) }
        mdnsThread.start()
        scanThread.start()
        mdnsThread.join((timeoutMs + 500).toLong())
        scanThread.join((timeoutMs + 500).toLong())

        val result = JSArray()
        for ((ip, f) in found) {
            val obj = JSObject()
            obj.put("transport", "tcp")
            obj.put("host", ip)
            obj.put("port", f.port)
            obj.put("name", f.name ?: ip)
            result.put(obj)
        }
        return result
    }

    // ── mDNS ─────────────────────────────────────────────────────────────

    private fun discoverMdns(found: ConcurrentHashMap<String, Found>, timeoutMs: Int) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val latch = CountDownLatch(SERVICE_TYPES.size)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        for (type in SERVICE_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    // resolve() completa host/puerto; el descubrimiento sólo trae el nombre.
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            val port = if (resolved.port > 0) resolved.port else RAW_PORT
                            found.compute(host) { _, existing ->
                                existing?.also { it.name = it.name ?: resolved.serviceName }
                                    ?: Found(resolved.serviceName, port)
                            }
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
            listeners.add(listener)
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        // mDNS es asíncrono; se le da la ventana completa y después se corta.
        latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        for (l in listeners) {
            try { nsd.stopServiceDiscovery(l) } catch (_: Exception) { /* ya detenido */ }
        }
    }

    // ── Barrido de subred ────────────────────────────────────────────────

    private fun scanSubnet(found: ConcurrentHashMap<String, Found>, timeoutMs: Int) {
        val base = localSubnetBase() ?: return
        val pool = Executors.newFixedThreadPool(SCAN_PARALLELISM)
        val deadline = System.currentTimeMillis() + timeoutMs

        for (host in 1..254) {
            val ip = "$base.$host"
            pool.execute {
                if (System.currentTimeMillis() > deadline) return@execute
                if (found.containsKey(ip)) return@execute
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, RAW_PORT), PROBE_TIMEOUT_MS)
                        // Conectó al 9100: lo tratamos como impresora candidata.
                        found.putIfAbsent(ip, Found(null, RAW_PORT))
                    }
                } catch (_: Exception) {
                    // cerrado / sin ruta — no es una impresora en 9100
                }
            }
        }

        pool.shutdown()
        pool.awaitTermination((timeoutMs + 500).toLong(), TimeUnit.MILLISECONDS)
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
