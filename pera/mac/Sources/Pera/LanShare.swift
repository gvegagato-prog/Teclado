import CryptoKit
import Foundation
import Network

/// Imágenes a calidad completa directas entre tus dispositivos por la red local (misma Wi-Fi o hotspot),
/// mucho más rápido que Bluetooth y sin servidores de internet. Mismo protocolo que en Android
/// (android/.../sync/LanShare.kt): el cliente envía «código\n»; el servidor responde
/// [longitud u64 big endian][nonce 12][datos cifrados + etiqueta] con AES-256-GCM. Se comprueba la huella
/// SHA-256. Usa Network.framework, que es lo que hace que macOS pida el permiso de «red local».
final class LanShare {
    static let shared = LanShare()

    private let q = DispatchQueue(label: "pera.lan")
    private var listener: NWListener?
    private var waiting: [(UInt16?) -> Void] = []
    private var offers: [String: (payload: Data, expires: Date)] = [:]
    private static let maxBytes: UInt64 = 70 * 1024 * 1024

    /// Ofrece la imagen; devuelve (en la cola principal) la referencia para mandar por Bluetooth, o nil
    /// si no hay red.
    func offer(_ img: Msg.Img, completion: @escaping (Msg.ImgRef?) -> Void) {
        q.async {
            let addrs = Self.addresses()
            guard !addrs.isEmpty else { return DispatchQueue.main.async { completion(nil) } }
            self.ensureListener { port in
                guard let port else { return DispatchQueue.main.async { completion(nil) } }
                let key = SymmetricKey(size: .bits256)
                guard let sealed = try? AES.GCM.seal(img.data, using: key).combined else {
                    return DispatchQueue.main.async { completion(nil) }
                }
                let token = Self.randomHex(16)
                let now = Date()
                self.offers = self.offers.filter { $0.value.expires > now }
                self.offers[token] = (sealed, now.addingTimeInterval(600))
                let keyB64 = key.withUnsafeBytes { Data($0) }.base64EncodedString()
                let ref = Msg.ImgRef(id: img.id, ts: img.ts, from: img.from, mime: img.mime, size: Int64(img.data.count),
                                     sha: Self.sha256(img.data), key: keyB64, addrs: addrs, port: Int(port), token: token)
                DispatchQueue.main.async { completion(ref) }
            }
        }
    }

    /// Descarga la imagen de [ref] probando cada dirección; nil (en la cola principal) si no se pudo.
    func fetch(_ ref: Msg.ImgRef, completion: @escaping (Msg.Img?) -> Void) {
        guard let keyData = Data(base64Encoded: ref.key), UInt64(ref.size) <= Self.maxBytes else {
            return completion(nil)
        }
        q.async { self.tryNext(ref, ref.addrs, SymmetricKey(data: keyData), completion) }
    }

    // MARK: - Cliente

    private final class Attempt {
        var finished = false
    }

    private func tryNext(_ ref: Msg.ImgRef, _ addrs: [String], _ key: SymmetricKey,
                         _ completion: @escaping (Msg.Img?) -> Void) {
        guard let addr = addrs.first, let port = NWEndpoint.Port(rawValue: UInt16(clamping: ref.port)) else {
            return DispatchQueue.main.async { completion(nil) }
        }
        let rest = Array(addrs.dropFirst())
        let c = NWConnection(host: NWEndpoint.Host(addr), port: port, using: .tcp)
        let attempt = Attempt()
        let finish: (Msg.Img?) -> Void = { [weak self] img in
            guard !attempt.finished else { return }
            attempt.finished = true
            c.cancel()
            if let img {
                DispatchQueue.main.async { completion(img) }
            } else {
                self?.tryNext(ref, rest, key, completion)
            }
        }
        // Sin respuesta en 3 s (no hay ruta) se pasa a la siguiente dirección; 30 s como máximo en total.
        q.asyncAfter(deadline: .now() + 3) { if c.state != .ready { finish(nil) } }
        q.asyncAfter(deadline: .now() + 30) { finish(nil) }
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                c.send(content: Data("\(ref.token)\n".utf8), completion: .contentProcessed { _ in })
                self?.readAll(c, Data()) { payload in
                    guard let payload,
                          let box = try? AES.GCM.SealedBox(combined: payload),
                          let data = try? AES.GCM.open(box, using: key),
                          Self.sha256(data) == ref.sha else { return finish(nil) }
                    finish(Msg.Img(id: ref.id, ts: ref.ts, from: ref.from, mime: ref.mime, data: data))
                }
            case .failed, .waiting:
                finish(nil)
            default:
                break
            }
        }
        c.start(queue: q)
    }

    private func readAll(_ c: NWConnection, _ acc: Data, _ done: @escaping (Data?) -> Void) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            var acc = acc
            if let data { acc.append(data) }
            if acc.count >= 8 {
                let len = acc.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
                if len > Self.maxBytes + 64 { return done(nil) }
                if UInt64(acc.count) >= 8 + len {
                    let start = acc.startIndex + 8
                    return done(acc.subdata(in: start ..< start + Int(len)))
                }
            }
            if error != nil || isComplete { return done(nil) }
            self?.readAll(c, acc, done)
        }
    }

    // MARK: - Servidor

    private func ensureListener(_ done: @escaping (UInt16?) -> Void) {
        if let l = listener, l.state == .ready, let p = l.port { return done(p.rawValue) }
        waiting.append(done)
        guard listener == nil else { return }
        guard let l = try? NWListener(using: .tcp, on: .any) else { return flush(nil) }
        listener = l
        l.newConnectionHandler = { [weak self] c in self?.serve(c) }
        l.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.flush(l.port?.rawValue)
            case .failed, .cancelled:
                self?.listener = nil
                self?.flush(nil)
            default: break
            }
        }
        l.start(queue: q)
    }

    private func flush(_ port: UInt16?) {
        let w = waiting
        waiting = []
        w.forEach { $0(port) }
    }

    private func serve(_ c: NWConnection) {
        c.start(queue: q)
        readLine(c, Data()) { [weak self] token in
            guard let self, let token, let offer = self.offers[token], offer.expires > Date() else { return c.cancel() }
            var len = UInt64(offer.payload.count).bigEndian
            var out = Data(bytes: &len, count: 8)
            out.append(offer.payload)
            c.send(content: out, completion: .contentProcessed { _ in c.cancel() })
        }
    }

    private func readLine(_ c: NWConnection, _ acc: Data, _ done: @escaping (String?) -> Void) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 128) { [weak self] data, _, isComplete, error in
            var acc = acc
            if let data { acc.append(data) }
            if let i = acc.firstIndex(of: 10) {
                return done(String(data: acc[acc.startIndex ..< i], encoding: .utf8)?.trimmingCharacters(in: .whitespaces))
            }
            if error != nil || isComplete || acc.count > 128 { return done(nil) }
            self?.readLine(c, acc, done)
        }
    }

    // MARK: - Utilidades

    /// IPv4 de las interfaces activas (Wi-Fi, hotspot…), sin la de bucle ni las de enlace local.
    static func addresses() -> [String] {
        var out: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            let flags = Int32(cur.pointee.ifa_flags)
            if let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
               flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let s = String(cString: host)
                    if !s.hasPrefix("169.254."), !out.contains(s) { out.append(s) }
                }
            }
            p = cur.pointee.ifa_next
        }
        return out
    }

    static func sha256(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    private static func randomHex(_ n: Int) -> String {
        (0..<n).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }
}
