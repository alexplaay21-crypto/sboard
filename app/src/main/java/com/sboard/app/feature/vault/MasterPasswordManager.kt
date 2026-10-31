package com.sboard.app.feature.vault

import com.sboard.app.tr
import com.sboard.app.feature.passkeys.PasskeyRepository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One process-wide session shared by the app UI and Android Autofill service. */
object VaultSessionProvider {
    val session = VaultSession()
}

/** Holds the unwrapped Vault key only in process memory. Call lock() when the Vault is closed. */
class VaultSession {
    private val monitor = Any()
    private var key: ByteArray? = null

    val isUnlocked: Boolean
        get() = synchronized(monitor) { key != null }

    internal fun unlock(vaultKey: ByteArray) = synchronized(monitor) {
        require(vaultKey.size == 32)
        key?.fill(0)
        key = vaultKey.copyOf()
    }

    fun copyKey(): ByteArray = synchronized(monitor) {
        (key ?: error("Vault is locked")).copyOf()
    }

    fun lock() = synchronized(monitor) {
        key?.fill(0)
        key = null
    }
}

class MasterPasswordManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = VaultDatabase.get(appContext)
    private val configDao = database.config()
    private val recordsDao = database.records()
    private val random = SecureRandom()

    suspend fun hasMasterPassword(): Boolean = configDao.get() != null

    /** First-time setup. Existing v1 device-key records are re-encrypted before config is committed. */
    suspend fun createMasterPassword(password: CharArray, session: VaultSession) {
        var passwordBytes: ByteArray? = null
        var derivedKey: ByteArray? = null
        var vaultKey: ByteArray? = null
        try {
            require(password.size >= 12) { tr("Мастер-пароль должен содержать минимум 12 символов") }
            check(configDao.get() == null) { tr("Мастер-пароль уже создан") }

            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val generatedVaultKey = ByteArray(VAULT_KEY_BYTES).also(random::nextBytes)
            vaultKey = generatedVaultKey
            val passwordData = passwordToUtf8(password)
            passwordBytes = passwordData
            val wrappingKey = withContext(Dispatchers.Default) { deriveKey(passwordData, salt, MEMORY_KIB, ITERATIONS, PARALLELISM) }
            derivedKey = wrappingKey
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val wrapped = aesGcm(Cipher.ENCRYPT_MODE, wrappingKey, iv, WRAP_AAD, generatedVaultKey)
            val config = VaultConfigEntity(
                salt = salt,
                iv = iv,
                wrappedVaultKey = wrapped,
                memoryKiB = MEMORY_KIB,
                iterations = ITERATIONS,
                parallelism = PARALLELISM
            )

            // Upgrade v1 device-key records in the same transaction as the new key envelope.
            database.withTransaction {
                recordsDao.getAll().forEach { old ->
                    val plain = LegacyDeviceCipher.decrypt(old.id, old.iv, old.ciphertext)
                    try {
                        val (newIv, newCiphertext) = encryptRecord(old.id, plain, generatedVaultKey)
                        recordsDao.insert(old.copy(iv = newIv, ciphertext = newCiphertext))
                    } finally {
                        plain.fill(0)
                    }
                }
                configDao.put(config)
            }
            session.unlock(generatedVaultKey)
        } finally {
            passwordBytes?.fill(0)
            derivedKey?.fill(0)
            vaultKey?.fill(0)
            password.fill('\u0000')
        }
    }

    /** Re-wraps the existing Vault key; records are not decrypted or re-encrypted. */
    suspend fun changeMasterPassword(newPassword: CharArray, session: VaultSession) {
        var passwordBytes: ByteArray? = null
        var derivedKey: ByteArray? = null
        var vaultKey: ByteArray? = null
        try {
            require(newPassword.size >= 12) { tr("Мастер-пароль должен содержать минимум 12 символов") }
            check(configDao.get() != null) { tr("Мастер-пароль ещё не создан") }
            vaultKey = session.copyKey()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            passwordBytes = passwordToUtf8(newPassword)
            derivedKey = withContext(Dispatchers.Default) { deriveKey(passwordBytes!!, salt, MEMORY_KIB, ITERATIONS, PARALLELISM) }
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val wrapped = aesGcm(Cipher.ENCRYPT_MODE, derivedKey!!, iv, WRAP_AAD, vaultKey!!)
            configDao.put(
                VaultConfigEntity(
                    salt = salt,
                    iv = iv,
                    wrappedVaultKey = wrapped,
                    memoryKiB = MEMORY_KIB,
                    iterations = ITERATIONS,
                    parallelism = PARALLELISM
                )
            )
        } finally {
            passwordBytes?.fill(0)
            derivedKey?.fill(0)
            vaultKey?.fill(0)
            newPassword.fill('\u0000')
        }
    }

    suspend fun unlock(password: CharArray, session: VaultSession) {
        var passwordBytes: ByteArray? = null
        var derivedKey: ByteArray? = null
        try {
            val config = configDao.get() ?: error(tr("Мастер-пароль ещё не создан"))
            validateKdf(config)
            val passwordData = passwordToUtf8(password)
            passwordBytes = passwordData
            val wrappingKey = withContext(Dispatchers.Default) {
                deriveKey(
                    passwordData,
                    config.salt,
                    config.memoryKiB,
                    config.iterations,
                    config.parallelism
                )
            }
            derivedKey = wrappingKey
            val vaultKey = try {
                aesGcm(
                    Cipher.DECRYPT_MODE,
                    wrappingKey,
                    config.iv,
                    WRAP_AAD,
                    config.wrappedVaultKey
                )
            } catch (_: Exception) {
                throw IllegalArgumentException(tr("Неверный мастер-пароль или повреждена конфигурация"))
            }
            try {
                require(vaultKey.size == VAULT_KEY_BYTES) { tr("Повреждён ключ хранилища") }
                session.unlock(vaultKey)
                // On a restored device, rebuild its Keystore-backed Passkey discovery cache.
                runCatching { PasskeyRepository(appContext, session).rebuildDeviceDiscoveryCache() }
            } finally {
                vaultKey.fill(0)
            }
        } finally {
            passwordBytes?.fill(0)
            derivedKey?.fill(0)
            password.fill('\u0000')
        }
    }

    private fun validateKdf(config: VaultConfigEntity) {
        require(config.salt.size == SALT_BYTES && config.iv.size == IV_BYTES)
        require(config.wrappedVaultKey.size == VAULT_KEY_BYTES + TAG_BYTES)
        require(config.memoryKiB in 32 * 1024..256 * 1024)
        require(config.iterations in 2..10)
        require(config.parallelism in 1..4)
    }

    private fun passwordToUtf8(password: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }.also {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        return ByteArray(32).also { output ->
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, output)
        }
    }

    private fun aesGcm(
        mode: Int,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun encryptRecord(id: String, plain: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        return iv to aesGcm(Cipher.ENCRYPT_MODE, key, iv, id.toByteArray(Charsets.UTF_8), plain)
    }

    private object LegacyDeviceCipher {
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "sboard_local_vault_v1"

        private fun key(): SecretKey {
            val store = KeyStore.getInstance(STORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }

        fun decrypt(id: String, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(ciphertext)
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val VAULT_KEY_BYTES = 32
        const val MEMORY_KIB = 64 * 1024
        const val ITERATIONS = 3
        const val PARALLELISM = 1
        val WRAP_AAD = "sBoard:VaultKey:v1".toByteArray(Charsets.UTF_8)
    }
}
