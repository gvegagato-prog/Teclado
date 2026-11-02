import CoreBluetooth
import Foundation

/// Protocolo Bluetooth LE de Pera. Debe coincidir con android/app/src/main/java/app/pera/Protocol.kt.
enum PeraProtocol {
    static let service = CBUUID(string: "b49ae33f-05ff-43dc-832e-1e1cbc315a8c")
    /// Escritura de 1 byte: 1 enciende el hotspot, 0 lo apaga.
    static let command = CBUUID(string: "c28cdba2-6e89-4698-ab29-d5caf1d9b653")
    /// Lectura y notificación: 1 byte con el estado.
    static let state = CBUUID(string: "e46e38d3-f5a3-469b-8476-d3a9740d2c89")
    /// Lectura: JSON con la red Wi-Fi del hotspot.
    static let wifi = CBUUID(string: "5e1e2505-2ebf-487d-b745-36b2692ef8bb")

    static let cmdOff: UInt8 = 0
    static let cmdOn: UInt8 = 1

    static let stateOff = 0
    static let stateTurningOn = 1
    static let stateOn = 2
    static let stateTurningOff = 3
    static let stateFailed = 4
    static let stateNoShizuku = 5

    static func stateText(_ s: Int) -> String {
        switch s {
        case stateOff: return "apagado"
        case stateTurningOn: return "encendiéndose"
        case stateOn: return "encendido"
        case stateTurningOff: return "apagándose"
        case stateFailed: return "error al encender"
        case stateNoShizuku: return "Shizuku no está activo en el móvil"
        default: return "desconocido (\(s))"
        }
    }

    struct Wifi: Decodable {
        let ssid: String
        let pass: String?
        let sec: String
    }
}
