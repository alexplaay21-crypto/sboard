package com.sboard.app.feature.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {
    @Test
    fun keyIsCopiedAndClearedOnLock() {
        val session = VaultSession()
        val original = ByteArray(32) { it.toByte() }

        session.unlock(original)
        original.fill(0)

        assertTrue(session.isUnlocked)
        val copy = session.copyKey()
        assertArrayEquals(ByteArray(32) { it.toByte() }, copy)
        copy.fill(0)

        session.lock()
        assertFalse(session.isUnlocked)
    }
}
