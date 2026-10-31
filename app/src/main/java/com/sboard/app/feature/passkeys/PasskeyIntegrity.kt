package com.sboard.app.feature.passkeys

/** Safe, non-secret result of a Passkey storage audit. Never include ciphertext or key bytes. */
enum class PasskeyIntegrityIssue {
    HEALTHY,
    LEGACY_METADATA_NEEDS_MIGRATION,
    SECURE_METADATA_MISSING,
    METADATA_AUTHENTICATION_FAILED,
    METADATA_INDEX_MISMATCH,
    INVALID_CREDENTIAL_MATERIAL,
    INVALID_METADATA_CONTENT,
    KEYSTORE_KEY_UNAVAILABLE,
    VAULT_LOCKED,
    ORPHANED_METADATA
}

enum class PasskeyRecoveryAction {
    NONE,
    UNLOCK_VAULT,
    MIGRATE_LEGACY_METADATA,
    RESTORE_SUPPORTED_BACKUP_OR_REREGISTER,
    REREGISTER_CREDENTIAL,
    INVESTIGATE_ORPHAN_WITHOUT_AUTO_DELETION
}

data class PasskeyIntegrityFinding(
    val recordId: String,
    val issue: PasskeyIntegrityIssue
) {
    val recommendedAction: PasskeyRecoveryAction get() = when (issue) {
        PasskeyIntegrityIssue.HEALTHY -> PasskeyRecoveryAction.NONE
        PasskeyIntegrityIssue.VAULT_LOCKED -> PasskeyRecoveryAction.UNLOCK_VAULT
        PasskeyIntegrityIssue.LEGACY_METADATA_NEEDS_MIGRATION -> PasskeyRecoveryAction.MIGRATE_LEGACY_METADATA
        PasskeyIntegrityIssue.INVALID_CREDENTIAL_MATERIAL -> PasskeyRecoveryAction.REREGISTER_CREDENTIAL
        PasskeyIntegrityIssue.ORPHANED_METADATA -> PasskeyRecoveryAction.INVESTIGATE_ORPHAN_WITHOUT_AUTO_DELETION
        else -> PasskeyRecoveryAction.RESTORE_SUPPORTED_BACKUP_OR_REREGISTER
    }
}

data class PasskeyIntegrityReport(
    val checkedRecords: Int,
    val findings: List<PasskeyIntegrityFinding>
) {
    val healthyCount: Int get() = findings.count { it.issue == PasskeyIntegrityIssue.HEALTHY }
    val problemCount: Int get() = findings.count { it.issue != PasskeyIntegrityIssue.HEALTHY }
    val requiresBackupRestore: Boolean get() = findings.any {
        it.issue == PasskeyIntegrityIssue.SECURE_METADATA_MISSING ||
            it.issue == PasskeyIntegrityIssue.METADATA_AUTHENTICATION_FAILED ||
            it.issue == PasskeyIntegrityIssue.METADATA_INDEX_MISMATCH ||
            it.issue == PasskeyIntegrityIssue.INVALID_METADATA_CONTENT ||
            it.issue == PasskeyIntegrityIssue.KEYSTORE_KEY_UNAVAILABLE
    }
}

/** Pure policy helpers, kept separate so integrity decisions can be JVM-tested. */
internal object PasskeyIntegrityPolicy {
    fun isBlindIndex(value: String): Boolean = value.length == 64 && value.all {
        it in '0'..'9' || it in 'a'..'f'
    }

    fun isCredentialMaterialValid(credentialIdLength: Int, encryptedPrivateKeyLength: Int,
                                  privateKeyIvLength: Int, signCount: Long): Boolean =
        credentialIdLength > 0 && encryptedPrivateKeyLength >= 16 &&
            privateKeyIvLength == 12 && signCount >= 0

    fun isMetadataContentValid(userHandleLength: Int, publicKeyCoseLength: Int): Boolean =
        userHandleLength in 1..64 && publicKeyCoseLength > 0

    fun isKeystoreUnavailable(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val name = current.javaClass.name
            if (name.endsWith("KeyPermanentlyInvalidatedException") ||
                name.endsWith("UnrecoverableKeyException") ||
                name.endsWith("UserNotAuthenticatedException") ||
                name.endsWith("KeyStoreException") ||
                name.endsWith("InvalidKeyException")) return true
            current = current.cause
        }
        return false
    }
}
