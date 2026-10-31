package com.sboard.app.feature.backup

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class BackupIntegrityPolicyTest {
    private val checksum = "0123456789abcdef0123456789abcdef"

    @Test fun acceptsMatchingSizeAndChecksum() {
        BackupIntegrityPolicy.requireMatch(1024L, 1024L, checksum, checksum.uppercase())
    }

    @Test fun rejectsMissingDriveChecksum() {
        assertThrows(IOException::class.java) {
            BackupIntegrityPolicy.requireMatch(1024L, 1024L, checksum, null)
        }
    }

    @Test fun rejectsDifferentSize() {
        assertThrows(IOException::class.java) {
            BackupIntegrityPolicy.requireMatch(1024L, 1023L, checksum, checksum)
        }
    }

    @Test fun rejectsDifferentChecksum() {
        assertThrows(IOException::class.java) {
            BackupIntegrityPolicy.requireMatch(1024L, 1024L, checksum, "ffffffffffffffffffffffffffffffff")
        }
    }

    @Test fun rejectsMalformedChecksum() {
        assertThrows(IOException::class.java) {
            BackupIntegrityPolicy.requireMatch(1024L, 1024L, checksum, "not-a-checksum")
        }
    }
}
