package com.sboard.app.feature.sync

import com.sboard.app.core.crypto.AesGcm
import com.sboard.app.core.crypto.Hkdf
import com.sboard.app.core.crypto.Sha256
import com.sboard.app.core.crypto.constantTimeEquals
import com.sboard.app.feature.vault.KeyWrap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Clear (but authenticated) header: everything a new device needs to ask for the master password. */
@Serializable
data class BackupHeader(
    val format: Int = BackupFormat.FORMAT_VERSION,
    val vaultId: String,
    val generation: Long,
    val writtenAt: Long,
    val writerDeviceId: String,
    val keyUpdatedAt: Long,
    val keyWraps: List<KeyWrap>,
    val app: String = "sBoard",
)

@Serializable
data class SyncRecord(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val modifiedBy: String,
    val iv: String,
    val ct: String,
)

@Serializable
data class SyncDevice(
    val id: String,
    val name: String,
    val lastSyncAt: Long,
    val revoked: Boolean = false,
    val platform: String = "android",
)

/** Encrypted body: every item is additionally encrypted individually with the Vault key. */
@Serializable
data class BackupBody(
    val items: List<SyncRecord>,
    val devices: List<SyncDevice> = emptyList(),
)

class BackupCorruptedException(message: String) : IllegalStateException(message)
class BackupAuthenticationException(message: String) : IllegalStateException(message)

/**
 * `sboard_backup.enc` layout (all integers big-endian):
 *
 *   "SBOARDv2" | u32 headerLen | header JSON | u32 ivLen | iv | u32 bodyLen | body | SHA-256(previous bytes)
 *
 * body = AES-256-GCM(HKDF(vaultKey, "sboard/backup-body/v2"), gzip(BackupBody JSON), AAD = magic ‖ header).
 * The trailing SHA-256 detects truncation/corruption before any decryption; the GCM tag authenticates
 * both header and body, so key wraps or device lists cannot be altered without the Vault key.
 */
object BackupFormat {
    const val FILE_NAME = "sboard_backup.enc"
    const val FORMAT_VERSION = 2
    private val MAGIC = "SBOARDv2".toByteArray(Charsets.US_ASCII)
    private const val MAX_SECTION = 64 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    class Parsed(val header: BackupHeader, internal val headerBytes: ByteArray, internal val iv: ByteArray, internal val body: ByteArray)

    fun bodyKey(vaultKey: ByteArray): ByteArray = Hkdf.derive(vaultKey, "sboard/backup-body/v2")

    fun encode(header: BackupHeader, body: BackupBody, vaultKey: ByteArray): ByteArray {
        val headerBytes = json.encodeToString(BackupHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        val plain = gzip(json.encodeToString(BackupBody.serializer(), body).toByteArray(Charsets.UTF_8))
        val key = bodyKey(vaultKey)
        val sealed = try { AesGcm.encrypt(key, plain, MAGIC + headerBytes) } finally { key.fill(0); plain.fill(0) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC)
            data.writeInt(headerBytes.size); data.write(headerBytes)
            data.writeInt(sealed.iv.size); data.write(sealed.iv)
            data.writeInt(sealed.ciphertext.size); data.write(sealed.ciphertext)
        }
        val content = out.toByteArray()
        return content + Sha256.of(content)
    }

    /** Parses and integrity-checks the container. Does not need any key. */
    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < MAGIC.size + 12 + 32) throw BackupCorruptedException("Backup file is truncated")
        val content = bytes.copyOfRange(0, bytes.size - 32)
        val digest = bytes.copyOfRange(bytes.size - 32, bytes.size)
        if (!constantTimeEquals(Sha256.of(content), digest)) throw BackupCorruptedException("Backup checksum mismatch")
        DataInputStream(ByteArrayInputStream(content)).use { data ->
            val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw BackupCorruptedException("Not an sBoard v2 backup")
            val headerBytes = readSection(data)
            val iv = readSection(data)
            val body = readSection(data)
            if (data.available() != 0) throw BackupCorruptedException("Unexpected trailing data")
            if (iv.size != AesGcm.IV_BYTES) throw BackupCorruptedException("Invalid IV")
            val header = try {
                json.decodeFromString(BackupHeader.serializer(), headerBytes.toString(Charsets.UTF_8))
            } catch (e: Exception) { throw BackupCorruptedException("Invalid header") }
            if (header.format > FORMAT_VERSION) throw BackupCorruptedException("Backup was created by a newer sBoard version")
            return Parsed(header, headerBytes, iv, body)
        }
    }

    fun decryptBody(parsed: Parsed, vaultKey: ByteArray): BackupBody {
        val key = bodyKey(vaultKey)
        val plain = try {
            AesGcm.decrypt(key, parsed.iv, parsed.body, MAGIC + parsed.headerBytes)
        } catch (e: Exception) {
            throw BackupAuthenticationException("Backup cannot be authenticated with this Vault key")
        } finally { key.fill(0) }
        try {
            return json.decodeFromString(BackupBody.serializer(), gunzip(plain).toString(Charsets.UTF_8))
        } finally { plain.fill(0) }
    }

    private fun readSection(data: DataInputStream): ByteArray {
        val size = data.readInt()
        if (size < 0 || size > MAX_SECTION || size > data.available()) throw BackupCorruptedException("Invalid section length")
        return ByteArray(size).also { data.readFully(it) }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
