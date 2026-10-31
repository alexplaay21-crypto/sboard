package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestorePreflightPolicyTest {
    private val validKey = "A".repeat(43)

    @Test fun acceptsExactlyFortyThreeBase64UrlCharacters() {
        assertTrue(BackupRestorePreflightPolicy.isValidRecoveryKey(validKey))
        assertTrue(BackupRestorePreflightPolicy.isValidRecoveryKey("A_z-".repeat(10) + "Abc"))
    }

    @Test fun rejectsWrongLengthAndUnsupportedCharacters() {
        assertFalse(BackupRestorePreflightPolicy.isValidRecoveryKey("A".repeat(42)))
        assertFalse(BackupRestorePreflightPolicy.isValidRecoveryKey("A".repeat(44)))
        assertFalse(BackupRestorePreflightPolicy.isValidRecoveryKey("A".repeat(42) + "+"))
        assertFalse(BackupRestorePreflightPolicy.isValidRecoveryKey(" ".repeat(43)))
    }

    @Test fun acceptsOnlyPositiveBackupSizesWithinLimit() {
        assertFalse(BackupRestorePreflightPolicy.isValidEncryptedBackupSize(0))
        assertTrue(BackupRestorePreflightPolicy.isValidEncryptedBackupSize(1))
        assertTrue(BackupRestorePreflightPolicy.isValidEncryptedBackupSize(
            BackupRestorePreflightPolicy.MAX_BACKUP_BYTES + BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD
        ))
        assertFalse(BackupRestorePreflightPolicy.isValidEncryptedBackupSize(
            BackupRestorePreflightPolicy.MAX_BACKUP_BYTES + BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD + 1
        ))
        assertFalse(BackupRestorePreflightPolicy.isValidEncryptedBackupSize(-1))
    }
}
