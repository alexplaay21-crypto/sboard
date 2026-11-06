package com.sboard.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.sboard.app.core.i18n.tr
import com.sboard.app.feature.sync.GoogleAccount
import kotlinx.coroutines.launch

/** Returns a function that starts Google authorization and reports a fresh access token. */
@Composable
fun rememberGoogleAuthorizer(onToken: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { GoogleAccount.handleConsentResult(context, result.data) }
                .onSuccess(onToken)
                .onFailure { onError(it.message ?: tr("Не удалось авторизоваться в Google")) }
        } else onError(tr("Авторизация отменена"))
    }
    return {
        val host = activity
        if (host == null) onError(tr("Не удалось открыть авторизацию Google"))
        else scope.launch {
            try {
                when (val auth = GoogleAccount.authorize(host)) {
                    is GoogleAccount.Authorization.Token -> onToken(auth.accessToken)
                    is GoogleAccount.Authorization.NeedsUserConsent ->
                        launcher.launch(IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build())
                }
            } catch (e: Exception) {
                onError(e.message ?: tr("Не удалось авторизоваться в Google"))
            }
        }
    }
}
