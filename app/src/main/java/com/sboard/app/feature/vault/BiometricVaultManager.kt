package com.sboard.app.feature.vault

import com.sboard.app.tr
import com.sboard.app.feature.passkeys.PasskeyAuthorization
import com.sboard.app.feature.passkeys.PasskeyRepository

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-operation biometric authorization: the prompt receives the actual Cipher in CryptoObject. */
class BiometricVaultManager(context: Context) {
    private val appContext = context.applicationContext
    private val dao = VaultDatabase.get(appContext).biometric()

    suspend fun isEnabled(): Boolean = dao.get() != null

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prepareEnrollmentCipher(): Cipher {
        val key = getOrCreateAuthBoundKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    suspend fun saveEnrollment(cipher: Cipher, session: VaultSession) {
        val vaultKey = session.copyKey()
        try {
            val encrypted = cipher.doFinal(vaultKey)
            dao.put(BiometricEnvelopeEntity(iv = cipher.iv, ciphertext = encrypted))
        } finally {
            vaultKey.fill(0)
        }
    }

    suspend fun prepareUnlockCipher(): Cipher? {
        val envelope = dao.get() ?: return null
        val key = try { getExistingKey() } catch (_: Exception) {
            disable()
            return null
        } ?: run {
            dao.delete()
            return null
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            }
        } catch (_: Exception) {
            disable()
            null
        }
    }

    suspend fun finishUnlock(cipher: Cipher, session: VaultSession) {
        val envelope = dao.get() ?: error(tr("Биометрия не настроена"))
        val vaultKey = cipher.doFinal(envelope.ciphertext)
        try {
            require(vaultKey.size == 32) { tr("Повреждён биометрический ключ") }
            session.unlock(vaultKey)
            runCatching { PasskeyRepository(appContext, session).rebuildDeviceDiscoveryCache() }
        } finally {
            vaultKey.fill(0)
        }
    }

    /** Call only from the successful BiometricPrompt callback, passing its authenticated Cipher. */
    suspend fun authorizePasskeyAfterBiometric(
        authenticatedCipher: Cipher,
        session: VaultSession,
        recordId: String
    ): PasskeyAuthorization {
        finishUnlock(authenticatedCipher, session)
        return PasskeyAuthorization.issue(recordId)
    }

    suspend fun disable() {
        dao.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(ALIAS) }
        }
    }

    private fun getExistingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
    }

    private fun getOrCreateAuthBoundKey(): SecretKey {
        try { getExistingKey()?.let { return it } } catch (_: Exception) {
            runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(ALIAS) } }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sboard_vault_biometric_v1"

        fun authenticate(
            activity: FragmentActivity,
            cipher: Cipher,
            title: String,
            onSuccess: (Cipher) -> Unit,
            onError: (String) -> Unit
        ) {
            val executor: Executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) onError(tr("Не удалось получить криптографическую сессию"))
                        else onSuccess(authenticatedCipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onError(errString.toString())
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(tr("Подтвердите личность для доступа к sBoard Vault"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(tr("Отмена"))
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
