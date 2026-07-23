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
        /** Corto a propósito: una impresora que no soporta DLE EOT nunca responde. */
        private const val STATUS_READ_TIMEOUT_MS = 800
    }

    /**
     * Pregunta las cuatro consultas de estado y devuelve los bytes crudos.
     * `null` en una posición significa "no contestó" — ver PrinterStatus.
     */
    fun status(host: String, port: Int): List<Int?> {
        val selectors = listOf(
            PrinterStatus.N_PRINTER,
            PrinterStatus.N_OFFLINE,
            PrinterStatus.N_ERROR,
            PrinterStatus.N_PAPER,
        )
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = STATUS_READ_TIMEOUT_MS
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                return selectors.map { n ->
                    try {
                        out.write(PrinterStatus.query(n))
                        out.flush()
                        val b = input.read()
                        if (b < 0) null else b
                    } catch (e: SocketTimeoutException) {
                        null
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw PrinterException("connect_failed", "Timeout conectando a $host:$port")
        } catch (e: IOException) {
            throw PrinterException("connect_failed", "No se pudo consultar $host:$port: ${e.message}")
        }
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
