package io.github.aimalygin.xrayrust.kmp

// Placeholder actuals: the cinterop binding of xray_ffi.h replaces them in the iOS step.
private const val NOT_WIRED = "the iOS xray-rust binding is not wired in this build"

internal actual fun platformRuntimeInfo(): XrayRuntimeInfo = throw UnsupportedOperationException(NOT_WIRED)

internal actual fun platformVlessImporter(): XrayLinkImporter? = null

internal actual fun platformCoreImporter(): XrayCoreImporter = XrayCoreImporter { _, _, _, _ ->
    throw UnsupportedOperationException(NOT_WIRED)
}
