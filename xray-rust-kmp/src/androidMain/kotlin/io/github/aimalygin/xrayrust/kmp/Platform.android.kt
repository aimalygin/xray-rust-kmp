package io.github.aimalygin.xrayrust.kmp

import org.xrayrust.mobile.XrayCore
import org.xrayrust.mobile.XrayImportedProfile
import org.xrayrust.mobile.XrayProfileFormat
import org.xrayrust.mobile.XrayProfileImporter
import org.xrayrust.mobile.XrayVlessUrlImporter

internal actual fun platformRuntimeInfo(): XrayRuntimeInfo {
    val info = XrayCore.ffiInfo()
    return XrayRuntimeInfo(XrayAbiVersion(info.version.major, info.version.minor), info.capabilityMask)
}

internal actual fun platformVlessImporter(): XrayLinkImporter? = XrayLinkImporter { text, name ->
    val imported = XrayVlessUrlImporter.profile(text)
    imported.toProfile(XrayProtocol.Vless, name ?: imported.name)
}

internal actual fun platformCoreImporter(): XrayCoreImporter = XrayCoreImporter { protocol, format, text, name ->
    val profileFormat = XrayProfileFormat.entries.firstOrNull { it.wireValue == format }
        ?: throw XrayImportException(XrayImportException.Reason.UnknownFormat, "xray-rust-mobile has no \"$format\" import")
    XrayProfileImporter.profile(text, profileFormat, name).toProfile(protocol)
}

private fun XrayImportedProfile.toProfile(protocol: XrayProtocol, displayName: String = name): XrayProfile =
    XrayProfile(displayName, serverAddress, protocol, configJson)
