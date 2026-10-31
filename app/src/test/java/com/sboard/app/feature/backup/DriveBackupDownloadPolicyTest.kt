package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DriveBackupDownloadPolicyTest {
    private val validMd5 = "0123456789abcdef0123456789abcdef"

    @Test fun acceptsCompleteBoundedDriveMetadata() {
        DriveBackupDownloadPolicy.requireTrustedMetadata(1024L, validMd5)
        DriveBackupDownloadPolicy.requireTrustedMetadata(
            BackupRestorePreflightPolicy.MAX_BACKUP_BYTES + BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD,
            validMd5.uppercase()
        )
    }

    @Test fun rejectsMissingOrInvalidSize() {
        listOf(null, 0L, -1L,
            BackupRestorePreflightPolicy.MAX_BACKUP_BYTES + BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD + 1
        ).forEach { size ->
            var rejected = false
            try {
                DriveBackupDownloadPolicy.requireTrustedMetadata(size, validMd5)
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Expected rejection for size=$size", rejected)
        }
    }

    @Test fun rejectsMissingOrMalformedMd5() {
        listOf(null, "", "abc", "g".repeat(32), "0".repeat(31), "0".repeat(33)).forEach { checksum ->
            var rejected = false
            try {
                DriveBackupDownloadPolicy.requireTrustedMetadata(1024L, checksum)
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Expected rejection for checksum=$checksum", rejected)
        }
    }
}
