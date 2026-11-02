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

    /// JSON tal como lo escribe Android (org.json).
    func testDecodesAndroidJson() {
        let json = #"{"t":"clip","id":"x1","ts":1730000000123,"from":"Galaxy","text":"línea\nnueva"}"#
        guard case let .clip(c)? = Msg.decode(Data(json.utf8)) else { return XCTFail() }
        XCTAssertEqual(c.ts, 1_730_000_000_123)
        XCTAssertEqual(c.text, "línea\nnueva")
        XCTAssertNil(Msg.decode(Data(#"{"t":"futuro"}"#.utf8)))
    }
}
