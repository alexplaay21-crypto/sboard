package com.sboard.app.feature.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncInboxResolutionPolicyTest {
    @Test fun acceptingRemoteIsMarkedAsRemoteResolution() {
        assertEquals("RESOLVED_REMOTE", SyncInboxResolutionPolicy.resolvedState(SyncInboxResolutionPolicy.Choice.ACCEPT_REMOTE))
    }

    @Test fun keepingLocalIsMarkedAsLocalResolution() {
        assertEquals("RESOLVED_LOCAL", SyncInboxResolutionPolicy.resolvedState(SyncInboxResolutionPolicy.Choice.KEEP_LOCAL))
    }

    @Test fun finalExistingRecordPublishesUpsert() {
        assertEquals("UPSERT", SyncInboxResolutionPolicy.resultingOperation(true))
    }

    @Test fun finalMissingRecordPublishesDeleteTombstone() {
        assertEquals("DELETE", SyncInboxResolutionPolicy.resultingOperation(false))
    }
}
