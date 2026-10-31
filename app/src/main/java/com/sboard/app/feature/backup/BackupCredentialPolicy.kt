package com.sboard.app.feature.backup

/** Pure validation rules for the explicit opt-in device-bound background credential. */
object BackupCredentialPolicy {
    fun isValidRecoveryKey(value: String): Boolean = BackupRestorePreflightPolicy.isValidRecoveryKey(value)

    fun canAcquire(
        userOptedIn: Boolean,
        deviceKeyConfigured: Boolean,
        vaultUnlocked: Boolean,
        silentDriveTokenAvailable: Boolean
    ): Boolean = userOptedIn && deviceKeyConfigured && vaultUnlocked && silentDriveTokenAvailable
}
