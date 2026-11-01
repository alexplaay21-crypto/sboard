package com.sboard.app.feature.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.IOException
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
            val token = result.accessToken?.takeIf { it.isNotBlank() }
                ?: throw SecurityException("Google requires interactive Drive authorization; open sBoard and reconnect Google Drive")
            if (result.grantedScopes?.contains(DRIVE_APPDATA_SCOPE) != true) {
                throw SecurityException("Google did not grant the required drive.appdata scope; reconnect and approve it")
            }
            token
        } catch (error: ApiException) {
            val hint = when (error.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> "Check Android OAuth package name and SHA-1 signing certificate"
                CommonStatusCodes.SIGN_IN_REQUIRED -> "Sign in to Google again"
                else -> error.status?.statusMessage ?: "Check Google OAuth configuration"
            }
            throw SecurityException("Google authorization failed (${error.statusCode}). $hint", error)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            val apiError = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ApiException>().firstOrNull()
            if (apiError != null) {
                val hint = when (apiError.statusCode) {
                    CommonStatusCodes.DEVELOPER_ERROR -> "Check Android OAuth package name and SHA-1 signing certificate"
                    CommonStatusCodes.SIGN_IN_REQUIRED -> "Sign in to Google again"
                    else -> apiError.status?.statusMessage ?: "Check Google OAuth configuration"
                }
                throw SecurityException("Google authorization failed (${apiError.statusCode}). $hint", apiError)
            }
            throw IOException("Could not obtain Google Drive authorization: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
