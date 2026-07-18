import Foundation

/// Error con código estable que llega a JS como `error.code`:
/// unavailable | not_found | permission_denied | connect_failed
/// | write_failed | invalid_transport | invalid_data
struct PrinterError: Error {
    let code: String
    let message: String

    init(_ code: String, _ message: String) {
        self.code = code
        self.message = message
    }
}
