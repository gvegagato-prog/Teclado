import AppKit
import Foundation

/// Sincronización de la familia en el Mac: portapapeles (automático) y redes Wi-Fi (se añaden al Mac
/// cuando lo confirmas, porque macOS pide la contraseña de administrador).
final class FamilySync: ObservableObject {
    @Published var status = "Desactivada"
    @Published var pendingNets: [PeraProtocol.Wifi] = []
    /// Red a la que está conectado el móvil ahora mismo.
    @Published var phoneNet: PeraProtocol.Wifi?
    private var lastJoinAttempt = Date.distantPast
    private var joining = false
    @Published var clipEnabled: Bool { didSet { defaults.set(clipEnabled, forKey: "clipOn") } }
    @Published var wifiEnabled: Bool { didSet { defaults.set(wifiEnabled, forKey: "wifiOn") } }

    private let defaults = UserDefaults.standard
    let link = SyncLink()
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
        // Si el Mac se queda sin Wi-Fi, vuelve a intentar unirse a la red del móvil.
        Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.joinPhoneNetIfOffline() }
    }

    /// Para volver a emparejar (p. ej. tras reinstalar Pera en el móvil, que cambia su identificador).
    func forgetHub() {
        UserDefaults.standard.removeObject(forKey: "pairedHub")
        link.restart()
        status = "Buscando un móvil con Pera…"
    }

    // MARK: - Mensajes

    private func greet() {
        link.send(.hello(device: deviceName, clipTs: latestTs))
        // Lo copiado aquí mientras el móvil no estaba: el móvil se queda con lo más reciente.
        if imageTs > (clip?.ts ?? 0) {
            if let img = lastImage, img.from == deviceName { link.send(.img(img)) }
        } else if let c = clip, c.from == deviceName {
            link.send(.clip(c))
        }
    }

    private func handle(_ msg: Msg) {
        switch msg {
        case let .img(i):
            guard clipEnabled, i.ts > latestTs, i.id != lastImage?.id, let image = NSImage(data: i.data) else { return }
            lastImage = i
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.writeObjects([image])
            ownChange = pb.changeCount
            lastChange = pb.changeCount
        case let .clip(c):
            guard clipEnabled else { return }
            if c.ts <= latestTs || c.id == clip?.id { return }
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
        case let .here(w):
            guard wifiEnabled else { return }
            let changed = w?.ssid != phoneNet?.ssid
            phoneNet = w
            // El móvil se ha conectado a otra red: el Mac se pasa a ella (aunque esté en otra Wi-Fi).
            joinPhoneNetIfOffline(force: changed)
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
        guard !secret else { return }
        // Imagen copiada (captura, foto, imagen de una web…): se reduce y se envía.
        let imageTypes: [NSPasteboard.PasteboardType] = [.png, .tiff, NSPasteboard.PasteboardType("public.jpeg")]
        if pb.availableType(from: imageTypes) != nil, pb.string(forType: .string) == nil,
           let image = NSImage(pasteboard: pb), let jpeg = Self.reduce(image) {
            let i = Msg.Img.new(jpeg, from: deviceName)
            lastImage = i
            link.send(.img(i))
            return
        }
        guard let text = pb.string(forType: .string), !text.isEmpty, text.count <= Self.maxClipChars else { return }
        if text == clip?.text { return }
        let c = Msg.Clip.new(text, from: deviceName)
        clip = c
        link.send(.clip(c))
    }

    /// Reduce una imagen a JPEG con el lado largo de 1600 px (Bluetooth LE es lento: decenas de KB/s).
    static func reduce(_ image: NSImage) -> Data? {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let scale = min(1, 1600 / Double(max(cg.width, cg.height)))
        let w = max(1, Int(Double(cg.width) * scale)), h = max(1, Int(Double(cg.height) * scale))
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        let rect = CGRect(x: 0, y: 0, width: w, height: h)
        ctx.setFillColor(CGColor(gray: 1, alpha: 1)) // fondo blanco para las transparencias
        ctx.fill(rect)
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: rect)
        guard let out = ctx.makeImage() else { return nil }
        return NSBitmapImageRep(cgImage: out).representation(using: .jpeg, properties: [.compressionFactor: 0.8])
    }

    /// Última imagen copiada (en Application Support, para enviarla al reconectar si es la más reciente).
    private var lastImage: Msg.Img? {
        get {
            guard let d = try? Data(contentsOf: Self.imageFile), case let .img(i)? = Msg.decode(d) else { return nil }
            return i
        }
        set {
            if let i = newValue {
                try? FileManager.default.createDirectory(at: Self.imageFile.deletingLastPathComponent(),
                                                         withIntermediateDirectories: true)
                try? Msg.img(i).encode().write(to: Self.imageFile)
                defaults.set(i.ts, forKey: "imageTs")
            }
        }
    }

    private static var imageFile: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Pera/ultima-imagen.bin")
    }

    private var imageTs: Int64 { (defaults.object(forKey: "imageTs") as? NSNumber)?.int64Value ?? 0 }

    /// Lo último copiado en la familia, sea texto o imagen.
    private var latestTs: Int64 { max(clip?.ts ?? 0, imageTs) }

    private var clip: Msg.Clip? {
        get {
            guard let d = defaults.data(forKey: "clip"), case let .clip(c)? = Msg.decode(d) else { return nil }
            return c
        }
        set { defaults.set(newValue.map { Msg.clip($0).encode() }, forKey: "clip") }
    }

    // MARK: - Redes Wi-Fi

    /// Si el Mac no tiene Wi-Fi, se une a la red del móvil aunque no la conociera.
    private func joinPhoneNetIfOffline(force: Bool = false) {
        guard wifiEnabled, let w = phoneNet, !joining, force || Date().timeIntervalSince(lastJoinAttempt) > 50 else { return }
        lastJoinAttempt = Date()
        joining = true
        DispatchQueue.global(qos: .utility).async {
            let shouldJoin = force || !WifiJoiner.hasWifiAddress()
            let joined = shouldJoin && WifiJoiner.join(w, attempts: 2)
            DispatchQueue.main.async {
                self.joining = false
                if joined { self.status = "Conectado a «\(w.ssid)», la Wi-Fi del móvil" }
            }
        }
    }

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
