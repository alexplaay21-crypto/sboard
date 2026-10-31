package com.sboard.app.feature.sync

import android.content.Context
import com.sboard.app.feature.backup.GoogleDriveSilentOAuthTokenProvider
import com.sboard.app.feature.backup.SilentDriveAccessTokenProvider
import com.sboard.app.feature.vault.VaultSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs an exchange only while the Vault is unlocked and Google can silently provide a token. */
class GoogleDriveSyncAutomationExecutor(
    private val session: VaultSession,
    private val tokenProvider: SilentDriveAccessTokenProvider = GoogleDriveSilentOAuthTokenProvider(),
    private val coordinatorFactory: (Context, VaultSession) -> GoogleDriveVaultSyncCoordinator = { context, vaultSession ->
        GoogleDriveVaultSyncCoordinator(context, vaultSession)
    }
) : SyncAutomationExecutor {
    override suspend fun synchronize(context: Context): SyncAutomationResult = mutex.withLock {
        if (!session.isUnlocked) return@withLock SyncAutomationResult.NEEDS_UNLOCK_OR_AUTH
        val token = tokenProvider.acquire(context.applicationContext)
            ?.takeIf { it.isNotBlank() }
            ?: return@withLock SyncAutomationResult.NEEDS_UNLOCK_OR_AUTH
        try {
            coordinatorFactory(context.applicationContext, session).synchronize(token)
            SyncAutomationResult.SUCCESS
        } finally {
            // Token is a short-lived String supplied by Google Identity and is not persisted by sBoard.
            // Java Strings cannot be zeroed; keep its lifetime scoped to this call.
        }
    }

    private companion object { val mutex = Mutex() }
}
