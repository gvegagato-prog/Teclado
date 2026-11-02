import Foundation

/// Troceado de mensajes para Bluetooth LE. Debe coincidir con android/.../sync/Framing.kt:
/// fragmento = [id u16][índice u16][total u16][datos…] en big endian.
enum Framing {
    static let header = 6
    static let maxMessage = 128 * 1024

    static func split(msgId: UInt16, data: Data, maxFrame: Int) -> [Data] {
        let chunk = max(1, maxFrame - header)
        let count = max(1, (data.count + chunk - 1) / chunk)
        guard count <= 0xFFFF else { return [] }
        let bytes = [UInt8](data)
        return (0..<count).map { i in
            let from = i * chunk
            let to = min(bytes.count, from + chunk)
            var frame = Data()
            frame.append(contentsOf: be(msgId))
            frame.append(contentsOf: be(UInt16(i)))
            frame.append(contentsOf: be(UInt16(count)))
            if from < to { frame.append(contentsOf: bytes[from..<to]) }
            return frame
        }
    }

    private static func be(_ v: UInt16) -> [UInt8] { [UInt8(v >> 8), UInt8(v & 0xFF)] }

    /// Recompone mensajes de un único emisor.
    final class Assembler {
        private var id = -1
        private var count = 0
        private var parts: [Data?] = []
        private var received = 0
        private var size = 0

        func add(_ frame: Data) -> Data? {
            let b = [UInt8](frame)
            guard b.count >= Framing.header else { return nil }
            let msgId = Int(b[0]) << 8 | Int(b[1])
            let index = Int(b[2]) << 8 | Int(b[3])
            let total = Int(b[4]) << 8 | Int(b[5])
            guard total > 0, index < total else { return nil }
            if msgId != id || total != count {
                id = msgId
                count = total
                parts = Array(repeating: nil, count: total)
                received = 0
                size = 0
            }
            if parts[index] == nil {
                let payload = Data(b[Framing.header...])
                size += payload.count
                if size > Framing.maxMessage { reset(); return nil }
                parts[index] = payload
                received += 1
            }
            guard received == count else { return nil }
            var out = Data()
            parts.forEach { out.append($0 ?? Data()) }
            reset()
            return out
        }

        private func reset() {
            id = -1
            count = 0
            parts = []
            received = 0
            size = 0
        }
    }
}
