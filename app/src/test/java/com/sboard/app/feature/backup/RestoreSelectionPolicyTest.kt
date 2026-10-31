package com.sboard.app.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreSelectionPolicyTest {
    private fun file(id: String, name: String) = GoogleDriveBackupClient.DriveBackupFile(
        id = id,
        name = name,
        modifiedTime = "2026-10-31T10:00:00Z",
        size = 1024L,
        md5Checksum = "0123456789abcdef0123456789abcdef"
    )

    @Test fun returnsOnlyExactCurrentDriveSelection() {
        val selected = file("abcde12345", BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4)))
        assertEquals(selected, RestoreSelectionPolicy.findCurrentSelection(listOf(selected), selected))
    }

    @Test fun rejectsSelectionThatWasDeletedFromDrive() {
        val selected = file("abcde12345", BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4)))
        assertNull(RestoreSelectionPolicy.findCurrentSelection(emptyList(), selected))
    }

    @Test fun rejectsSameIdWithDifferentName() {
        val selected = file("abcde12345", BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4)))
        val changed = file("abcde12345", BackupVersionPolicy.newFileName(1_798_000_000_001L, byteArrayOf(1, 2, 3, 4)))
        assertNull(RestoreSelectionPolicy.findCurrentSelection(listOf(changed), selected))
    }

    @Test fun acceptsLegacyCanonicalBackupForRestore() {
        val legacy = file("abcde12345", GoogleDriveBackupClient.BACKUP_NAME)
        assertEquals(legacy, RestoreSelectionPolicy.findCurrentSelection(listOf(legacy), legacy))
    }

    @Test fun rejectsMalformedFileNames() {
        val malformed = file("abcde12345", "sboard_backup.enc.bak")
        assertNull(RestoreSelectionPolicy.findCurrentSelection(listOf(malformed), malformed))
    }

    @Test fun rejectsMalformedDriveIds() {
        val selected = file("bad/id", BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4)))
        assertNull(RestoreSelectionPolicy.findCurrentSelection(listOf(selected), selected))
    }
}
