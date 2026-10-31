package com.sboard.app.feature.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TotpGeneratorTest {
    // RFC 6238 SHA-1 test secret: ASCII "12345678901234567890" in Base32.
    private val sha1Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun matchesRfc6238Sha1VectorAt59Seconds() {
        assertEquals("287082", TotpGenerator.generate(sha1Secret, 59_000L))
    }

    @Test
    fun generatesEightDigitCode() {
        assertEquals(8, TotpGenerator.generate(sha1Secret, 59_000L, digits = 8).length)
    }

    @Test
    fun rejectsInvalidBase32() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.generate("NOT*BASE32", 59_000L)
        }
    }

    @Test
    fun rejectsInvalidDigits() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.generate(sha1Secret, 59_000L, digits = 5)
        }
    }
}
