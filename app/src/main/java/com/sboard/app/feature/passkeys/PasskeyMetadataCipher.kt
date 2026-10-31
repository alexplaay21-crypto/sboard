package com.sboard.app.feature.passkeys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts display metadata with non-exportable Android Keystore keys while the Vault is locked.
 * This key is deliberately separate from the Vault key: discovery can decrypt metadata, never
 * private-key material. Private keys remain protected by PasskeyPrivateKeyCipher + VaultSession.
 */
internal object PasskeyMetadataCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "sboard.passkey.metadata.aes.v1"
    private const val HMAC_ALIAS = "sboard.passkey.metadata.index.v1"
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val MAX_ENCRYPTED_METADATA_BYTES = 400 * 1024

    data class Encrypted(val iv: ByteArray, val ciphertext: ByteArray)
    data class Metadata(
        val rpId: String,
        val rpName: String,
        val userName: String,
        val displayName: String,
        val userHandle: ByteArray,
        val publicKeyCose: ByteArray
    )

    fun indexForRpId(rpId: String): String {
        val normalized = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rpId))
        val mac = Mac.getInstance("HmacSHA256", ANDROID_KEYSTORE)
        mac.init(getOrCreateHmacKey())
        return mac.doFinal(normalized.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    fun encrypt(recordId: String, metadata: Metadata): Encrypted {
        val plain = encode(metadata)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
            cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
            return Encrypted(cipher.iv, cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(recordId: String, encrypted: Encrypted): Metadata {
        require(encrypted.iv.size == 12) { "Invalid metadata GCM IV" }
        require(encrypted.ciphertext.size in 16..MAX_ENCRYPTED_METADATA_BYTES) {
            "Invalid or oversized encrypted metadata"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(encrypted.ciphertext)
        return try { decode(plain) } finally { plain.fill(0) }
    }

    @Synchronized
    private fun getOrCreateAesKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    @Synchronized
    private fun getOrCreateHmacKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build())
            generateKey()
        }
    }

    private fun encode(value: Metadata): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeField(value.rpId.toByteArray(Charsets.UTF_8))
            out.writeField(value.rpName.toByteArray(Charsets.UTF_8))
            out.writeField(value.userName.toByteArray(Charsets.UTF_8))
            out.writeField(value.displayName.toByteArray(Charsets.UTF_8))
            out.writeField(value.userHandle)
            out.writeField(value.publicKeyCose)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): Metadata = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1) { "Unsupported Passkey metadata version" }
        fun readUtf8() = input.readField().toString(Charsets.UTF_8)
        val result = Metadata(readUtf8(), readUtf8(), readUtf8(), readUtf8(), input.readField(), input.readField())
        require(input.available() == 0) { "Trailing Passkey metadata bytes" }
        result
    }

    private fun DataOutputStream.writeField(value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES) { "Passkey metadata field too large" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readField(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES && size <= available()) { "Invalid Passkey metadata field" }
        return ByteArray(size).also(::readFully)
    }
}
