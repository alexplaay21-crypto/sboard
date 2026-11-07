package com.sboard.app.feature.sync

import com.sboard.app.core.crypto.Sha256
import com.sboard.app.core.crypto.randomBytes
import com.sboard.app.feature.vault.KeyWrap
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFormatTest {
    private val key = randomBytes(32)
    private val header = BackupHeader(vaultId = "v1", generation = 7, writtenAt = 100, writerDeviceId = "dev", keyUpdatedAt = 50,
        keyWraps = listOf(KeyWrap("password", "c2FsdA==", "aXY=", "Y3Q=", 65536, 3, 1)))
    private val body = BackupBody(listOf(SyncRecord("a", 1, 2, false, "dev", "aXY=", "Y3Q=")), listOf(SyncDevice("dev", "Pixel", 100)))

    @Test fun roundTrip() {
        val bytes = BackupFormat.encode(header, body, key)
        val parsed = BackupFormat.parse(bytes)
        assertEquals(header, parsed.header)
        assertEquals(body, BackupFormat.decryptBody(parsed, key))
    }

    @Test(expected = BackupCorruptedException::class)
    fun detectsCorruption() {
        val bytes = BackupFormat.encode(header, body, key)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        BackupFormat.parse(bytes)
    }

    @Test(expected = BackupCorruptedException::class)
    fun detectsTruncation() {
        val bytes = BackupFormat.encode(header, body, key)
        BackupFormat.parse(bytes.copyOf(bytes.size - 10))
    }

    @Test(expected = BackupAuthenticationException::class)
    fun wrongKeyIsRejected() {
        BackupFormat.decryptBody(BackupFormat.parse(BackupFormat.encode(header, body, key)), randomBytes(32))
    }

    /** An attacker who rewrites the clear header (e.g. key wraps) and fixes the checksum is still caught by GCM. */
    @Test(expected = BackupAuthenticationException::class)
    fun headerTamperingIsDetected() {
        val bytes = BackupFormat.encode(header, body, key)
        val text = String(bytes, Charsets.ISO_8859_1).replace("\"generation\":7", "\"generation\":9")
        val content = text.toByteArray(Charsets.ISO_8859_1).copyOf(bytes.size - 32)
        val forged = content + Sha256.of(content)
        BackupFormat.decryptBody(BackupFormat.parse(forged), key)
    }
}
