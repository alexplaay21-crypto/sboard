package com.sboard.app.feature.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sboard.app.core.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Schedules sync with WorkManager: debounced after local edits, on unlock/foreground, hourly while
 * possible, and with exponential back-off when the network or Google is unavailable. Work waits for
 * connectivity, so edits made offline are uploaded automatically once the connection returns.
 */
object SyncScheduler {
    private const val UNIQUE = "sboard-sync"
    private const val PERIODIC = "sboard-sync-periodic"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (AppSettings.syncOnUnmeteredOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun requestSoon(context: Context, keyMaterialChanged: Boolean = false) {
        if (!GoogleAccount.isConnected(context)) return
        if (!AppSettings.autoSync.value && !keyMaterialChanged) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestNow(context: Context, revokeDeviceId: String? = null) {
        if (!GoogleAccount.isConnected(context)) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_REVOKE to revokeDeviceId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!GoogleAccount.isConnected(context) || !AppSettings.autoSync.value) {
            wm.cancelUniqueWork(PERIODIC); return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppSettings.initialize(applicationContext)
        if (!GoogleAccount.isConnected(applicationContext)) return Result.success()
        val revoke = inputData.getString(KEY_REVOKE)
        return when (val outcome = SyncEngine(applicationContext).sync(revokeDeviceId = revoke)) {
            is SyncEngine.Outcome.Success -> Result.success()
            // Changes stay pending in the local database and are retried on the next unlock.
            SyncEngine.Outcome.Locked, SyncEngine.Outcome.NeedsAuth -> Result.success()
            is SyncEngine.Outcome.Retry -> if (runAttemptCount < 8) Result.retry() else Result.failure()
            is SyncEngine.Outcome.Failure -> Result.failure()
        }
    }

    companion object { const val KEY_REVOKE = "revoke_device" }
}
