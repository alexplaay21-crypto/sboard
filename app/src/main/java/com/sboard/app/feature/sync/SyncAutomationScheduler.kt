package com.sboard.app.feature.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Event-driven sync scheduler. No daily/periodic timer is created. */
class SyncAutomationScheduler(context: Context) {
    private val app = context.applicationContext
    private val work = WorkManager.getInstance(app)

    /**
     * Queue even while offline. WorkManager keeps the request pending until the
     * CONNECTED constraint is met, so an edit made in airplane mode is not lost
     * merely because no network was available at commit time.
     */
    fun requestAfterLocalChange() {
        enqueue(DEBOUNCE_SECONDS)
    }

    /** Called when the app returns to foreground or the Vault becomes available. */
    fun requestOnForeground() {
        enqueue(0)
    }

    fun cancel() { work.cancelUniqueWork(UNIQUE_SYNC) }

    private fun enqueue(delaySeconds: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncAutomationWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncAutomationWorker.KEY_REASON to SyncAutomationWorker.REASON_EVENT))
            .addTag(TAG)
            .build()
        // REPLACE restarts the debounce window for rapid consecutive edits.
        work.enqueueUniqueWork(UNIQUE_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val UNIQUE_SYNC = "sboard_google_drive_vault_sync"
        private const val TAG = "sboard_google_drive_sync"
        private const val DEBOUNCE_SECONDS = 3L
    }
}
