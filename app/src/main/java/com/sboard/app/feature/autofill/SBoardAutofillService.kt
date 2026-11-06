package com.sboard.app.feature.autofill

import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import com.sboard.app.R
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.VaultRepository
import kotlinx.coroutines.launch

/**
 * Android Autofill provider. Nothing is filled silently: every suggestion is an authentication
 * dataset that opens [AutofillActivity] for explicit confirmation (and biometrics/unlock).
 * Field contents are never logged.
 */
@Suppress("DEPRECATION")
class SBoardAutofillService : AutofillService() {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch { fill(request, cancellationSignal, callback) }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun fill(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            SBoardI18n.initialize(this); AppSettings.initialize(this)
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
            val form = FieldParser.parse(structure)
            if (form.fields.isEmpty() || form.packageName == packageName) return callback.onSuccess(null)
            val response = FillResponse.Builder()
            val session = VaultSessionProvider.session
            var datasets = 0
            if (session.isUnlocked) {
                val items = kotlinx.coroutines.withTimeoutOrNull(2500) { VaultRepository.get(this@SBoardAutofillService).snapshot() }.orEmpty()
                AutofillDatasets.matching(items, form).forEachIndexed { index, item ->
                    AutofillDatasets.lockedDataset(this, item, form, 1000 + index)?.let { response.addDataset(it); datasets++ }
                }
            }
            // Always offer an entry to unlock / search the Vault (also links the site or app to a login).
            val pickIntent = AutofillActivity.intent(this, if (session.isUnlocked) AutofillActivity.MODE_PICK else AutofillActivity.MODE_UNLOCK_PICK, null)
            val pickPresentation = AutofillDatasets.presentation(this,
                if (session.isUnlocked) tr("Выбрать в sBoard") else tr("Разблокировать sBoard"),
                form.webDomain ?: tr("Хранилище sBoard"), R.drawable.ic_autofill_lock)
            response.addDataset(Dataset.Builder(pickPresentation).apply {
                form.allIds.forEach { setValue(it, null, pickPresentation) }
                setAuthentication(AutofillDatasets.pendingIntentSender(this@SBoardAutofillService, pickIntent, 999))
            }.build())
            datasets++
            if (form.passwordIds.isNotEmpty()) {
                val saveType = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.usernameIds.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
                response.setSaveInfo(SaveInfo.Builder(saveType, form.passwordIds.toTypedArray()).apply {
                    if (form.usernameIds.isNotEmpty()) setOptionalIds(form.usernameIds.toTypedArray())
                }.build())
            }
            callback.onSuccess(if (datasets > 0 && !cancellationSignal.isCanceled) response.build() else null)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // cancelled by the system: return nothing
        } catch (_: Exception) {
            callback.onSuccess(null) // fail closed; never include field data in errors
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            SBoardI18n.initialize(this); AppSettings.initialize(this)
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
            val form = FieldParser.parse(structure, captureValues = true)
            val password = form.values[FieldKind.PASSWORD] ?: form.values[FieldKind.NEW_PASSWORD]
            if (password.isNullOrBlank()) return callback.onSuccess()
            val token = PendingSaves.put(
                PendingSaves.Entry(
                    username = form.values[FieldKind.USERNAME] ?: form.values[FieldKind.EMAIL].orEmpty(),
                    password = password,
                    webDomain = form.webDomain,
                    packageName = form.packageName.takeUnless { AutofillDatasets.isBrowser(it) },
                )
            )
            val intent = AutofillSaveActivity.intent(this, token)
            if (Build.VERSION.SDK_INT >= 28) {
                callback.onSuccess(AutofillDatasets.pendingIntentSender(this, intent, 2000))
            } else {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                callback.onSuccess()
            }
        } catch (_: Exception) {
            callback.onFailure(tr("Не удалось сохранить в sBoard"))
        }
    }
}

/** Credentials captured for saving stay in process memory only, for at most five minutes. */
object PendingSaves {
    data class Entry(val username: String, val password: String, val webDomain: String?, val packageName: String?, val createdAt: Long = System.currentTimeMillis())
    private val entries = HashMap<String, Entry>()

    @Synchronized fun put(entry: Entry): String {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.createdAt > 5 * 60_000 }
        val token = java.util.UUID.randomUUID().toString()
        entries[token] = entry
        return token
    }

    @Synchronized fun take(token: String): Entry? = entries.remove(token)?.takeIf { System.currentTimeMillis() - it.createdAt <= 5 * 60_000 }
}
