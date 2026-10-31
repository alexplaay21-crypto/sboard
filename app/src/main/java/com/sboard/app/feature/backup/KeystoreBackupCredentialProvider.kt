package com.sboard.app.feature.backup

import android.content.Context

/** Supplies a token only when it can be obtained silently; interactive consent is never launched by a Worker. */
fun interface SilentDriveAccessTokenProvider {
    suspend fun acquire(context: Context): String?
}

/**
 * Concrete provider for scheduled work. Backup snapshots contain the already encrypted Room
 * database; the separately opted-in device-bound Recovery Key protects the backup envelope.
 */
class KeystoreBackupCredentialProvider(
    context: Context,
    private val tokenProvider: SilentDriveAccessTokenProvider
) : BackupAutomationCredentialProvider {
    private val keyStore = DeviceBoundBackupKeyStore(context.applicationContext)

    override suspend fun acquire(context: Context): BackupAutomationCredentials? {
        if (!keyStore.isConfigured()) return null
        val token = tokenProvider.acquire(context.applicationContext)?.takeIf { it.isNotBlank() } ?: return null
        val recoveryChars = keyStore.acquireForBackup() ?: return null
        val recoveryKey = try {
            String(recoveryChars)
        } finally {
            recoveryChars.fill('\u0000')
        }
        return BackupAutomationCredentials(
            recoveryKey = recoveryKey,
            accessToken = token
        )
    }
}
