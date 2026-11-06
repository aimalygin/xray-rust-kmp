@file:OptIn(ExperimentalForeignApi::class)

package io.github.aimalygin.xrayrust.kmp

import cnames.structs.XrayError
import io.github.aimalygin.xrayrust.ffi.XRAY_STATUS_OK
import io.github.aimalygin.xrayrust.ffi.XrayStatus
import io.github.aimalygin.xrayrust.ffi.xray_error_free
import io.github.aimalygin.xrayrust.ffi.xray_ffi_capabilities
import io.github.aimalygin.xrayrust.ffi.xray_ffi_version_major
import io.github.aimalygin.xrayrust.ffi.xray_ffi_version_minor
import io.github.aimalygin.xrayrust.ffi.xray_profile_import_json
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.size_tVar

// Limits of xray_profile_import_json (xray_ffi.h, ABI 1.5+).
private const val MAX_IMPORT_TEXT_BYTES = 64 * 1024
private const val MAX_IMPORT_JSON_BYTES = 256 * 1024

internal actual fun platformRuntimeInfo(): XrayRuntimeInfo = XrayRuntimeInfo(
    abi = XrayAbiVersion(xray_ffi_version_major().toInt(), xray_ffi_version_minor().toInt()),
    capabilityMask = xray_ffi_capabilities().toLong(),
)

// The Swift app passes XrayVlessURLImporter wrapped in XrayLinkImporter to XrayProfiles.
internal actual fun platformVlessImporter(): XrayLinkImporter? = null

internal actual fun platformCoreImporter(): XrayCoreImporter = XrayCoreImporter { protocol, format, text, name ->
    checkImportSupported(protocol, format)
    if (text.encodeToByteArray().size > MAX_IMPORT_TEXT_BYTES) rejected("import text is too long")
    val request = buildJsonObject {
        put("format", format)
        put("text", text)
        name?.let { put("name", it) }
    }.toString().encodeToByteArray()
    val response = try {
        nativeImport(request)
    } finally {
        request.fill(0)
    }
    try {
        response.decodeToString().toProfile(protocol)
    } finally {
        response.fill(0)
    }
}

private fun checkImportSupported(protocol: XrayProtocol, format: String) {
    val info = platformRuntimeInfo()
    val capability = protocol.importCapability
    if (!info.supports(XrayCapability.ProfileImport) || capability == null || !info.supports(capability)) {
        throw XrayImportException(
            XrayImportException.Reason.UnknownFormat,
            "xray runtime ${info.abi} cannot import \"$format\"",
        )
    }
}

private fun rejected(message: String): Nothing = throw XrayImportException(XrayImportException.Reason.Rejected, message)

// Size query first, then the real call; the result holds credentials and is wiped by the caller.
private fun nativeImport(request: ByteArray): ByteArray = memScoped {
    val written = alloc<size_tVar>()
    val error = alloc<CPointerVar<XrayError>>()
    request.usePinned { pinnedRequest ->
        val input = pinnedRequest.addressOf(0).reinterpret<UByteVar>()
        val inputLength = request.size.convert<ULong>()
        checkStatus(xray_profile_import_json(input, inputLength, null, 0u, written.ptr, error.ptr), error)
        val length = written.value.toInt()
        if (length !in 1..MAX_IMPORT_JSON_BYTES) rejected("import result has an invalid size")
        val buffer = ByteArray(length + 1)
        try {
            buffer.usePinned { pinnedBuffer ->
                val status = xray_profile_import_json(
                    input, inputLength, pinnedBuffer.addressOf(0), buffer.size.convert(), written.ptr, error.ptr,
                )
                checkStatus(status, error)
            }
            if (written.value.toInt() != length) rejected("import result changed size")
            buffer.copyOf(length)
        } finally {
            buffer.fill(0)
        }
    }
}

// The FFI message may quote the imported text, so only the status code is kept.
private fun checkStatus(status: XrayStatus, error: CPointerVar<XrayError>) {
    xray_error_free(error.value)
    error.value = null
    if (status != XRAY_STATUS_OK) rejected("xray_profile_import_json failed with status $status")
}

private fun String.toProfile(protocol: XrayProtocol): XrayProfile {
    val result = Json.parseToJsonElement(this).jsonObject
    fun field(key: String): String = result[key]?.jsonPrimitive?.content ?: rejected("import result lacks $key")
    if (result["schemaVersion"]?.jsonPrimitive?.int != 1) rejected("unsupported import result schema")
    return XrayProfile(field("name"), field("serverAddress"), protocol, field("configJSON"))
}
