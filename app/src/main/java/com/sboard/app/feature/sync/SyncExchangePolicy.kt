package com.sboard.app.feature.sync

/** Pure decisions for an exchange pass; incoming events are staged, never silently applied. */
object SyncExchangePolicy {
    data class PendingSelection(val selected: List<SyncChangeEntity>, val supersededIds: List<String>)

    fun selectLatestPending(changes: List<SyncChangeEntity>, maxRecords: Int = 500): PendingSelection {
        require(maxRecords in 1..5000)
        val pending = changes.filter { it.deliveryState == "PENDING" }
        val latest = pending.groupBy { it.recordId }
            .values
            .map { rows -> rows.maxWith(compareBy<SyncChangeEntity> { it.revision }.thenBy { it.changedAt }) }
            .sortedWith(compareBy<SyncChangeEntity> { it.changedAt }.thenBy { it.changeId })
        val selected = latest.take(maxRecords)
        val selectedIds = selected.mapTo(hashSetOf()) { it.changeId }
        val selectedRecords = selected.mapTo(hashSetOf()) { it.recordId }
        val superseded = pending.filter { it.recordId in selectedRecords && it.changeId !in selectedIds }.map { it.changeId }
        return PendingSelection(selected, superseded)
    }

    fun shouldStageIncoming(
        change: EncryptedSyncEnvelope.Change,
        localDeviceId: String,
        knownLocalChangeIds: Set<String> = emptySet()
    ): Boolean {
        require(localDeviceId.isNotBlank())
        // A restored database already contains its historical journal. On a replacement
        // device the installation ID is new, so checking the journal prevents every
        // pre-backup Drive event from being misclassified as a fresh remote edit.
        if (change.changeId in knownLocalChangeIds) return false
        return change.deviceId != localDeviceId
    }
}
