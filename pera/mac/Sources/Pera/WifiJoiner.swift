import Foundation

/// Une el Mac a la red del hotspot.
///
/// Se usa `networksetup -setairportnetwork` porque CoreWLAN exige permiso de ubicación y, según
/// varios informes, devuelve redes sin nombre a apps que no están firmadas por Apple. Si falla, macOS
/// se conectará solo si la red es conocida (unirse a mano una vez con «Conectarse automáticamente»).
enum WifiJoiner {

    /// Devuelve true si macOS confirmó la conexión.
    static func join(_ wifi: PeraProtocol.Wifi, attempts: Int = 5) -> Bool {
        guard let device = wifiDevice() else { return false }
        for attempt in 0..<attempts {
            if attempt > 0 { Thread.sleep(forTimeInterval: 3) } // el hotspot tarda en ser visible
            var args = ["-setairportnetwork", device, wifi.ssid]
            if let pass = wifi.pass, wifi.sec != "open" { args.append(pass) }
            let (status, output) = run("/usr/sbin/networksetup", args)
            // networksetup suele devolver 0 aunque falle: el error va en el texto.
            let failed = status != 0 || output.localizedCaseInsensitiveContains("error")
                || output.localizedCaseInsensitiveContains("could not") || output.localizedCaseInsensitiveContains("failed")
            if !failed { return true }
        }
        return false
    }

    /// Nombre del interfaz Wi-Fi (normalmente en0).
    static func wifiDevice() -> String? {
        let (_, out) = run("/usr/sbin/networksetup", ["-listallhardwareports"])
        let lines = out.components(separatedBy: "\n")
        for (i, line) in lines.enumerated() where line.contains("Wi-Fi") || line.contains("AirPort") {
            if i + 1 < lines.count, lines[i + 1].hasPrefix("Device: ") {
                return String(lines[i + 1].dropFirst("Device: ".count)).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private static func run(_ path: String, _ args: [String]) -> (Int32, String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, error.localizedDescription) }
        p.waitUntilExit()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return (p.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }
}
