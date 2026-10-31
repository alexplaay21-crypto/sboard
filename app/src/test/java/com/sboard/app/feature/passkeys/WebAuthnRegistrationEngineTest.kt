package com.sboard.app.feature.passkeys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.math.BigInteger
import java.util.Base64

class WebAuthnRegistrationEngineTest {
    private fun request(alg: Int = -7): String {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val userId = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(9, 8, 7))
        return """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"$userId","name":"alex","displayName":"Alex"},"challenge":"$challenge","pubKeyCredParams":[{"type":"public-key","alg":$alg}],"attestation":"none"}"""
    }

    @Test fun createsNoneAttestationAndEs256CoseKey() {
        val result = WebAuthnRegistrationEngine.create(
            request(), "example.com", "https://example.com", userVerified = true
        )
        assertEquals(32, result.credentialId.size)
        assertArrayEquals(byteArrayOf(9, 8, 7), result.userHandle)
        assertEquals(0L, result.signCount)
        assertEquals(0x45, result.authenticatorData[32].toInt())
        assertTrue(result.authenticatorData.size > 37 + 16 + 2 + 32)
        assertEquals(0xa3.toByte(), result.attestationObject[0]) // three-entry CBOR map
        val clientData = JSONObject(String(result.clientDataJson))
        assertEquals("webauthn.create", clientData.getString("type"))
        assertEquals("https://example.com", clientData.getString("origin"))

        // Decode the EC2 COSE x/y coordinates and prove they correspond to the private key.
        // COSE map is deterministic: a5 01 02 03 26 20 01 21 58 20 <x> 22 58 20 <y>.
        val cose = result.publicKeyCose
        assertEquals(0xa5.toByte(), cose[0])
        assertEquals(0x01.toByte(), cose[1])
        assertEquals(0x02.toByte(), cose[2])
        assertEquals(0x03.toByte(), cose[3])
        assertEquals(0x26.toByte(), cose[4])
        assertEquals(0x20.toByte(), cose[5])
        assertEquals(0x01.toByte(), cose[6])
        assertEquals(0x21.toByte(), cose[7])
        assertEquals(0x58.toByte(), cose[8])
        assertEquals(0x20.toByte(), cose[9])
        val x = cose.copyOfRange(10, 42)
        assertEquals(0x22.toByte(), cose[42])
        assertEquals(0x58.toByte(), cose[43])
        assertEquals(0x20.toByte(), cose[44])
        val y = cose.copyOfRange(45, 77)
        assertEquals(77, cose.size)
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params)
        )
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(result.privateKeyPkcs8)
        )
        val proof = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("sboard-registration-test".toByteArray())
            sign()
        }
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update("sboard-registration-test".toByteArray())
            verify(proof)
        }
        assertTrue(verified)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOriginFromUnrelatedDomain() {
        WebAuthnRegistrationEngine.create(request(), "example.com", "https://attacker.test", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRpIdMismatch() {
        WebAuthnRegistrationEngine.create(request(), "evil.example", "https://example.com", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingEs256Support() {
        WebAuthnRegistrationEngine.create(request(alg = -257), "example.com", "https://example.com", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresUserVerification() {
        WebAuthnRegistrationEngine.create(request(), "example.com", "https://example.com", false)
    }
}
