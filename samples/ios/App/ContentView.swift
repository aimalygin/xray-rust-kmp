import SwiftUI
import XrayAppleShared
import XrayRustKmp

/// XrayVlessURLImporter (Swift, XrayAppleShared) until the core imports "format":"vless".
final class VlessImporter: XrayLinkImporter {
    func `import`(text: String, name: String?) throws -> XrayProfile {
        let profile = try XrayVlessURLImporter.profile(from: text)
        return XrayProfile(
            name: name ?? profile.name,
            serverAddress: profile.serverAddress,
            protocol: .vless,
            configJson: profile.configJSON
        )
    }
}

/// The store XrayPacketTunnelProvider reads; the shared keychain access group makes it visible to the extension.
final class KeychainConfigStore: XrayConfigStore {
    private let store = XrayKeychainConfigStore()

    func store(configJson: String, reference: String) throws {
        try store.store(configJSON: configJson, reference: reference)
    }
}

@MainActor
final class SampleModel: ObservableObject {
    @Published var link = ""
    @Published var profile: XrayProfile?
    @Published var state = "Idle"
    @Published var message = ""

    private let profiles = XrayProfiles(vlessImporter: VlessImporter())
    private let tunnel: XrayTunnel
    private var observation: XrayObservation?

    init() {
        let engine = XrayIosTunnelEngine(
            providerBundleIdentifier: "org.example.xraykmp.PacketTunnel",
            configStore: KeychainConfigStore(),
            localizedDescription: "Xray KMP sample"
        )
        tunnel = SwiftInteropKt.createXrayTunnel(engine: engine)
        message = Self.runtimeDescription()
        observation = tunnel.observeState { [weak self] state in
            self?.state = "\(state)"
        }
    }

    deinit {
        observation?.cancel()
    }

    func importLink() {
        do {
            profile = try profiles.importProfile(text: link, name: nil)
            message = "Imported \(profile?.protocol.name ?? "")"
        } catch {
            profile = nil
            message = "Import failed: \(error.localizedDescription)"
        }
    }

    func connect() {
        guard let profile else { return }
        tunnel.start(profile: profile)
    }

    func disconnect() {
        tunnel.stop()
    }

    private static func runtimeDescription() -> String {
        let info = XrayRuntime.shared.info()
        return "xray FFI \(info.abi): \(XrayRuntime.shared.checkCompatibility(info: info))"
    }
}

struct ContentView: View {
    @StateObject private var model = SampleModel()

    var body: some View {
        Form {
            Section("Link") {
                TextField("vless://, vmess://, trojan://, ss://, hy2://…", text: $model.link, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Import", action: model.importLink)
            }
            Section("Tunnel") {
                Text(model.state)
                HStack {
                    Button("Connect", action: model.connect).disabled(model.profile == nil)
                    Spacer()
                    Button("Disconnect", action: model.disconnect)
                }
            }
            Section("Runtime") {
                Text(model.message).font(.footnote)
            }
        }
    }
}
