package com.sboard.app.feature.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Executes only through an explicitly registered secure runtime; missing setup never uploads data. */
class BackupAutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Missing runtime wiring is a configuration issue, not a transient network failure.
        val status = BackupAutomationStatusStore(applicationContext)
        val executor = BackupAutomationRuntime.executor
        if (executor == null) {
            status.recordFailure("Backup executor is not configured")
            return Result.failure()
        }
        val reason = inputData.getString(KEY_REASON)?.takeIf { it in ALLOWED_REASONS } ?: REASON_CHANGE
        status.recordStarted(reason)
        return try {
            when (executor.runScheduledBackup(applicationContext, reason)) {
                BackupAutomationResult.SUCCESS -> {
                    status.recordSuccess()
                    Result.success()
                }
                BackupAutomationResult.RETRY -> {
                    status.recordFailure("Temporary backup error; WorkManager will retry")
                    Result.retry()
                }
                BackupAutomationResult.SKIPPED -> {
                    status.recordSkipped("Connect Google Drive or unlock backup credentials, then retry")
                    Result.success()
                }
            }
        } catch (error: SecurityException) {
            // Authorization/permission needs user action; avoid an endless tight retry loop.
            status.recordSkipped(error.message ?: "Google authorization needs attention")
            Result.failure()
        } catch (error: IllegalArgumentException) {
            status.recordFailure(error.message ?: "Invalid backup configuration")
            Result.failure()
        } catch (error: Exception) {
            status.recordFailure(error.message ?: "Temporary backup error; WorkManager will retry")
            Result.retry()
        }
    }

    companion object {
        const val KEY_REASON = "backup_reason"
        const val REASON_PERIODIC = "periodic"
        const val REASON_IMPORTANT_CHANGE = "important_change"
        const val REASON_MANUAL = "manual"
        const val REASON_CHANGE = REASON_IMPORTANT_CHANGE
        private val ALLOWED_REASONS = setOf(REASON_PERIODIC, REASON_IMPORTANT_CHANGE, REASON_MANUAL)
    }
}
