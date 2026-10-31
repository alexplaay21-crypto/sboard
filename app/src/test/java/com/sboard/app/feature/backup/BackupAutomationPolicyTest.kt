package com.sboard.app.feature.backup

import org.junit.Assert.*
import org.junit.Test

class BackupAutomationPolicyTest {
    @Test fun intervalBoundsAreEnforced() {
        assertEquals(24L * 60 * 60 * 1000, BackupAutomationPolicy.periodicIntervalMillis(24))
        assertEquals(12L, BackupAutomationPolicy.validateIntervalHours(12))
        assertEquals(168L, BackupAutomationPolicy.validateIntervalHours(168))
        assertThrows(IllegalArgumentException::class.java) { BackupAutomationPolicy.validateIntervalHours(11) }
        assertThrows(IllegalArgumentException::class.java) { BackupAutomationPolicy.validateIntervalHours(169) }
    }

    @Test fun retentionBoundsAreEnforced() {
        assertEquals(14, BackupAutomationPolicy.validateKeepVersions(14))
        assertThrows(IllegalArgumentException::class.java) { BackupAutomationPolicy.validateKeepVersions(0) }
        assertThrows(IllegalArgumentException::class.java) { BackupAutomationPolicy.validateKeepVersions(101) }
    }

    @Test fun eventTriggeredBackupNeedsOnlyDeviceRecoveryKeyAndDriveToken() {
        assertFalse(BackupAutomationPolicy.canExecute(false, true))
        assertFalse(BackupAutomationPolicy.canExecute(true, false))
        assertTrue(BackupAutomationPolicy.canExecute(true, true))
        assertEquals(3L, BackupAutomationPolicy.CHANGE_DEBOUNCE_SECONDS)
    }

    @Test fun setupMustBeCompleteBeforeAutomationIsEligible() {
        assertFalse(BackupAutomationPolicy.isEligible(true, false, true))
        assertFalse(BackupAutomationPolicy.isEligible(true, true, false))
        assertFalse(BackupAutomationPolicy.isEligible(false, true, true))
        assertTrue(BackupAutomationPolicy.isEligible(true, true, true))
    }
}
