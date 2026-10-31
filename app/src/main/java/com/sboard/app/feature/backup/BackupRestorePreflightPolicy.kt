package com.sboard.app.feature.backup

/** Shared, side-effect-free input checks used before any restore file or database is touched. */
internal object BackupRestorePreflightPolicy {
    const val MAX_BACKUP_BYTES = 256L * 1024L * 1024L
    const val MAX_BACKUP_OVERHEAD = 2L * 1024L * 1024L
    const val MIN_RECOVERY_KEY_LENGTH = 43

    fun isValidRecoveryKey(value: String): Boolean =
        value.length == MIN_RECOVERY_KEY_LENGTH && value.matches(RECOVERY_KEY_PATTERN)

    fun requireRecoveryKey(value: String) {
        require(isValidRecoveryKey(value)) { "Введите корректный Recovery Key" }
    }

    fun isValidEncryptedBackupSize(size: Long): Boolean =
        size in 1L..(MAX_BACKUP_BYTES + MAX_BACKUP_OVERHEAD)

    private val RECOVERY_KEY_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
}
