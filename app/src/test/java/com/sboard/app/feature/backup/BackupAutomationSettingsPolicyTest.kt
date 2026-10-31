package com.sboard.app.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupAutomationSettingsPolicyTest {
    @Test fun defaultsAreConservative() {
        assertEquals(24L, BackupAutomationPolicy.DEFAULT_INTERVAL_HOURS)
        assertEquals(14, BackupAutomationPolicy.DEFAULT_KEEP_VERSIONS)
        assertFalse(BackupAutomationPolicy.isEligible(false, true, true))
    }

    @Test fun intervalBoundsAreEnforced() {
        assertEquals(12L, BackupAutomationPolicy.validateIntervalHours(12))
        assertEquals(168L, BackupAutomationPolicy.validateIntervalHours(168))
        runCatching { BackupAutomationPolicy.validateIntervalHours(11) }.onSuccess { error("11 hours must be rejected") }
        runCatching { BackupAutomationPolicy.validateIntervalHours(169) }.onSuccess { error("169 hours must be rejected") }
    }

    @Test fun retentionBoundsAreEnforced() {
        assertEquals(1, BackupAutomationPolicy.validateKeepVersions(1))
        assertEquals(100, BackupAutomationPolicy.validateKeepVersions(100))
        runCatching { BackupAutomationPolicy.validateKeepVersions(0) }.onSuccess { error("0 versions must be rejected") }
        runCatching { BackupAutomationPolicy.validateKeepVersions(101) }.onSuccess { error("101 versions must be rejected") }
    }
}
