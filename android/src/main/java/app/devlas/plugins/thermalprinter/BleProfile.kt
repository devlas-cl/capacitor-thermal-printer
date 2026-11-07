package app.devlas.plugins.thermalprinter

/**
 * Reglas puras del transporte BLE: que servicio/caracteristica usar para
 * escribir ESC/POS y como trocear. Sin tipos de Android a proposito, para
 * poder probarlas con JUnit sin dispositivo (ver BleProfileTest).
 *
 * BLE no define un servicio de impresion estandar: cada fabricante expone un
 * "UART transparente" con una caracteristica escribible. A diferencia de la
 * web, donde Chrome obliga a declarar los servicios de antemano, el nativo
 * descubre todos los que tiene la impresora, asi que la lista de abajo solo
 * sirve para PRIORIZAR; si ninguno coincide se usa cualquier caracteristica
 * escribible que no sea de un servicio estandar del sistema.
 */
object BleProfile {

    data class Characteristic(
        val uuid: String,
        val canWrite: Boolean,
        val canWriteNoResponse: Boolean,
    )

    data class Service(val uuid: String, val characteristics: List<Characteristic>)

    /** Caracteristica elegida y como escribirle. */
    data class Channel(
        val serviceUuid: String,
        val characteristicUuid: String,
        /** true = WRITE_NO_RESPONSE (rapido, requiere pausas); false = WRITE con respuesta. */
        val withoutResponse: Boolean,
    )

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** UUID de 16 bits a su forma de 128 bits en minusculas. */
    fun shortUuid(short: Int): String = String.format("0000%04x%s", short, BASE_SUFFIX)

    /** Minusculas, y los de 16/32 bits expandidos a 128. */
    fun normalize(uuid: String): String {
        val u = uuid.trim().lowercase()
        return when (u.length) {
            4 -> "0000$u$BASE_SUFFIX"
            8 -> "$u$BASE_SUFFIX"
            else -> u
        }
    }

    /** UART transparentes de las termicas BLE, en orden de preferencia. */
    val KNOWN_PRINTER_SERVICES: List<String> = listOf(
        "49535343-fe7d-4ae5-8fa9-9fafd205e455", // ISSC / Microchip
        "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e", // Nordic UART
        shortUuid(0x18f0),
        shortUuid(0xffe0),
        shortUuid(0xff00),
        shortUuid(0xfee7),
        shortUuid(0xae30),
        shortUuid(0xae00),
        shortUuid(0xff80),
        shortUuid(0xfff0),
    )

    /** Servicios del sistema: jamas se les escribe ESC/POS. */
    private val SYSTEM_SERVICES: Set<String> = setOf(
        shortUuid(0x1800), // Generic Access
        shortUuid(0x1801), // Generic Attribute
        shortUuid(0x180a), // Device Information
        shortUuid(0x180f), // Battery
        "0000fe59-0000-1000-8000-00805f9b34fb", // Nordic DFU (actualizacion de firmware)
    )

    /**
     * Elige donde escribir. Primero un UART conocido, en el orden de
     * [KNOWN_PRINTER_SERVICES]; si no hay, el primer servicio no estandar con
     * una caracteristica escribible. Dentro del servicio prefiere
     * WRITE_NO_RESPONSE: es el modo normal de las termicas y el unico con
     * velocidad razonable para una boleta raster.
     */
    fun pickChannel(services: List<Service>): Channel? {
        val byUuid = services.associateBy { normalize(it.uuid) }
        for (known in KNOWN_PRINTER_SERVICES) {
            val svc = byUuid[known] ?: continue
            channelIn(svc)?.let { return it }
        }
        for (svc in services) {
            if (normalize(svc.uuid) in SYSTEM_SERVICES) continue
            channelIn(svc)?.let { return it }
        }
        return null
    }

    private fun channelIn(svc: Service): Channel? {
        val noResp = svc.characteristics.firstOrNull { it.canWriteNoResponse }
        if (noResp != null) return Channel(normalize(svc.uuid), normalize(noResp.uuid), true)
        val withResp = svc.characteristics.firstOrNull { it.canWrite }
        if (withResp != null) return Channel(normalize(svc.uuid), normalize(withResp.uuid), false)
        return null
    }

    /** Carga util por escritura: MTU menos 3 de cabecera ATT, con tope y piso. */
    const val DEFAULT_MTU = 23
    const val REQUESTED_MTU = 247
    private const val ATT_HEADER = 3
    private const val MAX_CHUNK = 180
    private const val MIN_CHUNK = 20

    fun chunkSize(mtu: Int): Int = (mtu - ATT_HEADER).coerceIn(MIN_CHUNK, MAX_CHUNK)

    /** Pausa entre escrituras sin respuesta: la termica barata pierde bytes si la ahogan. */
    const val CHUNK_GAP_MS = 20L

    /** Espera tras el ultimo byte para no cortar la cola; crece con el trabajo (como SPP). */
    private const val DRAIN_MIN_MS = 500L
    private const val DRAIN_MAX_MS = 5000L
    private const val DRAIN_BYTES_PER_MS = 8L

    fun drainMs(totalBytes: Int): Long =
        (totalBytes / DRAIN_BYTES_PER_MS).coerceIn(DRAIN_MIN_MS, DRAIN_MAX_MS)

    /** Cuantas escrituras hacen falta para [totalBytes] con [chunk] por escritura. */
    fun chunkCount(totalBytes: Int, chunk: Int): Int =
        if (totalBytes <= 0) 0 else (totalBytes + chunk - 1) / chunk
}
