import CoreBluetooth
import Foundation

/// Conexión permanente del Mac con el móvil para sincronizar (portapapeles y redes Wi-Fi).
///
/// Busca el móvil por Bluetooth LE; al encontrarlo conecta, se suscribe a los mensajes y saluda.
/// Si se pierde la conexión, CoreBluetooth la recupera sola en cuanto el móvil vuelve a estar cerca
/// (una petición de conexión no caduca). Todo ocurre en la cola principal.
final class SyncLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    var onConnected: () -> Void = {}
    var onMessage: (Msg) -> Void = { _ in }
    var onStatus: (String) -> Void = { _ in }
    private(set) var connected = false

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var msgIn: CBCharacteristic?
    private var foundHubId: Data?
    private let assembler = Framing.Assembler()
    private var nextMsgId: UInt16 = 0
    private var outbox: [Data] = []
    private var writing = false

    func start() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: .main)
    }

    /// Suelta el móvil actual y busca de nuevo (tras olvidar el emparejado).
    func restart() {
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
        lost()
        central?.stopScan()
        scan()
    }

    func send(_ msg: Msg) {
        guard connected, let p = peripheral else { return }
        // Fragmentos del tamaño de una escritura sin troceo del sistema (MTU - 3): el móvil no acepta
        // escrituras largas preparadas.
        let maxFrame = p.maximumWriteValueLength(for: .withoutResponse)
        nextMsgId &+= 1
        outbox.append(contentsOf: Framing.split(msgId: nextMsgId, data: msg.encode(), maxFrame: maxFrame))
        pump()
    }

    private func pump() {
        guard !writing, let p = peripheral, let c = msgIn, !outbox.isEmpty else { return }
        writing = true
        p.writeValue(outbox.removeFirst(), for: c, type: .withResponse)
    }

    private func scan() {
        guard let central, central.state == .poweredOn, peripheral == nil else { return }
        onStatus("Buscando el móvil…")
        central.scanForPeripherals(withServices: [PeraProtocol.service], options: nil)
    }

    private func lost() {
        connected = false
        msgIn = nil
        outbox.removeAll()
        writing = false
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if let p = peripheral { central.connect(p, options: nil) } else { scan() }
        case .poweredOff:
            lost()
            onStatus("Bluetooth apagado")
        case .unauthorized:
            onStatus("Permite el Bluetooth a Pera en Ajustes del Sistema › Privacidad y seguridad")
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard self.peripheral == nil, PeraProtocol.isOurHub(advertisementData) else { return }
        central.stopScan()
        foundHubId = PeraProtocol.hubId(advertisementData)
        self.peripheral = peripheral
        peripheral.delegate = self
        onStatus("Conectando…")
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([PeraProtocol.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return } // aviso de un móvil ya olvidado
        // Se vuelve a buscar desde cero (puede que no fuera nuestro móvil).
        self.peripheral = nil
        scan()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return } // aviso de un móvil ya olvidado
        let wasConnected = connected
        lost()
        if wasConnected {
            onStatus("Móvil fuera de alcance")
            central.connect(peripheral, options: nil) // se reconecta solo cuando vuelva
        } else {
            // Nunca llegó a funcionar (p. ej. emparejamiento rechazado): se busca de nuevo más tarde.
            self.peripheral = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in self?.scan() }
        }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = peripheral.services?.first(where: { $0.uuid == PeraProtocol.service }) else {
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([PeraProtocol.msgIn, PeraProtocol.msgOut], for: s)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let chars = service.characteristics ?? []
        msgIn = chars.first { $0.uuid == PeraProtocol.msgIn }
        guard msgIn != nil, let out = chars.first(where: { $0.uuid == PeraProtocol.msgOut }) else {
            onStatus("El móvil tiene una versión de Pera sin sincronización")
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        // Operación cifrada: si no estáis emparejados, macOS y el móvil piden confirmarlo.
        peripheral.setNotifyValue(true, for: out)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == PeraProtocol.msgOut else { return }
        if let error {
            onStatus("No se pudo emparejar con el móvil: \(error.localizedDescription)")
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        connected = true
        PeraProtocol.rememberHub(foundHubId)
        onStatus("Conectado al móvil")
        onConnected()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == PeraProtocol.msgIn else { return }
        writing = false
        if error != nil { outbox.removeAll() } // mensaje a medias: se descarta entero
        pump()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == PeraProtocol.msgOut, error == nil, let v = characteristic.value else { return }
        if let whole = assembler.add(v), let msg = Msg.decode(whole) { onMessage(msg) }
    }
}
