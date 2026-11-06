package io.github.aimalygin.xrayrust.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosFfiTest {
    @Test
    fun linkedRuntimeMatchesBinding() {
        val info = XrayRuntime.info()
        assertEquals(XrayRuntime.bindingAbi, info.abi)
        assertIs<XrayCompatibility.Compatible>(XrayRuntime.checkCompatibility(info))
    }

    @Test
    fun trojanLinkGoesThroughCoreImport() {
        val link = "trojan://8b0f6a52-4d1e-4c6b-9a57-2f3e1c0d9b7a@203.0.113.10:443?security=tls&sni=example.com#sample"
        val profile = XrayProfiles(vlessImporter = null).import(link).getOrThrow()
        assertEquals(XrayProtocol.Trojan, profile.protocol)
        assertEquals("203.0.113.10", profile.serverAddress)
        assertTrue(profile.configJson.contains("\"outbounds\""))
    }

    @Test
    fun rejectedLinkKeepsTextOutOfMessage() {
        val error = XrayProfiles(vlessImporter = null).import("trojan://secret-password@").exceptionOrNull()
        assertIs<XrayImportException>(error)
        assertTrue("secret-password" !in error.message.orEmpty())
    }
}
