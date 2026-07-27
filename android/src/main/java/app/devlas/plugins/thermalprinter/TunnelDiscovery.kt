package app.devlas.plugins.thermalprinter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Descubre hosts de impresión en la LAN (mDNS `_wbprint._tcp`). Lee de los TXT
 * el id, nombre, versión de protocolo y las impresoras publicadas, así el
 * cliente arma el destino túnel sin escribir nada a mano.
 *
 * Igual que TcpDiscovery, sólo tiene sentido en el dispositivo que está EN la
 * LAN: un dashboard remoto no ve la red del local.
 */
class TunnelDiscovery(private val context: Context) {

    fun discover(timeoutMs: Int): JSArray {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return JSArray()
        val hosts = ConcurrentHashMap<String, JSObject>()
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
            return JSArray()
        }

        // mDNS es asíncrono: se le da la ventana completa y después se corta.
        latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}

        val arr = JSArray()
        for (o in hosts.values) arr.put(o)
        return arr
    }
}
