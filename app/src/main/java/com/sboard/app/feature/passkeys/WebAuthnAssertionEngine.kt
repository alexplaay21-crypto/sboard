package com.sboard.app.feature.passkeys

import java.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/**
 * Builds the cryptographic core of a WebAuthn getAssertion response.
 * The caller must obtain [origin] from a trusted Android Credential Manager calling-app
 * identity/verified Digital Asset Links flow; never accept an origin from an untrusted UI field.
 * The caller must also authenticate the user before passing the decrypted private key.
 */
object WebAuthnAssertionEngine {
    data class Assertion(
        val credentialId: ByteArray,
        val clientDataJson: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
        val userHandle: ByteArray,
        val newSignCount: Long
    ) {
        fun toCredentialResponseJson(): String = JSONObject().apply {
            put("id", credentialId.base64Url())
            put("rawId", credentialId.base64Url())
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJson.base64Url())
                put("authenticatorData", authenticatorData.base64Url())
                put("signature", signature.base64Url())
                put("userHandle", userHandle.base64Url())
            })
        }.toString()
    }

    /**
     * Creates an assertion for one already-selected credential.
     * [requestJson] is the original PublicKeyCredentialRequestOptions JSON.
     */
    fun create(
        requestJson: String,
        expectedRpId: String,
        origin: String,
        credentialId: ByteArray,
        userHandle: ByteArray,
        privateKey: PrivateKey,
        currentSignCount: Long,
        userVerified: Boolean
    ): Assertion {
        require(credentialId.isNotEmpty()) { "Empty credential ID" }
        require(userHandle.isNotEmpty()) { "Empty user handle" }
        require(currentSignCount in 0..0xffff_fffeL) { "Invalid signature counter" }
        require(userVerified) { "User verification is required" }
        val request = JSONObject(requestJson)
        val rpId = WebAuthnOriginPolicy.normalizeRpId(request.optString("rpId"))
        val normalizedExpected = WebAuthnOriginPolicy.normalizeRpId(expectedRpId)
        require(rpId != null && normalizedExpected != null && rpId == normalizedExpected) { "RP ID mismatch or invalid RP ID" }
        require(WebAuthnOriginPolicy.isValidOriginForRpId(origin, normalizedExpected)) {
            "Origin does not match the relying-party ID"
        }
        val challenge = request.optString("challenge")
        require(challenge.isNotBlank()) { "Missing challenge" }
        val challengeBytes = challenge.decodeBase64Url()
        require(challengeBytes.isNotEmpty()) { "Invalid challenge" }

        val nextCounter = if (currentSignCount == 0L) 0L else currentSignCount + 1L
        val clientData = JSONObject().apply {
            put("type", "webauthn.get")
            put("challenge", challenge)
            put("origin", origin)
            put("crossOrigin", false)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val rpIdHash = MessageDigest.getInstance("SHA-256")
            .digest(normalizedExpected.toByteArray(StandardCharsets.UTF_8))
        // UP=1, UV=1. BE/BS are unset for a single-device credential.
        val flags: Byte = 0x05
        val authData = ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN)
            .put(rpIdHash)
            .put(flags)
            .putInt(nextCounter.toInt())
            .array()

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientData)
        val signedBytes = authData + clientDataHash
        val signature = when (privateKey.algorithm.uppercase()) {
            "EC", "ECDSA" -> Signature.getInstance("SHA256withECDSA")
            "RSA" -> Signature.getInstance("SHA256withRSA")
            else -> throw IllegalArgumentException("Unsupported WebAuthn signing key: ${privateKey.algorithm}")
        }.run {
            initSign(privateKey)
            update(signedBytes)
            sign()
        }

        return Assertion(
            credentialId.copyOf(), clientData, authData, signature,
            userHandle.copyOf(), nextCounter
        )
    }

    private fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
