import Foundation
import Network

/// Transporte TCP — impresión raw a puerto 9100 (JetDirect), el estándar de
/// térmicas de red cableada y WiFi. Usa Network.framework (NWConnection) —
/// nativo de iOS, sin librerías externas.
///
/// Requiere `NSLocalNetworkUsageDescription` en el Info.plist de la app host
/// (iOS pide permiso de "red local" la primera vez que se conecta a una IP
/// privada — sin ese string el sistema rechaza el diálogo silenciosamente).
final class TcpTransport {

    private static let connectTimeoutMs = 4000

    func print(host: String, port: UInt16, bytes: Data, completion: @escaping (Result<Void, PrinterError>) -> Void) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            completion(.failure(PrinterError("connect_failed", "Puerto inválido: \(port)")))
            return
        }

        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        let queue = DispatchQueue(label: "cl.devlas.thermalprinter.tcp")
        var finished = false

        func finish(_ result: Result<Void, PrinterError>) {
            guard !finished else { return }
            finished = true
            connection.cancel()
            completion(result)
        }

        let timeoutWork = DispatchWorkItem {
            finish(.failure(PrinterError("connect_failed", "Timeout conectando a \(host):\(port)")))
        }
        queue.asyncAfter(deadline: .now() + .milliseconds(Self.connectTimeoutMs), execute: timeoutWork)

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                timeoutWork.cancel()
                connection.send(content: bytes, completion: .contentProcessed { error in
                    if let error = error {
                        finish(.failure(PrinterError("connect_failed", "No se pudo imprimir en \(host):\(port): \(error.localizedDescription)")))
                        return
                    }
                    // Espera proporcional antes de cerrar — evita truncar el búfer de la
                    // impresora en trabajos grandes (patrón DantSu: ~1ms por 16 bytes).
                    let delayMs = min(2000, bytes.count / 16)
                    queue.asyncAfter(deadline: .now() + .milliseconds(delayMs)) {
                        finish(.success(()))
                    }
                })
            case .failed(let error):
                timeoutWork.cancel()
                finish(.failure(PrinterError("connect_failed", "No se pudo conectar a \(host):\(port): \(error.localizedDescription)")))
            case .cancelled:
                timeoutWork.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
