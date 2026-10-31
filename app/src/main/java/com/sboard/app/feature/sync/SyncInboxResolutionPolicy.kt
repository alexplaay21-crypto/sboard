package com.sboard.app.feature.sync

/** Pure state mapping for explicit inbox resolution. */
object SyncInboxResolutionPolicy {
    enum class Choice { ACCEPT_REMOTE, KEEP_LOCAL }

    fun resolvedState(choice: Choice): String = when (choice) {
        Choice.ACCEPT_REMOTE -> "RESOLVED_REMOTE"
        Choice.KEEP_LOCAL -> "RESOLVED_LOCAL"
    }

    fun resultingOperation(localRecordExistsAfterChoice: Boolean): String =
        if (localRecordExistsAfterChoice) "UPSERT" else "DELETE"
}
