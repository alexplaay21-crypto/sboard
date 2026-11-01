package com.sboard.app.feature.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Never stores a Vault key or OAuth token in WorkManager input/output data. */
class SyncAutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val status = SyncAutomationStatusStore(applicationContext)
        val executor = SyncAutomationRuntime.executor ?: run {
            status.failure("Google Drive sync executor is not configured")
            return Result.failure()
        }
        status.started()
        return try {
            when (executor.synchronize(applicationContext)) {
                SyncAutomationResult.SUCCESS -> { status.success(); Result.success() }
                SyncAutomationResult.RETRY -> { status.failure("Temporary sync failure; WorkManager will retry"); Result.retry() }
                SyncAutomationResult.NEEDS_UNLOCK_OR_AUTH -> {
                    status.attention("Unlock the sBoard Vault and reconnect Google Drive if authorization was revoked")
                    Result.success()
                }
            }
        } catch (error: SecurityException) {
            status.attention(error.message ?: "Google Drive authorization needs attention")
            Result.success()
        } catch (error: IllegalArgumentException) {
            status.failure(error.message ?: "Invalid Google Drive sync configuration")
            Result.failure()
        } catch (error: Exception) {
            status.failure(error.message ?: "Temporary Google Drive sync failure; WorkManager will retry")
            Result.retry()
        }
    }

    companion object {
        const val KEY_REASON = "sync_reason"
        const val REASON_EVENT = "vault_event"
    }
}
