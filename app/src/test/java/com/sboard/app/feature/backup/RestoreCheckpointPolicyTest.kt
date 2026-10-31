package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RestoreCheckpointPolicyTest {
    @Test fun acceptsFullyCheckpointedWal() {
        assertTrue(RestoreCheckpointPolicy.isComplete(0, 12, 12))
    }

    @Test fun acceptsDatabaseWithoutWalMode() {
        assertTrue(RestoreCheckpointPolicy.isComplete(0, -1, -1))
    }

    @Test fun rejectsBusyCheckpoint() {
        assertFalse(RestoreCheckpointPolicy.isComplete(1, 12, 12))
    }

    @Test fun rejectsPartiallyCheckpointedWal() {
        assertFalse(RestoreCheckpointPolicy.isComplete(0, 12, 8))
    }

    @Test fun rejectsInconsistentNegativeFrameCounts() {
        assertFalse(RestoreCheckpointPolicy.isComplete(0, -1, 0))
    }

    @Test fun requireCompleteFailsClosed() {
        try {
            RestoreCheckpointPolicy.requireComplete(0, 12, 8)
            error("Partial checkpoint must be rejected")
        } catch (_: IOException) {
            // Expected: restore must not proceed with an incomplete WAL checkpoint.
        }
    }
}
