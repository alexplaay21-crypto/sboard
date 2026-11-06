package com.sboard.app.feature.sync

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultLockedException
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.DeviceIdentity
import com.sboard.app.data.ItemCipher
import com.sboard.app.data.ItemHistoryEntity
import com.sboard.app.data.VaultDatabase
import com.sboard.app.data.VaultItemEntity
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.feature.vault.VaultKeyManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Two-way synchronisation of the Vault with a single encrypted file (`sboard_backup.enc`) in the
 * user's Google Drive appDataFolder. Google only ever receives ciphertext.
 *
 * Every run: download → verify checksum + GCM authenticity → reconcile key wraps → three-way merge
 * → apply locally → upload in place (optimistic concurrency on the Drive file version, retried if
 * another device wrote meanwhile) → mark items as confirmed. Nothing is considered synchronised
 * until the upload response is received.
 */
class SyncEngine(context: Context) {
    private val app = context.applicationContext
    private val db = VaultDatabase.get(app)
    private val dao = db.items()
    private val keys = VaultKeyManager(app)
    private val session = VaultSessionProvider.session
    private val drive = DriveClient()

    sealed class Outcome {
        data class Success(val pulled: Int, val pushed: Int, val conflicts: Int) : Outcome()
        data object Locked : Outcome()
        data object NeedsAuth : Outcome()
        data class Retry(val message: String) : Outcome()
        data class Failure(val state: SyncState, val message: String) : Outcome()
    }

    class CloudVault(val file: DriveClient.RemoteFile, val parsed: BackupFormat.Parsed, val duplicates: List<DriveClient.RemoteFile>)

    /** Finds and integrity-checks the cloud Vault (no key needed). Null when none exists. */
    suspend fun inspectCloud(token: String): CloudVault? {
        val files = drive.findBackups(token)
        val newest = files.firstOrNull() ?: return null
        val parsed = BackupFormat.parse(drive.download(token, newest))
        return CloudVault(newest, parsed, files.drop(1))
    }

    suspend fun sync(tokenOverride: String? = null, revokeDeviceId: String? = null): Outcome = lock.withLock {
        if (!session.isUnlocked) return@withLock Outcome.Locked.also { status(SyncState.PENDING_LOCKED, tr("Синхронизация продолжится после разблокировки")) }
        status(SyncState.RUNNING, null)
        var token = tokenOverride ?: GoogleAccount.silentToken(app)
            ?: return@withLock Outcome.NeedsAuth.also { status(SyncState.NEEDS_AUTH, tr("Войдите в Google ещё раз, чтобы продолжить синхронизацию")) }
        var authRetried = false
        while (true) {
            try {
                return@withLock runSync(token, revokeDeviceId).also { report(it) }
            } catch (e: DriveAuthException) {
                if (authRetried) return@withLock Outcome.NeedsAuth.also { report(it) }
                authRetried = true
                GoogleAccount.invalidate(app, token)
                token = GoogleAccount.silentToken(app) ?: return@withLock Outcome.NeedsAuth.also { report(it) }
            } catch (e: BackupCorruptedException) {
                return@withLock Outcome.Failure(SyncState.CORRUPTED, tr("Облачная копия повреждена") + ": ${e.message}").also { report(it) }
            } catch (e: BackupAuthenticationException) {
                return@withLock Outcome.Failure(SyncState.CORRUPTED, tr("Облачная копия не прошла проверку подлинности")).also { report(it) }
            } catch (e: VaultLockedException) {
                return@withLock Outcome.Locked.also { report(it) }
            } catch (e: DriveHttpException) {
                return@withLock (if (e.retryable) Outcome.Retry(e.message.orEmpty()) else Outcome.Failure(SyncState.ERROR, e.message.orEmpty())).also { report(it) }
            } catch (e: IOException) {
                return@withLock Outcome.Retry(tr("Нет соединения. Повторим автоматически.")).also { report(it) }
            } catch (e: Exception) {
                return@withLock Outcome.Failure(SyncState.ERROR, e.message ?: e.javaClass.simpleName).also { report(it) }
            }
        }
        @Suppress("UNREACHABLE_CODE") Outcome.Locked
    }

    private suspend fun runSync(token: String, revokeDeviceId: String?): Outcome {
        val selfId = DeviceIdentity.id(app)
        val selfName = DeviceIdentity.displayName()
        val vaultId = keys.vaultId() ?: error(tr("Хранилище ещё не создано"))
        repeat(MAX_ATTEMPTS) {
            val now = System.currentTimeMillis()
            val files = drive.findBackups(token)
            if (files.isEmpty()) {
                // First device: publish the local Vault.
                val records = dao.getAll().map(SyncMerger::toRecord)
                val devices = listOf(SyncDevice(selfId, selfName, now))
                val content = session.withKey { key -> BackupFormat.encode(header(vaultId, 1, now, selfId), BackupBody(records, devices), key) }
                drive.create(token, content, 1)
                records.forEach { dao.markSynced(it.id, SyncMerger.recordHash(it)) }
                SyncStatus.update(app) { it.copy(remoteItems = records.count { r -> !r.deleted }, remoteGeneration = 1, devices = devices) }
                return Outcome.Success(0, records.size, 0)
            }
            val main = files.first()
            // Fold accidental duplicates (two devices creating the file simultaneously) into the main file.
            var extraPulled = 0
            for (dup in files.drop(1)) {
                val parsed = BackupFormat.parse(drive.download(token, dup))
                if (parsed.header.vaultId != vaultId) continue
                val body = session.withKey { BackupFormat.decryptBody(parsed, it) }
                extraPulled += applyMerge(body.items).pulled
            }

            val parsed = BackupFormat.parse(drive.download(token, main))
            if (parsed.header.vaultId != vaultId) {
                return Outcome.Failure(SyncState.VAULT_MISMATCH, tr("В Google Drive хранится другое хранилище sBoard. Выберите, объединить их или заменить облачную копию."))
            }
            val body = session.withKey { BackupFormat.decryptBody(parsed, it) }
            if (body.devices.any { it.id == selfId && it.revoked }) {
                GoogleAccount.revoke(app)
                return Outcome.Failure(SyncState.REVOKED, tr("Это устройство отключено от синхронизации на другом устройстве"))
            }

            // Key material (master password / Recovery Key) follows the most recent change.
            val localKeyUpdated = keys.keyUpdatedAt()
            var keyMaterialNewerLocally = false
            when {
                parsed.header.keyUpdatedAt > localKeyUpdated -> keys.adoptWraps(parsed.header.keyWraps, vaultId, parsed.header.keyUpdatedAt)
                parsed.header.keyUpdatedAt < localKeyUpdated -> keyMaterialNewerLocally = true
            }

            val merge = applyMerge(body.items)
            var devices = SyncMerger.mergeDevices(body.devices, selfId, selfName, now)
            if (revokeDeviceId != null && revokeDeviceId != selfId) devices = devices.map { if (it.id == revokeDeviceId) it.copy(revoked = true) else it }
            val selfEntry = body.devices.firstOrNull { it.id == selfId }
            val deviceStale = selfEntry == null || now - selfEntry.lastSyncAt > DEVICE_REFRESH_MS || revokeDeviceId != null
            var generation = parsed.header.generation
            if (merge.cloudChanged || keyMaterialNewerLocally || deviceStale || files.size > 1) {
                generation += 1
                val keyUpdated = maxOf(localKeyUpdated, parsed.header.keyUpdatedAt)
                val content = session.withKey { key ->
                    BackupFormat.encode(header(vaultId, generation, now, selfId, keyUpdated), BackupBody(merge.upload, devices), key)
                }
                // Optimistic concurrency: if another device wrote since our download, merge again.
                if (drive.metadata(token, main.id).version != main.version) return@repeat
                drive.update(token, main.id, content, generation)
                files.drop(1).forEach { runCatching { drive.delete(token, it.id) } }
            }
            merge.upload.forEach { dao.markSynced(it.id, SyncMerger.recordHash(it)) }
            dao.purgeSyncedTombstones(now - SyncMerger.TOMBSTONE_TTL_MS)
            SyncStatus.update(app) {
                it.copy(remoteItems = merge.upload.count { r -> !r.deleted }, remoteGeneration = generation, devices = devices, conflicts = merge.conflicts)
            }
            return Outcome.Success(merge.pulled + extraPulled, if (merge.cloudChanged) merge.pushed else 0, merge.conflicts)
        }
        return Outcome.Retry(tr("Облачная копия часто меняется другим устройством. Повторим позже."))
    }

    /** Merges remote records into the local database atomically and keeps conflict losers in history. */
    private suspend fun applyMerge(remote: List<SyncRecord>): SyncMerger.Result = db.withTransaction {
        val result = SyncMerger.merge(dao.getAll(), remote)
        if (result.conflictLosers.isNotEmpty()) {
            session.withKey { key ->
                result.conflictLosers.forEach { loser ->
                    val payload = runCatching { ItemCipher.open(key, loser) }.getOrNull() ?: return@forEach
                    val sealed = ItemCipher.sealHistory(key, loser.id, payload)
                    db.history().insert(ItemHistoryEntity(itemId = loser.id, iv = sealed.iv, ciphertext = sealed.ciphertext,
                        itemUpdatedAt = loser.updatedAt, savedAt = System.currentTimeMillis(), reason = "conflict"))
                }
            }
        }
        if (result.localUpserts.isNotEmpty()) dao.upsertAll(result.localUpserts)
        result
    }

    private suspend fun header(vaultId: String, generation: Long, now: Long, selfId: String, keyUpdatedAt: Long? = null) = BackupHeader(
        vaultId = vaultId, generation = generation, writtenAt = now, writerDeviceId = selfId,
        keyUpdatedAt = keyUpdatedAt ?: keys.keyUpdatedAt(), keyWraps = keys.exportWraps(),
    )

    // ---- Restore / join / replace ------------------------------------------------------------

    /** New device: restores the cloud Vault using the master password (or Recovery Key) typed by the user. */
    suspend fun restore(cloud: CloudVault, secret: String): Int = lock.withLock {
        val key = keys.unwrapCloudKey(cloud.parsed.header.keyWraps, secret)
        try {
            val body = BackupFormat.decryptBody(cloud.parsed, key)
            db.withTransaction {
                dao.clear()
                db.history().clear()
                dao.upsertAll(body.items.map { SyncMerger.toEntity(it, SyncMerger.recordHash(it)) })
                keys.adoptWraps(cloud.parsed.header.keyWraps, cloud.parsed.header.vaultId, cloud.parsed.header.keyUpdatedAt)
            }
            Biometrics.disable(app)
            session.unlock(key)
            SyncStatus.update(app) {
                it.copy(state = SyncState.SUCCESS, lastSuccessAt = System.currentTimeMillis(), lastAttemptAt = System.currentTimeMillis(),
                    message = null, remoteItems = body.items.count { r -> !r.deleted }, remoteGeneration = cloud.parsed.header.generation, devices = body.devices)
            }
            body.items.count { !it.deleted }
        } finally { key.fill(0) }
    }

    /**
     * This device already has its own Vault, but the account holds a different one. Re-encrypts all
     * local items with the cloud Vault key so that both sets are merged into the cloud Vault.
     * Afterwards the cloud master password unlocks this device.
     */
    suspend fun joinCloudVault(cloud: CloudVault, cloudSecret: String): Int = lock.withLock {
        val cloudKey = keys.unwrapCloudKey(cloud.parsed.header.keyWraps, cloudSecret)
        try {
            BackupFormat.decryptBody(cloud.parsed, cloudKey) // proves the key before touching local data
            val deviceId = DeviceIdentity.id(app)
            var moved = 0
            session.withKey { localKey ->
                db.withTransaction {
                    val reencrypted = dao.getAll().filter { !it.deleted }.mapNotNull { row ->
                        val payload = runCatching { ItemCipher.open(localKey, row) }.getOrNull() ?: return@mapNotNull null
                        moved++
                        ItemCipher.seal(cloudKey, row.id, row.createdAt, maxOf(row.updatedAt, System.currentTimeMillis()), false, deviceId, payload, null)
                    }
                    dao.clear()
                    db.history().clear()
                    dao.upsertAll(reencrypted)
                    keys.adoptWraps(cloud.parsed.header.keyWraps, cloud.parsed.header.vaultId, cloud.parsed.header.keyUpdatedAt)
                }
            }
            Biometrics.disable(app)
            session.unlock(cloudKey)
            moved
        } finally { cloudKey.fill(0) }
    }

    /** Overwrites the cloud copy with this device's Vault (explicit, destructive user action). */
    suspend fun replaceCloudWithLocal(token: String) = lock.withLock {
        val vaultId = keys.vaultId() ?: error(tr("Хранилище ещё не создано"))
        val now = System.currentTimeMillis()
        val selfId = DeviceIdentity.id(app)
        val files = drive.findBackups(token)
        dao.markAllUnsynced()
        val records = dao.getAll().map(SyncMerger::toRecord)
        val devices = listOf(SyncDevice(selfId, DeviceIdentity.displayName(), now))
        val generation = (files.firstOrNull()?.let { runCatching { BackupFormat.parse(drive.download(token, it)).header.generation }.getOrNull() } ?: 0L) + 1
        val content = session.withKey { key -> BackupFormat.encode(header(vaultId, generation, now, selfId), BackupBody(records, devices), key) }
        if (files.isEmpty()) drive.create(token, content, generation) else drive.update(token, files.first().id, content, generation)
        files.drop(1).forEach { runCatching { drive.delete(token, it.id) } }
        records.forEach { dao.markSynced(it.id, SyncMerger.recordHash(it)) }
        SyncStatus.update(app) { it.copy(state = SyncState.SUCCESS, lastSuccessAt = now, lastAttemptAt = now, message = null,
            remoteItems = records.count { r -> !r.deleted }, remoteGeneration = generation, devices = devices) }
    }

    /** Deletes the cloud copy (all sboard_backup.enc files in appDataFolder). Local data is untouched. */
    suspend fun deleteCloudCopy(token: String) = lock.withLock {
        drive.findBackups(token).forEach { drive.delete(token, it.id) }
        dao.markAllUnsynced()
        SyncStatus.update(app) { it.copy(remoteItems = 0, remoteGeneration = 0, devices = emptyList()) }
    }

    suspend fun refreshProfile(token: String) {
        drive.userInfo(token)?.let { (email, name) -> GoogleAccount.markConnected(app, email, name) }
    }

    private fun status(state: SyncState, message: String?) {
        SyncStatus.update(app) { it.copy(state = state, message = message, lastAttemptAt = System.currentTimeMillis()) }
    }

    private fun report(outcome: Outcome) {
        val now = System.currentTimeMillis()
        SyncStatus.update(app) { s ->
            when (outcome) {
                is Outcome.Success -> s.copy(state = SyncState.SUCCESS, lastSuccessAt = now, lastAttemptAt = now, message = null,
                    pulled = outcome.pulled, pushed = outcome.pushed, conflicts = outcome.conflicts)
                Outcome.Locked -> s.copy(state = SyncState.PENDING_LOCKED, lastAttemptAt = now, message = tr("Синхронизация продолжится после разблокировки"))
                Outcome.NeedsAuth -> s.copy(state = SyncState.NEEDS_AUTH, lastAttemptAt = now, message = tr("Войдите в Google ещё раз, чтобы продолжить синхронизацию"))
                is Outcome.Retry -> s.copy(state = SyncState.OFFLINE, lastAttemptAt = now, message = outcome.message)
                is Outcome.Failure -> s.copy(state = outcome.state, lastAttemptAt = now, message = outcome.message)
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val DEVICE_REFRESH_MS = 6L * 60 * 60 * 1000
        private val lock = Mutex()
    }
}
