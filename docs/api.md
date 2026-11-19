# API reference (0.10.1)

The API is open for discussion and may change in a later release.

- `XrayRuntime.info()` reads `xray_ffi_version_major/minor` and the capability mask of the loaded
  native library. `XrayRuntime.checkCompatibility()` / `requireCompatible()` fail with a reason when
  the runtime is older than the ABI the binding is built against (1.17, xray-rust-mobile 0.10.1) or
  lacks a required capability.
- `XrayProfiles().import(text): Result<XrayProfile>` detects the protocol and routes it explicitly:
  every format `xray_profile_import_json` supports (http, socks,
  trojan, shadowsocks2022, vmess, hysteria2, wireguard) goes through the core; VLESS goes through a
  platform importer (see below). Errors are `XrayImportException` with a reason and no input text.
  `XrayProtocol.Shadowsocks` covers both families of `ss://` links (Shadowsocks 2022 and AEAD-2017):
  the core imports them with one format.
- `XrayTunnel(engine, scope, policy)`: `state: StateFlow<TunnelState>` (`Idle`, `Connecting`,
  `Connected`, `Failed`, `Stopping`), `start(profile)`, `stop()`, `onNetworkChanged()`.
- `TunnelPolicy`: retries, backoff, restart and fallback are app policy. Defaults are conservative:
  3 start attempts with 1 s → 30 s backoff, no restart on network change, no fallback.
  A start attempt that stays silent for `startTimeout` (60 s) counts as failed, so the tunnel
  never hangs in `Connecting`.
  `fallbackWithoutRouting` (drop the `routing` section after all attempts fail) changes where
  traffic goes, so it is only an explicit opt-in.
- Network changes: the engine says which protocols its platform wrapper rebinds without restarting
  the core (`XrayTunnelEngine.rebindsOnNetworkChange`); the controller never restarts those.
  Restarting drops every flow, so any other protocol restarts only with
  `restartOnNetworkChange = true`. Today that is Hysteria2 and WireGuard on iOS and nothing on
  Android (see the known gap in [android.md](android.md)).
- Engine events carry the generation of the start they belong to, so a late `Stopped` or `Failed`
  of a replaced session cannot end the new one.
- `TunnelFailure(kind, message, code)`: the message holds the error type (Android) or the `NSError`
  domain and code (iOS), never core or config text; `code` is the native status when there is one.
  The kind decides what happens next: `StartFailed`, `StartTimeout` and `Fatal` follow the retry
  policy; `ConfigRejected` (the core refused the config) skips the retries of that same config and
  goes straight to the fallback or to `Failed`; `PermissionRequired` (no VPN consent on Android)
  ends the start at once.
- A tunnel the library did not start (already up when the app launched, or switched on in the
  system settings) is reported by the engine as `Started(XrayEngineEvent.EXTERNAL_SESSION)`. The
  controller adopts it: the state becomes `Connected(protocol = null)` and `stop()` works. It has
  no profile, so it is never restarted or retried. Today only the iOS engine reports such sessions;
  on Android the service lives in the app process and cannot outlive it.

## VLESS

In 0.10.0 of this library VLESS links go through the platform importers: `XrayVlessUrlImporter`
from the AAR on Android, and on iOS the Swift `XrayVlessURLImporter`, handed to
`XrayProfiles(vlessImporter = …)` as an `XrayLinkImporter`.

xray-rust-mobile 0.10.0 (ABI 1.17) also imports VLESS through the core: `xray_profile_import_json`
accepts `"format":"vless"` ([xray-rust#213](https://github.com/aimalygin/xray-rust/pull/213)) under
the `VLESS_PROFILE_IMPORT` capability. This library does not use that path yet. Switching
`XrayProtocol.Vless` to it would change which parser reads a link (the SDK documents that the core
importer and the platform parsers can disagree on the same link) and leave `vlessImporter`,
`XrayLinkImporter` and `XrayImportException.Reason.NoPlatformImporter` without a job, so it is left
for a later release together with a decision on that API. `XrayCapability` lists the bits up to
ABI 1.15; the two that ABI 1.16 and 1.17 add (`TUN_MTU`, `VLESS_PROFILE_IMPORT`) are not in it yet.
