# xray-rust-kmp

**One Kotlin API to import a proxy link and run a VPN tunnel on Android and iOS.**

[xray-rust](https://github.com/aimalygin/xray-rust) is an Xray-compatible proxy core written in
Rust. [xray-rust-mobile](https://github.com/aimalygin/xray-rust-mobile) packages it for phones:
an AAR for Android and an XCFramework plus Swift package for iOS. This library sits on top and
gives Kotlin Multiplatform apps a single API for both, so the tunnel logic lives in shared code.

> [!NOTE]
> **Status: early.** Version 0.10.0 is released as the tag `v0.10.0` and a GitHub release of this
> repository. It is built against xray-rust-mobile 0.10.0 (xray-rust core 0.10.0, C ABI 1.17).
> It is **not on Maven Central**: you build it from a checkout of the tag (see
> [Use it in your project](#use-it-in-your-project)). The API is open for discussion and may
> change in a later release. The library has not been run on a real device yet. It started as
> step 1 of [xray-rust#197](https://github.com/aimalygin/xray-rust/issues/197).

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
xray-rust-mobile, the SDK's own provider (its release notes say what was tested on devices).
Kotlin runs only in the app.

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

What 0.10.0 of this library does not use yet from xray-rust-mobile 0.10.0:

- **VLESS through the core.** The core's `xray_profile_import_json` accepts `"format":"vless"`
  since xray-rust 0.10.0 ([xray-rust#213](https://github.com/aimalygin/xray-rust/pull/213)), but
  this library still imports VLESS links with the platform importers, as in the table. Moving to
  the core changes which parser reads a link (the SDK documents that the two can disagree on the
  same link) and leaves the `vlessImporter` parameter without a job, so it waits for a later
  release. Details are in [docs/api.md](docs/api.md#vless).
- **Network-change rebind on Android.** `XrayVpnService` 0.10.0 can rebind on its own when the
  network changes ([xray-rust#214](https://github.com/aimalygin/xray-rust/pull/214), opt-in through
  `networkChangeRebindEnabled()`), but `XrayKmpVpnService` does not opt in: that needs a test on a
  device across a Wi-Fi/cellular change. Until then `AndroidTunnelEngine` reports no rebind.
  Details are in [docs/android.md](docs/android.md).
- **Start replacement on Android.** `XrayVpnService` 0.10.0 can replace a start in flight and
  reports `onXrayTunnelStartCancelled`. `XrayKmpVpnService` still waits for a start to report
  before it replaces or stops it, which stays correct.
- **TUN MTU.** The SDK lets the host choose the TUN MTU (ABI 1.16); this library has no option for
  it and runs at the default 1500.

## Use it in your project

The library is not published to Maven Central or any other Maven repository. Build it from a
checkout of the release tag, as the samples in this repository do.

```sh
git clone --branch v0.10.0 https://github.com/aimalygin/xray-rust-kmp.git
```

**Gradle (Android and shared Kotlin).** Include the checkout as a
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html) and depend on
the library's coordinates; Gradle builds it from source instead of looking for it in a repository.

```kotlin
// settings.gradle.kts
includeBuild("../xray-rust-kmp")

// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aimalygin:xray-rust-kmp:0.10.0")
        }
    }
}
```

The included build needs network access on its first run: it fetches the pinned xray-rust-mobile
AAR from Maven Central and the XCFramework and C header from the xray-rust-mobile 0.10.0 release,
and checks each against [`release/mobile.env`](release/mobile.env). Your build must also resolve
`io.github.aimalygin:xray-rust-mobile:0.10.0` from Maven Central, since the library depends on it.
This setup was checked with the same Kotlin (2.4.21) and Android Gradle plugin (9.5.1) versions as
this repository ([`gradle/libs.versions.toml`](gradle/libs.versions.toml)), compiling for Android
and linking iOS frameworks for both targets.

**iOS.** A shared module that builds its own iOS framework gets the library, and the core
embedded in it, linked into that framework like any Kotlin dependency. An app can instead use this
library's own framework, `XrayRustKmp`, built and embedded from Xcode with
`./gradlew :xray-rust-kmp:embedAndSignAppleFrameworkForXcode`, as
[`samples/ios/project.yml`](samples/ios/project.yml) does. Either way, add the Swift package
`https://github.com/aimalygin/xray-rust-mobile` at exactly `0.10.0`: the Network Extension uses its
`XrayAppleTunnel` product, the app its pure-Swift `XrayAppleShared` (keychain config store, VLESS
importer). [docs/ios.md](docs/ios.md) explains the split.

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
that file and the SwiftPM `exactVersion` in `samples/ios/project.yml`, which `generate.sh` checks
against it.

## Repository layout

| Path | What it is |
|---|---|
| `xray-rust-kmp/` | The library, `io.github.aimalygin:xray-rust-kmp:0.10.0` (Android, `iosArm64`, `iosSimulatorArm64`) |
| `samples/android/`, `samples/ios/` | Minimal sample apps |
| `release/mobile.env` | The pinned xray-rust-mobile release |
| `docs/` | [API reference](docs/api.md), [Android notes](docs/android.md), [iOS notes](docs/ios.md) |

## Contributing and security

Changes arrive as pull requests from forks. Report vulnerabilities through GitHub's private
vulnerability reporting for this repository, not in public issues, and include only synthetic
reproduction data.

## License

[Mozilla Public License 2.0](LICENSE), the same as xray-rust.
