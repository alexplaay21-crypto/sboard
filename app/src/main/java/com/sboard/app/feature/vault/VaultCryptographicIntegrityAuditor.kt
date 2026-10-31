package com.sboard.app.feature.vault

import com.sboard.app.feature.otp.TotpGenerator
import com.sboard.app.feature.otp.TotpParameters
import com.sboard.app.feature.passkeys.PasskeyMetadataCipher
import com.sboard.app.feature.passkeys.PasskeyPrivateKeyCipher
import com.sboard.app.feature.passkeys.PasskeyPortableMetadataCipher
import com.sboard.app.feature.passkeys.PasskeyPortableMetadataEntity
import com.sboard.app.feature.passkeys.PasskeyRecordEntity
import com.sboard.app.feature.passkeys.PasskeySecureMetadataEntity
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

/** Aggregate-only result: never includes decrypted fields, secrets, keys, or ciphertext. */
data class VaultCryptographicIntegrityReport(
    val vaultRecordsChecked: Int,
    val vaultRecordsFailed: Int,
    val passkeysChecked: Int,
    val passkeysFailed: Int,
    val otpEntriesChecked: Int,
    val otpEntriesFailed: Int
) {
    val isValid: Boolean get() = vaultRecordsFailed == 0 && passkeysFailed == 0 && otpEntriesFailed == 0
}

/** Authenticates encrypted payloads after the user has unlocked the restored Vault. */
internal object VaultCryptographicIntegrityAuditor {
    fun audit(
        records: List<VaultRecordEntity>,
        passkeys: List<PasskeyRecordEntity>,
        secureMetadata: List<PasskeySecureMetadataEntity>,
        portableMetadata: List<PasskeyPortableMetadataEntity>,
        vaultKey: ByteArray
    ): VaultCryptographicIntegrityReport {
        require(vaultKey.size == 32) { "Vault key must be 256-bit" }
        var recordFailures = 0
        var otpChecked = 0
        var otpFailures = 0

        records.forEach { entity ->
            var plain: ByteArray? = null
            try {
                plain = VaultAtRestCipher.decrypt(entity.id, entity.iv, entity.ciphertext, vaultKey)
                val json = JSONObject(plain.toString(Charsets.UTF_8))
                // These keys are part of every Vault record created by the current repository.
                listOf("title", "category", "username", "password", "website", "notes")
                    .forEach { field -> require(json.has(field)) { "Missing Vault field" } }
                val category = json.optString("category", "")
                if (category.equals("otp", ignoreCase = true) || category.equals("2fa", ignoreCase = true) ||
                    category.equals("totp", ignoreCase = true)) {
                    otpChecked++
                    val secret = json.optString("password", "")
                    val notes = json.optString("notes", "")
                    val params = TotpParameters.fromVaultNotes(notes)
                    // Generate once at a fixed timestamp to validate Base32 secret and algorithm.
                    TotpGenerator.generate(secret, timestampMillis = 0L, digits = params.digits,
                        periodSeconds = params.periodSeconds, algorithm = params.algorithm)
                }
            } catch (_: Exception) {
                recordFailures++
                // If the record was classified as OTP before a later validation failed, count it.
                // No exception text or secret data is retained or logged.
                if (plain != null) {
                    try {
                        val json = JSONObject(plain.toString(Charsets.UTF_8))
                        val category = json.optString("category", "")
                        if (category.equals("otp", true) || category.equals("2fa", true) || category.equals("totp", true)) otpFailures++
                    } catch (_: Exception) { /* malformed JSON is already counted as a Vault failure */ }
                }
            } finally {
                plain?.fill(0)
            }
        }

        var passkeyFailures = 0
        val metadataById = secureMetadata.associateBy { it.recordId }
        val portableById = portableMetadata.associateBy { it.recordId }
        passkeys.forEach { entity ->
            var privateKey: ByteArray? = null
            try {
                // Current schema stores a keyed RP-ID index in passkey_records.rpId.
                // Recover the authenticated RP ID from the separately encrypted metadata.
                val portableRow = portableById[entity.id]
                val metadataRow = metadataById[entity.id]
                val rpId = when {
                    portableRow != null -> {
                        val metadata = PasskeyPortableMetadataCipher.decrypt(entity.id,
                            PasskeyPortableMetadataCipher.Encrypted(portableRow.iv, portableRow.ciphertext), vaultKey)
                        try { metadata.rpId } finally {
                            metadata.userHandle.fill(0)
                            metadata.publicKeyCose.fill(0)
                        }
                    }
                    metadataRow != null -> {
                        val metadata = PasskeyMetadataCipher.decrypt(entity.id,
                            PasskeyMetadataCipher.Encrypted(metadataRow.iv, metadataRow.ciphertext))
                        try { metadata.rpId } finally {
                            metadata.userHandle.fill(0)
                            metadata.publicKeyCose.fill(0)
                        }
                    }
                    else -> entity.rpId // Legacy pre-v5 rows stored the clear RP ID directly.
                }
                privateKey = PasskeyPrivateKeyCipher.decrypt(
                    PasskeyPrivateKeyCipher.EncryptedPrivateKey(entity.iv, entity.encryptedPrivateKey),
                    vaultKey, entity.id, rpId
                )
                // The current WebAuthn registration engine creates ES256 P-256 PKCS#8 keys.
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKey))
                require(entity.credentialId.isNotEmpty())
                require(entity.signCount >= 0L)
            } catch (_: Exception) {
                passkeyFailures++
            } finally {
                privateKey?.fill(0)
            }
        }

        return VaultCryptographicIntegrityReport(
            vaultRecordsChecked = records.size,
            vaultRecordsFailed = recordFailures,
            passkeysChecked = passkeys.size,
            passkeysFailed = passkeyFailures,
            otpEntriesChecked = otpChecked,
            otpEntriesFailed = otpFailures
        )
    }
}
