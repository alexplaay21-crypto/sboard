package com.sboard.app.feature.passkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WebAuthnRegistrationPolicyTest {
    private fun request(extra: String = ""): String {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val userId = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3))
        return """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"$userId","name":"alex","displayName":"Alex"},"challenge":"$challenge","pubKeyCredParams":[{"type":"public-key","alg":-7}],"attestation":"none"$extra}"""
    }

    @Test fun acceptsSupportedRequestAndReturnsMetadata() {
        val validated = WebAuthnRegistrationPolicy.validate(request(), "example.com", "https://example.com")
        assertEquals("example.com", validated.rpId)
        assertEquals("Example", validated.rpName)
        assertEquals("alex", validated.userName)
        assertArrayEquals(byteArrayOf(1, 2, 3), validated.userId)
        assertTrue(validated.excludedCredentialIds.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortChallenge() {
        val invalid = request().replace(Regex("\"challenge\":\"[^\"]+\""), "\"challenge\":\"YWJj\"")
        WebAuthnRegistrationPolicy.validate(invalid, "example.com", "https://example.com")
    }

    @Test fun acceptsCredPropsExtension() {
        val validated = WebAuthnRegistrationPolicy.validate(request(",\"extensions\":{\"credProps\":true}"), "example.com", "https://example.com")
        assertTrue(validated.credPropsRequested)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCrossPlatformAttachmentRequirement() {
        WebAuthnRegistrationPolicy.validate(request(",\"authenticatorSelection\":{\"authenticatorAttachment\":\"cross-platform\"}"), "example.com", "https://example.com")
    }

    @Test fun parsesExcludedCredentialIds() {
        val excluded = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(7, 8, 9))
        val validated = WebAuthnRegistrationPolicy.validate(
            request(",\"excludeCredentials\":[{\"type\":\"public-key\",\"id\":\"$excluded\"}]"),
            "example.com", "https://example.com"
        )
        assertEquals(1, validated.excludedCredentialIds.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), validated.excludedCredentialIds.single())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
