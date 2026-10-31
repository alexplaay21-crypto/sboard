package com.sboard.app.feature.vault

import com.sboard.app.tr
import com.sboard.app.feature.passkeys.PasskeyRepository

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Recovery code contains 256 bits of random entropy. It is shown once and never persisted in plaintext. */
class VaultRecoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val dao = VaultDatabase.get(appContext).recovery()
    private val random = SecureRandom()

    suspend fun hasRecoveryKey(): Boolean = dao.get() != null

    suspend fun createRecoveryKey(session: VaultSession): String = createRecoveryKeyInternal(session, replaceExisting = false)

    private suspend fun createRecoveryKeyInternal(session: VaultSession, replaceExisting: Boolean): String {
        if (!replaceExisting) check(dao.get() == null) { tr("Recovery Key уже создан. Используйте обновление ключа.") }
        val raw = ByteArray(32).also(random::nextBytes)
        try {
            val code = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val salt = ByteArray(16).also(random::nextBytes)
            val derived = withContext(Dispatchers.Default) { derive(code.toByteArray(Charsets.US_ASCII), salt, MEMORY_KIB, ITERATIONS, PARALLELISM) }
            try {
                val vaultKey = session.copyKey()
                try {
                    val iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
                    cipher.updateAAD(AAD)
                    val wrapped = cipher.doFinal(vaultKey)
                    dao.put(
                        RecoveryEnvelopeEntity(
                            salt = salt,
                            iv = iv,
                            ciphertext = wrapped,
                            memoryKiB = MEMORY_KIB,
                            iterations = ITERATIONS,
                            parallelism = PARALLELISM
                        )
                    )
                    return code
                } finally {
                    vaultKey.fill(0)
                }
            } finally {
                derived.fill(0)
            }
        } finally {
            raw.fill(0)
        }
    }

    suspend fun recover(recoveryCode: String, session: VaultSession) {
        require(recoveryCode.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            tr("Проверьте Recovery Key: формат неверный")
        }
        val envelope = dao.get() ?: error(tr("Recovery Key не настроен"))
        require(envelope.salt.size == 16 && envelope.iv.size == 12)
        require(envelope.ciphertext.size == 48)
        require(envelope.memoryKiB in 32 * 1024..256 * 1024)
        require(envelope.iterations in 2..10 && envelope.parallelism in 1..4)

        val codeBytes = recoveryCode.toByteArray(Charsets.US_ASCII)
        val derived = withContext(Dispatchers.Default) {
            derive(
                codeBytes, envelope.salt,
                envelope.memoryKiB, envelope.iterations, envelope.parallelism
            )
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, envelope.iv))
            cipher.updateAAD(AAD)
            val vaultKey = try {
                cipher.doFinal(envelope.ciphertext)
            } catch (_: Exception) {
                throw IllegalArgumentException(tr("Recovery Key неверный или данные повреждены"))
            }
            try {
                require(vaultKey.size == 32) { tr("Повреждён ключ хранилища") }
                session.unlock(vaultKey)
                runCatching { PasskeyRepository(appContext, session).rebuildDeviceDiscoveryCache() }
            } finally {
                vaultKey.fill(0)
            }
        } finally {
            codeBytes.fill(0)
            derived.fill(0)
        }
    }

    suspend fun replaceRecoveryKey(session: VaultSession): String =
        createRecoveryKeyInternal(session, replaceExisting = true)

    suspend fun removeRecoveryKey() = dao.delete()

    private fun derive(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        return ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password, it) }
    }

    private companion object {
        const val MEMORY_KIB = 64 * 1024
        const val ITERATIONS = 3
        const val PARALLELISM = 1
        val AAD = "sBoard:RecoveryEnvelope:v1".toByteArray(Charsets.UTF_8)
    }
}
