package com.sboard.app.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupVersionPolicyTest {
    @Test fun generatedNamesAreUniqueAndSortable() {
        val older = BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(0, 0, 0, 1))
        val newer = BackupVersionPolicy.newFileName(1_798_000_000_001L, byteArrayOf(0, 0, 0, 2))
        assertTrue(BackupVersionPolicy.isVersionedFileName(older))
        assertTrue(older < newer)
        assertFalse(older == newer)
    }

    @Test fun legacyCanonicalBackupIsRestorableButNotVersioned() {
        assertFalse(BackupVersionPolicy.isVersionedFileName(GoogleDriveBackupClient.BACKUP_NAME))
        assertTrue(BackupVersionPolicy.isRestorableFileName(GoogleDriveBackupClient.BACKUP_NAME))
        assertFalse(BackupVersionPolicy.isRestorableFileName("sboard_backup.enc.bak"))
    }

    @Test fun rejectsMalformedNamesAndParsesTimestamp() {
        val valid = BackupVersionPolicy.newFileName(1_798_000_000_000L, byteArrayOf(1, 2, 3, 4))
        assertEquals(1_798_000_000_000L, BackupVersionPolicy.timestampFromFileName(valid))
        assertNull(BackupVersionPolicy.timestampFromFileName("sboard_backup_v1_123_bad.enc"))
    }

    @Test fun deletionGuardNeverAllowsRemovingTheLastRecoveryPoint() {
        assertFalse(BackupVersionDeletionPolicy.canDelete(0))
        assertFalse(BackupVersionDeletionPolicy.canDelete(1))
        assertTrue(BackupVersionDeletionPolicy.canDelete(2))
    }

    @Test fun retentionPreviewNeverDeletesAndKeepsNewest() {
        val first = BackupVersionPolicy.newFileName(1000L, byteArrayOf(0, 0, 0, 1))
        val second = BackupVersionPolicy.newFileName(2000L, byteArrayOf(0, 0, 0, 2))
        val third = BackupVersionPolicy.newFileName(3000L, byteArrayOf(0, 0, 0, 3))
        val preview = BackupVersionPolicy.retentionPreview(listOf(first, "unknown.enc", third, second), keepNewest = 2)
        assertEquals(listOf(third, second), preview.kept)
        assertEquals(listOf(first), preview.candidatesForManualDeletion)
        assertEquals(listOf("unknown.enc"), preview.ignoredInvalidNames)
    }
}
