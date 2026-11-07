package com.sboard.app.feature.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.sboard.app.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google OAuth via the Identity AuthorizationClient. sBoard never stores OAuth tokens: Google Play
 * services caches the grant, and every sync asks for a fresh short-lived access token.
 * Scopes: drive.appdata (hidden app folder only) + basic profile/email for the account screen.
 */
object GoogleAccount {
    const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private val scopes = listOf(
        Scope(SCOPE_APPDATA),
        Scope("https://www.googleapis.com/auth/userinfo.email"),
        Scope("https://www.googleapis.com/auth/userinfo.profile"),
    )
    private const val PREFS = "sboard_account"

    data class Account(val email: String, val name: String)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _account.value = if (prefs.getBoolean("connected", false))
            Account(prefs.getString("email", "").orEmpty(), prefs.getString("name", "").orEmpty()) else null
    }

    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("connected", false)

    fun markConnected(context: Context, email: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("connected", true).putString("email", email).putString("name", name).apply()
        _account.value = Account(email, name)
    }

    fun markDisconnected(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _account.value = null
    }

    sealed class Authorization {
        data class Token(val accessToken: String) : Authorization()
        data class NeedsUserConsent(val pendingIntent: PendingIntent) : Authorization()
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    /** Starts authorization. Returns a token directly if already granted, otherwise a consent intent. */
    suspend fun authorize(activity: Activity): Authorization {
        val result = try {
            Identity.getAuthorizationClient(activity).authorize(request()).await()
        } catch (e: ApiException) { throw IllegalStateException(describe(e), e) }
        return toAuthorization(result)
    }

    fun handleConsentResult(context: Context, data: Intent?): String {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) { throw IllegalStateException(describe(e), e) }
        return result.accessToken?.takeIf { it.isNotBlank() } ?: error(tr("Google не выдал токен доступа"))
    }

    /** Silent token for background sync; null when the user must re-consent in the app. */
    suspend fun silentToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val result = Identity.getAuthorizationClient(context.applicationContext).authorize(request()).await()
            if (result.hasResolution()) null
            else result.accessToken?.takeIf { it.isNotBlank() && result.grantedScopes.contains(SCOPE_APPDATA) }
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED || e.statusCode == CommonStatusCodes.RESOLUTION_REQUIRED) null
            else throw IllegalStateException(describe(e), e)
        }
    }

    /** Invalidates a rejected token so the next request gets a new one. */
    suspend fun invalidate(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context.applicationContext, token) }
    }

    suspend fun revoke(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            Identity.getAuthorizationClient(context.applicationContext)
                .revokeAccess(RevokeAccessRequest.builder().setScopes(scopes).build()).await()
        }
        markDisconnected(context)
    }

    private fun toAuthorization(result: AuthorizationResult): Authorization {
        if (result.hasResolution()) {
            return Authorization.NeedsUserConsent(result.pendingIntent ?: error(tr("Google не вернул запрос подтверждения доступа")))
        }
        return Authorization.Token(result.accessToken?.takeIf { it.isNotBlank() } ?: error(tr("Google не выдал токен доступа")))
    }

    /** SHA-1 of the certificate this APK is signed with — what the Google OAuth Android client must contain. */
    fun signingSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) emptyArray() else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching "—"
        java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("—")

    fun describe(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR -> tr("Google OAuth не настроен для этой сборки: нужен Android OAuth-клиент с package name com.sboard.app и SHA-1 сертификата подписи APK.")
        CommonStatusCodes.NETWORK_ERROR -> tr("Нет соединения с Google")
        CommonStatusCodes.CANCELED -> tr("Авторизация отменена")
        else -> tr("Ошибка авторизации Google") + " (${e.statusCode})"
    }
}
