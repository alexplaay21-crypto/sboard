package com.sboard.app.feature.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Non-secret local change journal. It stores identifiers and revision metadata only;
 * Vault values, passwords, passkey private keys and recovery material never enter this table.
 * The journal is groundwork for safe multi-device reconciliation, not a sync transport.
 */
@Entity(
    tableName = "sync_change_journal",
    indices = [Index(value = ["recordId", "revision"]), Index(value = ["deviceId", "revision"]), Index(value = ["deliveryState"])]
)
data class SyncChangeEntity(
    @PrimaryKey val changeId: String,
    val recordId: String,
    /** UPSERT or DELETE. */
    val operation: String,
    val deviceId: String,
    /** Monotonic per-device Lamport counter. */
    val revision: Long,
    val changedAt: Long,
    /** PENDING, SENT or SUPERSEDED. */
    @ColumnInfo(defaultValue = "'PENDING'") val deliveryState: String = "PENDING",
    /** Cached randomized envelope makes retries byte-for-byte idempotent after process death. */
    val encryptedEnvelope: ByteArray? = null
)


/** Incoming encrypted changes are staged here; plaintext Vault fields are never persisted. */
@Entity(tableName = "sync_inbox", indices = [Index(value = ["deviceId", "revision"])])
data class SyncInboxEntity(
    @PrimaryKey val changeId: String,
    val recordId: String,
    val operation: String,
    val deviceId: String,
    val revision: Long,
    val changedAt: Long,
    val encryptedEnvelope: ByteArray,
    val receivedAt: Long,
    /** PENDING_REVIEW until an explicit conflict/apply workflow is completed. */
    val state: String = "PENDING_REVIEW"
)

@Dao
interface SyncInboxDao {
    @androidx.room.Query("SELECT * FROM sync_inbox WHERE changeId = :changeId LIMIT 1")
    suspend fun getById(changeId: String): SyncInboxEntity?

    @androidx.room.Query("UPDATE sync_inbox SET state = :state WHERE changeId = :changeId AND state = 'PENDING_REVIEW'")
    suspend fun markResolved(changeId: String, state: String): Int

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: SyncInboxEntity): Long

    @androidx.room.Query("SELECT * FROM sync_inbox WHERE state = 'PENDING_REVIEW' ORDER BY receivedAt ASC LIMIT :limit")
    suspend fun pendingReview(limit: Int = 500): List<SyncInboxEntity>

    @androidx.room.Query("SELECT COUNT(*) FROM sync_inbox WHERE state = 'PENDING_REVIEW'")
    suspend fun pendingCount(): Int
}
