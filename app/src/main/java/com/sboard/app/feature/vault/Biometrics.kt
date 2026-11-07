package com.sboard.app.feature.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.BiometricEnvelopeEntity
import com.sboard.app.data.VaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

sealed class AuthResult {
    data object Success : AuthResult()
    data object Cancelled : AuthResult()
    data class Failed(val message: String) : AuthResult()
}

/**
 * Biometric unlock binds the Vault key to an Android Keystore AES key that requires a fresh
 * BIOMETRIC_STRONG authentication for every use (CryptoObject), and is invalidated when new
 * fingerprints/faces are enrolled. The wrapped key is device-local and is never synced.
 */
object Biometrics {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "sboard_vault_biometric_v1"

    fun canUseStrong(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Authenticators used for "confirm it's you" prompts (biometric or screen lock). */
    private fun confirmAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun canConfirm(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(confirmAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun observeEnabled(context: Context): Flow<Boolean> =
        VaultDatabase.get(context).biometric().observeCount().map { it > 0 }

    suspend fun isEnabled(context: Context): Boolean = withContext(Dispatchers.IO) { VaultDatabase.get(context).biometric().get() != null }

    /** Enrolls biometric unlock for the currently unlocked Vault. */
    suspend fun enable(activity: FragmentActivity): AuthResult {
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, createKey()) }
        } catch (e: Exception) {
            return AuthResult.Failed(tr("Не удалось создать ключ биометрии"))
        }
        return when (val result = promptCrypto(activity, cipher, tr("Включить вход по биометрии"))) {
            is CryptoOutcome.Ok -> {
                val dao = VaultDatabase.get(activity).biometric()
                withContext(Dispatchers.IO) {
                    VaultSessionProvider.session.withKey { key ->
                        val ct = result.cipher.doFinal(key)
                        dao.put(BiometricEnvelopeEntity(iv = result.cipher.iv, ciphertext = ct))
                    }
                }
                AuthResult.Success
            }
            is CryptoOutcome.Cancelled -> AuthResult.Cancelled
            is CryptoOutcome.Error -> AuthResult.Failed(result.message)
        }
    }

    suspend fun disable(context: Context) = withContext(Dispatchers.IO) {
        VaultDatabase.get(context).biometric().delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(ALIAS) } }
    }

    /** Unlocks the Vault with biometrics. */
    suspend fun unlock(activity: FragmentActivity): AuthResult {
        val envelope = withContext(Dispatchers.IO) { VaultDatabase.get(activity).biometric().get() }
            ?: return AuthResult.Failed(tr("Биометрия не настроена"))
        val cipher = try {
            val key = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey
                ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(activity)
            return AuthResult.Failed(tr("Биометрические данные изменились. Войдите мастер-паролем и включите биометрию снова."))
        } catch (_: Exception) {
            return AuthResult.Failed(tr("Биометрическая разблокировка недоступна"))
        }
        return when (val result = promptCrypto(activity, cipher, tr("Открыть sPass"))) {
            is CryptoOutcome.Ok -> try {
                val vaultKey = result.cipher.doFinal(envelope.ciphertext)
                VaultKeyManager(activity).unlockWithRawKey(vaultKey)
                AuthResult.Success
            } catch (_: Exception) {
                AuthResult.Failed(tr("Не удалось расшифровать ключ хранилища"))
            }
            is CryptoOutcome.Cancelled -> AuthResult.Cancelled
            is CryptoOutcome.Error -> AuthResult.Failed(result.message)
        }
    }

    /**
     * Asks the user to confirm a sensitive action (fill a password, reveal a card) with biometrics or
     * screen lock. If the device has neither, the explicit in-app confirmation is the only gate.
     */
    suspend fun confirm(activity: FragmentActivity, title: String, subtitle: String? = null): AuthResult {
        if (!canConfirm(activity)) return AuthResult.Success
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(AuthResult.Success)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(if (errorCode.isCancel()) AuthResult.Cancelled else AuthResult.Failed(errString.toString()))
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(confirmAuthenticators())
                .setConfirmationRequired(true)
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }

    private sealed class CryptoOutcome {
        data class Ok(val cipher: Cipher) : CryptoOutcome()
        data object Cancelled : CryptoOutcome()
        data class Error(val message: String) : CryptoOutcome()
    }

    private suspend fun promptCrypto(activity: FragmentActivity, cipher: Cipher, title: String): CryptoOutcome =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (cont.isActive) cont.resume(if (c != null) CryptoOutcome.Ok(c) else CryptoOutcome.Error(tr("Не удалось получить криптографическую сессию")))
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(if (errorCode.isCancel()) CryptoOutcome.Cancelled else CryptoOutcome.Error(errString.toString()))
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(tr("Подтвердите личность для доступа к sBoard"))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText(tr("Мастер-пароль"))
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }

    private fun Int.isCancel() = this == BiometricPrompt.ERROR_USER_CANCELED ||
        this == BiometricPrompt.ERROR_NEGATIVE_BUTTON || this == BiometricPrompt.ERROR_CANCELED

    private fun createKey(): SecretKey {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(ALIAS) } }
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(builder.build()) }.generateKey()
    }
}
