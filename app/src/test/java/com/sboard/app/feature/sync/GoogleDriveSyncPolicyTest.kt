package com.sboard.app.feature.sync

import org.junit.Assert.*
import org.junit.Test

class GoogleDriveSyncPolicyTest {
    @Test fun stableChangeIdProducesStableFilename() {
        val id = "12345678-1234-1234-1234-123456789abc"
        assertEquals(GoogleDriveSyncPolicy.fileName(id), GoogleDriveSyncPolicy.fileName(id))
    }

    @Test fun validSyncFilenameIsRecognized() {
        assertTrue(GoogleDriveSyncPolicy.isSyncFileName("sboard_sync_v1_12345678-1234.sbsync"))
    }

    @Test fun backupAndUnknownFilesAreNotSyncFiles() {
        assertFalse(GoogleDriveSyncPolicy.isSyncFileName("sboard_backup_20261031.zip"))
        assertFalse(GoogleDriveSyncPolicy.isSyncFileName("random.txt"))
    }

    @Test fun invalidChangeIdsAreRejected() {
        listOf("", "short", "../escape-name", "id with spaces", "x".repeat(129)).forEach { id ->
            try { GoogleDriveSyncPolicy.fileName(id); fail("Expected invalid ID rejection: $id") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun maxEnvelopeSizeIsFourMiB() {
        assertEquals(4 * 1024 * 1024, GoogleDriveSyncPolicy.MAX_ENVELOPE_BYTES)
    }
}
