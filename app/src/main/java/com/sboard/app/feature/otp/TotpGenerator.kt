package com.sboard.app.feature.otp

import java.nio.ByteBuffer
import java.security.InvalidKeyException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP generator. The Base32 secret must come from an unlocked Vault record. */
object TotpGenerator {
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(
        base32Secret: String,
        timestampMillis: Long = System.currentTimeMillis(),
        digits: Int = 6,
        periodSeconds: Int = 30,
        algorithm: String = "SHA1"
    ): String {
        require(digits in 6..8) { "TOTP digits must be between 6 and 8" }
        require(periodSeconds in 15..120) { "TOTP period must be between 15 and 120 seconds" }
        require(timestampMillis >= 0) { "Timestamp must not be negative" }

        val normalizedAlgorithm = when (algorithm.uppercase()) {
            "SHA1", "HMAC-SHA1" -> "HmacSHA1"
            "SHA256", "HMAC-SHA256" -> "HmacSHA256"
            "SHA512", "HMAC-SHA512" -> "HmacSHA512"
            else -> throw IllegalArgumentException("Unsupported TOTP algorithm")
        }
        val secret = decodeBase32(base32Secret)
        try {
            require(secret.isNotEmpty()) { "TOTP secret is empty" }
            val counter = timestampMillis / (periodSeconds * 1000L)
            val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance(normalizedAlgorithm)
            mac.init(SecretKeySpec(secret, normalizedAlgorithm))
            val hash = mac.doFinal(counterBytes)
            val offset = hash[hash.lastIndex].toInt() and 0x0f
            val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
            val modulus = when (digits) {
                6 -> 1_000_000L
                7 -> 10_000_000L
                else -> 100_000_000L
            }
            return (binary.toLong() % modulus).toString().padStart(digits, '0')
        } catch (error: InvalidKeyException) {
            throw IllegalArgumentException("Invalid TOTP secret", error)
        } finally {
            secret.fill(0)
        }
    }

    /** Strict RFC 4648 Base32 decoder; whitespace and optional padding are accepted. */
    internal fun decodeBase32(value: String): ByteArray {
        val normalized = value.uppercase().filterNot { it.isWhitespace() || it == '-' }
        require(normalized.isNotEmpty()) { "TOTP secret is empty" }
        val withoutPadding = normalized.trimEnd('=')
        require(withoutPadding.none { it == '=' }) { "Invalid Base32 padding" }
        require(withoutPadding.all { it in BASE32_ALPHABET }) { "Invalid Base32 character" }

        var buffer = 0
        var bitsLeft = 0
        val output = ArrayList<Byte>((withoutPadding.length * 5) / 8)
        for (char in withoutPadding) {
            val value5 = BASE32_ALPHABET.indexOf(char)
            buffer = (buffer shl 5) or value5
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output += ((buffer shr bitsLeft) and 0xff).toByte()
            }
        }
        // RFC 4648 permits only zero unused bits in the final partial octet.
        if (bitsLeft > 0) {
            val mask = (1 shl bitsLeft) - 1
            require((buffer and mask) == 0) { "Non-zero unused Base32 bits" }
        }
        require(output.isNotEmpty()) { "TOTP secret is too short" }
        return output.toByteArray()
    }
}

data class TotpParameters(
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val algorithm: String = "SHA1"
) {
    init {
        require(digits in 6..8)
        require(periodSeconds in 15..120)
        require(algorithm.uppercase() in setOf("SHA1", "SHA256", "SHA512"))
    }

    companion object {
        /** Optional metadata in Vault notes: sboard-otp: digits=6;period=30;algorithm=SHA1 */
        fun fromVaultNotes(notes: String): TotpParameters {
            val line = notes.lineSequence().firstOrNull {
                it.trim().startsWith("sboard-otp:", ignoreCase = true)
            } ?: return TotpParameters()
            val values = line.substringAfter(':').split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                val value = part.substringAfter('=', "").trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
            return TotpParameters(
                digits = values["digits"]?.toIntOrNull() ?: 6,
                periodSeconds = values["period"]?.toIntOrNull() ?: 30,
                algorithm = values["algorithm"] ?: "SHA1"
            )
        }
    }
}
