package com.sboard.app.feature.passkeys

import com.sboard.app.feature.passkeys.PasskeyPrivateKeyCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PasskeyPrivateKeyCipherTest {
    @Test
    fun roundTripPreservesPrivateKeyBytes() {
        val key = ByteArray(32) { it.toByte() }
        val privateKey = ByteArray(96) { (it * 3).toByte() }
        val encrypted = PasskeyPrivateKeyCipher.encrypt(privateKey, key, "record-1", "example.com")
        val restored = PasskeyPrivateKeyCipher.decrypt(encrypted, key, "record-1", "example.com")
        assertArrayEquals(privateKey, restored)
        restored.fill(0)
        key.fill(0)
    }

    @Test
    fun ciphertextCannotBeMovedToAnotherRpId() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val encrypted = PasskeyPrivateKeyCipher.encrypt(
            byteArrayOf(1, 2, 3, 4), key, "record-1", "example.com"
        )
        assertThrows(Exception::class.java) {
            PasskeyPrivateKeyCipher.decrypt(encrypted, key, "record-1", "attacker.example")
        }
        key.fill(0)
    }
}
