package com.sboard.app.feature.sync

import android.content.Context

/** Runtime bridge for background sync. Secrets are acquired at execution time, never put in WorkManager Data. */
interface SyncAutomationExecutor {
    suspend fun synchronize(context: Context): SyncAutomationResult
}

enum class SyncAutomationResult { SUCCESS, RETRY, NEEDS_UNLOCK_OR_AUTH }

object SyncAutomationRuntime {
    @Volatile var executor: SyncAutomationExecutor? = null
        private set

    fun install(value: SyncAutomationExecutor) { executor = value }
    fun clear() { executor = null }
}
