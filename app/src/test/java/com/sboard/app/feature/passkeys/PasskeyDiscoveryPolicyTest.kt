package com.sboard.app.feature.passkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyDiscoveryPolicyTest {
    private val credentialId = byteArrayOf(0x01, 0x23, 0x45, 0x67)

    @Test fun nullAllowListAllowsDiscoverableCredential() {
        assertTrue(PasskeyDiscoveryPolicy.isAllowed(credentialId, null))
    }

    @Test fun matchingAllowListEntryIsAccepted() {
        assertTrue(PasskeyDiscoveryPolicy.isAllowed(credentialId, setOf("01234567")))
    }

    @Test fun nonMatchingAllowListEntryIsRejected() {
        assertFalse(PasskeyDiscoveryPolicy.isAllowed(credentialId, setOf("deadbeef")))
    }

    @Test fun malformedNonEmptyAllowListFailsClosed() {
        assertFalse(PasskeyDiscoveryPolicy.isAllowed(credentialId, emptySet()))
    }
}
