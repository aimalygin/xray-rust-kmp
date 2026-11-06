# xray-rust-kmp

Kotlin Multiplatform layer for [xray-rust](https://github.com/aimalygin/xray-rust):
one API for Android and iOS, built on the published
[xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) artifacts.

**Status:** step 1 of [aimalygin/xray-rust#197](https://github.com/aimalygin/xray-rust/issues/197):
a sample that wires the library on both platforms. The API below is a proposal to discuss
on that pull request; nothing is released yet. Changes arrive as pull requests from forks.

Report vulnerabilities through GitHub's private vulnerability reporting for
this repository, not in public issues, and include only synthetic
reproduction data.

## Layout

| Path | What it is |
|---|---|
| `release/mobile.env` | The pinned xray-rust-mobile release: version, AAR and XCFramework checksums, FFI header hash, ABI. The single source of truth for the Gradle build and the iOS build. |
| `xray-rust-kmp/` | The library, `io.github.aimalygin:xray-rust-kmp`. Targets: Android, `iosArm64`, `iosSimulatorArm64`. |
| `samples/android/` | Minimal Android app: paste a link, Import, Connect/Disconnect, tunnel state. |
| `samples/ios/` | The same on iOS: XcodeGen spec for a SwiftUI app plus a packet-tunnel extension. |
| `.github/workflows/ci.yml` | macOS CI: library build and tests, iOS framework link, both samples. |

The Android target depends on `io.github.aimalygin:xray-rust-mobile` from Maven Central at the
version in `release/mobile.env`, pinned strictly; `./gradlew :xray-rust-kmp:verifyXrayRustMobileAar`
(part of `check`) compares the resolved AAR with `XRAY_RUST_MOBILE_AAR_SHA256`.

## API (step 1)

- `XrayRuntime.info()` reads `xray_ffi_version_major/minor` and the capability mask of the loaded
  native library. `XrayRuntime.checkCompatibility()` / `requireCompatible()` fail with a reason when
  the runtime is older than the ABI the binding is built against (1.15, xray-rust-mobile 0.9.0) or
  lacks a required capability.
- `XrayProfiles().import(text): Result<XrayProfile>` detects the protocol and routes it explicitly:
  every format `xray_profile_import_json` supports (http, socks,
  trojan, shadowsocks2022, vmess, hysteria2, wireguard) goes through the core; VLESS goes through a
  platform importer (see below). Errors are `XrayImportException` with a reason and no input text.
- `XrayTunnel(engine, scope, policy)`: `state: StateFlow<TunnelState>` (`Idle`, `Connecting`,
  `Connected`, `Failed`, `Stopping`), `start(profile)`, `stop()`, `onNetworkChanged()`.
- `TunnelPolicy`: retries, backoff, restart and fallback are app policy. Defaults are conservative:
  3 start attempts with 1 s → 30 s backoff, no restart on network change, no fallback.
  `fallbackWithoutRouting` (drop the `routing` section after all attempts fail) changes where
  traffic goes, so it is only an explicit opt-in.
- Network changes: the engine says which protocols its platform wrapper rebinds without restarting
  the core (`XrayTunnelEngine.rebindsOnNetworkChange`); the controller never restarts those.
  Restarting drops every flow, so any other protocol restarts only with
  `restartOnNetworkChange = true`. Today that is Hysteria2 and WireGuard on iOS and nothing on
  Android (see the known gap below).
- Engine events carry the generation of the start they belong to, so a late `Stopped` or `Failed`
  of a replaced session cannot end the new one. Failure messages hold the error type (Android) or
  the `NSError` domain and code (iOS), never core or config text.

### Android

`AndroidTunnelEngine(context)` drives `XrayKmpVpnService`, an open subclass of the AAR's
`XrayVpnService`. Declare it (or your subclass) in the manifest with
`android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter, and ask for
VPN consent with `VpnService.prepare` before `start` (see the sample). The config reaches the
service in process memory, never in Intent extras.

`XrayVpnService` 0.9.0 ignores a start while an earlier start is still running, and a stop during
startup cancels it without a callback. `XrayKmpVpnService` therefore never cancels a start in
flight: a newer start or a stop waits until it reports (started or failed) and then replaces or
stops it; only destroying the service cancels it at once. A "replace session" call or a cancel
callback in xray-rust-mobile would remove that wait.

Known gap: in xray-rust-mobile 0.9.0 `XrayVpnService` does not run `XrayNetworkChangeCoordinator`
and does not expose its `XrayCore`, so a service built on it gets no Hysteria2/WireGuard rebind
yet; `AndroidTunnelEngine` reports no rebind, and `restartOnNetworkChange = true` restarts these
protocols too. Closing it needs a hook in xray-rust-mobile (for example, a callback with the started core),
not a second coordinator here.

### VLESS

`xray_profile_import_json` has no `"format":"vless"` today. Until it does, VLESS links go through
the platform importers: `XrayVlessUrlImporter` from the AAR on Android, and on iOS the Swift
`XrayVlessURLImporter`, handed to `XrayProfiles(vlessImporter = …)` as an `XrayLinkImporter`.
Once the core accepts VLESS, `XrayProtocol.Vless` gets its core format and capability and every
platform switches to the core path with no API change.

### iOS

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

#### Header and binary pinning

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

#### iOS sample

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

## Building

JDK 21 and the Android SDK (`local.properties` or `ANDROID_HOME`):

```sh
./gradlew :xray-rust-kmp:testAndroidHostTest :xray-rust-kmp:verifyXrayRustMobileAar
./gradlew :samples:android:assembleDebug
./gradlew :xray-rust-kmp:iosSimulatorArm64Test               # macOS: common tests + FFI import
./gradlew :xray-rust-kmp:linkDebugFrameworkIosSimulatorArm64  # macOS
```

## License

[Mozilla Public License 2.0](LICENSE), the same as xray-rust.
