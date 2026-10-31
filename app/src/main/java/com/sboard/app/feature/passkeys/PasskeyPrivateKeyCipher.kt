package com.sboard.app.feature.passkeys

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps a passkey PKCS#8 private key with the currently unlocked 256-bit Vault Key.
 * The RP ID and record ID are authenticated as AAD, preventing ciphertext swapping.
 * Callers must wipe decryptedPrivateKey after signing and must never log/export it.
 */
object PasskeyPrivateKeyCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    data class EncryptedPrivateKey(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(
        privateKeyPkcs8: ByteArray,
        vaultKey: ByteArray,
        recordId: String,
        rpId: String
    ): EncryptedPrivateKey {
        require(privateKeyPkcs8.isNotEmpty()) { "Private key is empty" }
        val key = requireVaultKey(vaultKey)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(recordId, rpId))
        return EncryptedPrivateKey(cipher.iv, cipher.doFinal(privateKeyPkcs8))
    }

    fun decrypt(
        encrypted: EncryptedPrivateKey,
        vaultKey: ByteArray,
        recordId: String,
        rpId: String
    ): ByteArray {
        require(encrypted.iv.size == 12) { "Invalid GCM IV" }
        val key = requireVaultKey(vaultKey)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.iv))
        cipher.updateAAD(aad(recordId, rpId))
        return cipher.doFinal(encrypted.ciphertext)
    }

    private fun requireVaultKey(bytes: ByteArray): SecretKeySpec {
        require(bytes.size == 32) { "Vault Key must be 256 bits" }
        return SecretKeySpec(bytes, "AES")
    }

    private fun aad(recordId: String, rpId: String): ByteArray =
        "sboard-passkey-v1\u0000$recordId\u0000${rpId.lowercase()}".toByteArray(Charsets.UTF_8)
}
