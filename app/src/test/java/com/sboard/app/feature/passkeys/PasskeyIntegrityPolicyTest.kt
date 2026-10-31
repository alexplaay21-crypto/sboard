package com.sboard.app.feature.passkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException

class PasskeyIntegrityPolicyTest {
    @Test fun recognizesOnlyLowercaseSha256BlindIndexes() {
        assertTrue(PasskeyIntegrityPolicy.isBlindIndex("a".repeat(64)))
        assertTrue(PasskeyIntegrityPolicy.isBlindIndex("0123456789abcdef".repeat(4)))
        assertFalse(PasskeyIntegrityPolicy.isBlindIndex("example.com"))
        assertFalse(PasskeyIntegrityPolicy.isBlindIndex("A".repeat(64)))
        assertFalse(PasskeyIntegrityPolicy.isBlindIndex("a".repeat(63)))
    }

    @Test fun rejectsIncompleteCredentialMaterial() {
        assertTrue(PasskeyIntegrityPolicy.isCredentialMaterialValid(
            1, 32, 12, 0
        ))
        assertFalse(PasskeyIntegrityPolicy.isCredentialMaterialValid(
            0, 32, 12, 0
        ))
        assertFalse(PasskeyIntegrityPolicy.isCredentialMaterialValid(
            1, 15, 12, 0
        ))
        assertFalse(PasskeyIntegrityPolicy.isCredentialMaterialValid(
            1, 32, 11, 0
        ))
        assertFalse(PasskeyIntegrityPolicy.isCredentialMaterialValid(
            1, 32, 12, -1
        ))
    }

    @Test fun rejectsIncompleteEncryptedMetadataContents() {
        assertTrue(PasskeyIntegrityPolicy.isMetadataContentValid(16, 65))
        assertFalse(PasskeyIntegrityPolicy.isMetadataContentValid(0, 65))
        assertFalse(PasskeyIntegrityPolicy.isMetadataContentValid(65, 65))
        assertFalse(PasskeyIntegrityPolicy.isMetadataContentValid(16, 0))
    }

    @Test fun mapsDamagedMetadataToNonDestructiveRecoveryAction() {
        assertEquals(PasskeyRecoveryAction.RESTORE_SUPPORTED_BACKUP_OR_REREGISTER,
            PasskeyIntegrityFinding("x", PasskeyIntegrityIssue.METADATA_AUTHENTICATION_FAILED).recommendedAction)
        assertEquals(PasskeyRecoveryAction.INVESTIGATE_ORPHAN_WITHOUT_AUTO_DELETION,
            PasskeyIntegrityFinding("x", PasskeyIntegrityIssue.ORPHANED_METADATA).recommendedAction)
    }

    @Test fun detectsInvalidatedKeystoreKeyExceptions() {
        assertTrue(PasskeyIntegrityPolicy.isKeystoreUnavailable(InvalidKeyException("invalidated")))
        assertFalse(PasskeyIntegrityPolicy.isKeystoreUnavailable(IllegalStateException("other")))
    }

    @Test fun reportsBackupRestoreWhenKeystoreKeyIsUnavailable() {
        val report = PasskeyIntegrityReport(2, listOf(
            PasskeyIntegrityFinding("a", PasskeyIntegrityIssue.HEALTHY),
            PasskeyIntegrityFinding("b", PasskeyIntegrityIssue.KEYSTORE_KEY_UNAVAILABLE)
        ))
        assertTrue(report.requiresBackupRestore)
        assertEquals(1, report.healthyCount)
        assertEquals(1, report.problemCount)
    }

    @Test fun metadataAuthenticationFailureRequiresBackupRestore() {
        val report = PasskeyIntegrityReport(1, listOf(
            PasskeyIntegrityFinding("a", PasskeyIntegrityIssue.METADATA_AUTHENTICATION_FAILED)
        ))
        assertTrue(report.requiresBackupRestore)
    }

    @Test fun ordinaryLegacyMigrationDoesNotClaimBackupIsRequired() {
        val report = PasskeyIntegrityReport(1, listOf(
            PasskeyIntegrityFinding("a", PasskeyIntegrityIssue.LEGACY_METADATA_NEEDS_MIGRATION)
        ))
        assertFalse(report.requiresBackupRestore)
    }

    @Test fun lockedVaultRequestsUnlockInsteadOfBackupRestore() {
        val finding = PasskeyIntegrityFinding("a", PasskeyIntegrityIssue.VAULT_LOCKED)
        val report = PasskeyIntegrityReport(1, listOf(finding))
        assertEquals(PasskeyRecoveryAction.UNLOCK_VAULT, finding.recommendedAction)
        assertFalse(report.requiresBackupRestore)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertEquals(expected: PasskeyRecoveryAction, actual: PasskeyRecoveryAction) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
