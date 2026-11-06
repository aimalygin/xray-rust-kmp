# Android

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
