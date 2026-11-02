import AppKit
import ServiceManagement
import SwiftUI

/// Pera para Mac: icono en la barra de menús (junto al Wi-Fi) para usar los datos del móvil.
@main
struct PeraApp: App {
    @StateObject private var model = PeraModel()
    @StateObject private var family = FamilySync()

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model, family: family)
        } label: {
            Image(systemName: model.symbol)
        }
        .menuBarExtraStyle(.menu)
    }
}

struct MenuContent: View {
    @ObservedObject var model: PeraModel
    @ObservedObject var family: FamilySync

    var body: some View {
        Text(model.status)
        Divider()
        Button("Conectar a los datos del móvil") { model.run(turnOn: true) }
            .disabled(model.busy)
        Button("Apagar el hotspot del móvil") { model.run(turnOn: false) }
            .disabled(model.busy)
        Divider()
        Text("Familia Pera: \(family.status)")
        Toggle("Sincronizar lo copiado", isOn: $family.clipEnabled)
        Toggle("Sincronizar redes Wi-Fi", isOn: $family.wifiEnabled)
        if !family.pendingNets.isEmpty {
            Button("Añadir \(family.pendingNets.count) redes Wi-Fi de la familia a este Mac…") {
                family.addPendingNetworks()
            }
        }
        Divider()
        Toggle("Abrir al iniciar sesión", isOn: Binding(
            get: { model.launchAtLogin },
            set: { model.setLaunchAtLogin($0) }
        ))
        Button("Salir de Pera") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }
}

final class PeraModel: ObservableObject {
    @Published var status = "Listo"
    @Published var busy = false
    @Published var on = false
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled

    private let link = PeraLink()

    var symbol: String {
        if busy { return "ellipsis.circle" }
        return on ? "personalhotspot" : "antenna.radiowaves.left.and.right"
    }

    func run(turnOn: Bool) {
        guard !busy else { return }
        busy = true
        link.run(turnOn: turnOn, progress: { [weak self] text in
            self?.status = text
        }, completion: { [weak self] outcome in
            self?.handle(outcome)
        })
    }

    private func handle(_ outcome: PeraLink.Outcome) {
        switch outcome {
        case .failure(let message):
            busy = false
            status = message
        case .success(let on, let wifi):
            self.on = on
            guard on else {
                busy = false
                status = "Hotspot apagado"
                return
            }
            guard let wifi else {
                busy = false
                status = "Hotspot encendido. Si no se conecta solo, elígelo una vez en el menú Wi-Fi."
                return
            }
            status = "Conectando a «\(wifi.ssid)»…"
            DispatchQueue.global(qos: .userInitiated).async {
                let joined = WifiJoiner.join(wifi)
                DispatchQueue.main.async {
                    self.busy = false
                    self.status = joined
                        ? "Conectado a «\(wifi.ssid)»"
                        : "Hotspot encendido. Si no se conecta solo, elige «\(wifi.ssid)» una vez en el menú Wi-Fi."
                }
            }
        }
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            status = "No se pudo cambiar el inicio de sesión: \(error.localizedDescription)"
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}
