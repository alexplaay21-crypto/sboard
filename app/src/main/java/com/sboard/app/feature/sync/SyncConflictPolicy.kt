package com.sboard.app.feature.sync

data class SyncRevision(
    val deviceId: String,
    val counter: Long,
    val changedAt: Long
)

enum class SyncConflictDecision { KEEP_LOCAL, ACCEPT_REMOTE, NO_CHANGE, NEEDS_USER_RESOLUTION }

/**
 * Conservative conflict policy. Counters from different devices are not directly comparable:
 * unless one side is an exact duplicate, concurrent cross-device edits require user resolution.
 * This deliberately avoids silently overwriting a password with another device's edit.
 */
object SyncConflictPolicy {
    fun decide(
        local: SyncRevision,
        remote: SyncRevision,
        localFingerprint: String,
        remoteFingerprint: String
    ): SyncConflictDecision {
        require(local.deviceId.isNotBlank() && remote.deviceId.isNotBlank())
        require(local.counter >= 0L && remote.counter >= 0L)
        require(localFingerprint.isNotBlank() && remoteFingerprint.isNotBlank())
        if (local.deviceId == remote.deviceId) {
            return when {
                local.counter > remote.counter -> SyncConflictDecision.KEEP_LOCAL
                local.counter < remote.counter -> SyncConflictDecision.ACCEPT_REMOTE
                localFingerprint == remoteFingerprint -> SyncConflictDecision.NO_CHANGE
                else -> SyncConflictDecision.NEEDS_USER_RESOLUTION
            }
        }
        if (localFingerprint == remoteFingerprint) return SyncConflictDecision.NO_CHANGE
        return SyncConflictDecision.NEEDS_USER_RESOLUTION
    }
}
