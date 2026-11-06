package com.sboard.app.feature.passkeys

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.sboard.app.core.crypto.B64
import com.sboard.app.core.crypto.toHex
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/**
 * Android 14+ Credential Manager provider for passkeys stored in the sBoard Vault.
 * Discovery never touches private keys; when the Vault is locked only an "Unlock sBoard" action is
 * offered. Signing and creation happen in [PasskeyActivity] after explicit user verification.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SBoardCredentialProviderService : CredentialProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        SBoardI18n.initialize(this); AppSettings.initialize(this)
        scope.launch {
            val response = runCatching { PasskeyProvider.buildGetResponse(this@SBoardCredentialProviderService, request) }
                .getOrElse { BeginGetCredentialResponse.Builder().build() }
            if (!cancellationSignal.isCanceled) callback.onResult(response)
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        SBoardI18n.initialize(this); AppSettings.initialize(this)
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnsupportedException("sBoard stores passkeys only"))
            return
        }
        val intent = Intent(this, PasskeyActivity::class.java).putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_CREATE)
        val pending = PendingIntent.getActivity(this, 4100, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val accountName = runCatching { JSONObject(request.requestJson).getJSONObject("user").optString("name") }.getOrNull().orEmpty()
        val entry = CreateEntry.Builder(accountName.ifBlank { "sBoard" }, pending)
            .setDescription(tr("Passkey будет сохранён в зашифрованном хранилище sBoard и синхронизирован между вашими устройствами"))
            .build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null) // sBoard keeps no per-app sign-in session state.
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object PasskeyProvider {
    suspend fun buildGetResponse(context: Context, request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val builder = BeginGetCredentialResponse.Builder()
        val options = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        if (options.isEmpty()) return builder.build()
        if (!VaultSessionProvider.session.isUnlocked) {
            val intent = Intent(context, PasskeyActivity::class.java).putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_UNLOCK)
            val pending = PendingIntent.getActivity(context, 4200, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return builder.addAuthenticationAction(AuthenticationAction(tr("Разблокировать sBoard"), pending)).build()
        }
        val store = PasskeyStore(context)
        options.forEach { option ->
            val json = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return@forEach
            val rpId = json.optString("rpId").trim()
            if (rpId.isBlank()) return@forEach
            store.forRelyingParty(rpId, allowedIds(json)).forEach { item ->
                val pk = item.payload.passkey!!
                val intent = Intent(context, PasskeyActivity::class.java)
                    .putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_GET)
                    .putExtra(PasskeyActivity.EXTRA_ITEM_ID, item.id)
                val pending = PendingIntent.getActivity(context, item.id.hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addCredentialEntry(
                    PublicKeyCredentialEntry.Builder(context, pk.userName.ifBlank { pk.userDisplayName.ifBlank { pk.rpId } }, pending, option)
                        .setDisplayName(pk.userDisplayName.ifBlank { item.title })
                        .setAutoSelectAllowed(false)
                        .apply { if (pk.lastUsedAt > 0) setLastUsedTime(Instant.ofEpochMilli(pk.lastUsedAt)) }
                        .build()
                )
            }
        }
        return builder.build()
    }

    private fun allowedIds(json: JSONObject): Set<String>? {
        val allow = json.optJSONArray("allowCredentials") ?: return null
        if (allow.length() == 0) return null
        return (0 until allow.length()).mapNotNull { i ->
            allow.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { runCatching { B64.fromUrl(it).toHex() }.getOrNull() }
        }.toSet()
    }
}
