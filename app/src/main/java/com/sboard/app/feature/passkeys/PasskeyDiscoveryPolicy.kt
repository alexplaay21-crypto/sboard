package com.sboard.app.feature.passkeys

/** Small, JVM-testable allowCredentials policy used by provider discovery. */
internal object PasskeyDiscoveryPolicy {
    /** null means the request omitted/emptied allowCredentials; an empty set means malformed IDs. */
    fun isAllowed(credentialId: ByteArray, allowedCredentialIdsHex: Set<String>?): Boolean {
        if (allowedCredentialIdsHex == null) return true
        val hex = credentialId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return hex in allowedCredentialIdsHex
    }
}
