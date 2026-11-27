package io.github.aimalygin.xrayrust.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TunnelPolicyTest {
    private val policy = TunnelPolicy()
    private val startFailed = TunnelFailure.Kind.StartFailed

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(1.seconds, policy.backoffBefore(2))
        assertEquals(2.seconds, policy.backoffBefore(3))
        assertEquals(30.seconds, policy.backoffBefore(20))
    }

    @Test
    fun defaultsRetryThenGiveUpWithoutFallback() {
        assertEquals(FailureDecision.Retry(2, 1.seconds), policy.onFailure(attempt = 1, withoutRouting = false, startFailed))
        assertEquals(FailureDecision.GiveUp, policy.onFailure(attempt = 3, withoutRouting = false, startFailed))
    }

    @Test
    fun missingPermissionAndRejectedConfigAreNotRetried() {
        val optedIn = policy.copy(fallbackWithoutRouting = true)
        val permission = TunnelFailure.Kind.PermissionRequired
        val rejected = TunnelFailure.Kind.ConfigRejected
        assertEquals(FailureDecision.GiveUp, optedIn.onFailure(attempt = 1, withoutRouting = false, permission))
        assertEquals(FailureDecision.GiveUp, policy.onFailure(attempt = 1, withoutRouting = false, rejected))
        assertEquals(FailureDecision.FallbackWithoutRouting, optedIn.onFailure(attempt = 1, withoutRouting = false, rejected))
        assertEquals(FailureDecision.GiveUp, optedIn.onFailure(attempt = 1, withoutRouting = true, rejected))
    }

    @Test
    fun fallbackWithoutRoutingIsOptInAndUsedOnce() {
        val optedIn = policy.copy(fallbackWithoutRouting = true)
        assertEquals(FailureDecision.FallbackWithoutRouting, optedIn.onFailure(attempt = 3, withoutRouting = false, startFailed))
        assertEquals(FailureDecision.GiveUp, optedIn.onFailure(attempt = 3, withoutRouting = true, startFailed))
    }

    @Test
    fun networkChangeRestartsOnlyWithoutRebind() {
        val restarting = policy.copy(restartOnNetworkChange = true)
        assertEquals(NetworkChangeDecision.LeaveToWrapper, restarting.onNetworkChange(rebinds = true))
        assertEquals(NetworkChangeDecision.Restart, restarting.onNetworkChange(rebinds = false))
        assertEquals(NetworkChangeDecision.Ignore, policy.onNetworkChange(rebinds = false))
    }
}

class XrayProtocolTest {
    @Test
    fun detectsSchemesAndWireguardConfig() {
        assertEquals(XrayProtocol.Vless, XrayProtocol.detect(" VLESS://id@vpn.example.com:443"))
        assertEquals(XrayProtocol.Hysteria2, XrayProtocol.detect("hy2://auth@vpn.example.com"))
        assertEquals(XrayProtocol.Shadowsocks, XrayProtocol.detect("ss://x@192.0.2.1:8388"))
        assertEquals(XrayProtocol.Wireguard, XrayProtocol.detect("[Interface]\nPrivateKey = x"))
        assertEquals(null, XrayProtocol.detect("ftp://vpn.example.com"))
    }

    @Test
    fun vlessUsesPlatformImporterUntilCoreSupportsIt() {
        assertEquals(XrayImportRoute.PlatformVless, XrayProtocol.Vless.importRoute)
        assertEquals(XrayImportRoute.Core("hysteria2"), XrayProtocol.Hysteria2.importRoute)
    }

    @Test
    fun importRoutesVlessToPlatformAndOthersToCore() {
        val profiles = XrayProfiles(
            vlessImporter = { _, _ -> profile(XrayProtocol.Vless) },
            coreImporter = { protocol, format, _, _ -> profile(protocol, name = format) },
        )
        assertEquals(XrayProtocol.Vless, profiles.import("vless://id@vpn.example.com:443").getOrThrow().protocol)
        assertEquals("trojan", profiles.import("trojan://pw@vpn.example.com:443").getOrThrow().name)
    }

    @Test
    fun importFailuresAreTypedAndDoNotQuoteInput() {
        val profiles = XrayProfiles(vlessImporter = null) { _, _, text, _ -> error("bad $text") }
        val unknown = profiles.import("ftp://vpn.example.com").exceptionOrNull()
        assertIs<XrayImportException>(unknown)
        assertEquals(XrayImportException.Reason.UnknownFormat, unknown.reason)
        val noImporter = assertIs<XrayImportException>(profiles.import("vless://id@vpn.example.com").exceptionOrNull())
        assertEquals(XrayImportException.Reason.NoPlatformImporter, noImporter.reason)
        val rejected = assertIs<XrayImportException>(profiles.import("vmess://secret").exceptionOrNull())
        assertEquals(XrayImportException.Reason.Rejected, rejected.reason)
        assertFalse(rejected.message.orEmpty().contains("secret"))
    }
}

class XrayRuntimeTest {
    private val required = XrayRuntime.requiredCapabilities.sumOf { it.mask }

    // Relative to the pin, so moving release/mobile.env to a newer ABI does not need test edits.
    private val binding = XrayRuntime.bindingAbi

    @Test
    fun acceptsBindingAbiAndNewerMinor() {
        assertEquals(XrayCompatibility.Compatible, XrayRuntime.checkCompatibility(XrayRuntimeInfo(binding, required)))
        val newerMinor = XrayAbiVersion(binding.major, binding.minor + 1)
        assertEquals(XrayCompatibility.Compatible, XrayRuntime.checkCompatibility(XrayRuntimeInfo(newerMinor, required)))
    }

    @Test
    fun rejectsOlderMinorOtherMajorAndMissingCapabilities() {
        val olderMinor = XrayAbiVersion(binding.major, binding.minor - 1)
        assertIs<XrayCompatibility.Incompatible>(XrayRuntime.checkCompatibility(XrayRuntimeInfo(olderMinor, required)))
        val nextMajor = XrayAbiVersion(binding.major + 1, 0)
        assertIs<XrayCompatibility.Incompatible>(XrayRuntime.checkCompatibility(XrayRuntimeInfo(nextMajor, required)))
        assertIs<XrayCompatibility.Incompatible>(XrayRuntime.checkCompatibility(XrayRuntimeInfo(binding, 0)))
    }

    @Test
    fun bindingAbiComesFromMobileEnv() {
        assertEquals(XrayAbiVersion(XrayBindingPin.FFI_ABI_MAJOR, XrayBindingPin.FFI_ABI_MINOR), XrayRuntime.bindingAbi)
    }

    @Test
    fun capabilityBitsMatchHeader() {
        val info = XrayRuntimeInfo(XrayAbiVersion(1, 15), 1L shl 18)
        assertTrue(info.supports(XrayCapability.ProfileImport))
        assertEquals(setOf(XrayCapability.ProfileImport), info.capabilities)
    }

    @Test
    fun abi116To118BitsMatchHeader() {
        assertEquals(1L shl 29, XrayCapability.TunMtu.mask)
        assertEquals(1L shl 30, XrayCapability.VlessProfileImport.mask)
        // A 64-bit macro in xray_ffi.h, since a C enum constant cannot hold it; positive as a Long.
        assertEquals(0x8000_0000L, XrayCapability.RuntimeWorkerThreads.mask)
        val info = XrayRuntimeInfo(XrayAbiVersion(1, 18), (1L shl 18) or (1L shl 31))
        assertEquals(setOf(XrayCapability.ProfileImport, XrayCapability.RuntimeWorkerThreads), info.capabilities)
    }

    @Test
    fun fallbackConfigDropsOnlyRouting() {
        val stripped = configWithoutRouting("""{"outbounds":[{"tag":"proxy"}],"routing":{"rules":[]}}""")
        assertEquals("""{"outbounds":[{"tag":"proxy"}]}""", stripped)
    }
}

internal fun profile(protocol: XrayProtocol, name: String = "test") =
    XrayProfile(name, serverAddress = "vpn.example.com", protocol = protocol, configJson = """{"routing":{}}""")
