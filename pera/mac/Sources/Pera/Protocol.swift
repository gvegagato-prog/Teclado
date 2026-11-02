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

    /// Canal de mensajes Mac → móvil (escritura de fragmentos).
    static let msgIn = CBUUID(string: "4af7edca-a0a3-49ed-b043-e555361dd8ee")
    /// Canal de mensajes móvil → Mac (notificación de fragmentos).
    static let msgOut = CBUUID(string: "f049b244-0074-4753-b89f-8c89ae98f59b")

    /// ¿Es este anuncio el de nuestro móvil? Sin identificador guardado se acepta el primero; tras
    /// emparejar, solo el nuestro.
    static func isOurHub(_ advertisementData: [String: Any]) -> Bool {
        guard let mine = UserDefaults.standard.data(forKey: "pairedHub") else { return true }
        return hubId(advertisementData) == mine
    }

    /// 4 bytes en los datos de fabricante del anuncio (0xFFFF, little endian: FF FF + id). Pera 0.2-0.3
    /// los ponía en los datos de servicio de la respuesta al escaneo: también se aceptan.
    static func hubId(_ advertisementData: [String: Any]) -> Data? {
        if let m = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
           m.count == 2 + 4, m[m.startIndex] == 0xFF, m[m.startIndex + 1] == 0xFF {
            return Data(m.dropFirst(2))
        }
        return (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[service]
    }

    static func rememberHub(_ id: Data?) {
        guard let id, UserDefaults.standard.data(forKey: "pairedHub") == nil else { return }
        UserDefaults.standard.set(id, forKey: "pairedHub")
    }

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

    struct Wifi: Decodable, Equatable {
        let ssid: String
        let pass: String?
        let sec: String
        /// Red oculta (no anuncia su nombre). Ausente en mensajes de Pera ≤ 0.7.
        var hid: Bool? = nil
    }
}
