package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCredentialPolicyTest {
    private val validKey = "A".repeat(43)

    @Test fun acceptsOnlyExpectedRecoveryKeyFormat() {
        assertTrue(BackupCredentialPolicy.isValidRecoveryKey(validKey))
        assertFalse(BackupCredentialPolicy.isValidRecoveryKey("short"))
        assertFalse(BackupCredentialPolicy.isValidRecoveryKey("A".repeat(42)))
        assertFalse(BackupCredentialPolicy.isValidRecoveryKey("A".repeat(44)))
        assertFalse(BackupCredentialPolicy.isValidRecoveryKey("A".repeat(42) + "!"))
    }

    @Test fun backgroundCredentialRequiresEveryGate() {
        assertTrue(BackupCredentialPolicy.canAcquire(true, true, true, true))
        assertFalse(BackupCredentialPolicy.canAcquire(false, true, true, true))
        assertFalse(BackupCredentialPolicy.canAcquire(true, false, true, true))
        assertFalse(BackupCredentialPolicy.canAcquire(true, true, false, true))
        assertFalse(BackupCredentialPolicy.canAcquire(true, true, true, false))
    }
}
