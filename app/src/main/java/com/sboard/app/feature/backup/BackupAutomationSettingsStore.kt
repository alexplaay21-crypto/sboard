package com.sboard.app.feature.backup

import android.content.Context

/** Stores non-secret backup preferences only. Recovery Keys and OAuth tokens never belong here. */
class BackupAutomationSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutomationDesired(): Boolean = prefs.getBoolean(KEY_DESIRED, false)

    fun setAutomationDesired(enabled: Boolean) {
        check(prefs.edit().putBoolean(KEY_DESIRED, enabled).commit())
    }

    fun isDriveConnected(): Boolean = prefs.getBoolean(KEY_DRIVE_CONNECTED, false)

    /** Stores only consent state; OAuth access tokens remain in Google Identity's cache. */
    fun setDriveConnected(connected: Boolean) {
        check(prefs.edit().putBoolean(KEY_DRIVE_CONNECTED, connected).commit())
    }

    fun intervalHours(): Long {
        val stored = prefs.getLong(KEY_INTERVAL_HOURS, BackupAutomationPolicy.DEFAULT_INTERVAL_HOURS)
        return runCatching { BackupAutomationPolicy.validateIntervalHours(stored) }
            .getOrElse { BackupAutomationPolicy.DEFAULT_INTERVAL_HOURS.also(::setIntervalHours) }
    }

    fun setIntervalHours(hours: Long) {
        BackupAutomationPolicy.validateIntervalHours(hours)
        check(prefs.edit().putLong(KEY_INTERVAL_HOURS, hours).commit())
    }

    fun keepVersions(): Int {
        val stored = prefs.getInt(KEY_KEEP_VERSIONS, BackupAutomationPolicy.DEFAULT_KEEP_VERSIONS)
        return runCatching { BackupAutomationPolicy.validateKeepVersions(stored) }
            .getOrElse { BackupAutomationPolicy.DEFAULT_KEEP_VERSIONS.also(::setKeepVersions) }
    }

    fun setKeepVersions(count: Int) {
        BackupAutomationPolicy.validateKeepVersions(count)
        check(prefs.edit().putInt(KEY_KEEP_VERSIONS, count).commit())
    }

    private companion object {
        const val PREFS = "sboard_backup_ui_settings_v1"
        const val KEY_DESIRED = "automation_desired"
        const val KEY_DRIVE_CONNECTED = "drive_connected"
        const val KEY_INTERVAL_HOURS = "interval_hours"
        const val KEY_KEEP_VERSIONS = "keep_versions"
    }
}
