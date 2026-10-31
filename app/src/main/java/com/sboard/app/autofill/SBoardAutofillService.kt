package com.sboard.app.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.sboard.app.feature.vault.VaultRepository
import com.sboard.app.feature.vault.VaultSessionProvider
import com.sboard.app.feature.otp.TotpGenerator
import com.sboard.app.feature.otp.TotpParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Conservative Autofill implementation: only offers credentials when the saved website
 * exactly matches the web origin host. Never logs field contents.
 * The Vault must already be unlocked by the user in sBoard.
 */
class SBoardAutofillService : AutofillService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val session = VaultSessionProvider.session
        if (!session.isUnlocked || cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            try {
                val structure = request.fillContexts.lastOrNull()?.structure
                if (structure == null) {
                    callback.onSuccess(null)
                    return@launch
                }
                val fields = findFields(structure)
                if (fields.usernameIds.isEmpty() && fields.passwordIds.isEmpty() && fields.otpIds.isEmpty()) {
                    callback.onSuccess(null)
                    return@launch
                }

                val host = findWebDomain(structure)
                if (host.isNullOrBlank()) {
                    callback.onSuccess(null)
                    return@launch
                }

                val records = VaultRepository(applicationContext, session).getAll()
                val response = FillResponse.Builder()
                var count = 0
                records.asSequence()
                    .filter { matchesHost(it.website, host) }
                    .take(40)
                    .forEach { record ->
                        if (cancellationSignal.isCanceled) return@forEach
                        val isOtpRecord = record.category.equals("Код 2FA", ignoreCase = true) ||
                            record.category.equals("OTP", ignoreCase = true) ||
                            record.category.equals("2FA", ignoreCase = true)

                        if (isOtpRecord && fields.otpIds.isNotEmpty() && record.password.isNotBlank()) {
                            val code = runCatching {
                                val params = TotpParameters.fromVaultNotes(record.notes)
                                TotpGenerator.generate(
                                    base32Secret = record.password,
                                    digits = params.digits,
                                    periodSeconds = params.periodSeconds,
                                    algorithm = params.algorithm
                                )
                            }.getOrNull()
                            if (code != null) {
                                val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                    setTextViewText(android.R.id.text1, record.title.ifBlank { "sBoard • 2FA" })
                                }
                                val dataset = Dataset.Builder(presentation).apply {
                                    fields.otpIds.forEach { id ->
                                        setValue(id, AutofillValue.forText(code), presentation)
                                    }
                                }.build()
                                response.addDataset(dataset)
                                count++
                            }
                        } else if (!isOtpRecord && record.password.isNotBlank() &&
                            (fields.usernameIds.isNotEmpty() || fields.passwordIds.isNotEmpty())) {
                            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                setTextViewText(android.R.id.text1, record.title.ifBlank { record.username.ifBlank { "sBoard credential" } })
                            }
                            val dataset = Dataset.Builder(presentation).apply {
                                fields.usernameIds.forEach { id ->
                                    setValue(id, AutofillValue.forText(record.username), presentation)
                                }
                                fields.passwordIds.forEach { id ->
                                    setValue(id, AutofillValue.forText(record.password), presentation)
                                }
                            }.build()
                            response.addDataset(dataset)
                            count++
                        }
                    }

                callback.onSuccess(if (count > 0) response.build() else null)
            } catch (cancelled: CancellationException) {
                // The system has cancelled this request; do not return data.
            } catch (_: Exception) {
                // Fail closed. Never expose secrets or exception details in logs.
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        // Saving is intentionally not enabled until an explicit, reviewed save flow is connected to Vault UI.
        callback.onSuccess()
    }

    private data class FieldIds(
        val usernameIds: List<AutofillId>,
        val passwordIds: List<AutofillId>,
        val otpIds: List<AutofillId>
    )

    private fun findFields(structure: AssistStructure): FieldIds {
        val usernames = mutableListOf<AutofillId>()
        val passwords = mutableListOf<AutofillId>()
        val otp = mutableListOf<AutofillId>()
        fun visit(node: AssistStructure.ViewNode) {
            val id = node.autofillId
            if (id != null && node.isVisibleToUser && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val hints = node.autofillHints.orEmpty().map { it.lowercase() }
                val hint = node.hint?.toString()?.lowercase().orEmpty()
                val resourceId = node.idEntry?.lowercase().orEmpty()
                val isOtp = hints.any { it.contains("otp") || it.contains("onetimecode") || it.contains("one-time-code") || it.contains("smsotp") } ||
                    hint.contains("one-time code") || hint.contains("verification code") || hint.contains("authenticator code") ||
                    resourceId.contains("otp") || resourceId.contains("verification_code")
                val passwordInput = (node.inputType and 0x000000f0) == 0x00000080 ||
                    (node.inputType and 0x000000f0) == 0x000000e0
                val isPassword = hints.any { it.contains("password") } || passwordInput
                val isUsername = hints.any { it.contains("username") || it.contains("email") } ||
                    (!isPassword && (hint.contains("user") || hint.contains("email") || hint.contains("login")))
                if (isOtp) otp += id else if (isPassword) passwords += id else if (isUsername) usernames += id
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        return FieldIds(usernames, passwords, otp)
    }

    private fun findWebDomain(structure: AssistStructure): String? {
        fun visit(node: AssistStructure.ViewNode): String? {
            node.webDomain?.takeIf { it.isNotBlank() }?.let { return it }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))?.let { return it }
            return null
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)?.let { return it }
        return null
    }

    private fun matchesHost(savedWebsite: String, currentHost: String): Boolean {
        val savedHost = runCatching {
            val normalized = if (savedWebsite.contains("://")) savedWebsite else "https://$savedWebsite"
            URI(normalized).host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return false
        val current = currentHost.lowercase().removePrefix("www.")
        if (!savedHost.contains('.') || savedHost.startsWith('.') || savedHost.endsWith('.')) return false
        // Exact host match by default: do not leak a credential to an unreviewed subdomain.
        return current == savedHost
    }
}
