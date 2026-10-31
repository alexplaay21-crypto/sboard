package com.sboard.app.feature.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, authenticated transport envelope for one Vault change.
 * The optional record blob is the existing VaultRecordEntity payload (IV + ciphertext),
 * which is already encrypted at rest with the shared Vault key. Journal metadata is encrypted too.
 * This class deliberately has no Drive/network dependency.
 */
object EncryptedSyncEnvelope {
    private val MAGIC = byteArrayOf(0x53, 0x42, 0x53, 0x59, 0x4E, 0x43, 0x01, 0x00) // SBSYNC v1
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024
    private const val MAX_ID_BYTES = 256
    private const val MAX_RECORD_BLOB_BYTES = 3 * 1024 * 1024
    private const val KDF_CONTEXT = "sBoard multi-device sync envelope key v1"
    private val random = SecureRandom()

    enum class Operation { UPSERT, DELETE }

    data class Change(
        val changeId: String,
        val recordId: String,
        val operation: Operation,
        val deviceId: String,
        val revision: Long,
        val changedAt: Long,
        /** Opaque row data: 12-byte at-rest IV followed by encrypted row ciphertext. */
        val encryptedRecord: ByteArray? = null
    )

    fun encode(change: Change, vaultKey: ByteArray): ByteArray {
        validate(change)
        require(vaultKey.size == 32) { "Vault key must be 256-bit" }
        val plaintext = serialize(change)
        require(plaintext.size <= MAX_RECORD_BLOB_BYTES + 2_000) { "Sync change is too large" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val derivedKey = deriveKey(vaultKey)
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad())
            cipher.doFinal(plaintext)
        } finally {
            derivedKey.fill(0)
            plaintext.fill(0)
        }
        val output = ByteArrayOutputStream(MAGIC.size + 4 + NONCE_BYTES + 4 + ciphertext.size)
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(VERSION)
            data.write(nonce)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
        }
        nonce.fill(0)
        ciphertext.fill(0)
        return output.toByteArray().also { require(it.size <= MAX_ENVELOPE_BYTES) }
    }

    @Throws(IOException::class, javax.crypto.AEADBadTagException::class)
    fun decode(envelope: ByteArray, vaultKey: ByteArray): Change {
        require(vaultKey.size == 32) { "Vault key must be 256-bit" }
        if (envelope.size !in (MAGIC.size + 4 + NONCE_BYTES + 4 + 16)..MAX_ENVELOPE_BYTES) {
            throw IOException("Invalid sync envelope size")
        }
        val input = DataInputStream(ByteArrayInputStream(envelope))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Unknown sync envelope format")
        if (input.readInt() != VERSION) throw IOException("Unsupported sync envelope version")
        val nonce = ByteArray(NONCE_BYTES)
        input.readFully(nonce)
        val ciphertextLength = input.readInt()
        if (ciphertextLength < 16 || ciphertextLength > MAX_ENVELOPE_BYTES || ciphertextLength != input.available()) {
            throw IOException("Invalid sync ciphertext length")
        }
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)
        val derivedKey = deriveKey(vaultKey)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad())
            cipher.doFinal(ciphertext)
        } finally {
            derivedKey.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
        return try {
            deserialize(plaintext).also(::validate)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun serialize(change: Change): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            writeString(out, change.changeId)
            writeString(out, change.recordId)
            out.writeByte(change.operation.ordinal)
            writeString(out, change.deviceId)
            out.writeLong(change.revision)
            out.writeLong(change.changedAt)
            val blob = change.encryptedRecord ?: ByteArray(0)
            out.writeInt(blob.size)
            out.write(blob)
        }
        return bytes.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): Change {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val changeId = readString(input)
        val recordId = readString(input)
        val operationOrdinal = input.readUnsignedByte()
        val operation = Operation.values().getOrNull(operationOrdinal) ?: throw IOException("Unknown sync operation")
        val deviceId = readString(input)
        val revision = input.readLong()
        val changedAt = input.readLong()
        val blobLength = input.readInt()
        if (blobLength < 0 || blobLength > MAX_RECORD_BLOB_BYTES || blobLength > input.available()) {
            throw IOException("Invalid encrypted record length")
        }
        val blob = if (blobLength == 0) null else ByteArray(blobLength).also(input::readFully)
        if (input.available() != 0) throw IOException("Trailing sync payload data")
        return Change(changeId, recordId, operation, deviceId, revision, changedAt, blob)
    }

    private fun validate(change: Change) {
        validateId(change.changeId, "changeId")
        validateId(change.recordId, "recordId")
        validateId(change.deviceId, "deviceId")
        require(change.revision > 0L) { "Revision must be positive" }
        require(change.changedAt >= 0L) { "Timestamp cannot be negative" }
        when (change.operation) {
            Operation.UPSERT -> {
                val blob = requireNotNull(change.encryptedRecord) { "UPSERT requires encrypted record data" }
                require(blob.size in 28..MAX_RECORD_BLOB_BYTES) { "Invalid encrypted record payload" }
                // The row IV is 12 bytes and the AES-GCM ciphertext includes at least a 16-byte tag.
            }
            Operation.DELETE -> require(change.encryptedRecord == null || change.encryptedRecord.isEmpty()) {
                "DELETE must not contain record data"
            }
        }
    }

    private fun validateId(value: String, field: String) {
        require(value.isNotBlank() && !value.contains('\u0000') && value.toByteArray(StandardCharsets.UTF_8).size <= MAX_ID_BYTES) {
            "Invalid $field"
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_ID_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 1..MAX_ID_BYTES || size > input.available()) throw IOException("Invalid sync text field")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8).also {
            if (it.isBlank() || it.contains('\u0000')) throw IOException("Invalid sync text field")
        }
    }

    private fun deriveKey(vaultKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(vaultKey, "HmacSHA256"))
        return mac.doFinal(KDF_CONTEXT.toByteArray(StandardCharsets.UTF_8))
    }

    private fun aad(): ByteArray = MAGIC + byteArrayOf(0, 0, 0, VERSION.toByte())
}
