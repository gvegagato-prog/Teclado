import CoreBluetooth
import Foundation

/// Habla con Pera en el móvil por Bluetooth LE: lo busca, le pide encender/apagar el hotspot y, al
/// encenderlo, lee la red Wi-Fi. Todo ocurre en la cola principal.
final class PeraLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    enum Outcome {
        case success(on: Bool, wifi: PeraProtocol.Wifi?)
        case failure(String)
    }

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var commandChar: CBCharacteristic?
    private var stateChar: CBCharacteristic?
    private var wifiChar: CBCharacteristic?

    private var turnOn = true
    private var progress: (String) -> Void = { _ in }
    private var completion: ((Outcome) -> Void)?
    private var timeout: DispatchWorkItem?
    private var poll: Timer?
    private var commandSent = false
    private var foundHubId: Data?

    var isBusy: Bool { completion != nil }

    func run(turnOn: Bool, progress: @escaping (String) -> Void, completion: @escaping (Outcome) -> Void) {
        guard !isBusy else { return }
        self.turnOn = turnOn
        self.progress = progress
        self.completion = completion
        commandSent = false
        arm(seconds: 20, message: "No encuentro el móvil. ¿Tiene Pera activado y el Bluetooth encendido?")
        if let central, central.state == .poweredOn {
            startScan()
        } else if central == nil {
            // Crear el gestor dispara el aviso de permiso de Bluetooth la primera vez.
            central = CBCentralManager(delegate: self, queue: .main)
        }
    }

    // MARK: - Pasos

    private func startScan() {
        progress("Buscando el móvil…")
        central?.scanForPeripherals(withServices: [PeraProtocol.service], options: nil)
    }

    private func sendCommand() {
        guard let peripheral, let commandChar else { return }
        progress(turnOn ? "Encendiendo el hotspot…" : "Apagando el hotspot…")
        commandSent = true
        let value = Data([turnOn ? PeraProtocol.cmdOn : PeraProtocol.cmdOff])
        peripheral.writeValue(value, for: commandChar, type: .withResponse)
        arm(seconds: 30, message: "El móvil no terminó de \(turnOn ? "encender" : "apagar") el hotspot")
    }

    private func handleState(_ s: Int) {
        guard commandSent else { return }
        let target = turnOn ? PeraProtocol.stateOn : PeraProtocol.stateOff
        if s == PeraProtocol.stateNoShizuku {
            finish(.failure("En el móvil, Shizuku no está activo. Ábrelo y arráncalo."))
        } else if s == PeraProtocol.stateFailed {
            finish(.failure("El móvil no pudo encender el hotspot"))
        } else if s == target {
            if turnOn, let wifiChar {
                progress("Leyendo la red Wi-Fi…")
                peripheral?.readValue(for: wifiChar)
            } else {
                finish(.success(on: turnOn, wifi: nil))
            }
        }
    }

    private func arm(seconds: Double, message: String) {
        timeout?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.finish(.failure(message)) }
        timeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func finish(_ outcome: Outcome) {
        timeout?.cancel()
        timeout = nil
        poll?.invalidate()
        poll = nil
        central?.stopScan()
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        commandChar = nil
        stateChar = nil
        wifiChar = nil
        let done = completion
        completion = nil
        done?(outcome)
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if isBusy && peripheral == nil { startScan() }
        case .poweredOff:
            if isBusy { finish(.failure("Activa el Bluetooth del Mac")) }
        case .unauthorized:
            if isBusy { finish(.failure("Permite el Bluetooth a Pera en Ajustes del Sistema › Privacidad y seguridad")) }
        case .unsupported:
            if isBusy { finish(.failure("Este Mac no admite Bluetooth LE")) }
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard isBusy, self.peripheral == nil, PeraProtocol.isOurHub(advertisementData) else { return }
        foundHubId = PeraProtocol.hubId(advertisementData)
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        progress("Conectando…")
        central.connect(peripheral, options: nil)
        arm(seconds: 15, message: "El móvil no responde")
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([PeraProtocol.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish(.failure("No se pudo conectar: \(error?.localizedDescription ?? "sin detalle")"))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if isBusy { finish(.failure("Se perdió la conexión con el móvil")) }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == PeraProtocol.service }) else {
            return finish(.failure("El móvil no tiene Pera activado"))
        }
        peripheral.discoverCharacteristics([PeraProtocol.command, PeraProtocol.state, PeraProtocol.wifi], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            switch c.uuid {
            case PeraProtocol.command: commandChar = c
            case PeraProtocol.state: stateChar = c
            case PeraProtocol.wifi: wifiChar = c
            default: break
            }
        }
        guard let stateChar, commandChar != nil else {
            return finish(.failure("Versión de Pera incompatible en el móvil"))
        }
        // Primera operación cifrada: si no estáis emparejados, macOS y el móvil piden confirmarlo.
        progress("Si aparece, acepta el emparejamiento en el Mac y en el móvil")
        arm(seconds: 45, message: "Emparejamiento no completado")
        peripheral.setNotifyValue(true, for: stateChar)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == PeraProtocol.state else { return }
        if let error {
            return finish(.failure("No se pudo emparejar con el móvil: \(error.localizedDescription)"))
        }
        PeraProtocol.rememberHub(foundHubId) // cifrado y emparejado: este es nuestro móvil
        sendCommand()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == PeraProtocol.command else { return }
        if let error {
            return finish(.failure("El móvil rechazó la orden: \(error.localizedDescription)"))
        }
        // Por si se pierde alguna notificación, se consulta el estado cada 3 s.
        poll?.invalidate()
        poll = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            guard let self, let p = self.peripheral, let s = self.stateChar else { return }
            p.readValue(for: s)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == PeraProtocol.wifi && error != nil {
            // El hotspot ya está encendido: sin la red, el Mac se une si la conoce de antes.
            return finish(.success(on: true, wifi: nil))
        }
        guard error == nil, let data = characteristic.value else { return }
        switch characteristic.uuid {
        case PeraProtocol.state:
            if let b = data.first { handleState(Int(b)) }
        case PeraProtocol.wifi:
            let wifi = try? JSONDecoder().decode(PeraProtocol.Wifi.self, from: data)
            finish(.success(on: true, wifi: wifi))
        default:
            break
        }
    }
}
