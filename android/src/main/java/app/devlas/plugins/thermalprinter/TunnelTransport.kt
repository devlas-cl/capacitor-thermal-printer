package app.devlas.plugins.thermalprinter

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Cliente de túnel: manda los bytes ESC/POS (ya en base64) a otra terminal que
 * corre como host (ver PrintHostServer) y el host los imprime en su impresora
 * local. Permite, por ejemplo, imprimir en una impresora USB de otra caja.
 *
 * Protocolo: una línea JSON de request, una línea JSON de response, sobre un
 * socket LAN plano. El cliente es nativo, así que no hay mixed-content ni cert
 * de por medio; la autorización es un `token` a nivel app (el PIN del host).
 */
class TunnelTransport {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000
        /** El host imprime ANTES de responder, así que la lectura espera más. */
        private const val READ_TIMEOUT_MS = 15000
        const val PROTO = 1
        const val DEFAULT_PORT = 9110
    }

    fun print(host: String, port: Int, printerId: String, token: String?, base64Data: String) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS

                val req = JSONObject()
                    .put("proto", PROTO)
                    .put("op", "print")
                    .put("printerId", printerId)
                    .put("format", "escpos-raw")
                    .put("data", base64Data)
                if (!token.isNullOrEmpty()) req.put("token", token)

                val out = socket.getOutputStream()
                out.write((req.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val line = reader.readLine()
                    ?: throw PrinterException("connect_failed", "El host no respondió")
                val res = JSONObject(line)
                if (!res.optBoolean("ok", false)) {
                    val msg = res.optString("error", "El host rechazó la impresión")
                    val code = res.optString("code", "write_failed")
                    throw PrinterException(code, msg)
                }
            }
        } catch (e: SocketTimeoutException) {
            throw PrinterException("connect_failed", "Timeout con el host $host:$port")
        } catch (e: IOException) {
            throw PrinterException("connect_failed", "No se pudo llegar al host $host:$port: ${e.message}")
        }
    }
}
