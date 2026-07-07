package app.devlas.plugins.thermalprinter

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Transporte TCP — impresión raw a puerto 9100 (JetDirect), el estándar de
 * térmicas de red cableada y WiFi. Sin librerías: un Socket y ya.
 */
class TcpTransport {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val CHUNK_SIZE = 8192
    }

    fun print(host: String, port: Int, bytes: ByteArray) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val out = socket.getOutputStream()
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(CHUNK_SIZE, bytes.size - offset)
                    out.write(bytes, offset, len)
                    offset += len
                }
                out.flush()
                // Espera proporcional antes de cerrar — evita truncar el búfer de la
                // impresora en trabajos grandes (patrón DantSu: ~1ms por 16 bytes).
                Thread.sleep(minOf(2000L, bytes.size / 16L))
            }
        } catch (e: SocketTimeoutException) {
            throw PrinterException("connect_failed", "Timeout conectando a $host:$port")
        } catch (e: IOException) {
            throw PrinterException("connect_failed", "No se pudo imprimir en $host:$port: ${e.message}")
        }
    }
}
