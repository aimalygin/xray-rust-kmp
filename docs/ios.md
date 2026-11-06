# iOS

The split follows the process boundary:

| Process | Code | Job |
|---|---|---|
| App | Kotlin/Native framework `XrayRustKmp` + Swift UI | Runtime check, profile import (`xray_profile_import_json` through cinterop), `XrayTunnel` state machine and policy, `XrayIosTunnelEngine` driving `NETunnelProviderManager` |
| Network Extension | Swift only: `final class PacketTunnelProvider: XrayPacketTunnelProvider {}` from the SwiftPM product `XrayAppleTunnel` | TUN pump, utun fd, tunnel DNS settings, network observer with Hysteria2/WireGuard rebind; device-tested in every xray-rust-mobile release |

No Kotlin/Native runtime is loaded in the extension (Network Extension memory limit), and the
extension code is not reimplemented in Kotlin.

How a start reaches the extension: `XrayIosTunnelEngine.start(configJson)` writes the config to
the keychain through an app-supplied `XrayConfigStore` (the sample uses `XrayKeychainConfigStore`
from `XrayAppleShared`, the store `XrayPacketTunnelProvider` reads), saves a
`NETunnelProviderProtocol` whose `providerConfiguration["configReference"]` names that keychain
item, and calls `startVPNTunnel` with the same reference in `xrayConfigReference`. The JSON itself
never goes into preferences or start options. App and extension must share a keychain access
group (the sample's entitlements list `$(AppIdentifierPrefix)org.example.xraykmp.shared`).
`NEVPNStatusDidChange` feeds `XrayIosTunnelEngine.status` (a `StateFlow`) and the engine events:
Connected → `Started`; Disconnected after a stop → `Stopped`; Disconnected otherwise →
`Failed` with the domain/code from `fetchLastDisconnectError` (iOS 16+), or `Stopped` if there is
none. Network changes stay with the extension's observer, so `TunnelPolicy.restartOnNetworkChange`
only matters for protocols without rebind.

Swift sees a few helpers because Objective-C export has no default arguments, `Result` or flows:
`profiles.importProfile(text:name:)` (throws), `SwiftInteropKt.createXrayTunnel(engine:)`,
`tunnel.observeState { … }` / `engine.observeStatus { … }` returning a cancellable
`XrayObservation`. A Swift `XrayLinkImporter` may throw; the error becomes `XrayImportException`.

## Header and binary pinning

The release `XrayRust.xcframework` holds bare `libxray_ffi.a` slices without headers; the header
ships in the SwiftPM package (`Sources/XrayRustFFI/include`). Gradle takes both from the same
release, at no configuration-time network access:

- `downloadXrayFfiHeader` / `downloadXrayModulemap` fetch `xray_ffi.h` and `module.modulemap` from
  the raw source at `XRAY_RUST_MOBILE_COMMIT` (the commit of tag `XRAY_RUST_MOBILE_TAG`) and fail
  unless they match `XRAY_RUST_FFI_HEADER_SHA256` / `XRAY_RUST_MODULEMAP_SHA256`, copied from that
  tag's `release/core.env`.
- `downloadXrayXcframework` fetches `XrayRust.xcframework.zip` from the GitHub release and fails
  unless it matches `XRAY_RUST_MOBILE_XCFRAMEWORK_ZIP_SHA256` (the `binaryTarget` checksum in the
  tag's `Package.swift`); `extractXrayXcframework` unpacks it.
- cinterop (`src/nativeInterop/cinterop/xrayffi.def`) binds the header and embeds the slice of the
  target: `ios-arm64` for `iosArm64`, `ios-arm64_x86_64-simulator` for `iosSimulatorArm64`.
  The archive needs only libSystem, so there are no extra linker flags.
- Everything lands in `xray-rust-kmp/build/xray-rust-mobile/<version>/`; tasks are up to date
  while the hashes and files are unchanged.

At runtime `XrayRuntime.checkCompatibility()` reads `xray_ffi_version_major/minor` and
`xray_ffi_capabilities` from the linked core; the importer additionally checks `ProfileImport`
and the protocol's capability before calling `xray_profile_import_json`, wipes request and
result buffers, frees the `XrayError` slot after every call and never copies the FFI error
message (it may quote the link).

The app links the core once, inside `XrayRustKmp.framework`; it uses only the pure-Swift
`XrayAppleShared` product from SwiftPM. The extension links the core through `XrayAppleTunnel`.

## iOS sample

`samples/ios/project.yml` (XcodeGen) defines the `App` target (SwiftUI: link field, Import,
Connect/Disconnect, state; embeds the framework with `embedAndSignAppleFrameworkForXcode`) and the
`PacketTunnel` extension, with SwiftPM `https://github.com/aimalygin/xray-rust-mobile` at
`exactVersion` equal to `XRAY_RUST_MOBILE_VERSION`. Bundle ids are placeholders under
`org.example.xraykmp`; the team is empty, so set yours to run on a device (a Network Extension
does not run in the Simulator).

```sh
samples/ios/generate.sh   # checks the SwiftPM pin against release/mobile.env, runs xcodegen
xcodebuild -project samples/ios/XrayKmpSample.xcodeproj -scheme App -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```
