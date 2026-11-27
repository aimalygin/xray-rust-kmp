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

`XrayVpnService` (xray-rust-mobile 0.10.0) does not call `startForeground`, and neither does `XrayKmpVpnService`.
An app that wants the tunnel to outlive its UI promotes the service to the foreground in a
subclass (notification, `FOREGROUND_SERVICE` permission and a service type). The sample does not.
Always-on VPN is not supported yet: a start by the system carries no config.

`XraySessionSequencer` (unit-tested, used by `XrayKmpVpnService`) never cancels a start in
flight: a newer start or a stop waits until it reports (started or failed) and then replaces or
stops it; only destroying the service cancels it at once. xray-rust-mobile 0.9.0 needed that wait,
because it ignored a start while an earlier one was still running and cancelled a start during
startup without a callback. `XrayVpnService` 0.10.0 replaces a start in flight and reports
`onXrayTunnelStartCancelled`; `XrayKmpVpnService` does not use that yet, and the wait stays
correct with it.

Network changes: `XrayVpnService` 0.10.0 can rebind the tunnel's carrier on its own when the
network changes ([xray-rust#214](https://github.com/aimalygin/xray-rust/pull/214); opt-in through
`networkChangeRebindEnabled()`, which needs `ACCESS_NETWORK_STATE` in the app manifest).
`XrayKmpVpnService` does not opt in, because the rebind has not been tested through this library
on a device across a Wi-Fi/cellular change. So
`AndroidTunnelEngine` reports no rebind, and `restartOnNetworkChange = true` restarts every
protocol. A subclass that turns the rebind on itself should keep `restartOnNetworkChange = false`,
since the engine still reports no rebind and the controller would restart a tunnel the service has
just moved.

Worker threads: from the xray-rust-mobile release that carries
[xray-rust#384](https://github.com/aimalygin/xray-rust/pull/384) (ABI 1.18), `XrayVpnService` reads
`runtimeWorkerThreads()` once per start and gives it to every core the start creates. Override it
in your subclass of `XrayKmpVpnService` to run the core on fewer threads (1 to 64; `null`, the
default, keeps the core's count). The socket-protect callback runs on a runtime worker, so measure
throughput with a burst of new connections before you lower it.

Since xray-rust-mobile 0.10.0 the service also reports a file-descriptor read or write loop that
stopped as `onXrayTunnelFatalError`, so `TunnelFailure.Kind.Fatal` and the policy's restart can now
happen on Android with the default backend.
