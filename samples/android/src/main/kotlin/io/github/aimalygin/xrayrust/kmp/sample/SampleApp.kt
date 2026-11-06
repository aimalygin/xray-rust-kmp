package io.github.aimalygin.xrayrust.kmp.sample

import android.app.Application
import io.github.aimalygin.xrayrust.kmp.AndroidTunnelEngine
import io.github.aimalygin.xrayrust.kmp.XrayProfiles
import io.github.aimalygin.xrayrust.kmp.XrayTunnel
import kotlinx.coroutines.MainScope

class SampleApp : Application() {
    val profiles by lazy { XrayProfiles() }
    val tunnel by lazy { XrayTunnel(AndroidTunnelEngine(this), MainScope()) }
}
