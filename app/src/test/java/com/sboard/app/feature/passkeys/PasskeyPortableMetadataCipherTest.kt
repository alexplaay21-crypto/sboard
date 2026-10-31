package com.sboard.app.feature.passkeys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PasskeyPortableMetadataCipherTest {
    private fun metadata() = PasskeyMetadataCipher.Metadata(
        rpId = "example.com",
        rpName = "Example",
        userName = "alex",
        displayName = "Alex",
        userHandle = byteArrayOf(1, 2, 3, 4),
        publicKeyCose = byteArrayOf(5, 6, 7, 8, 9)
    )

    @Test
    fun roundTripUsesVaultKeyAndPreservesAllMetadata() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val original = metadata()
        val encrypted = PasskeyPortableMetadataCipher.encrypt("passkey-1", original, key)
        val restored = PasskeyPortableMetadataCipher.decrypt("passkey-1", encrypted, key)

        assertEquals(original.rpId, restored.rpId)
        assertEquals(original.rpName, restored.rpName)
        assertEquals(original.userName, restored.userName)
        assertEquals(original.displayName, restored.displayName)
        assertArrayEquals(original.userHandle, restored.userHandle)
        assertArrayEquals(original.publicKeyCose, restored.publicKeyCose)
        key.fill(0)
        restored.userHandle.fill(0)
        restored.publicKeyCose.fill(0)
    }

    @Test
    fun anotherDeviceCanDecryptWithSameVaultKey() {
        val sameVaultKeyRestoredOnSecondDevice = ByteArray(32) { (it * 2).toByte() }
        val encrypted = PasskeyPortableMetadataCipher.encrypt(
            "passkey-2", metadata(), sameVaultKeyRestoredOnSecondDevice
        )
        val secondDeviceKey = sameVaultKeyRestoredOnSecondDevice.copyOf()
        val restored = PasskeyPortableMetadataCipher.decrypt("passkey-2", encrypted, secondDeviceKey)
        assertEquals("example.com", restored.rpId)
        secondDeviceKey.fill(0)
        sameVaultKeyRestoredOnSecondDevice.fill(0)
        restored.userHandle.fill(0)
        restored.publicKeyCose.fill(0)
    }

    @Test
    fun rejectsWrongVaultKey() {
        val key = ByteArray(32) { 3 }
        val wrongKey = ByteArray(32) { 4 }
        val encrypted = PasskeyPortableMetadataCipher.encrypt("passkey-3", metadata(), key)
        assertThrows(Exception::class.java) {
            PasskeyPortableMetadataCipher.decrypt("passkey-3", encrypted, wrongKey)
        }
        key.fill(0)
        wrongKey.fill(0)
    }

    @Test
    fun ciphertextCannotBeMovedToAnotherRecord() {
        val key = ByteArray(32) { 9 }
        val encrypted = PasskeyPortableMetadataCipher.encrypt("record-a", metadata(), key)
        assertThrows(Exception::class.java) {
            PasskeyPortableMetadataCipher.decrypt("record-b", encrypted, key)
        }
        key.fill(0)
    }
}
