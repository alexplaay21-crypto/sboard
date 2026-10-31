package com.sboard.app.feature.passkeys

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived, single-use proof that a strong biometric prompt completed for one credential.
 * The constructor is internal: application code must obtain a grant from BiometricVaultManager.
 */
class PasskeyAuthorization internal constructor(
    internal val recordId: String,
    private val issuedAtMillis: Long,
    private val consumed: AtomicBoolean = AtomicBoolean(false)
) {
    internal fun consumeFor(expectedRecordId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (recordId != expectedRecordId) return false
        if (nowMillis < issuedAtMillis || nowMillis - issuedAtMillis > VALIDITY_MILLIS) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val VALIDITY_MILLIS = 30_000L

        internal fun issue(recordId: String, nowMillis: Long = System.currentTimeMillis()): PasskeyAuthorization {
            require(recordId.isNotBlank())
            return PasskeyAuthorization(recordId, nowMillis)
        }
    }
}
