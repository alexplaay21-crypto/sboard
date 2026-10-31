package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sboard.app.feature.vault.VAULT_DATABASE_SCHEMA_VERSION

class RestoreCompatibilityPolicyTest {
    @Test fun acceptsKnownRoomSchemaVersions() {
        for (version in 1..VAULT_DATABASE_SCHEMA_VERSION) assertTrue("schema $version", RestoreCompatibilityPolicy.isSupportedSchema(version))
    }

    @Test fun acceptsCurrentDatabaseSchemaVersion() {
        assertTrue(RestoreCompatibilityPolicy.isSupportedSchema(VAULT_DATABASE_SCHEMA_VERSION))
    }

    @Test fun rejectsEmptyFutureAndNegativeVersions() {
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(0))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(-1))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(VAULT_DATABASE_SCHEMA_VERSION + 1))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(Int.MAX_VALUE))
    }
}
