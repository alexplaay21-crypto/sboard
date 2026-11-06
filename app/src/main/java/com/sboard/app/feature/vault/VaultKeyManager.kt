package com.sboard.app.feature.vault

import android.content.Context
import androidx.room.withTransaction
import com.sboard.app.core.crypto.AesGcm
import com.sboard.app.core.crypto.Argon2Kdf
import com.sboard.app.core.crypto.B64
import com.sboard.app.core.crypto.KdfParams
import com.sboard.app.core.crypto.randomBytes
import com.sboard.app.core.crypto.toUtf8Bytes
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSession
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.LegacyVaultMigrator
import com.sboard.app.data.RecoveryEnvelopeEntity
import com.sboard.app.data.VaultConfigEntity
import com.sboard.app.data.VaultDatabase
import com.sboard.app.data.put
import com.sboard.app.feature.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

/** A Vault key wrapped by a secret (master password or Recovery Key) — safe to upload. */
@Serializable
data class KeyWrap(
    val kind: String,
    val salt: String,
    val iv: String,
    val ct: String,
    val m: Int,
    val t: Int,
    val p: Int,
) {
    companion object {
        const val PASSWORD = "password"
        const val RECOVERY = "recovery"
    }
}

class WrongSecretException(message: String) : IllegalArgumentException(message)

/**
 * Manages the Vault key hierarchy:
 *   random 256-bit Vault key  ← AES-256-GCM ←  Argon2id(master password)
 *                             ← AES-256-GCM ←  Argon2id(Recovery Key)       (optional)
 *                             ← AES-256-GCM ←  Android Keystore biometric key (device-local)
 * The master password and the Google account password are independent.
 */
class VaultKeyManager(context: Context, private val session: VaultSession = VaultSessionProvider.session) {
    private val appContext = context.applicationContext
    private val database = VaultDatabase.get(appContext)
    private val config = database.config()
    private val recovery = database.recovery()
    private val meta = database.meta()

    suspend fun hasVault(): Boolean = withContext(Dispatchers.IO) { config.get() != null }
    suspend fun hasRecoveryKey(): Boolean = withContext(Dispatchers.IO) { recovery.get() != null }
    suspend fun vaultId(): String? = withContext(Dispatchers.IO) { meta.get(META_VAULT_ID) }
    suspend fun keyUpdatedAt(): Long = withContext(Dispatchers.IO) { meta.get(META_KEY_UPDATED)?.toLongOrNull() ?: 0L }

    /** Creates a brand-new Vault protected by [password]. */
    suspend fun createVault(password: CharArray) = withContext(Dispatchers.Default) {
        try {
            validateNewPassword(password)
            check(config.get() == null) { tr("Хранилище уже создано") }
            val vaultKey = randomBytes(32)
            try {
                val wrap = wrap(KeyWrap.PASSWORD, password.toUtf8Bytes(), vaultKey, PASSWORD_AAD)
                database.withTransaction {
                    config.put(wrap.toConfig())
                    meta.put(META_VAULT_ID, UUID.randomUUID().toString())
                    meta.put(META_KEY_UPDATED, System.currentTimeMillis().toString())
                    meta.delete("legacy_records_pending")
                }
                session.unlock(vaultKey)
            } finally { vaultKey.fill(0) }
        } finally { password.fill('\u0000') }
    }

    suspend fun unlockWithPassword(password: CharArray) = withContext(Dispatchers.Default) {
        try {
            val cfg = config.get() ?: error(tr("Хранилище ещё не создано"))
            val vaultKey = unwrap(cfg.toWrap(), password.toUtf8Bytes(), PASSWORD_AAD)
                ?: throw WrongSecretException(tr("Неверный мастер-пароль"))
            finishUnlock(vaultKey)
        } finally { password.fill('\u0000') }
    }

    suspend fun unlockWithRecoveryKey(code: String) = withContext(Dispatchers.Default) {
        val env = recovery.get() ?: error(tr("Recovery Key не настроен"))
        val vaultKey = unwrap(env.toWrap(), normalizeRecoveryKey(code).toByteArray(Charsets.US_ASCII), RECOVERY_AAD)
            ?: throw WrongSecretException(tr("Неверный Recovery Key"))
        finishUnlock(vaultKey)
    }

    /** Called by biometric unlock once the Keystore cipher released the key. */
    suspend fun unlockWithRawKey(vaultKey: ByteArray) = withContext(Dispatchers.Default) { finishUnlock(vaultKey) }

    private suspend fun finishUnlock(vaultKey: ByteArray) {
        try {
            require(vaultKey.size == 32) { tr("Повреждён ключ хранилища") }
            session.unlock(vaultKey)
            runCatching { LegacyVaultMigrator.migrateIfNeeded(appContext, vaultKey) }
        } finally { vaultKey.fill(0) }
        SyncScheduler.requestSoon(appContext)
    }

    /** Re-wraps the existing Vault key; items are not re-encrypted. Propagates via sync. */
    suspend fun changeMasterPassword(current: CharArray, newPassword: CharArray) = withContext(Dispatchers.Default) {
        try {
            validateNewPassword(newPassword)
            val cfg = config.get() ?: error(tr("Хранилище ещё не создано"))
            val vaultKey = unwrap(cfg.toWrap(), current.toUtf8Bytes(), PASSWORD_AAD)
                ?: throw WrongSecretException(tr("Текущий мастер-пароль неверный"))
            try {
                val wrap = wrap(KeyWrap.PASSWORD, newPassword.toUtf8Bytes(), vaultKey, PASSWORD_AAD)
                database.withTransaction {
                    config.put(wrap.toConfig())
                    meta.put(META_KEY_UPDATED, System.currentTimeMillis().toString())
                }
            } finally { vaultKey.fill(0) }
        } finally {
            current.fill('\u0000'); newPassword.fill('\u0000')
        }
        SyncScheduler.requestSoon(appContext, keyMaterialChanged = true)
    }

    /** Generates a new 256-bit Recovery Key, replacing any previous one. Shown to the user once. */
    suspend fun createRecoveryKey(): String = withContext(Dispatchers.Default) {
        val raw = randomBytes(32)
        try {
            val code = B64.url(raw)
            session.withKey { key ->
                val wrap = wrap(KeyWrap.RECOVERY, code.toByteArray(Charsets.US_ASCII), key, RECOVERY_AAD)
                database.withTransaction {
                    recovery.put(wrap.toRecovery())
                    meta.put(META_KEY_UPDATED, System.currentTimeMillis().toString())
                }
            }
            SyncScheduler.requestSoon(appContext, keyMaterialChanged = true)
            code
        } finally { raw.fill(0) }
    }

    suspend fun removeRecoveryKey() = withContext(Dispatchers.IO) {
        database.withTransaction {
            recovery.delete()
            meta.put(META_KEY_UPDATED, System.currentTimeMillis().toString())
        }
        SyncScheduler.requestSoon(appContext, keyMaterialChanged = true)
    }

    // ---- Sync / restore support -------------------------------------------------------------

    suspend fun exportWraps(): List<KeyWrap> = withContext(Dispatchers.IO) {
        listOfNotNull(config.get()?.toWrap(), recovery.get()?.toWrap())
    }

    /** Unwraps a cloud key using the password or Recovery Key the user typed on this device. */
    suspend fun unwrapCloudKey(wraps: List<KeyWrap>, secret: String): ByteArray = withContext(Dispatchers.Default) {
        val password = wraps.firstOrNull { it.kind == KeyWrap.PASSWORD }
        val rec = wraps.firstOrNull { it.kind == KeyWrap.RECOVERY }
        password?.let { unwrap(it, secret.toByteArray(Charsets.UTF_8), PASSWORD_AAD) }
            ?: rec?.let { w -> runCatching { normalizeRecoveryKey(secret) }.getOrNull()?.let { unwrap(w, it.toByteArray(Charsets.US_ASCII), RECOVERY_AAD) } }
            ?: throw WrongSecretException(tr("Пароль не подходит к облачному хранилищу"))
    }

    /** Adopts key wraps (and optionally the vault id) from the cloud; used on restore and after remote password change. */
    suspend fun adoptWraps(wraps: List<KeyWrap>, vaultId: String, keyUpdatedAt: Long) = withContext(Dispatchers.IO) {
        val password = wraps.firstOrNull { it.kind == KeyWrap.PASSWORD } ?: error("Cloud backup has no password wrap")
        val rec = wraps.firstOrNull { it.kind == KeyWrap.RECOVERY }
        database.withTransaction {
            config.put(password.toConfig())
            if (rec != null) recovery.put(rec.toRecovery()) else recovery.delete()
            meta.put(META_VAULT_ID, vaultId)
            meta.put(META_KEY_UPDATED, keyUpdatedAt.toString())
        }
    }

    // ---- Internals ------------------------------------------------------------------------------

    private fun validateNewPassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) { tr("Мастер-пароль должен содержать минимум 10 символов") }
    }

    private fun wrap(kind: String, secret: ByteArray, vaultKey: ByteArray, aad: ByteArray): KeyWrap {
        val salt = randomBytes(Argon2Kdf.SALT_BYTES)
        val params = KdfParams.DEFAULT
        val derived = Argon2Kdf.derive(secret, salt, params)
        try {
            val sealed = AesGcm.encrypt(derived, vaultKey, aad)
            return KeyWrap(kind, B64.std(salt), B64.std(sealed.iv), B64.std(sealed.ciphertext), params.memoryKiB, params.iterations, params.parallelism)
        } finally {
            derived.fill(0); secret.fill(0)
        }
    }

    private fun unwrap(wrap: KeyWrap, secret: ByteArray, aad: ByteArray): ByteArray? {
        val params = KdfParams(wrap.m, wrap.t, wrap.p)
        return try {
            params.validate()
            val salt = B64.fromStd(wrap.salt)
            fun attempt(derived: ByteArray): ByteArray? = try {
                runCatching { AesGcm.decrypt(derived, B64.fromStd(wrap.iv), B64.fromStd(wrap.ct), aad) }.getOrNull()?.takeIf { it.size == 32 }
            } finally { derived.fill(0) }
            val prefs = appContext.getSharedPreferences("sboard_kdf", Context.MODE_PRIVATE)
            attempt(Argon2Kdf.derive(secret, salt, params))?.also { prefs.edit().putBoolean("native_verified", true).apply() }
                // Until the fast native path has been confirmed once on this device, cross-check with
                // the Java implementation so an implementation difference can never lock a user out.
                ?: if (prefs.getBoolean("native_verified", false)) null else attempt(Argon2Kdf.deriveJava(secret, salt, params))
        } catch (_: Exception) {
            null
        } finally { secret.fill(0) }
    }

    private fun VaultConfigEntity.toWrap() = KeyWrap(KeyWrap.PASSWORD, B64.std(salt), B64.std(iv), B64.std(wrappedVaultKey), memoryKiB, iterations, parallelism)
    private fun RecoveryEnvelopeEntity.toWrap() = KeyWrap(KeyWrap.RECOVERY, B64.std(salt), B64.std(iv), B64.std(ciphertext), memoryKiB, iterations, parallelism)
    private fun KeyWrap.toConfig() = VaultConfigEntity(salt = B64.fromStd(salt), iv = B64.fromStd(iv), wrappedVaultKey = B64.fromStd(ct), memoryKiB = m, iterations = t, parallelism = p)
    private fun KeyWrap.toRecovery() = RecoveryEnvelopeEntity(salt = B64.fromStd(salt), iv = B64.fromStd(iv), ciphertext = B64.fromStd(ct), memoryKiB = m, iterations = t, parallelism = p)

    companion object {
        const val MIN_PASSWORD_LENGTH = 10
        const val META_VAULT_ID = "vault_id"
        const val META_KEY_UPDATED = "key_updated_at"
        private val PASSWORD_AAD = "sBoard:VaultKey:v1".toByteArray(Charsets.UTF_8)
        private val RECOVERY_AAD = "sBoard:RecoveryEnvelope:v1".toByteArray(Charsets.UTF_8)

        fun normalizeRecoveryKey(input: String): String {
            val compact = input.filterNot { it.isWhitespace() }
            require(compact.matches(Regex("^[A-Za-z0-9_-]{43}$"))) { tr("Recovery Key должен содержать 43 символа") }
            return compact
        }

        fun formatRecoveryKey(code: String): String = code.chunked(4).joinToString(" ")
    }
}
