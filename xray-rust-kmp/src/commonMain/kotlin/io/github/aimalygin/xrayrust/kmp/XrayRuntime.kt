package io.github.aimalygin.xrayrust.kmp

/** C ABI version reported by `xray_ffi_version_major/minor` of the loaded native library. */
public data class XrayAbiVersion(val major: Int, val minor: Int) : Comparable<XrayAbiVersion> {
    override fun compareTo(other: XrayAbiVersion): Int =
        compareValuesBy(this, other, XrayAbiVersion::major, XrayAbiVersion::minor)

    override fun toString(): String = "$major.$minor"
}

/** Capability bits of `xray_ffi_capabilities`, numbered as in `xray_ffi.h`. */
public enum class XrayCapability(public val bit: Int) {
    ConfigWarnings(0),
    GeodataSearch(1),
    SocketProtection(2),
    StartupProbe(3),
    FileLogging(4),
    TunPacketIo(5),
    TunFileDescriptor(6),
    TunBatchPoll(7),
    TunRuntimeProfiles(8),
    DnsBootstrapPolicy(9),
    TunStats(10),
    TunDiagnosticEvents(11),
    OutboundSelection(12),
    OutboundHealth(13),
    ConnectionManagement(14),
    RoutingPolicyUpdate(15),
    Hysteria2Outbound(16),
    WireguardOutbound(17),
    ProfileImport(18),
    TrojanOutbound(19),
    Shadowsocks2022Outbound(20),
    VmessOutbound(21),
    OutboundProbe(22),
    HysteriaStreamLimits(23),
    TunAdmission(24),
    StartupProbePreflight(25),
    SocksProfileImport(26),
    HttpProfileImport(27),
    OutboundProbeCancel(28),
    ;

    public val mask: Long get() = 1L shl bit
}

public data class XrayRuntimeInfo(val abi: XrayAbiVersion, val capabilityMask: Long) {
    public fun supports(capability: XrayCapability): Boolean = capabilityMask and capability.mask == capability.mask

    public val capabilities: Set<XrayCapability>
        get() = XrayCapability.entries.filterTo(mutableSetOf(), ::supports)
}

public sealed interface XrayCompatibility {
    public data object Compatible : XrayCompatibility

    public data class Incompatible(val reason: String) : XrayCompatibility
}

public class XrayIncompatibleRuntimeException(message: String) : IllegalStateException(message)

public object XrayRuntime {
    /** ABI of the pinned xray-rust-mobile release (release/mobile.env) this binding is built against. */
    public val bindingAbi: XrayAbiVersion = XrayAbiVersion(major = 1, minor = 15)

    /** Capabilities every platform implementation of this binding relies on. */
    public val requiredCapabilities: Set<XrayCapability> = setOf(
        XrayCapability.ProfileImport,
        XrayCapability.TunFileDescriptor,
    )

    /** Reads version and capabilities from the loaded native library. */
    public fun info(): XrayRuntimeInfo = platformRuntimeInfo()

    public fun checkCompatibility(info: XrayRuntimeInfo = info()): XrayCompatibility {
        val missing = requiredCapabilities.filterNot(info::supports)
        return when {
            info.abi.major != bindingAbi.major ->
                XrayCompatibility.Incompatible("xray FFI ABI major ${info.abi.major}, binding needs ${bindingAbi.major}")
            info.abi < bindingAbi ->
                XrayCompatibility.Incompatible("xray FFI ABI ${info.abi} is older than $bindingAbi")
            missing.isNotEmpty() ->
                XrayCompatibility.Incompatible("xray runtime lacks capabilities: ${missing.joinToString()}")
            else -> XrayCompatibility.Compatible
        }
    }

    /** Throws [XrayIncompatibleRuntimeException] with the reason when [checkCompatibility] fails. */
    public fun requireCompatible(info: XrayRuntimeInfo = info()): XrayRuntimeInfo =
        when (val compatibility = checkCompatibility(info)) {
            XrayCompatibility.Compatible -> info
            is XrayCompatibility.Incompatible -> throw XrayIncompatibleRuntimeException(compatibility.reason)
        }
}

internal expect fun platformRuntimeInfo(): XrayRuntimeInfo
