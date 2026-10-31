package com.sboard.app.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDiscoveryPolicyTest {
    private fun file(
        id: String,
        name: String,
        size: Long? = 1024L,
        md5: String? = "0123456789abcdef0123456789abcdef"
    ) = GoogleDriveBackupClient.DriveBackupFile(
        id = id,
        name = name,
        modifiedTime = "2026-10-31T10:00:00Z",
        size = size,
        md5Checksum = md5
    )

    @Test fun onlyTrustedRestorableFilesAreSelectable() {
        val valid = file("valid12345", BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4)))
        val missingSize = file("missing123", GoogleDriveBackupClient.BACKUP_NAME, size = null)
        val missingMd5 = file("missing456", BackupVersionPolicy.newFileName(1_798_000_000_001L, byteArrayOf(4, 3, 2, 1)), md5 = null)
        val invalidName = file("invalid123", "sboard_backup.enc.bak")

        val result = BackupDiscoveryPolicy.filter(listOf(valid, missingSize, missingMd5, invalidName))

        assertEquals(listOf(valid), result.usable)
        assertEquals(2, result.rejectedMetadataCount)
    }

    @Test fun legacyBackupRemainsSelectableWhenMetadataIsTrusted() {
        val legacy = file("legacy12345", GoogleDriveBackupClient.BACKUP_NAME)
        val result = BackupDiscoveryPolicy.filter(listOf(legacy))
        assertEquals(listOf(legacy), result.usable)
        assertEquals(0, result.rejectedMetadataCount)
    }

    @Test fun emptyInputProducesEmptyDiscoveryResult() {
        val result = BackupDiscoveryPolicy.filter(emptyList())
        assertTrue(result.usable.isEmpty())
        assertEquals(0, result.rejectedMetadataCount)
    }
}
