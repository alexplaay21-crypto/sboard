package com.sboard.app.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared secure random source. */
internal val secureRandom: SecureRandom by lazy { SecureRandom() }

fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

/** AES-256-GCM with a fresh 96-bit IV per message and optional associated data. */
object AesGcm {
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32

    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): Sealed {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return Sealed(iv, cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        require(iv.size == IV_BYTES) { "Invalid IV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

/** Argon2id parameters; persisted next to every password-wrapped key. */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    fun validate() {
        require(memoryKiB in 32 * 1024..256 * 1024) { "Unsupported Argon2 memory" }
        require(iterations in 2..10) { "Unsupported Argon2 iterations" }
        require(parallelism in 1..4) { "Unsupported Argon2 parallelism" }
    }

    companion object {
        /** OWASP-recommended class of parameters, tuned for phones (64 MiB, t=3, p=1). */
        val DEFAULT = KdfParams(64 * 1024, 3, 1)
    }
}

object Argon2Kdf {
    const val SALT_BYTES = 16

    /**
     * Native libargon2 (argon2kt) is ~5–10× faster than the pure-Java implementation on phones,
     * which keeps 64 MiB / t=3 unlocks around a second. Both produce identical RFC 9106 output;
     * the Java implementation is the fallback (JVM unit tests, devices without the native lib).
     */
    fun derive(secret: ByteArray, salt: ByteArray, params: KdfParams, length: Int = 32): ByteArray {
        params.validate()
        require(salt.size >= SALT_BYTES) { "Salt too short" }
        nativeDerive(secret, salt, params, length)?.let { return it }
        return deriveJava(secret, salt, params, length)
    }

    @Volatile private var nativeUnavailable = false

    private fun nativeDerive(secret: ByteArray, salt: ByteArray, params: KdfParams, length: Int): ByteArray? {
        if (nativeUnavailable) return null
        return try {
            com.lambdapioneer.argon2kt.Argon2Kt().hash(
                mode = com.lambdapioneer.argon2kt.Argon2Mode.ARGON2_ID,
                password = secret,
                salt = salt,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKiB,
                parallelism = params.parallelism,
                hashLengthInBytes = length,
            ).rawHashAsByteArray()
        } catch (_: Throwable) {
            nativeUnavailable = true
            null
        }
    }

    /** Pure-Java Argon2id (BouncyCastle). */
    fun deriveJava(secret: ByteArray, salt: ByteArray, params: KdfParams, length: Int = 32): ByteArray {
        params.validate()
        require(salt.size >= SALT_BYTES) { "Salt too short" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        return ByteArray(length).also { out ->
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(secret, out)
        }
    }
}

/** RFC 5869 HKDF-SHA256, used to derive purpose-specific subkeys from the Vault key. */
object Hkdf {
    fun derive(ikm: ByteArray, info: String, length: Int = 32, salt: ByteArray = ByteArray(32)): ByteArray =
        derive(ikm, info.toByteArray(StandardCharsets.UTF_8), length, salt)

    fun derive(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray): ByteArray {
        require(length in 1..255 * 32) { "Invalid HKDF length" }
        val prk = hmac(salt, ikm)
        try {
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                val n = minOf(block.size, length - offset)
                System.arraycopy(block, 0, out, offset, n)
                offset += n
                previous.fill(0)
                previous = block
                counter++
            }
            previous.fill(0)
            return out
        } finally {
            prk.fill(0)
        }
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

object Sha256 {
    fun of(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hex(data: ByteArray): String = of(data).toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

object B64 {
    fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromUrl(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trim().trimEnd('='))
    fun std(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun fromStd(value: String): ByteArray = Base64.getDecoder().decode(value.trim())
}

/** Constant-time comparison for MACs and hashes. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/** UTF-8 encode a CharArray without creating an immutable String copy. */
fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer: ByteBuffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    val out = ByteArray(buffer.remaining())
    buffer.get(out)
    if (buffer.hasArray()) buffer.array().fill(0)
    return out
}
