package com.sboard.app.feature.passkeys

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Vault-key encrypted metadata that can be restored on any device with the same Vault Key. */
internal object PasskeyPortableMetadataCipher {
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val MAX_CIPHERTEXT_BYTES = 400 * 1024

    data class Encrypted(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(recordId: String, metadata: PasskeyMetadataCipher.Metadata, vaultKey: ByteArray): Encrypted {
        require(vaultKey.size == 32) { "Vault Key must be 256 bits" }
        val plain = encode(metadata)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(vaultKey, "AES"))
            cipher.updateAAD(aad(recordId))
            return Encrypted(cipher.iv, cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(recordId: String, encrypted: Encrypted, vaultKey: ByteArray): PasskeyMetadataCipher.Metadata {
        require(vaultKey.size == 32) { "Vault Key must be 256 bits" }
        require(encrypted.iv.size == 12) { "Invalid metadata IV" }
        require(encrypted.ciphertext.size in 16..MAX_CIPHERTEXT_BYTES) { "Invalid metadata ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, encrypted.iv))
        cipher.updateAAD(aad(recordId))
        val plain = cipher.doFinal(encrypted.ciphertext)
        return try { decode(plain) } finally { plain.fill(0) }
    }

    private fun aad(recordId: String): ByteArray =
        "sboard-passkey-portable-metadata-v1\u0000$recordId".toByteArray(Charsets.UTF_8)

    private fun encode(value: PasskeyMetadataCipher.Metadata): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeField(value.rpId.toByteArray(Charsets.UTF_8))
            out.writeField(value.rpName.toByteArray(Charsets.UTF_8))
            out.writeField(value.userName.toByteArray(Charsets.UTF_8))
            out.writeField(value.displayName.toByteArray(Charsets.UTF_8))
            out.writeField(value.userHandle)
            out.writeField(value.publicKeyCose)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): PasskeyMetadataCipher.Metadata =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported portable metadata version" }
            fun readUtf8() = input.readField().toString(Charsets.UTF_8)
            val metadata = PasskeyMetadataCipher.Metadata(
                readUtf8(), readUtf8(), readUtf8(), readUtf8(), input.readField(), input.readField()
            )
            require(input.available() == 0) { "Trailing portable metadata bytes" }
            metadata
        }

    private fun DataOutputStream.writeField(value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES) { "Metadata field too large" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readField(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES && size <= available()) { "Invalid metadata field" }
        return ByteArray(size).also(::readFully)
    }
}
