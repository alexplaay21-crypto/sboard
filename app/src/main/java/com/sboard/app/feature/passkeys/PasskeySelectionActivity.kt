package com.sboard.app.feature.passkeys

import android.os.Bundle
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sboard.app.feature.vault.BiometricVaultManager
import com.sboard.app.feature.vault.VaultSessionProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Final selected-passkey flow. The system supplies the original provider request through the
 * mutable PendingIntent. The activity never accepts RP ID, request JSON, or origin from UI extras.
 * Browser-delegated clientDataHash requests are rejected until a reviewed browser allowlist and
 * clientDataHash signing path are implemented.
 */
class PasskeySelectionActivity : FragmentActivity() {
    private val completed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val providerRequest = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (_: Exception) {
            null
        }
        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        val option = providerRequest?.credentialOptions
            ?.singleOrNull() as? GetPublicKeyCredentialOption

        if (providerRequest == null || recordId.isNullOrBlank() || option == null) {
            finishWithException("Некорректный запрос Passkey")
            return
        }
        if (option.clientDataHash != null) {
            finishWithException("Делегированные запросы браузера пока не поддерживаются")
            return
        }

        val biometricManager = BiometricVaultManager(applicationContext)
        lifecycleScope.launch {
            val cipher = try {
                biometricManager.prepareUnlockCipher()
            } catch (_: Exception) {
                null
            }
            if (cipher == null) {
                finishWithException("Биометрическая защита Vault не настроена")
                return@launch
            }

            BiometricVaultManager.authenticate(
                activity = this@PasskeySelectionActivity,
                cipher = cipher,
                title = "Подтвердите вход через Passkey",
                onSuccess = { authenticatedCipher ->
                    lifecycleScope.launch {
                        try {
                            val session = VaultSessionProvider.session
                            val authorization = biometricManager.authorizePasskeyAfterBiometric(
                                authenticatedCipher = authenticatedCipher,
                                session = session,
                                recordId = recordId
                            )
                            val requestJson = option.requestJson
                            val rpId = JSONObject(requestJson).optString("rpId")
                            require(rpId.isNotBlank()) { "Missing RP ID" }

                            val repository = PasskeyRepository(applicationContext, session)
                            val coordinator = TrustedPasskeyAssertionCoordinator(
                                originProvider = CallingAppTrustedOriginProvider(providerRequest.callingAppInfo),
                                assertionPipeline = PasskeyAssertionPipeline(repository)
                            )
                            val assertion = coordinator.create(
                                recordId = recordId,
                                requestJson = requestJson,
                                rpId = rpId,
                                authorization = authorization
                            )
                            val response = GetCredentialResponse(
                                PublicKeyCredential(assertion.toCredentialResponseJson())
                            )
                            PendingIntentHandler.setGetCredentialResponse(intent, response)
                            if (completed.compareAndSet(false, true)) {
                                setResult(RESULT_OK, intent)
                                finish()
                            }
                        } catch (_: Exception) {
                            finishWithException("Не удалось выполнить безопасную авторизацию Passkey")
                        }
                    }
                },
                onError = {
                    finishCancelled()
                }
            )
        }
    }

    private fun finishWithException(message: String) {
        if (!completed.compareAndSet(false, true)) return
        runCatching {
            PendingIntentHandler.setGetCredentialException(
                intent,
                GetCredentialUnsupportedException(message)
            )
            setResult(RESULT_OK, intent)
        }.onFailure {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun finishCancelled() {
        if (!completed.compareAndSet(false, true)) return
        // Cancellation is not converted into a successful credential response.
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_RECORD_ID = "com.sboard.app.passkeys.RECORD_ID"
    }
}
