package app.devlas.plugins.thermalprinter

import com.getcapacitor.JSObject

/**
 * Consulta de estado en tiempo real ESC/POS — `DLE EOT n` (0x10 0x04 n).
 *
 * La impresora responde **1 byte** por consulta. Es "en tiempo real": la
 * firmware la atiende sin encolarla detrás del trabajo en curso, así que sirve
 * para preguntar "¿tenés papel?" antes de mandar un ticket.
 *
 * ## Advertencia: no todas las térmicas lo implementan
 *
 * En clones baratos pasan dos cosas feas:
 *
 * 1. **No responden** — se resuelve con el timeout corto y `supported = false`.
 * 2. **Interpretan los bytes como datos** y escupen basura en el papel.
 *
 * Por eso esto NO se llama solo en cada impresión: es una consulta explícita
 * que la app decide hacer (pantalla de ajustes, chequeo previo a cobrar), y la
 * primera vez conviene probarla con la impresora real de cada local.
 */
object PrinterStatus {

    /** Selectores de `DLE EOT n` que consultamos. */
    const val N_PRINTER = 1
    const val N_OFFLINE = 2
    const val N_ERROR = 3
    const val N_PAPER = 4

    fun query(n: Int): ByteArray = byteArrayOf(0x10, 0x04, n.toByte())

    /**
     * Una respuesta válida trae bit1 en 1 y bit0 en 0 (0x02 fijo), y bit4 en 1
     * (0x10 fijo). Sirve para descartar eco de datos o ruido de línea.
     */
    private fun isValid(b: Int): Boolean = (b and 0x93) == 0x12

    /**
     * Arma el estado a partir de las cuatro respuestas. Cualquiera puede venir
     * null (la impresora no contestó esa consulta) y se degrada a "desconocido"
     * en vez de inventar un valor.
     */
    fun toJs(
        printer: Int?,
        offline: Int?,
        error: Int?,
        paper: Int?,
    ): JSObject {
        val obj = JSObject()

        val anyValid = listOf(printer, offline, error, paper).any { it != null && isValid(it) }
        obj.put("supported", anyValid)

        // n=1 bit3: 1 = offline.
        if (printer != null && isValid(printer)) {
            obj.put("online", (printer and 0x08) == 0)
        }

        // n=2 bit2: 1 = tapa abierta.
        if (offline != null && isValid(offline)) {
            obj.put("coverOpen", (offline and 0x04) != 0)
        }

        // n=3 bit3: error del cortador. bit5: error irrecuperable.
        // bit6: error auto-recuperable (típicamente cabezal sobrecalentado).
        if (error != null && isValid(error)) {
            obj.put("cutterError", (error and 0x08) != 0)
            obj.put("fatalError", (error and 0x20) != 0)
            obj.put("recoverableError", (error and 0x40) != 0)
        }

        // n=4 bits 2-3: papel por acabarse. bits 5-6: sin papel.
        if (paper != null && isValid(paper)) {
            obj.put("paperNearEnd", (paper and 0x0C) != 0)
            obj.put("paperOut", (paper and 0x60) != 0)
        }

        return obj
    }
}
