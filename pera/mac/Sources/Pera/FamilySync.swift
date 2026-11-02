import AppKit
import Foundation

/// Sincronización de la familia en el Mac: portapapeles (automático) y redes Wi-Fi (se añaden al Mac
/// cuando lo confirmas, porque macOS pide la contraseña de administrador).
final class FamilySync: ObservableObject {
    @Published var status = "Desactivada"
    @Published var pendingNets: [PeraProtocol.Wifi] = []
    @Published var clipEnabled: Bool { didSet { defaults.set(clipEnabled, forKey: "clipOn") } }
    @Published var wifiEnabled: Bool { didSet { defaults.set(wifiEnabled, forKey: "wifiOn") } }

    private let defaults = UserDefaults.standard
    private let link = SyncLink()
    private let deviceName = Host.current().localizedName ?? "Mac"
    private var timer: Timer?
    private var lastChange = NSPasteboard.general.changeCount
    private var ownChange = -1

    static let maxClipChars = 32_000

    init() {
        clipEnabled = defaults.object(forKey: "clipOn") as? Bool ?? true
        wifiEnabled = defaults.object(forKey: "wifiOn") as? Bool ?? true
        pendingNets = loadNets("pendingNets")
        link.onStatus = { [weak self] in self?.status = $0 }
        link.onConnected = { [weak self] in self?.greet() }
        link.onMessage = { [weak self] in self?.handle($0) }
        // Empieza nada más abrir la app, sin esperar a que se abra el menú.
        DispatchQueue.main.async { [weak self] in self?.start() }
    }

    private func start() {
        link.start()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.checkPasteboard() }
    }

    // MARK: - Mensajes

    private func greet() {
        link.send(.hello(device: deviceName, clipTs: clip?.ts ?? 0))
        // Lo copiado aquí mientras el móvil no estaba: el móvil se queda con lo más reciente.
        if let c = clip, c.from == deviceName { link.send(.clip(c)) }
    }

    private func handle(_ msg: Msg) {
        switch msg {
        case let .clip(c):
            guard clipEnabled else { return }
            if let current = clip, !(c.ts > current.ts && c.id != current.id) { return }
            clip = c
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(c.text, forType: .string)
            ownChange = pb.changeCount
            lastChange = pb.changeCount
        case let .nets(list):
            guard wifiEnabled else { return }
            let applied = Set(defaults.stringArray(forKey: "appliedNets") ?? [])
            let fresh = list.filter { syncable($0) && !applied.contains(key($0)) }
            var pending = pendingNets.filter { p in !fresh.contains { $0.ssid == p.ssid } }
            pending.append(contentsOf: fresh)
            pendingNets = pending
            saveNets(pending, "pendingNets")
        case .hello:
            break
        }
    }

    // MARK: - Portapapeles

    private func checkPasteboard() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChange else { return }
        lastChange = pb.changeCount
        guard clipEnabled, pb.changeCount != ownChange else { return }
        // Convenio de nspasteboard.org: los gestores de contraseñas marcan así lo que copian.
        let types = pb.types ?? []
        let secret = types.contains { $0.rawValue == "org.nspasteboard.ConcealedType" || $0.rawValue == "org.nspasteboard.TransientType" }
        guard !secret, let text = pb.string(forType: .string), !text.isEmpty, text.count <= Self.maxClipChars else { return }
        if text == clip?.text { return }
        let c = Msg.Clip.new(text, from: deviceName)
        clip = c
        link.send(.clip(c))
    }

    private var clip: Msg.Clip? {
        get {
            guard let d = defaults.data(forKey: "clip"), case let .clip(c)? = Msg.decode(d) else { return nil }
            return c
        }
        set { defaults.set(newValue.map { Msg.clip($0).encode() }, forKey: "clip") }
    }

    // MARK: - Redes Wi-Fi

    /// Añade al Mac las redes pendientes (una sola petición de contraseña de administrador).
    func addPendingNetworks() {
        let ok = pendingNets.filter { $0.sec != "wpa3" }
        let skipped = pendingNets.count - ok.count
        guard !ok.isEmpty else {
            status = skipped > 0 ? "macOS no permite añadir redes WPA3 desde Pera: elígelas a mano una vez" : "No hay redes nuevas"
            return
        }
        let result = WifiJoiner.addPreferred(ok)
        if result == nil {
            var applied = defaults.stringArray(forKey: "appliedNets") ?? []
            applied.append(contentsOf: ok.map(key))
            defaults.set(applied, forKey: "appliedNets")
            pendingNets = pendingNets.filter { $0.sec == "wpa3" }
            saveNets(pendingNets, "pendingNets")
            status = "Añadidas \(ok.count) redes" + (skipped > 0 ? " (\(skipped) WPA3: a mano)" : "")
        } else {
            status = result!
        }
    }

    private func syncable(_ w: PeraProtocol.Wifi) -> Bool {
        guard !w.ssid.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        switch w.sec {
        case "open": return true
        case "wpa2", "wpa3": return !(w.pass ?? "").isEmpty
        default: return false
        }
    }

    private func key(_ w: PeraProtocol.Wifi) -> String { "\(w.ssid)|\(w.pass ?? "")|\(w.sec)" }

    private func loadNets(_ k: String) -> [PeraProtocol.Wifi] {
        guard let d = defaults.data(forKey: k), case let .nets(list)? = Msg.decode(d) else { return [] }
        return list
    }

    private func saveNets(_ list: [PeraProtocol.Wifi], _ k: String) {
        defaults.set(Msg.nets(list).encode(), forKey: k)
    }
}
