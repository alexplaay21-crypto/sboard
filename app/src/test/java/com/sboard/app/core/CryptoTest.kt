package com.sboard.app.core

import com.sboard.app.core.crypto.AesGcm
import com.sboard.app.core.crypto.Argon2Kdf
import com.sboard.app.core.crypto.B64
import com.sboard.app.core.crypto.Hkdf
import com.sboard.app.core.crypto.KdfParams
import com.sboard.app.core.crypto.randomBytes
import com.sboard.app.core.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoTest {
    @Test fun aesGcmRoundTripWithAad() {
        val key = randomBytes(32)
        val sealed = AesGcm.encrypt(key, "secret".toByteArray(), "aad".toByteArray())
        assertEquals(12, sealed.iv.size)
        assertArrayEquals("secret".toByteArray(), AesGcm.decrypt(key, sealed.iv, sealed.ciphertext, "aad".toByteArray()))
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcmRejectsWrongAad() {
        val key = randomBytes(32)
        val sealed = AesGcm.encrypt(key, "secret".toByteArray(), "aad".toByteArray())
        AesGcm.decrypt(key, sealed.iv, sealed.ciphertext, "other".toByteArray())
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcmRejectsTamperedCiphertext() {
        val key = randomBytes(32)
        val sealed = AesGcm.encrypt(key, "secret".toByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 1).toByte()
        AesGcm.decrypt(key, sealed.iv, sealed.ciphertext)
    }

    @Test fun ivsAreUnique() {
        val key = randomBytes(32)
        val ivs = (1..200).map { B64.std(AesGcm.encrypt(key, byteArrayOf(1)).iv) }.toSet()
        assertEquals(200, ivs.size)
    }

    /** RFC 5869, test case 1. */
    @Test fun hkdfMatchesRfc5869Vector() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.derive(ikm, info, 42, salt)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test fun argon2idIsDeterministicAndSaltDependent() {
        val salt = ByteArray(16) { 1 }
        val params = KdfParams(32 * 1024, 2, 1)
        val a = Argon2Kdf.derive("correct horse".toByteArray(), salt, params)
        val b = Argon2Kdf.derive("correct horse".toByteArray(), salt, params)
        val c = Argon2Kdf.derive("correct horse".toByteArray(), ByteArray(16) { 2 }, params)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun argon2RejectsWeakParameters() {
        Argon2Kdf.derive("x".toByteArray(), ByteArray(16), KdfParams(1024, 1, 1))
    }
}
