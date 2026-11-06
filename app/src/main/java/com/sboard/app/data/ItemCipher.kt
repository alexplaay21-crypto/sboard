package com.sboard.app.data

import com.sboard.app.core.crypto.AesGcm
import com.sboard.app.core.crypto.Sha256
import com.sboard.app.core.crypto.toHex

/**
 * Item encryption: AES-256-GCM with the Vault key. The associated data binds the ciphertext to
 * its id, modification time and deletion flag, so the clear sync metadata cannot be swapped or
 * replayed onto a different item without failing authentication.
 */
object ItemCipher {
    private const val VERSION = "sboard.item.v2"

    fun aad(id: String, updatedAt: Long, deleted: Boolean): ByteArray =
        "$VERSION|$id|$updatedAt|${if (deleted) 1 else 0}".toByteArray(Charsets.UTF_8)

    fun contentHash(iv: ByteArray, ciphertext: ByteArray): String = Sha256.of(iv + ciphertext).toHex()

    fun seal(
        key: ByteArray, id: String, createdAt: Long, updatedAt: Long, deleted: Boolean,
        modifiedBy: String, payload: VaultItemPayload?, baseHash: String?,
    ): VaultItemEntity {
        val plain = if (deleted || payload == null) "{}".toByteArray() else VaultItemPayload.encode(payload)
        try {
            val sealed = AesGcm.encrypt(key, plain, aad(id, updatedAt, deleted))
            return VaultItemEntity(
                id = id, iv = sealed.iv, ciphertext = sealed.ciphertext,
                createdAt = createdAt, updatedAt = updatedAt, deleted = deleted,
                modifiedBy = modifiedBy, contentHash = contentHash(sealed.iv, sealed.ciphertext),
                baseHash = baseHash,
            )
        } finally {
            plain.fill(0)
        }
    }

    /** Verifies authenticity and returns the payload (null for an authentic tombstone). */
    fun open(key: ByteArray, entity: VaultItemEntity): VaultItemPayload? {
        val plain = AesGcm.decrypt(key, entity.iv, entity.ciphertext, aad(entity.id, entity.updatedAt, entity.deleted))
        try {
            return if (entity.deleted) null else VaultItemPayload.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    /** History snapshots use their own AAD so they can never be confused with live items. */
    fun sealHistory(key: ByteArray, itemId: String, payload: VaultItemPayload): AesGcm.Sealed {
        val plain = VaultItemPayload.encode(payload)
        try { return AesGcm.encrypt(key, plain, "sboard.history.v1|$itemId".toByteArray()) } finally { plain.fill(0) }
    }

    fun openHistory(key: ByteArray, itemId: String, iv: ByteArray, ciphertext: ByteArray): VaultItemPayload {
        val plain = AesGcm.decrypt(key, iv, ciphertext, "sboard.history.v1|$itemId".toByteArray())
        try { return VaultItemPayload.decode(plain) } finally { plain.fill(0) }
    }
}
