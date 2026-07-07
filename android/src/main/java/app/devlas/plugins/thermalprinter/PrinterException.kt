package app.devlas.plugins.thermalprinter

/**
 * Error con código estable que llega a JS como `error.code`:
 * unavailable | not_found | permission_denied | connect_failed
 * | write_failed | invalid_transport | invalid_data
 */
class PrinterException(val code: String, message: String) : Exception(message)
