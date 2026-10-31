package com.sboard.app.feature.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.sboard.app.feature.vault.VaultSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/**
 * Android 14+ Credential Manager discovery integration.
 * Private key bytes are never read during discovery; only metadata is exposed to the system UI.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SBoardCredentialProviderService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val response = BeginGetCredentialResponse.Builder()
                // Discovery must work after process death and while the Vault is locked.
                // The repository query is a narrow Room projection: it does not read the encrypted
                // private-key column, IV, or public-key bytes. Actual key access remains behind the
                // interactive BiometricPrompt + one-shot authorization in PasskeySelectionActivity.
                val repository = PasskeyRepository(applicationContext, VaultSessionProvider.session)
                val options = request.beginGetCredentialOptions
                    .filterIsInstance<BeginGetPublicKeyCredentialOption>()
                for (option in options) {
                    if (cancellationSignal.isCanceled) return@launch
                    val json = JSONObject(option.requestJson)
                    val rpId = json.optString("rpId").trim().lowercase()
                    if (rpId.isBlank()) continue
                    val allowedIds = parseAllowedCredentialIds(json)
                    repository.listForRelyingParty(rpId)
                        .asSequence()
                        .filter { PasskeyDiscoveryPolicy.isAllowed(it.credentialId, allowedIds) }
                        .forEach { record ->
                            val intent = Intent(this@SBoardCredentialProviderService, PasskeySelectionActivity::class.java)
                                .putExtra(PasskeySelectionActivity.EXTRA_RECORD_ID, record.id)
                            val pendingIntent = PendingIntent.getActivity(
                                this@SBoardCredentialProviderService,
                                record.id.hashCode(),
                                intent,
                                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            val entry = PublicKeyCredentialEntry.Builder(
                                this@SBoardCredentialProviderService,
                                record.userName.ifBlank { record.displayName.ifBlank { record.rpName } },
                                pendingIntent,
                                option
                            )
                                .setDisplayName(record.displayName.ifBlank { record.rpName })
                                .setAutoSelectAllowed(false)
                                .apply {
                                    if (record.lastUsedAt > 0L) setLastUsedTime(Instant.ofEpochMilli(record.lastUsedAt))
                                }
                                .build()
                            response.addCredentialEntry(entry)
                        }
                }
                if (!cancellationSignal.isCanceled) callback.onResult(response.build())
            } catch (_: Throwable) {
                if (!cancellationSignal.isCanceled) callback.onResult(BeginGetCredentialResponse.Builder().build())
            }
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        callback.onError(CreateCredentialUnsupportedException("Passkey registration is not available in this build yet."))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        // sBoard keeps no per-site sign-in session; all credentials remain in the encrypted vault.
        callback.onResult(null)
    }

    private fun parseAllowedCredentialIds(json: JSONObject): Set<String>? {
        val allowCredentials = json.optJSONArray("allowCredentials") ?: return null
        if (allowCredentials.length() == 0) return null
        return buildSet {
            for (index in 0 until allowCredentials.length()) {
                val id = allowCredentials.optJSONObject(index)?.optString("id") ?: continue
                if (id.isNotBlank()) add(id.base64UrlToHexOrNull() ?: id)
            }
        }
    }

    private fun String.base64UrlToHexOrNull(): String? = try {
        val bytes = android.util.Base64.decode(
            this,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        bytes.toHex()
    } catch (_: IllegalArgumentException) { null }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
