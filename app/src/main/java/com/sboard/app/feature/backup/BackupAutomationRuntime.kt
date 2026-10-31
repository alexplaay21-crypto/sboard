package com.sboard.app.feature.backup

import android.content.Context

/**
 * App integration point. Implementations must obtain short-lived Drive authorization at run time,
 * obtain backup encryption material without persisting it in WorkManager input, and close/zero
 * sensitive material after the run. Never place OAuth tokens or Recovery Keys in Work Data.
 */
interface BackupAutomationExecutor {
    suspend fun runScheduledBackup(context: Context, reason: String): BackupAutomationResult
}

enum class BackupAutomationResult { SUCCESS, RETRY, SKIPPED }

/** Installed by SBoardApplication after secure backup dependencies are initialized. */
object BackupAutomationRuntime {
    @Volatile var executor: BackupAutomationExecutor? = null
        private set

    /** Explicitly install after the app has configured a secure, non-persistent credential provider. */
    fun install(value: BackupAutomationExecutor) {
        executor = value
    }

    fun clear() {
        executor = null
    }
}
