package com.sboard.app.feature.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Explicit Google authorization lifecycle operations for sBoard.
 * Access tokens are never copied into app preferences or files.
 */
class GoogleDriveOAuthManager {
    /**
     * Revokes the app's Google authorization. Google documents that revokeAccess clears
     * locally cached tokens and revokes all scopes previously granted to this app.
     * sBoard currently requests only drive.appdata.
     */
    suspend fun revokeDriveAccess(context: Context) = withContext(Dispatchers.IO) {
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(GoogleDriveSilentOAuthTokenProvider.DRIVE_APPDATA_SCOPE)))
            .build()
        Tasks.await(
            Identity.getAuthorizationClient(context.applicationContext).revokeAccess(request),
            30,
            TimeUnit.SECONDS
        )
    }
}
