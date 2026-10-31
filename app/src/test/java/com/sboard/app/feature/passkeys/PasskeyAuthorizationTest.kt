package com.sboard.app.feature.passkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyAuthorizationTest {
    @Test fun grantIsBoundToCredential() {
        val grant = PasskeyAuthorization.issue("credential-a", 1_000L)
        assertFalse(grant.consumeFor("credential-b", 1_001L))
        assertTrue(grant.consumeFor("credential-a", 1_001L))
    }

    @Test fun grantCanOnlyBeConsumedOnce() {
        val grant = PasskeyAuthorization.issue("credential-a", 1_000L)
        assertTrue(grant.consumeFor("credential-a", 1_001L))
        assertFalse(grant.consumeFor("credential-a", 1_002L))
    }

    @Test fun grantExpiresAfterThirtySeconds() {
        val grant = PasskeyAuthorization.issue("credential-a", 1_000L)
        assertFalse(grant.consumeFor("credential-a", 31_001L))
    }

    @Test fun grantRejectsClockMovingBackwards() {
        val grant = PasskeyAuthorization.issue("credential-a", 1_000L)
        assertFalse(grant.consumeFor("credential-a", 999L))
    }
}
