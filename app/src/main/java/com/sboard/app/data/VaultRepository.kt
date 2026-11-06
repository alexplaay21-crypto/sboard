package com.sboard.app.data

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.core.security.VaultSession
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.feature.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.UUID

data class HistoryEntry(val historyId: Long, val savedAt: Long, val reason: String, val payload: VaultItemPayload)

/** Decrypted view of the Vault. Plaintext exists only in memory while the session is unlocked. */
class VaultRepository private constructor(
    private val appContext: Context,
    private val session: VaultSession,
) {
    private val database = VaultDatabase.get(appContext)
    private val dao = database.items()
    private val historyDao = database.history()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _corruptedCount = MutableStateFlow(0)
    val corruptedCount: StateFlow<Int> = _corruptedCount.asStateFlow()

    /** All live items, decrypted. Empty while locked. */
    val items: StateFlow<List<VaultItem>> = combine(dao.observeAll(), session.unlocked) { rows, unlocked ->
        if (!unlocked) emptyList() else decryptAll(rows)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pendingSyncCount: Flow<Int> = dao.observePendingCount()

    private fun decryptAll(rows: List<VaultItemEntity>): List<VaultItem> {
        if (!session.isUnlocked) return emptyList()
        var failures = 0
        val result = runCatching {
            session.withKey { key ->
                rows.asSequence().filter { !it.deleted }.mapNotNull { row ->
                    runCatching { ItemCipher.open(key, row)?.let { VaultItem(row.id, row.createdAt, row.updatedAt, it) } }
                        .getOrElse { failures++; null }
                }.sortedWith(compareByDescending<VaultItem> { it.favorite }.thenBy { it.title.lowercase() }).toList()
            }
        }.getOrDefault(emptyList())
        _corruptedCount.value = failures
        return result
    }

    /** Fresh decrypted snapshot straight from the database (no dependency on flow timing). */
    suspend fun snapshot(): List<VaultItem> = withContext(Dispatchers.IO) {
        if (!session.isUnlocked) emptyList() else decryptAll(dao.getAll())
    }

    suspend fun get(id: String): VaultItem? = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: return@withContext null
        if (row.deleted) return@withContext null
        session.withKey { key -> ItemCipher.open(key, row)?.let { VaultItem(row.id, row.createdAt, row.updatedAt, it) } }
    }

    /** Creates or updates an item; the previous version is kept in local history. Returns the id. */
    suspend fun save(id: String?, payload: VaultItemPayload, historyReason: String = "edit", snapshot: Boolean = true): String = withContext(Dispatchers.IO) {
        val normalized = payload.normalized()
        require(normalized.title.isNotBlank()) { "Title required" }
        val itemId = id ?: UUID.randomUUID().toString()
        val deviceId = DeviceIdentity.id(appContext)
        session.withKey { key ->
            database.withTransaction {
                val existing = dao.get(itemId)
                val now = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0L) + 1)
                if (existing != null && !existing.deleted) {
                    val previous = runCatching { ItemCipher.open(key, existing) }.getOrNull()
                    if (previous != null && previous != normalized) {
                        // Only snapshot meaningful edits; favorite toggles are not history.
                        if (snapshot && previous.copy(favorite = normalized.favorite) != normalized) saveHistory(key, itemId, previous, existing.updatedAt, historyReason)
                    } else if (previous == normalized) return@withTransaction
                }
                dao.upsert(
                    ItemCipher.seal(key, itemId, existing?.createdAt ?: now, now, false, deviceId, normalized, existing?.baseHash)
                )
            }
        }
        SyncScheduler.requestSoon(appContext)
        itemId
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val item = get(id) ?: return
        save(id, item.payload.copy(favorite = favorite))
    }

    /** Deletes an item by writing an authenticated tombstone so that sync propagates the deletion. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val deviceId = DeviceIdentity.id(appContext)
        session.withKey { key ->
            database.withTransaction {
                val existing = dao.get(id) ?: return@withTransaction
                if (existing.deleted) return@withTransaction
                runCatching { ItemCipher.open(key, existing) }.getOrNull()?.let {
                    saveHistory(key, id, it, existing.updatedAt, "delete")
                }
                val now = maxOf(System.currentTimeMillis(), existing.updatedAt + 1)
                dao.upsert(ItemCipher.seal(key, id, existing.createdAt, now, true, deviceId, null, existing.baseHash))
            }
        }
        SyncScheduler.requestSoon(appContext)
    }

    /** Restores a deleted or older version from history as the newest version. */
    suspend fun restoreFromHistory(historyId: Long): String? = withContext(Dispatchers.IO) {
        val entry = historyDao.get(historyId) ?: return@withContext null
        val payload = session.withKey { key -> ItemCipher.openHistory(key, entry.itemId, entry.iv, entry.ciphertext) }
        save(entry.itemId, payload, historyReason = "restore")
    }

    suspend fun history(itemId: String): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val rows = historyDao.forItem(itemId)
        session.withKey { key ->
            rows.mapNotNull { row ->
                runCatching { HistoryEntry(row.historyId, row.savedAt, row.reason, ItemCipher.openHistory(key, row.itemId, row.iv, row.ciphertext)) }.getOrNull()
            }
        }
    }

    /** Recently deleted items that can still be restored from this device's history. */
    suspend fun recentlyDeleted(): List<Pair<String, HistoryEntry>> = withContext(Dispatchers.IO) {
        val tombstones = dao.getAll().filter { it.deleted }
        session.withKey { key ->
            tombstones.mapNotNull { row ->
                val last = historyDao.forItem(row.id).firstOrNull() ?: return@mapNotNull null
                runCatching {
                    row.id to HistoryEntry(last.historyId, last.savedAt, last.reason, ItemCipher.openHistory(key, row.id, last.iv, last.ciphertext))
                }.getOrNull()
            }
        }
    }

    internal suspend fun saveHistory(key: ByteArray, itemId: String, payload: VaultItemPayload, itemUpdatedAt: Long, reason: String) {
        val sealed = ItemCipher.sealHistory(key, itemId, payload)
        historyDao.insert(ItemHistoryEntity(itemId = itemId, iv = sealed.iv, ciphertext = sealed.ciphertext,
            itemUpdatedAt = itemUpdatedAt, savedAt = System.currentTimeMillis(), reason = reason))
        historyDao.trim(itemId, MAX_HISTORY_PER_ITEM)
    }

    companion object {
        const val MAX_HISTORY_PER_ITEM = 15

        @Volatile private var instance: VaultRepository? = null

        fun get(context: Context): VaultRepository = instance ?: synchronized(this) {
            instance ?: VaultRepository(context.applicationContext, VaultSessionProvider.session).also { instance = it }
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTests() = synchronized(this) { instance?.scope?.cancel(); instance = null }
    }
}
