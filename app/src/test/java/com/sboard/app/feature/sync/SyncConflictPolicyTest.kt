package com.sboard.app.feature.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictPolicyTest {
    private val local = SyncRevision("device-a", 12, 1000)
    private val remote = SyncRevision("device-a", 10, 900)

    @Test fun newerRevisionOnSameDeviceWins() {
        assertEquals(
            SyncConflictDecision.KEEP_LOCAL,
            SyncConflictPolicy.decide(local, remote, "hash-a", "hash-b")
        )
    }

    @Test fun olderLocalRevisionAcceptsRemoteOnSameDevice() {
        assertEquals(
            SyncConflictDecision.ACCEPT_REMOTE,
            SyncConflictPolicy.decide(remote, local, "hash-b", "hash-a")
        )
    }

    @Test fun sameRevisionAndSameContentIsNoChange() {
        assertEquals(
            SyncConflictDecision.NO_CHANGE,
            SyncConflictPolicy.decide(local, local, "same", "same")
        )
    }

    @Test fun sameRevisionButDifferentContentRequiresResolution() {
        assertEquals(
            SyncConflictDecision.NEEDS_USER_RESOLUTION,
            SyncConflictPolicy.decide(local, local, "local", "remote")
        )
    }

    @Test fun crossDeviceDifferentContentNeverSilentlyOverwrites() {
        assertEquals(
            SyncConflictDecision.NEEDS_USER_RESOLUTION,
            SyncConflictPolicy.decide(local, SyncRevision("device-b", 99, 5000), "local", "remote")
        )
    }

    @Test fun crossDeviceIdenticalContentIsNoChange() {
        assertEquals(
            SyncConflictDecision.NO_CHANGE,
            SyncConflictPolicy.decide(local, SyncRevision("device-b", 99, 5000), "same", "same")
        )
    }
}
