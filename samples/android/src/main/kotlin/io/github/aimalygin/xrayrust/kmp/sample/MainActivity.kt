package io.github.aimalygin.xrayrust.kmp.sample

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aimalygin.xrayrust.kmp.TunnelState
import io.github.aimalygin.xrayrust.kmp.XrayCompatibility
import io.github.aimalygin.xrayrust.kmp.XrayProfile
import io.github.aimalygin.xrayrust.kmp.XrayRuntime
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    private val app get() = application as SampleApp
    private var profile: XrayProfile? = null

    private lateinit var linkField: EditText
    private lateinit var profileText: TextView
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linkField = EditText(this).apply {
            hint = "vless://… / trojan://… / hy2://…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        profileText = TextView(this).apply { text = runtimeSummary() }
        stateText = TextView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(linkField)
                addView(button("Import") { importLink() })
                addView(button("Connect") { requestVpnConsent() })
                addView(button("Disconnect") { app.tunnel.stop() })
                addView(profileText)
                addView(stateText)
            },
        )
        scope.launch { app.tunnel.state.collect { stateText.text = it.label() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Plain Activity result API keeps the sample free of AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_CONSENT && resultCode == RESULT_OK) connect()
    }

    private fun importLink() {
        app.profiles.import(linkField.text.toString())
            .onSuccess {
                profile = it
                profileText.text = "Imported ${it.protocol}: ${it.name}"
            }
            .onFailure { profileText.text = "Import failed: ${it.message}" }
    }

    @Suppress("DEPRECATION")
    private fun requestVpnConsent() {
        val consent = VpnService.prepare(this)
        if (consent == null) connect() else startActivityForResult(consent, VPN_CONSENT)
    }

    private fun connect() {
        val imported = profile
        if (imported == null) profileText.text = "Import a link first" else app.tunnel.start(imported)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun runtimeSummary(): String = when (val compatibility = XrayRuntime.checkCompatibility()) {
        XrayCompatibility.Compatible -> "xray FFI ${XrayRuntime.info().abi}"
        is XrayCompatibility.Incompatible -> "Incompatible runtime: ${compatibility.reason}"
    }

    private fun TunnelState.label(): String = when (this) {
        TunnelState.Idle -> "Idle"
        is TunnelState.Connecting -> "Connecting (attempt $attempt)"
        is TunnelState.Connected -> "Connected via ${protocol ?: "a tunnel started elsewhere"}"
        is TunnelState.Failed -> "Failed (${failure.kind}): ${failure.message}"
        TunnelState.Stopping -> "Stopping"
    }

    private companion object {
        const val VPN_CONSENT = 1
    }
}
