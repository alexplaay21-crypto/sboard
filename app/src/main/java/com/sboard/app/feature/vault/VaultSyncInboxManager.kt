package com.sboard.app.feature.vault

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.feature.sync.DeviceIdentityStore
import com.sboard.app.feature.sync.EncryptedSyncEnvelope
import com.sboard.app.feature.sync.SyncChangeEntity
import com.sboard.app.feature.sync.SyncInboxEntity
import com.sboard.app.feature.sync.SyncInboxResolutionPolicy
import java.io.IOException
import java.util.UUID

/**
 * Resolves authenticated changes staged by GoogleDriveVaultSyncCoordinator.
 * Incoming values are never applied merely because they arrived from Drive: the user
 * must explicitly choose the remote or local version for a conflicting record.
 */
class VaultSyncInboxManager(
    context: Context,
    private val session: VaultSession
) {
    private val database = VaultDatabase.get(context.applicationContext)
    private val records = database.records()
    private val inbox = database.syncInbox()
    private val identity = DeviceIdentityStore(context.applicationContext)

    data class PendingReview(
        val changeId: String,
        val recordId: String,
        val operation: String,
        val deviceId: String,
        val revision: Long,
        val changedAt: Long,
        val receivedAt: Long
    )

    enum class Decision { ACCEPT_REMOTE, KEEP_LOCAL }

    suspend fun pendingReviews(limit: Int = 200): List<PendingReview> =
        inbox.pendingReview(limit.coerceIn(1, 1000)).map { item ->
            try {
                PendingReview(item.changeId, item.recordId, item.operation, item.deviceId, item.revision, item.changedAt, item.receivedAt)
            } finally {
                item.encryptedEnvelope.fill(0)
            }
        }

    /**
     * Applies the selected side and publishes that choice as a fresh local journal event.
     * The fresh event ensures other devices can converge on the user's decision too.
     */
    suspend fun resolve(changeId: String, decision: Decision) {
        resolveInternal(changeId, decision, onlyWhenRecordAbsent = false)
    }

    /**
     * Automatically accepts only changes that cannot overwrite a local record.
     * Existing local records are always left for explicit user review.
     * The absence check is repeated inside the same Room transaction as the apply.
     */
    suspend fun applyNonConflicting(limit: Int = 200): Int {
        val candidates = inbox.pendingReview(limit.coerceIn(1, 500))
        var applied = 0
        for (candidate in candidates) {
            try {
                if (resolveInternal(candidate.changeId, Decision.ACCEPT_REMOTE, onlyWhenRecordAbsent = true)) {
                    applied++
                }
            } catch (_: IOException) {
                // Keep malformed, raced, or otherwise unsafe items in the review inbox.
            } finally {
                candidate.encryptedEnvelope.fill(0)
            }
        }
        return applied
    }

    private suspend fun resolveInternal(
        changeId: String,
        decision: Decision,
        onlyWhenRecordAbsent: Boolean
    ): Boolean {
        require(changeId.isNotBlank() && changeId.length <= 256)
        val staged = inbox.getById(changeId) ?: throw IOException("Sync review item was not found")
        if (staged.state != "PENDING_REVIEW") throw IOException("Sync review item was already resolved")

        val key = session.copyKey()
        try {
            val decoded = try {
                EncryptedSyncEnvelope.decode(staged.encryptedEnvelope, key)
            } finally {
                staged.encryptedEnvelope.fill(0)
            }
            try {
                validateStagedMetadata(staged, decoded)
                val remoteRecord = if (decoded.operation == EncryptedSyncEnvelope.Operation.UPSERT) {
                    decodeRemoteRecord(decoded.recordId, decoded.encryptedRecord, decoded.changedAt, key)
                } else null

                val resolved = database.withTransaction {
                    val currentInbox = inbox.getById(changeId)
                        ?: throw IOException("Sync review item disappeared")
                    if (currentInbox.state != "PENDING_REVIEW") {
                        currentInbox.encryptedEnvelope.fill(0)
                        throw IOException("Sync review item was already resolved")
                    }
                    currentInbox.encryptedEnvelope.fill(0)

                    val localRecord = records.getById(decoded.recordId)
                    if (onlyWhenRecordAbsent && localRecord != null) return@withTransaction false
                    if (decision == Decision.ACCEPT_REMOTE) {
                        when (decoded.operation) {
                            EncryptedSyncEnvelope.Operation.UPSERT -> records.insert(
                                requireNotNull(remoteRecord).copy(createdAt = localRecord?.createdAt ?: decoded.changedAt)
                            )
                            EncryptedSyncEnvelope.Operation.DELETE -> records.delete(decoded.recordId)
                        }
                    }

                    // Publish the chosen state as a new local event. Never echo the original
                    // remote change ID: the decision itself is a distinct causal event.
                    val finalRecord = records.getById(decoded.recordId)
                    val operation = SyncInboxResolutionPolicy.resultingOperation(finalRecord != null)
                    val localEvent = SyncChangeEntity(
                        changeId = UUID.randomUUID().toString(),
                        recordId = decoded.recordId,
                        operation = operation,
                        deviceId = identity.deviceId(),
                        revision = identity.nextRevision(decoded.revision),
                        changedAt = System.currentTimeMillis()
                    )
                    records.insertSyncChange(localEvent)
                    val state = SyncInboxResolutionPolicy.resolvedState(
                        if (decision == Decision.ACCEPT_REMOTE) SyncInboxResolutionPolicy.Choice.ACCEPT_REMOTE
                        else SyncInboxResolutionPolicy.Choice.KEEP_LOCAL
                    )
                    if (inbox.markResolved(changeId, state) != 1) {
                        throw IOException("Unable to mark sync review item as resolved")
                    }
                    true
                }
                return resolved
            } finally {
                decoded.encryptedRecord?.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    private fun validateStagedMetadata(staged: SyncInboxEntity, decoded: EncryptedSyncEnvelope.Change) {
        if (decoded.changeId != staged.changeId || decoded.recordId != staged.recordId ||
            decoded.deviceId != staged.deviceId || decoded.revision != staged.revision ||
            decoded.changedAt != staged.changedAt || decoded.operation.name != staged.operation) {
            throw IOException("Authenticated sync metadata does not match the staged item")
        }
    }

    private fun decodeRemoteRecord(
        recordId: String,
        blob: ByteArray?,
        changedAt: Long,
        key: ByteArray
    ): VaultRecordEntity {
        val payload = blob ?: throw IOException("Remote UPSERT has no encrypted record")
        if (payload.size !in 28..(3 * 1024 * 1024)) throw IOException("Invalid remote record payload")
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        // Authenticate the inner at-rest AES-GCM record before allowing it into Room.
        val plaintext = try {
            VaultAtRestCipher.decrypt(recordId, iv, ciphertext, key)
        } catch (error: Exception) {
            iv.fill(0)
            ciphertext.fill(0)
            throw IOException("Remote Vault record authentication failed", error)
        }
        plaintext.fill(0)
        return VaultRecordEntity(recordId, iv, ciphertext, changedAt, changedAt)
    }
}
