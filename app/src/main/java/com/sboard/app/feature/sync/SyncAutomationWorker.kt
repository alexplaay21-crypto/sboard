package com.sboard.app.feature.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Never stores a Vault key or OAuth token in WorkManager input/output data. */
class SyncAutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val executor = SyncAutomationRuntime.executor ?: return Result.failure()
        return try {
            when (executor.synchronize(applicationContext)) {
                SyncAutomationResult.SUCCESS -> Result.success()
                SyncAutomationResult.RETRY -> Result.retry()
                SyncAutomationResult.NEEDS_UNLOCK_OR_AUTH -> Result.success()
            }
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_REASON = "sync_reason"
        const val REASON_EVENT = "vault_event"
    }
}
