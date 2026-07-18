import Foundation
import Capacitor

/// Plugin de impresión térmica ESC/POS para iOS.
///
/// Solo transporte TCP (impresora de red) — USB y Bluetooth Clásico (SPP) en
/// iOS requieren el programa MFi (Made for iPhone) de Apple vía el framework
/// External Accessory; no es algo que se resuelva con código, es una
/// certificación de hardware aparte. Ver docs/IMPRESION_RED_PLAN.md.
@objc(ThermalPrinterPlugin)
public class ThermalPrinterPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ThermalPrinterPlugin"
    public let jsName = "ThermalPrinter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "print", returnType: CAPPluginReturnPromise)
    ]

    private let tcp = TcpTransport()
    private let printQueue = DispatchQueue(label: "cl.devlas.thermalprinter.print")

    // ── list ─────────────────────────────────────────────────────────────
    // TCP no es listable (la IP la ingresa el usuario) — igual que en Android.

    @objc func list(_ call: CAPPluginCall) {
        switch call.getString("transport") {
        case "usb", "bluetooth":
            call.reject("USB/Bluetooth no disponibles en iOS sin certificación MFi", "unavailable")
        default:
            call.reject("transport debe ser 'usb' o 'bluetooth'", "invalid_transport")
        }
    }

    // ── requestPermission ────────────────────────────────────────────────

    @objc func requestPermission(_ call: CAPPluginCall) {
        switch call.getString("transport") {
        case "tcp":
            call.resolve(["granted": true])
        case "usb", "bluetooth":
            call.reject("USB/Bluetooth no disponibles en iOS sin certificación MFi", "unavailable")
        default:
            call.reject("transport debe ser 'usb', 'tcp' o 'bluetooth'", "invalid_transport")
        }
    }

    // ── print ────────────────────────────────────────────────────────────

    @objc func print(_ call: CAPPluginCall) {
        guard let dataStr = call.getString("data"), !dataStr.isEmpty else {
            call.reject("Falta 'data' (bytes ESC/POS en base64)", "invalid_data")
            return
        }
        guard let bytes = Data(base64Encoded: dataStr) else {
            call.reject("'data' no es base64 válido", "invalid_data")
            return
        }

        switch call.getString("transport") {
        case "tcp":
            guard let host = call.getString("host") else {
                call.reject("Falta 'host' para transporte tcp", "connect_failed")
                return
            }
            let port = UInt16(call.getInt("port") ?? 9100)
            printQueue.async { [weak self] in
                self?.tcp.print(host: host, port: port, bytes: bytes) { result in
                    switch result {
                    case .success:
                        call.resolve()
                    case .failure(let error):
                        call.reject(error.message, error.code)
                    }
                }
            }
        case "usb", "bluetooth":
            call.reject("USB/Bluetooth no disponibles en iOS sin certificación MFi", "unavailable")
        default:
            call.reject("transport debe ser 'usb', 'tcp' o 'bluetooth'", "invalid_transport")
        }
    }
}
