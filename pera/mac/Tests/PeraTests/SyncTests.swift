import CryptoKit
import XCTest
@testable import Pera

final class SyncTests: XCTestCase {

    /// Mismo caso fijo que SyncTest.kt (Android): ambos lados deben trocear byte a byte igual.
    func testFramingMatchesAndroid() {
        let frames = Framing.split(msgId: 5, data: Data("hola mundo".utf8), maxFrame: 10)
        XCTAssertEqual(frames.map { [UInt8]($0) }, [
            [0, 5, 0, 0, 0, 3] + Array("hola".utf8),
            [0, 5, 0, 1, 0, 3] + Array(" mun".utf8),
            [0, 5, 0, 2, 0, 3] + Array("do".utf8),
        ])
    }

    func testFramingRoundTrip() {
        for size in [0, 1, 14, 15, 500, 5000, 100_000] {
            let data = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31) })
            let frames = Framing.split(msgId: 7, data: data, maxFrame: 182)
            let a = Framing.Assembler()
            var out: Data?
            for f in frames { out = a.add(f) }
            XCTAssertEqual(out, data, "size=\(size)")
        }
    }

    func testMessagesRoundTrip() {
        let clip = Msg.Clip(id: "abc", ts: 1_730_000_000_000, from: "Tablet", text: "Hola «mundo» ñ 🍐")
        guard case let .clip(c)? = Msg.decode(Msg.clip(clip).encode()) else { return XCTFail() }
        XCTAssertEqual(c, clip)

        let nets = [PeraProtocol.Wifi(ssid: "Casa", pass: "12345678", sec: "wpa2"),
                    PeraProtocol.Wifi(ssid: "Café", pass: nil, sec: "open")]
        guard case let .nets(n)? = Msg.decode(Msg.nets(nets).encode()) else { return XCTFail() }
        XCTAssertEqual(n, nets)
    }

    func testHereRoundTripAndAndroidJson() {
        let w = PeraProtocol.Wifi(ssid: "Casa", pass: "12345678", sec: "wpa2")
        guard case let .here(n)? = Msg.decode(Msg.here(w).encode()) else { return XCTFail() }
        XCTAssertEqual(n, w)
        guard case let .here(none)? = Msg.decode(Data(#"{"t":"here","net":null}"#.utf8)) else { return XCTFail() }
        XCTAssertNil(none)
    }

    /// Mismo formato binario que Android (Msg.Img en Messages.kt): 0x01, u16 de cabecera, JSON, bytes.
    func testImageBinaryRoundTripAndLayout() {
        let data = Data((0..<300_000).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        let img = Msg.Img(id: "i1", ts: 1_730_000_000_000, from: "Mac", mime: "image/jpeg", data: data)
        let bytes = Msg.img(img).encode()
        XCTAssertEqual(bytes.first, 1)
        let hdrLen = Int(bytes[1]) << 8 | Int(bytes[2])
        let hdr = String(data: bytes.subdata(in: 3 ..< 3 + hdrLen), encoding: .utf8) ?? ""
        XCTAssertTrue(hdr.contains("\"t\":\"img\""))
        guard case let .img(back)? = Msg.decode(bytes) else { return XCTFail() }
        XCTAssertEqual(back, img)
        // Y a través del troceado.
        let a = Framing.Assembler()
        var out: Data?
        for f in Framing.split(msgId: 3, data: bytes, maxFrame: 244) { out = a.add(f) ?? out }
        guard let whole = out, case let .img(viaFrames)? = Msg.decode(whole) else { return XCTFail() }
        XCTAssertEqual(viaFrames, img)
    }

    /// Mismo vector que LanShareTest.kt (Android): ambos descifran igual el formato nonce+datos+etiqueta.
    func testDecryptsSharedVector() throws {
        let key = SymmetricKey(data: Data(0..<32))
        let hex = "000102030405060708090a0b1767a47ae588ad69e928f3eaa7e71f8441c7bee5f91480be5a4d96af"
        let payload = Data(stride(from: 0, to: hex.count, by: 2).map {
            UInt8(hex[hex.index(hex.startIndex, offsetBy: $0) ..< hex.index(hex.startIndex, offsetBy: $0 + 2)], radix: 16)!
        })
        let data = try AES.GCM.open(AES.GCM.SealedBox(combined: payload), using: key)
        XCTAssertEqual(String(data: data, encoding: .utf8), "Pera mordida")
    }

    func testImgRefRoundTrip() {
        let r = Msg.ImgRef(id: "a", ts: 5, from: "Mac", mime: "image/png", size: 10, sha: "ab", key: "a2V5",
                           addrs: ["192.168.1.2", "10.0.0.3"], port: 51234, token: "cafe")
        guard case let .imgref(back)? = Msg.decode(Msg.imgref(r).encode()) else { return XCTFail() }
        XCTAssertEqual(back, r)
        guard case let .imgreq(id)? = Msg.decode(Msg.imgreq("a").encode()) else { return XCTFail() }
        XCTAssertEqual(id, "a")
    }

    /// JSON tal como lo escribe Android (org.json).
    func testDecodesAndroidJson() {
        let json = #"{"t":"clip","id":"x1","ts":1730000000123,"from":"Galaxy","text":"línea\nnueva"}"#
        guard case let .clip(c)? = Msg.decode(Data(json.utf8)) else { return XCTFail() }
        XCTAssertEqual(c.ts, 1_730_000_000_123)
        XCTAssertEqual(c.text, "línea\nnueva")
        XCTAssertNil(Msg.decode(Data(#"{"t":"futuro"}"#.utf8)))
    }
}
