import Foundation

/// Mensajes de la familia Pera. Debe coincidir con android/.../sync/Messages.kt.
enum Msg {
    case hello(device: String, clipTs: Int64)
    case clip(Clip)
    case nets([PeraProtocol.Wifi])
    /// La red a la que está conectado el móvil ahora mismo (nil = ninguna).
    case here(PeraProtocol.Wifi?)
    /// Última imagen copiada (mensaje binario: [0x01][u16 cabecera][cabecera JSON][bytes]).
    case img(Img)

    struct Img: Equatable {
        let id: String
        let ts: Int64
        let from: String
        let mime: String
        let data: Data

        static func new(_ data: Data, from: String) -> Img {
            Img(id: UUID().uuidString.lowercased(), ts: Int64(Date().timeIntervalSince1970 * 1000), from: from,
                mime: "image/jpeg", data: data)
        }
    }

    struct Clip: Equatable {
        let id: String
        let ts: Int64
        let from: String
        let text: String

        static func new(_ text: String, from: String) -> Clip {
            Clip(id: UUID().uuidString.lowercased(), ts: Int64(Date().timeIntervalSince1970 * 1000), from: from, text: text)
        }
    }

    func encode() -> Data {
        if case let .img(i) = self {
            let hdr = (try? JSONSerialization.data(withJSONObject:
                ["t": "img", "id": i.id, "ts": i.ts, "from": i.from, "mime": i.mime] as [String: Any])) ?? Data()
            var out = Data([1, UInt8(hdr.count >> 8), UInt8(hdr.count & 0xFF)])
            out.append(hdr)
            out.append(i.data)
            return out
        }
        var o: [String: Any]
        switch self {
        case .img:
            return Data()
        case let .hello(device, clipTs):
            o = ["t": "hello", "dev": device, "clip": clipTs]
        case let .clip(c):
            o = ["t": "clip", "id": c.id, "ts": c.ts, "from": c.from, "text": c.text]
        case let .nets(list):
            o = ["t": "nets", "nets": list.map(Msg.json)]
        case let .here(w):
            if let w {
                o = ["t": "here", "net": Msg.json(w)]
            } else {
                o = ["t": "here", "net": NSNull()]
            }
        }
        return (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
    }

    private static func json(_ w: PeraProtocol.Wifi) -> [String: Any] {
        var o: [String: Any] = ["ssid": w.ssid, "pass": w.pass ?? NSNull(), "sec": w.sec]
        if w.hid == true { o["hid"] = true }
        return o
    }

    private static func wifi(_ n: [String: Any]) -> PeraProtocol.Wifi? {
        guard let ssid = n["ssid"] as? String else { return nil }
        return PeraProtocol.Wifi(ssid: ssid, pass: n["pass"] as? String, sec: n["sec"] as? String ?? "wpa2",
                                 hid: n["hid"] as? Bool)
    }

    static func decode(_ data: Data) -> Msg? {
        if data.first == 1 {
            let b = [UInt8](data.prefix(3))
            guard b.count == 3 else { return nil }
            let len = Int(b[1]) << 8 | Int(b[2])
            guard data.count >= 3 + len,
                  let h = (try? JSONSerialization.jsonObject(with: data.subdata(in: data.startIndex + 3 ..< data.startIndex + 3 + len))) as? [String: Any],
                  h["t"] as? String == "img", let id = h["id"] as? String,
                  let ts = (h["ts"] as? NSNumber)?.int64Value else { return nil }
            return .img(Img(id: id, ts: ts, from: h["from"] as? String ?? "?", mime: h["mime"] as? String ?? "image/jpeg",
                            data: data.subdata(in: data.startIndex + 3 + len ..< data.endIndex)))
        }
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let t = o["t"] as? String else { return nil }
        switch t {
        case "hello":
            return .hello(device: o["dev"] as? String ?? "?", clipTs: (o["clip"] as? NSNumber)?.int64Value ?? 0)
        case "clip":
            guard let id = o["id"] as? String, let ts = (o["ts"] as? NSNumber)?.int64Value,
                  let text = o["text"] as? String else { return nil }
            return .clip(Clip(id: id, ts: ts, from: o["from"] as? String ?? "?", text: text))
        case "nets":
            guard let arr = o["nets"] as? [[String: Any]] else { return nil }
            return .nets(arr.compactMap(wifi))
        case "here":
            return .here((o["net"] as? [String: Any]).flatMap(wifi))
        default:
            return nil // de una versión futura de Pera
        }
    }
}
