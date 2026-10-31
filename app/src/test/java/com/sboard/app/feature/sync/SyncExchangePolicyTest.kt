package com.sboard.app.feature.sync

import org.junit.Assert.*
import org.junit.Test

class SyncExchangePolicyTest {
    @Test fun onlyNewestPendingChangePerRecordIsSelected() {
        val rows = listOf(
            row("old", "record-a", 1), row("new", "record-a", 2), row("other", "record-b", 1)
        )
        val result = SyncExchangePolicy.selectLatestPending(rows)
        assertEquals(setOf("new", "other"), result.selected.map { it.changeId }.toSet())
        assertEquals(listOf("old"), result.supersededIds)
    }

    @Test fun sentRowsAreNotSelectedAgain() {
        val result = SyncExchangePolicy.selectLatestPending(listOf(row("sent", "r", 1, "SENT")))
        assertTrue(result.selected.isEmpty())
    }

    @Test fun remoteChangesFromSameDeviceAreIgnored() {
        val change = EncryptedSyncEnvelope.Change("change-123", "record-123", EncryptedSyncEnvelope.Operation.DELETE, "device-a", 3, 10)
        assertFalse(SyncExchangePolicy.shouldStageIncoming(change, "device-a"))
        assertTrue(SyncExchangePolicy.shouldStageIncoming(change, "device-b"))
    }

    @Test fun restoredJournalSuppressesAlreadyIncludedDriveChangesOnReplacementDevice() {
        val change = EncryptedSyncEnvelope.Change("change-123", "record-123", EncryptedSyncEnvelope.Operation.DELETE, "old-device", 3, 10)
        assertFalse(SyncExchangePolicy.shouldStageIncoming(change, "new-device", setOf("change-123")))
        assertTrue(SyncExchangePolicy.shouldStageIncoming(change, "new-device", emptySet()))
    }

    private fun row(id: String, record: String, revision: Long, state: String = "PENDING") =
        SyncChangeEntity(id, record, "UPSERT", "device-a", revision, revision * 100, state)
}
