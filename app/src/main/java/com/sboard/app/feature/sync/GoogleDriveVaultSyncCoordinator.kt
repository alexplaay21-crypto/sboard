package com.sboard.app.feature.sync

import android.content.Context
import com.sboard.app.feature.vault.VaultDatabase
import com.sboard.app.feature.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Exchanges encrypted record changes with the user's Google Drive appDataFolder.
 * Outgoing journal entries are compacted to the newest pending state per record.
 * Incoming changes are authenticated and stored encrypted in sync_inbox for a later
 * explicit conflict-resolution/apply workflow. This coordinator intentionally does not
 * overwrite local Vault records automatically.
 */
class GoogleDriveVaultSyncCoordinator(
    context: Context,
    private val session: VaultSession,
    private val transport: GoogleDriveSyncTransport = GoogleDriveSyncTransport()
) {
    private val database = VaultDatabase.get(context.applicationContext)
    private val records = database.records()
    private val inbox = database.syncInbox()
    private val identity = DeviceIdentityStore(context.applicationContext)

    data class Result(
        val uploaded: Int,
        val superseded: Int,
        val stagedIncoming: Int,
        val ignoredOwnChanges: Int,
        val alreadyKnownChanges: Int,
        val alreadyStaged: Int,
        val pendingReview: Int
    )

    suspend fun synchronize(accessToken: String, maxOutgoing: Int = 500): Result =
        withContext(Dispatchers.IO) {
            require(maxOutgoing in 1..5000)
            val key = session.copyKey()
            try {
                var uploaded = 0
                var superseded = 0
                var staged = 0
                var own = 0
                var known = 0
                var duplicate = 0

                val selection = SyncExchangePolicy.selectLatestPending(
                    records.getPendingSyncJournal(limit = 5000), maxOutgoing
                )
                selection.supersededIds.forEach { records.setSyncDeliveryState(it, "SUPERSEDED"); superseded++ }

                for (journal in selection.selected) {
                    val row = if (journal.operation == "UPSERT") records.getById(journal.recordId) else null
                    if (journal.operation == "UPSERT" && row == null) {
                        // A later deletion may have raced with this snapshot; leave it pending for next pass.
                        continue
                    }
                    val cached = journal.encryptedEnvelope
                    val envelope = if (cached != null) {
                        cached.copyOf()
                    } else {
                        val blob = row?.let(::packEncryptedRow)
                        val operation = when (journal.operation) {
                            "UPSERT" -> EncryptedSyncEnvelope.Operation.UPSERT
                            "DELETE" -> EncryptedSyncEnvelope.Operation.DELETE
                            else -> throw IOException("Unknown local sync operation")
                        }
                        val change = EncryptedSyncEnvelope.Change(
                            changeId = journal.changeId,
                            recordId = journal.recordId,
                            operation = operation,
                            deviceId = journal.deviceId,
                            revision = journal.revision,
                            changedAt = journal.changedAt,
                            encryptedRecord = blob
                        )
                        val candidate = try { EncryptedSyncEnvelope.encode(change, key) } finally { blob?.fill(0) }
                        try {
                            records.cacheSyncEnvelopeIfAbsent(journal.changeId, candidate)
                            val persisted = records.getSyncChange(journal.changeId)?.encryptedEnvelope
                            (persisted ?: candidate).copyOf()
                        } finally {
                            candidate.fill(0)
                        }
                    }
                    try {
                        transport.uploadChange(journal.changeId, envelope, accessToken)
                        records.setSyncDeliveryState(journal.changeId, "SENT")
                        uploaded++
                    } finally {
                        envelope.fill(0)
                    }
                }

                val remoteChanges = transport.listChanges(accessToken)
                for (remote in remoteChanges) {
                    val filenameChangeId = GoogleDriveSyncPolicy.changeIdFromFileName(remote.name) ?: continue
                    val envelope = transport.downloadChange(remote, accessToken)
                    try {
                        val decoded = EncryptedSyncEnvelope.decode(envelope, key)
                        if (decoded.changeId != filenameChangeId) throw IOException("Sync filename and authenticated change ID do not match")
                        val knownLocally = records.getSyncChange(decoded.changeId) != null
                        val shouldStage = SyncExchangePolicy.shouldStageIncoming(
                            decoded,
                            identity.deviceId(),
                            if (knownLocally) setOf(decoded.changeId) else emptySet()
                        )
                        if (!shouldStage) {
                            decoded.encryptedRecord?.fill(0)
                            if (knownLocally) known++ else own++
                            continue
                        }
                        val inserted = inbox.insertIfAbsent(
                            SyncInboxEntity(
                                changeId = decoded.changeId,
                                recordId = decoded.recordId,
                                operation = decoded.operation.name,
                                deviceId = decoded.deviceId,
                                revision = decoded.revision,
                                changedAt = decoded.changedAt,
                                encryptedEnvelope = envelope.copyOf(),
                                receivedAt = System.currentTimeMillis()
                            )
                        )
                        if (inserted == -1L) duplicate++ else staged++
                        decoded.encryptedRecord?.fill(0)
                    } finally {
                        envelope.fill(0)
                    }
                }
                Result(uploaded, superseded, staged, own, known, duplicate, inbox.pendingCount())
            } finally {
                key.fill(0)
            }
        }

    private fun packEncryptedRow(row: com.sboard.app.feature.vault.VaultRecordEntity): ByteArray {
        val out = ByteArrayOutputStream(row.iv.size + row.ciphertext.size + 8)
        DataOutputStream(out).use { data ->
            data.write(row.iv)
            data.write(row.ciphertext)
        }
        return out.toByteArray().also {
            if (it.size > 3 * 1024 * 1024) { it.fill(0); throw IOException("Vault record exceeds sync limit") }
        }
    }
}
