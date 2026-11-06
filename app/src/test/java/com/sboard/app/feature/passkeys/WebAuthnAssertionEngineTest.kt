package com.sboard.app.feature.passkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

class WebAuthnAssertionEngineTest {
    private fun request(): String {
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { (it + 1).toByte() })
        return """{"rpId":"example.com","challenge":"$challenge","allowCredentials":[]}"""
    }

    @Test fun createsVerifiableEs256Assertion() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val request = """{"rpId":"example.com","challenge":"$challenge","allowCredentials":[]}"""
        val result = WebAuthnAssertionEngine.create(
            request, "example.com", "https://example.com", byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5), pair.private, 7L, true
        )
        assertEquals(8L, result.newSignCount)
        assertEquals(37, result.authenticatorData.size)
        assertEquals(0x1D, result.authenticatorData[32].toInt())
        val clientHash = MessageDigest.getInstance("SHA-256").digest(result.clientDataJson)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(result.authenticatorData + clientHash)
        assertTrue(verifier.verify(result.signature))
        val clientData = JSONObject(String(result.clientDataJson))
        assertEquals("webauthn.get", clientData.getString("type"))
        assertEquals("https://example.com", clientData.getString("origin"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOriginFromUnrelatedDomain() {
        WebAuthnAssertionEngine.create(
            request(), "example.com", "https://attacker.test", byteArrayOf(1), byteArrayOf(2),
            java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair().private,
            currentSignCount = 0L, userVerified = true
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRpIdMismatch() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        WebAuthnAssertionEngine.create(
            """{"rpId":"evil.example","challenge":"AQ"}""", "example.com",
            "https://example.com", byteArrayOf(1), byteArrayOf(2), pair.private, 0L, true
        )
    }
}
