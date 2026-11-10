# xray-rust-kmp

**One Kotlin API to import a proxy link and run a VPN tunnel on Android and iOS.**

[xray-rust](https://github.com/aimalygin/xray-rust) is an Xray-compatible proxy core written in
Rust. [xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) packages it for phones:
an AAR for Android and an XCFramework plus Swift package for iOS. This library sits on top and
gives Kotlin Multiplatform apps a single API for both, so the tunnel logic lives in shared code.

> [!NOTE]
> **Status: early, not released.** This is step 1 of
> [xray-rust#197](https://github.com/aimalygin/xray-rust/issues/197): the library builds against
> the published xray-rust-mobile 0.9.0, and a sample app uses it on both platforms. The API is open
> for discussion. Nothing is on Maven Central yet.

## What it looks like

```kotlin
val profiles = XrayProfiles()
val tunnel = XrayTunnel(engine, scope)   // engine: AndroidTunnelEngine or XrayIosTunnelEngine

// 1. Check that the native core matches this library.
XrayRuntime.requireCompatible()

// 2. Turn a link into a profile.
val profile = profiles.import("trojan://…").getOrThrow()

// 3. Connect, and watch the state.
tunnel.start(profile)
tunnel.state.collect { state ->
    // Idle → Connecting → Connected, or Failed with a typed reason
}

tunnel.stop()
```

The engine is the only platform-specific part. On Android it is `AndroidTunnelEngine(context)`.
On iOS it is `XrayIosTunnelEngine(...)`, which talks to the system VPN through `NETunnelProviderManager`.

## How it fits together

```text
                Your app (shared Kotlin)
     XrayProfiles · XrayTunnel · TunnelPolicy · XrayRuntime
                │                              │
        Android │                              │ iOS
                ▼                              ▼
   xray-rust-mobile AAR              App: Kotlin/Native framework
   XrayVpnService + core               (import, state, policy)
   in your app process                        │ NETunnelProviderManager
                                              ▼
                                     Network Extension: Swift only
                                     XrayPacketTunnelProvider + core
```

On iOS the tunnel runs in a separate process, the Network Extension, which has a tight memory
limit. That process stays pure Swift: one line subclassing `XrayPacketTunnelProvider` from
xray-rust-mobile, which is already tested on devices every release. Kotlin runs only in the app.

## What works today

| | Android | iOS |
|---|:---:|:---:|
| Check the core's ABI version and capabilities | ✅ | ✅ |
| Import http, socks, trojan, shadowsocks2022, vmess, hysteria2, wireguard links (core importer) | ✅ | ✅ |
| Import VLESS links | ✅ AAR importer | ✅ Swift importer, passed in from the app |
| Start and stop the tunnel, observe its state | ✅ | ✅ |
| Retries with backoff, start timeout | ✅ | ✅ |
| Show and stop a tunnel that was already running | not needed: same process | ✅ |
| Stay connected when the network changes (Hysteria2, WireGuard) | ⚠️ see below | ✅ |
| Run on a real device | not tested yet | not tested yet |

- **VLESS** goes through the platform importers for now, because the core's
  `xray_profile_import_json` has no VLESS format yet.
  [xray-rust#213](https://github.com/aimalygin/xray-rust/pull/213) adds it (merged, not released
  yet). Once a release ships it, VLESS moves to the shared core path with no API change.
- **Network changes on Android:** `XrayVpnService` in xray-rust-mobile 0.9.0 does not run the
  network change coordinator and does not expose its core, so Hysteria2 and WireGuard cannot rebind
  yet. [xray-rust#214](https://github.com/aimalygin/xray-rust/pull/214) makes `XrayVpnService` rebind on
  its own (opt-in); it is merged and waits for a release. Details are in [docs/android.md](docs/android.md).

## Your app decides the policy

Retries, restarts and fallback are product decisions, so the library only applies what you set
in `TunnelPolicy`:

```kotlin
val tunnel = XrayTunnel(
    engine, scope,
    policy = TunnelPolicy(
        maxStartAttempts = 3,            // default: 3 tries, 1 s → 30 s apart
        startTimeout = 60.seconds,       // default: a silent start counts as a failed try
        restartOnNetworkChange = false,  // default: a restart drops every open connection
        fallbackWithoutRouting = false,  // default: off, because it changes where traffic goes
    ),
)
```

Protocols that the platform can rebind are never restarted on a network change.

## Try the samples

You need JDK 21 and the Android SDK; for iOS also macOS with Xcode and
[XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
# Android: build the sample APK
./gradlew :samples:android:assembleDebug

# iOS: generate the Xcode project and build for the simulator
samples/ios/generate.sh
xcodebuild -project samples/ios/XrayKmpSample.xcodeproj -scheme App -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO

# Library tests
./gradlew :xray-rust-kmp:testAndroidHostTest :xray-rust-kmp:iosSimulatorArm64Test
```

Each sample has a link field, Import, Connect and Disconnect. A Network Extension does not run in
the Simulator, so to connect on iOS set your signing team and run on a device.

## Versions and integrity

The library pins one xray-rust-mobile release in [`release/mobile.env`](release/mobile.env):
the version and the checksums of the AAR, the XCFramework and the C header. The build downloads
exactly those files and fails if any checksum differs. Updating xray-rust-mobile means changing
that one file.

## Repository layout

| Path | What it is |
|---|---|
| `xray-rust-kmp/` | The library, `io.github.aimalygin:xray-rust-kmp` (Android, `iosArm64`, `iosSimulatorArm64`) |
| `samples/android/`, `samples/ios/` | Minimal sample apps |
| `release/mobile.env` | The pinned xray-rust-mobile release |
| `docs/` | [API reference](docs/api.md), [Android notes](docs/android.md), [iOS notes](docs/ios.md) |

## Contributing and security

Changes arrive as pull requests from forks. Report vulnerabilities through GitHub's private
vulnerability reporting for this repository, not in public issues, and include only synthetic
reproduction data.

## License

[Mozilla Public License 2.0](LICENSE), the same as xray-rust.
