package com.sboard.app.feature.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opt-in, device-bound storage for the Recovery Key used only by scheduled backups.
 * The Recovery Key is encrypted with a non-exportable Android Keystore AES key; neither the
 * plaintext key nor an OAuth token is written to preferences or WorkManager input.
 *
 * This is a convenience copy, not a replacement for the user's offline Recovery Key.
 * It is unavailable after uninstall/device loss and may become unavailable if Keystore data
 * is cleared or the Android Keystore key is invalidated.
 */
class DeviceBoundBackupKeyStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun isConfigured(): Boolean = synchronized(lock) {
        prefs.getInt(KEY_VERSION, 0) == FORMAT_VERSION &&
            !prefs.getString(KEY_IV, null).isNullOrBlank() &&
            !prefs.getString(KEY_CIPHERTEXT, null).isNullOrBlank()
    }

    /** Call only after the user explicitly enables background backup and confirms this choice. */
    fun saveForBackgroundBackup(recoveryKey: String) = synchronized(lock) {
        require(BackupCredentialPolicy.isValidRecoveryKey(recoveryKey)) {
            "Recovery Key format is invalid"
        }
        val plain = recoveryKey.toByteArray(Charsets.US_ASCII)
        try {
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(plain)
            try {
                val ok = prefs.edit()
                    .putInt(KEY_VERSION, FORMAT_VERSION)
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
                check(ok) { "Could not persist encrypted backup credential" }
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Returns a short-lived in-memory copy. The caller must clear it after the backup run. */
    fun acquireForBackup(): CharArray? = synchronized(lock) {
        if (!isConfigured()) return null
        require(prefs.getInt(KEY_VERSION, 0) == FORMAT_VERSION) { "Unsupported backup credential version" }
        val iv = decode(prefs.getString(KEY_IV, null) ?: return null)
        val encrypted = decode(prefs.getString(KEY_CIPHERTEXT, null) ?: return null)
        try {
            require(iv.size == IV_BYTES && encrypted.size == RECOVERY_KEY_BYTES + TAG_BYTES) {
                "Stored backup credential is damaged"
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(encrypted)
            try {
                val value = plain.toString(Charsets.US_ASCII)
                require(BackupCredentialPolicy.isValidRecoveryKey(value)) { "Stored backup credential is invalid" }
                value.toCharArray()
            } finally {
                plain.fill(0)
            }
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    /** Removes the encrypted copy and its wrapping key. The user's offline Recovery Key is unaffected. */
    fun clear() = synchronized(lock) {
        check(prefs.edit().clear().commit()) { "Could not clear backup credential preferences" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = getKeyOrNull()
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey = getKeyOrNull()
        ?: throw IllegalStateException("Android Keystore backup key is unavailable; re-enable backup credentials")

    private fun getKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
    }

    private fun decode(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException("Stored backup credential encoding is damaged")
    }

    private companion object {
        const val PREFS = "sboard_backup_device_credential_v1"
        const val KEY_VERSION = "format_version"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "com.sboard.backup.recovery-key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val RECOVERY_KEY_BYTES = 43
        val AAD = "sBoard:DeviceBoundBackupRecoveryKey:v1".toByteArray(Charsets.UTF_8)
    }
}
