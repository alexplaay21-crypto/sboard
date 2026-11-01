package com.sboard.app.feature.backup

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Supplies short-lived credentials at execution time. Implementations must not persist the
 * Recovery Key or OAuth access token and should return null when user interaction is required.
 * A worker cannot display consent UI; in that case the caller should ask the user to open sBoard.
 */
interface BackupAutomationCredentialProvider {
    suspend fun acquire(context: Context): BackupAutomationCredentials?
}

/** Values are process-local only. Do not place this object in WorkManager Data or preferences. */
data class BackupAutomationCredentials(
    val recoveryKey: String,
    val accessToken: String
)

/** Creates an authenticated local snapshot and uploads it as a new immutable Drive version. */
class DriveBackupAutomationExecutor(
    private val credentialProvider: BackupAutomationCredentialProvider,
    private val backupManagerFactory: (Context) -> EncryptedBackupManager = { EncryptedBackupManager(it) },
    private val driveClient: GoogleDriveBackupClient = GoogleDriveBackupClient()
) : BackupAutomationExecutor {
    override suspend fun runScheduledBackup(context: Context, reason: String): BackupAutomationResult =
        processMutex.withLock {
            val app = context.applicationContext
            val credentials = credentialProvider.acquire(app)
                ?: return@withLock BackupAutomationResult.SKIPPED

            if (credentials.recoveryKey.isBlank() || credentials.accessToken.isBlank()) {
                return@withLock BackupAutomationResult.SKIPPED
            }

            val temp = File(app.cacheDir, "sboard_auto_${UUID.randomUUID()}.enc")
            try {
                backupManagerFactory(app).createBackup(credentials.recoveryKey, temp)
                if (!temp.isFile || temp.length() <= 0L) throw IOException("Encrypted backup was not created")
                driveClient.uploadVersioned(temp, credentials.accessToken)
                BackupAutomationResult.SUCCESS
            } finally {
                // Best-effort removal of encrypted staging data. No plaintext DB copy is created here.
                temp.delete()
            }
        }

    private companion object {
        val processMutex = Mutex()
    }
}
