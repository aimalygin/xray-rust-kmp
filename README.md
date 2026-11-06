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

The Android target depends on `io.github.aimalygin:xray-rust-mobile` from Maven Central at the
version in `release/mobile.env`, pinned strictly; `./gradlew :xray-rust-kmp:verifyXrayRustMobileAar`
(part of `check`) compares the resolved AAR with `XRAY_RUST_MOBILE_AAR_SHA256`.

## API (step 1)

- `XrayRuntime.info()` reads `xray_ffi_version_major/minor` and the capability mask of the loaded
  native library. `XrayRuntime.checkCompatibility()` / `requireCompatible()` fail with a reason when
  the runtime is older than the ABI the binding is built against (1.15, xray-rust-mobile 0.9.0) or
  lacks a required capability.
- `XrayProfiles().import(text): Result<XrayProfile>` detects the protocol and routes it explicitly
  (`XrayProtocol.importRoute`): every format `xray_profile_import_json` supports (http, socks,
  trojan, shadowsocks2022, vmess, hysteria2, wireguard) goes through the core; VLESS goes through a
  platform importer (see below). Errors are `XrayImportException` with a reason and no input text.
- `XrayTunnel(engine, scope, policy)`: `state: StateFlow<TunnelState>` (`Idle`, `Connecting`,
  `Connected`, `Failed`, `Stopping`), `start(profile)`, `stop()`, `onNetworkChanged()`.
- `TunnelPolicy`: retries, backoff, restart and fallback are app policy. Defaults are conservative:
  3 start attempts with 1 s → 30 s backoff, no restart on network change, no fallback.
  `fallbackWithoutRouting` (drop the `routing` section after all attempts fail) changes where
  traffic goes, so it is only an explicit opt-in.
- Network changes: Hysteria2 and WireGuard are rebound by the platform wrapper without restarting
  the core; the controller never restarts them. Restarting drops every flow, so other protocols
  restart only with `restartOnNetworkChange = true`.

### Android

`AndroidTunnelEngine(context)` drives `XrayKmpVpnService`, an open subclass of the AAR's
`XrayVpnService`. Declare it (or your subclass) in the manifest with
`android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter, and ask for
VPN consent with `VpnService.prepare` before `start` (see the sample). The config reaches the
service in process memory, never in Intent extras.

Known gap: in xray-rust-mobile 0.9.0 `XrayVpnService` does not run `XrayNetworkChangeCoordinator`
and does not expose its `XrayCore`, so a service built on it gets no Hysteria2/WireGuard rebind
yet. Closing it needs a hook in xray-rust-mobile (for example, a callback with the started core),
not a second coordinator here.

### VLESS

`xray_profile_import_json` has no `"format":"vless"` today. Until it does, VLESS links go through
the platform importers: `XrayVlessUrlImporter` from the AAR on Android, and on iOS the Swift
`XrayVlessURLImporter`, handed to `XrayProfiles(vlessImporter = …)` as an `XrayLinkImporter`.
Once the core accepts VLESS, `XrayProtocol.Vless` gets its core format and capability and every
platform switches to the core path with no API change.

### iOS (next step)

- cinterop against `xray_ffi.h` taken from the same xray-rust-mobile release as
  `XrayRust.xcframework.zip`, both checked against the hashes in `release/mobile.env`; the
  runtime check above runs before use.
- The Network Extension stays a thin Swift subclass of `XrayPacketTunnelProvider`
  (`XrayAppleTunnel`), which already owns the TUN pump, DNS settings and network-change rebind.
  Kotlin/Native runs only in the app process (no K/N runtime inside the extension, given its
  memory limit); the iOS `XrayTunnelEngine` starts and stops the extension.

## Building

JDK 21 and the Android SDK (`local.properties` or `ANDROID_HOME`):

```sh
./gradlew :xray-rust-kmp:testAndroidHostTest :xray-rust-kmp:verifyXrayRustMobileAar
./gradlew :samples:android:assembleDebug
./gradlew :xray-rust-kmp:compileKotlinIosSimulatorArm64   # macOS
```

## License

[Mozilla Public License 2.0](LICENSE), the same as xray-rust.
