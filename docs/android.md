# Android

`AndroidTunnelEngine(context)` drives `XrayKmpVpnService`, an open subclass of the AAR's
`XrayVpnService`. Declare it (or your subclass) in the manifest with
`android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter, and ask for
VPN consent with `VpnService.prepare` before `start` (see the sample); without consent the start
fails at once with `TunnelFailure.Kind.PermissionRequired` and is not retried. The config reaches
the service in process memory, never in Intent extras.

Commands travel through a process-local queue; the intent only wakes the service, which then
handles the queued starts and stops in the order they were issued. Two consequences:

- The service must run in the process that owns `AndroidTunnelEngine`. Declared with another
  `android:process`, it never sees a command (it logs a warning when it starts).
- A stop always lands: if the system refuses the wake-up intent (background start restrictions),
  the engine hands the stop to the live service directly.

`XrayVpnService` 0.9.0 does not call `startForeground`, and neither does `XrayKmpVpnService`.
An app that wants the tunnel to outlive its UI promotes the service to the foreground in a
subclass (notification, `FOREGROUND_SERVICE` permission and a service type). The sample does not.
Always-on VPN is not supported yet: a start by the system carries no config.

`XrayVpnService` 0.9.0 ignores a start while an earlier start is still running, and a stop during
startup cancels it without a callback. `XraySessionSequencer` (unit-tested, used by
`XrayKmpVpnService`) therefore never cancels a start in flight: a newer start or a stop waits until it reports (started or failed) and then replaces or
stops it; only destroying the service cancels it at once. A "replace session" call or a cancel
callback in xray-rust-mobile would remove that wait.

Known gap: in xray-rust-mobile 0.9.0 `XrayVpnService` does not run `XrayNetworkChangeCoordinator`
and does not expose its `XrayCore`, so a service built on it gets no Hysteria2/WireGuard rebind
yet; `AndroidTunnelEngine` reports no rebind, and `restartOnNetworkChange = true` restarts these
protocols too. Closing it needs a hook in xray-rust-mobile (for example, a callback with the started core),
not a second coordinator here.

[xray-rust#214](https://github.com/aimalygin/xray-rust/pull/214) adds both to `XrayVpnService`: an opt-in
network-change rebind and start replacement with an `onXrayTunnelStartCancelled` callback. It is
merged but not released yet. Once a xray-rust-mobile release ships it, `AndroidTunnelEngine` reports rebind and `XrayKmpVpnService` drops
the wait.
