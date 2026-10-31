package com.sboard.app.feature.sync

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class EncryptedSyncEnvelopeTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test fun upsertRoundTripsEncryptedRowAndMetadata() {
        val original = EncryptedSyncEnvelope.Change(
            changeId = "change-1", recordId = "record-1", operation = EncryptedSyncEnvelope.Operation.UPSERT,
            deviceId = "device-a", revision = 7, changedAt = 123456,
            encryptedRecord = ByteArray(64) { (it * 3).toByte() }
        )
        val encoded = EncryptedSyncEnvelope.encode(original, key)
        val decoded = EncryptedSyncEnvelope.decode(encoded, key)
        assertEquals(original.changeId, decoded.changeId)
        assertEquals(original.recordId, decoded.recordId)
        assertEquals(original.operation, decoded.operation)
        assertEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.revision, decoded.revision)
        assertEquals(original.changedAt, decoded.changedAt)
        assertArrayEquals(original.encryptedRecord, decoded.encryptedRecord)
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("record-1"))
    }

    @Test fun deleteRoundTripsWithoutRecordPayload() {
        val original = EncryptedSyncEnvelope.Change(
            "change-2", "record-2", EncryptedSyncEnvelope.Operation.DELETE, "device-b", 8, 123457
        )
        val decoded = EncryptedSyncEnvelope.decode(EncryptedSyncEnvelope.encode(original, key), key)
        assertEquals(EncryptedSyncEnvelope.Operation.DELETE, decoded.operation)
        assertNull(decoded.encryptedRecord)
    }

    @Test fun tamperingFailsAuthentication() {
        val change = EncryptedSyncEnvelope.Change(
            "change-3", "record-3", EncryptedSyncEnvelope.Operation.DELETE, "device-a", 9, 123458
        )
        val encoded = EncryptedSyncEnvelope.encode(change, key)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        try {
            EncryptedSyncEnvelope.decode(encoded, key)
            fail("Tampered envelope must not decode")
        } catch (_: AEADBadTagException) { }
    }

    @Test fun wrongVaultKeyFailsAuthentication() {
        val change = EncryptedSyncEnvelope.Change(
            "change-4", "record-4", EncryptedSyncEnvelope.Operation.DELETE, "device-a", 10, 123459
        )
        try {
            EncryptedSyncEnvelope.decode(EncryptedSyncEnvelope.encode(change, key), ByteArray(32) { 99 })
            fail("Wrong key must not decode")
        } catch (_: AEADBadTagException) { }
    }

    @Test fun upsertWithoutEncryptedPayloadIsRejected() {
        try {
            EncryptedSyncEnvelope.encode(
                EncryptedSyncEnvelope.Change("change-5", "record-5", EncryptedSyncEnvelope.Operation.UPSERT, "device-a", 11, 123460), key
            )
            fail("Missing UPSERT payload must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun deleteWithPayloadIsRejected() {
        try {
            EncryptedSyncEnvelope.encode(
                EncryptedSyncEnvelope.Change("change-6", "record-6", EncryptedSyncEnvelope.Operation.DELETE, "device-a", 12, 123461, ByteArray(32)), key
            )
            fail("DELETE payload must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
