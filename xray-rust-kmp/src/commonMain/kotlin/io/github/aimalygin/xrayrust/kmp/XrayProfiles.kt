package io.github.aimalygin.xrayrust.kmp

/**
 * Outbound protocols the binding can import. [coreFormat] is the `"format"` value of
 * `xray_profile_import_json`; `null` means the core cannot import it yet and the
 * platform importer is used instead (see [importRoute]).
 */
public enum class XrayProtocol(
    internal val coreFormat: String?,
    internal val importCapability: XrayCapability?,
) {
    // VLESS goes through the core once xray_profile_import_json accepts "format":"vless":
    // set coreFormat = "vless" and its capability here; nothing else changes.
    Vless(coreFormat = null, importCapability = null),
    Vmess("vmess", XrayCapability.VmessOutbound),
    Trojan("trojan", XrayCapability.TrojanOutbound),
    Shadowsocks2022("shadowsocks2022", XrayCapability.Shadowsocks2022Outbound),
    Hysteria2("hysteria2", XrayCapability.Hysteria2Outbound),
    Wireguard("wireguard", XrayCapability.WireguardOutbound),
    Socks("socks", XrayCapability.SocksProfileImport),
    Http("http", XrayCapability.HttpProfileImport),
    ;

    public companion object {
        private val schemes = mapOf(
            "vless" to Vless,
            "vmess" to Vmess,
            "trojan" to Trojan,
            "ss" to Shadowsocks2022,
            "hy2" to Hysteria2,
            "hysteria2" to Hysteria2,
            "socks" to Socks,
            "socks5" to Socks,
            "http" to Http,
            "https" to Http,
        )

        /** Detects the protocol of a share link or a WireGuard `[Interface]` config; `null` if unknown. */
        public fun detect(text: String): XrayProtocol? {
            val trimmed = text.trim()
            if (trimmed.startsWith("[Interface]", ignoreCase = true)) return Wireguard
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
            return schemes[scheme.lowercase()]
        }
    }
}

/** A self-contained TUN profile. [configJson] holds credentials and is never printed. */
public class XrayProfile(
    public val name: String,
    public val serverAddress: String,
    public val protocol: XrayProtocol,
    public val configJson: String,
) {
    override fun toString(): String = "XrayProfile(protocol=$protocol, <redacted>)"
}

/** Where a protocol's text is turned into a profile. */
internal sealed interface XrayImportRoute {
    /** `xray_profile_import_json` with this `"format"`. */
    data class Core(val format: String) : XrayImportRoute

    /** A platform importer: `XrayVlessUrlImporter` on Android, the Swift importer handed in on iOS. */
    data object PlatformVless : XrayImportRoute
}

internal val XrayProtocol.importRoute: XrayImportRoute
    get() = coreFormat?.let(XrayImportRoute::Core) ?: XrayImportRoute.PlatformVless

/** Platform importer for links the core cannot import yet (VLESS today). */
public fun interface XrayLinkImporter {
    // @Throws lets a Swift importer throw; its NSError arrives as a Kotlin exception.
    @Throws(Exception::class)
    public fun import(text: String, name: String?): XrayProfile
}

public class XrayImportException(public val reason: Reason, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause) {
    public enum class Reason { UnknownFormat, NoPlatformImporter, Rejected }
}

internal fun interface XrayCoreImporter {
    fun import(protocol: XrayProtocol, format: String, text: String, name: String?): XrayProfile
}

public class XrayProfiles internal constructor(
    private val vlessImporter: XrayLinkImporter?,
    private val coreImporter: XrayCoreImporter,
) {
    /**
     * [vlessImporter] handles VLESS until the core imports it. Android defaults to the AAR's
     * `XrayVlessUrlImporter`; on iOS pass the Swift `XrayVlessURLImporter` wrapped in [XrayLinkImporter].
     */
    public constructor(vlessImporter: XrayLinkImporter? = platformVlessImporter()) :
        this(vlessImporter, platformCoreImporter())

    /** Imports a share link or WireGuard config without network access or VPN startup. */
    public fun import(text: String, name: String? = null): Result<XrayProfile> {
        val protocol = XrayProtocol.detect(text)
            ?: return Result.failure(XrayImportException(XrayImportException.Reason.UnknownFormat, "unknown link format"))
        return when (val route = protocol.importRoute) {
            is XrayImportRoute.Core -> importWith { coreImporter.import(protocol, route.format, text, name) }
            XrayImportRoute.PlatformVless -> vlessImporter
                ?.let { importer -> importWith { importer.import(text, name) } }
                ?: Result.failure(
                    XrayImportException(XrayImportException.Reason.NoPlatformImporter, "no VLESS importer on this platform"),
                )
        }
    }

    // Import errors may quote credentials, so only the type survives into the message. Exception, not
    // RuntimeException: an error thrown by a Swift importer arrives as ObjCErrorException.
    private inline fun importWith(block: () -> XrayProfile): Result<XrayProfile> = try {
        Result.success(block())
    } catch (error: XrayImportException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(
            XrayImportException(XrayImportException.Reason.Rejected, "import rejected: ${error::class.simpleName}", error),
        )
    }
}

internal expect fun platformVlessImporter(): XrayLinkImporter?

internal expect fun platformCoreImporter(): XrayCoreImporter
