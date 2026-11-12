package io.github.aimalygin.xrayrust.kmp

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Swift entry points: Objective-C export has no default arguments, `Result` or flows. */
@Throws(XrayImportException::class)
public fun XrayProfiles.importProfile(text: String, name: String?): XrayProfile = import(text, name).getOrThrow()

/** A tunnel whose state machine runs on the main queue, with the default [TunnelPolicy]. */
public fun createXrayTunnel(engine: XrayTunnelEngine): XrayTunnel = XrayTunnel(engine, MainScope())

public fun createXrayTunnel(engine: XrayTunnelEngine, policy: TunnelPolicy): XrayTunnel =
    XrayTunnel(engine, MainScope(), policy)

public class XrayObservation internal constructor(private val job: Job) {
    public fun cancel(): Unit = job.cancel()
}

/** Calls [onChange] on the main queue with the current state and every change until cancelled. */
public fun XrayTunnel.observeState(onChange: (TunnelState) -> Unit): XrayObservation =
    XrayObservation(MainScope().launch { state.collect(onChange) })

public fun XrayIosTunnelEngine.observeStatus(onChange: (XrayVpnStatus) -> Unit): XrayObservation =
    XrayObservation(MainScope().launch { status.collect(onChange) })
