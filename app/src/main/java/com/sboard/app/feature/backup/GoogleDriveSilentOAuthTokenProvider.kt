package com.sboard.app.feature.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Obtains a short-lived Drive appData token from Google Identity's cached user grant.
 * It never stores access/refresh tokens. If Google requires user interaction, the worker
 * receives null and waits for the user to open sBoard and complete authorization.
 */
class GoogleDriveSilentOAuthTokenProvider : SilentDriveAccessTokenProvider {
    override suspend fun acquire(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()
            val result = Tasks.await(
                Identity.getAuthorizationClient(context.applicationContext).authorize(request),
                30,
                TimeUnit.SECONDS
            )
            if (result.hasResolution()) return@withContext null
            val token = result.accessToken?.takeIf { it.isNotBlank() } ?: return@withContext null
            if (result.grantedScopes?.contains(DRIVE_APPDATA_SCOPE) != true) return@withContext null
            token
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
