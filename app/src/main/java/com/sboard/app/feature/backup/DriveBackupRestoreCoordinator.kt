package com.sboard.app.feature.backup

import android.content.Context
import com.sboard.app.feature.vault.VaultSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Explicit restore flow for a fresh install or a replacement device.
 * The user chooses a snapshot and supplies the Recovery Key; no restore happens during sign-in.
 * OAuth tokens and Recovery Keys are passed only in memory and never persisted by this class.
 */
class DriveBackupRestoreCoordinator(
    context: Context,
    private val driveClient: GoogleDriveBackupClient = GoogleDriveBackupClient(),
    private val backupManagerFactory: (Context) -> EncryptedBackupManager = { EncryptedBackupManager(it) }
) {
    private val appContext = context.applicationContext

    suspend fun listAvailableBackups(accessToken: String): List<GoogleDriveBackupClient.DriveBackupFile> =
        discoverAvailableBackups(accessToken).backups

    /**
     * Discovers only backups that can pass the same metadata preflight used by download.
     * Invalid/unverifiable Drive entries are counted separately so the UI does not present
     * them as selectable restore points or misleadingly report an empty Drive folder.
     */
    suspend fun discoverAvailableBackups(accessToken: String): BackupDiscoveryResult {
        require(accessToken.isNotBlank()) { "Google Drive authorization is required" }
        val filtered = BackupDiscoveryPolicy.filter(driveClient.listVersions(accessToken))
        return BackupDiscoveryResult(
            backups = filtered.usable,
            rejectedMetadataCount = filtered.rejectedMetadataCount
        )
    }

    data class BackupDiscoveryResult(
        val backups: List<GoogleDriveBackupClient.DriveBackupFile>,
        val rejectedMetadataCount: Int
    )

    /**
     * Re-checks the selected file against a fresh Drive listing before download, then verifies
     * Drive metadata, decrypts/authenticates every AES-GCM chunk, validates SQLite and atomically
     * replaces the local database with rollback protection.
     */
    suspend fun restoreSelectedBackup(
        selected: GoogleDriveBackupClient.DriveBackupFile,
        accessToken: String,
        recoveryKey: String,
        session: VaultSession
    ): RestoreResult = restoreMutex.withLock {
        require(accessToken.isNotBlank()) { "Google Drive authorization is required" }
        BackupRestorePreflightPolicy.requireRecoveryKey(recoveryKey)
        require(BackupVersionPolicy.isRestorableFileName(selected.name)) {
            "Выбрана неподдерживаемая резервная копия"
        }

        val current = driveClient.listVersions(accessToken)
        val confirmed = RestoreSelectionPolicy.findCurrentSelection(current, selected)
            ?: throw IOException("Выбранная резервная копия больше недоступна в Google Drive")

        val downloaded = File(appContext.cacheDir, "sboard_restore_${UUID.randomUUID()}.enc")
        try {
            driveClient.downloadVersion(confirmed, downloaded, accessToken)
            backupManagerFactory(appContext).restoreBackup(
                recoveryKey = recoveryKey,
                backup = downloaded,
                session = session
            )
            RestoreResult(
                fileId = confirmed.id,
                fileName = confirmed.name,
                downloadedBytes = downloaded.length(),
                restoredAtEpochMillis = System.currentTimeMillis()
            )
        } finally {
            // The downloaded file is encrypted, but still remove it promptly after restore.
            downloaded.delete()
        }
    }

    data class RestoreResult(
        val fileId: String,
        val fileName: String,
        val downloadedBytes: Long,
        val restoredAtEpochMillis: Long
    )

    private companion object {
        val restoreMutex = Mutex()
    }
}

/** Pure policy to prevent restoring stale or tampered list selections. */
internal object RestoreSelectionPolicy {
    fun findCurrentSelection(
        current: List<GoogleDriveBackupClient.DriveBackupFile>,
        selected: GoogleDriveBackupClient.DriveBackupFile
    ): GoogleDriveBackupClient.DriveBackupFile? {
        if (!BackupVersionPolicy.isRestorableFileName(selected.name)) return null
        if (!selected.id.matches(Regex("[A-Za-z0-9_-]{5,256}"))) return null
        return current.firstOrNull { candidate ->
            candidate.id == selected.id &&
                candidate.name == selected.name &&
                BackupVersionPolicy.isRestorableFileName(candidate.name)
        }
    }
}
