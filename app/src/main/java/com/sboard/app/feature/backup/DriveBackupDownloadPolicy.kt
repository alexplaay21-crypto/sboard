package com.sboard.app.feature.backup

import java.io.IOException

/** Fail-closed metadata checks for immutable, versioned Drive snapshots. */
internal object DriveBackupDownloadPolicy {
    fun requireTrustedMetadata(size: Long?, md5Checksum: String?) {
        val maxSize = BackupRestorePreflightPolicy.MAX_BACKUP_BYTES +
            BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD
        if (size == null || size !in 1L..maxSize) {
            throw IOException("Google Drive не предоставил допустимый размер резервной копии")
        }
        val checksum = md5Checksum?.lowercase()
        if (checksum == null || !checksum.matches(Regex("[0-9a-f]{32}"))) {
            throw IOException("Google Drive не предоставил корректную контрольную сумму резервной копии")
        }
    }
}
