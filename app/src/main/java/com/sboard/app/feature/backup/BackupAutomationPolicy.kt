package com.sboard.app.feature.backup

/** Pure scheduling rules; no credentials or recovery material are stored in WorkManager input. */
object BackupAutomationPolicy {
    const val CHANGE_DEBOUNCE_SECONDS = 3L
    const val DEFAULT_INTERVAL_HOURS = 24L // retained only to read older preference files
    const val MIN_INTERVAL_HOURS = 12L
    const val MAX_INTERVAL_HOURS = 168L
    const val DEFAULT_KEEP_VERSIONS = 14
    const val MIN_KEEP_VERSIONS = 1
    const val MAX_KEEP_VERSIONS = 100

    fun validateIntervalHours(hours: Long): Long {
        require(hours in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS) { "Backup interval must be between 12 and 168 hours" }
        return hours
    }

    fun validateKeepVersions(count: Int): Int {
        require(count in MIN_KEEP_VERSIONS..MAX_KEEP_VERSIONS) { "Keep count must be between 1 and 100" }
        return count
    }

    fun periodicIntervalMillis(hours: Long): Long = validateIntervalHours(hours) * 60L * 60L * 1000L

    /** The Room database already stores Vault payloads encrypted; the worker needs only ephemeral upload credentials. */
    fun canExecute(recoveryKeyPresent: Boolean, driveTokenPresent: Boolean): Boolean =
        recoveryKeyPresent && driveTokenPresent

    /** Backups are eligible only when the user enabled automation and has completed setup. */
    fun isEligible(enabled: Boolean, recoveryMaterialReady: Boolean, driveAuthorizationReady: Boolean): Boolean =
        enabled && recoveryMaterialReady && driveAuthorizationReady
}
