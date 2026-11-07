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
            val inlineRequest = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest else null
            // A fresh code from SMS / messengers / mail is offered first for one-time-code fields.
            com.sboard.app.feature.otpinbox.OtpInbox.current()?.let { otp ->
                val otpIds = form.fields[FieldKind.OTP].orEmpty()
                if (otpIds.isNotEmpty()) {
                    val title = tr("Код {0}", otp.code)
                    val pres = AutofillDatasets.presentation(this, title, tr("из {0}", otp.sourceApp), R.drawable.ic_autofill_key)
                    response.addDataset(Dataset.Builder(pres).apply {
                        otpIds.forEach { setValue(it, android.view.autofill.AutofillValue.forText(otp.code), pres) }
                        if (Build.VERSION.SDK_INT >= 30) AutofillDatasets.inlinePresentation(this@SBoardAutofillService, inlineRequest, datasets, title, otp.sourceApp)?.let { setInlinePresentation(it) }
                    }.build())
                    datasets++
                }
            }
            if (!session.isUnlocked && datasets == 0) {
                // Locked: one "Unlock sBoard" entry for the whole form. After unlocking, the activity
                // returns the matching logins as ready-to-tap suggestions (no second step).
                val pres = AutofillDatasets.presentation(this, tr("Разблокировать sBoard"), form.webDomain ?: tr("Хранилище sBoard"), R.drawable.ic_autofill_lock)
                val sender = AutofillDatasets.pendingIntentSender(this, AutofillActivity.intent(this, AutofillActivity.MODE_UNLOCK_LIST, null), 998)
                if (Build.VERSION.SDK_INT >= 30) {
                    val inline = AutofillDatasets.inlinePresentation(this, inlineRequest, 0, tr("Разблокировать sBoard"), null)
                    @Suppress("DEPRECATION")
                    if (inline != null) response.setAuthentication(form.allIds.toTypedArray(), sender, pres, inline)
                    else response.setAuthentication(form.allIds.toTypedArray(), sender, pres)
                } else response.setAuthentication(form.allIds.toTypedArray(), sender, pres)
                datasets++
            } else {
                if (session.isUnlocked) {
                    val items = kotlinx.coroutines.withTimeoutOrNull(2500) { VaultRepository.get(this@SBoardAutofillService).snapshot() }.orEmpty()
                    val confirm = AppSettings.biometricForSensitive.value
                    AutofillDatasets.matching(items, form).forEachIndexed { index, item ->
                        val ds = if (confirm) AutofillDatasets.lockedDataset(this, item, form, 1000 + index, inlineRequest, datasets)
                            else AutofillDatasets.filledDataset(this, item, form, inlineRequest, datasets)
                        ds?.let { response.addDataset(it); datasets++ }
                    }
                }
                // Entry to search the whole Vault (also links this site/app to the chosen login).
                val pickIntent = AutofillActivity.intent(this, if (session.isUnlocked) AutofillActivity.MODE_PICK else AutofillActivity.MODE_UNLOCK_PICK, null)
                val pickTitle = if (session.isUnlocked) tr("Выбрать в sBoard") else tr("Разблокировать sBoard")
                val pickPresentation = AutofillDatasets.presentation(this, pickTitle, form.webDomain ?: tr("Хранилище sBoard"), R.drawable.ic_autofill_lock)
                response.addDataset(Dataset.Builder(pickPresentation).apply {
                    form.allIds.forEach { setValue(it, null, pickPresentation) }
                    setAuthentication(AutofillDatasets.pendingIntentSender(this@SBoardAutofillService, pickIntent, 999))
                    if (Build.VERSION.SDK_INT >= 30) AutofillDatasets.inlinePresentation(this@SBoardAutofillService, inlineRequest, datasets, pickTitle, null)?.let { setInlinePresentation(it) }
                }.build())
                datasets++
            }
            if (form.passwordIds.isNotEmpty()) {
                val saveType = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.usernameIds.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
                response.setSaveInfo(SaveInfo.Builder(saveType, form.passwordIds.toTypedArray()).apply {
                    if (form.usernameIds.isNotEmpty()) setOptionalIds(form.usernameIds.toTypedArray())
                    // Many apps and single-page web logins never finish the activity: also offer to
                    // save when the login fields disappear (navigation, view swap, WebView change).
                    setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
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
