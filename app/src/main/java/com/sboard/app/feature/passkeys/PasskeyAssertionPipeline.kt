package com.sboard.app.feature.passkeys

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Narrow persistence contract used by the assertion pipeline and its JVM tests. */
interface PasskeyAssertionStore {
    suspend fun getMetadata(recordId: String): PasskeyRecordEntity?
    suspend fun openPrivateKey(recordId: String, authorization: PasskeyAuthorization): ByteArray
    suspend fun markUsed(recordId: String, newSignCount: Long)
}

/**
 * Orchestrates one selected Passkey assertion. The origin must come from trusted Credential
 * Manager calling-app identity / verified Digital Asset Links, never from request JSON or UI.
 * A biometric authorization is consumed before private-key material is decrypted.
 */
class PasskeyAssertionPipeline(private val store: PasskeyAssertionStore) {
    suspend fun create(
        recordId: String,
        requestJson: String,
        trustedOrigin: String,
        authorization: PasskeyAuthorization
    ): WebAuthnAssertionEngine.Assertion {
        require(recordId.isNotBlank())
        val record = requireNotNull(store.getMetadata(recordId)) { "Passkey not found" }
        val request = JSONObject(requestJson)
        requireRequestAllowsCredential(request, record.credentialId)

        val pkcs8 = store.openPrivateKey(recordId, authorization)
        try {
            val privateKey = decodeEcPrivateKey(pkcs8)
            val assertion = WebAuthnAssertionEngine.create(
                requestJson = requestJson,
                expectedRpId = record.rpId,
                origin = trustedOrigin,
                credentialId = record.credentialId,
                userHandle = record.userHandle,
                privateKey = privateKey,
                currentSignCount = record.signCount,
                userVerified = true
            )
            // Persist only after a valid signature has been produced.
            store.markUsed(recordId, assertion.newSignCount)
            return assertion
        } finally {
            pkcs8.fill(0)
        }
    }

    private fun decodeEcPrivateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    private fun requireRequestAllowsCredential(request: JSONObject, credentialId: ByteArray) {
        val allowed = request.optJSONArray("allowCredentials") ?: return
        if (allowed.length() == 0) return
        val target = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId)
        val matched = (0 until allowed.length()).any { index ->
            val item = allowed.optJSONObject(index) ?: return@any false
            item.optString("type") == "public-key" && item.optString("id") == target
        }
        require(matched) { "Selected credential is not in allowCredentials" }
    }
}
