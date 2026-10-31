package com.sboard.app.feature.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules backup work without ever persisting OAuth tokens, Recovery Keys or Vault keys. */
class BackupAutomationScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Enables change-triggered backups. No daily timer is installed. */
    fun enableChangeBased() {
        preferences.edit().putBoolean(KEY_ENABLED, true).apply()
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    @Deprecated("sBoard now backs up after database changes, not on a daily timer")
    fun enableDaily(intervalHours: Long = BackupAutomationPolicy.DEFAULT_INTERVAL_HOURS) {
        BackupAutomationPolicy.validateIntervalHours(intervalHours)
        preferences.edit().putBoolean(KEY_ENABLED, true).putLong(KEY_INTERVAL_HOURS, intervalHours).apply()
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    fun schedulePeriodic(intervalHours: Long) {
        val interval = BackupAutomationPolicy.periodicIntervalMillis(intervalHours)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupAutomationWorker>(interval, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(workDataOf(BackupAutomationWorker.KEY_REASON to BackupAutomationWorker.REASON_PERIODIC))
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** User-requested immediate backup; bypasses the change debounce but still requires network. */
    fun requestManualBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupAutomationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(BackupAutomationWorker.KEY_REASON to BackupAutomationWorker.REASON_MANUAL))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_MANUAL, ExistingWorkPolicy.REPLACE, request)
    }

    /** Call after a meaningful vault change; WorkManager coalesces bursts into one pending task. */
    fun requestAfterImportantChange() {
        if (!isEnabled()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupAutomationWorker>()
            .setInitialDelay(BackupAutomationPolicy.CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(workDataOf(BackupAutomationWorker.KEY_REASON to BackupAutomationWorker.REASON_IMPORTANT_CHANGE))
            .addTag(TAG)
            .build()
        // REPLACE resets the short debounce window so a burst of edits becomes one snapshot.
        workManager.enqueueUniqueWork(UNIQUE_CHANGE, ExistingWorkPolicy.REPLACE, request)
    }

    fun disable() {
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(UNIQUE_CHANGE)
        workManager.cancelUniqueWork(UNIQUE_MANUAL)
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)
    fun configuredIntervalHours(): Long = preferences.getLong(KEY_INTERVAL_HOURS, BackupAutomationPolicy.DEFAULT_INTERVAL_HOURS)

    companion object {
        private const val PREFS = "sboard_backup_automation"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val UNIQUE_PERIODIC = "sboard_backup_periodic"
        private const val UNIQUE_CHANGE = "sboard_backup_important_change"
        private const val UNIQUE_MANUAL = "sboard_backup_manual"
        private const val TAG = "sboard_backup_automation"
    }
}
