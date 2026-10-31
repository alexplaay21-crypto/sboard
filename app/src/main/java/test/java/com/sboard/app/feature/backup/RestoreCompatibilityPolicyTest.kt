package com.sboard.app.feature.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCompatibilityPolicyTest {
    @Test fun acceptsKnownRoomSchemaVersions() {
        for (version in 1..5) assertTrue("schema $version", RestoreCompatibilityPolicy.isSupportedSchema(version))
    }

    @Test fun rejectsEmptyFutureAndNegativeVersions() {
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(0))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(-1))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(6))
        assertFalse(RestoreCompatibilityPolicy.isSupportedSchema(Int.MAX_VALUE))
    }
}
