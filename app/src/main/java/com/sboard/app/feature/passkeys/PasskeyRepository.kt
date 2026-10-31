package com.sboard.app.feature.passkeys

import android.content.Context
import com.sboard.app.feature.vault.VaultDatabase
import com.sboard.app.feature.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Passkey private keys and portable metadata use the Vault Key. A separate Android Keystore
 * encrypted cache is retained only for fast discovery while this particular device is locked.
 * The cache is rebuildable and is never the only copy of metadata.
 */
class PasskeyRepository(
    context: Context,
    private val vaultSession: VaultSession
) : PasskeyAssertionStore {
    private val dao = VaultDatabase.get(context).passkeys()

    suspend fun save(
        rpId: String,
        rpName: String,
        userName: String,
        displayName: String,
        userHandle: ByteArray,
        credentialId: ByteArray,
        publicKeyCose: ByteArray,
        privateKeyPkcs8: ByteArray,
        signCount: Long = 0L
    ): String = withContext(Dispatchers.IO) {
        val normalizedRpId = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rpId))
        require(userHandle.isNotEmpty() && credentialId.isNotEmpty())
        require(publicKeyCose.isNotEmpty() && privateKeyPkcs8.isNotEmpty())
        require(signCount >= 0)

        val id = UUID.randomUUID().toString()
        val vaultKey = vaultSession.copyKey()
        val metadata = PasskeyMetadataCipher.Metadata(
            rpId = normalizedRpId,
            rpName = rpName,
            userName = userName,
            displayName = displayName,
            userHandle = userHandle.copyOf(),
            publicKeyCose = publicKeyCose.copyOf()
        )
        val encryptedPrivateKey: PasskeyPrivateKeyCipher.EncryptedPrivateKey
        val portableMetadata: PasskeyPortableMetadataCipher.Encrypted
        try {
            encryptedPrivateKey = PasskeyPrivateKeyCipher.encrypt(privateKeyPkcs8, vaultKey, id, normalizedRpId)
            portableMetadata = PasskeyPortableMetadataCipher.encrypt(id, metadata, vaultKey)
        } finally {
            vaultKey.fill(0)
            metadata.userHandle.fill(0)
            metadata.publicKeyCose.fill(0)
        }
        // Device-local metadata cache enables locked discovery on this phone only.
        val localMetadata = PasskeyMetadataCipher.Metadata(
            normalizedRpId, rpName, userName, displayName, userHandle.copyOf(), publicKeyCose.copyOf()
        )
        val encryptedMetadata = try { PasskeyMetadataCipher.encrypt(id, localMetadata) }
        finally {
            localMetadata.userHandle.fill(0)
            localMetadata.publicKeyCose.fill(0)
        }
        val now = System.currentTimeMillis()
        // rpId stores only a keyed blind index; all user/site labels and userHandle are encrypted.
        dao.upsertComplete(
            record = PasskeyRecordEntity(
                id = id,
                rpId = PasskeyMetadataCipher.indexForRpId(normalizedRpId),
                rpName = "", userName = "", displayName = "",
                userHandle = byteArrayOf(), credentialId = credentialId.copyOf(),
                publicKeyCose = byteArrayOf(),
                encryptedPrivateKey = encryptedPrivateKey.ciphertext,
                iv = encryptedPrivateKey.iv,
                signCount = signCount, createdAt = now, lastUsedAt = 0L
            ),
            localMetadata = PasskeySecureMetadataEntity(
                recordId = id,
                rpIdIndex = PasskeyMetadataCipher.indexForRpId(normalizedRpId),
                iv = encryptedMetadata.iv,
                ciphertext = encryptedMetadata.ciphertext
            ),
            portableMetadata = PasskeyPortableMetadataEntity(
                recordId = id,
                iv = portableMetadata.iv,
                ciphertext = portableMetadata.ciphertext
            )
        )
        id
    }

    override suspend fun getMetadata(recordId: String): PasskeyRecordEntity? = withContext(Dispatchers.IO) {
        val row = dao.getById(recordId) ?: return@withContext null
        val portable = dao.portableMetadataById(recordId)
        if (portable != null) return@withContext restorePortableMetadata(row, portable)
        // One-time migration of older device-bound metadata while the Vault is unlocked.
        migrateLegacyRow(row)
        val migrated = dao.portableMetadataById(recordId) ?: return@withContext null
        restorePortableMetadata(requireNotNull(dao.getById(recordId)), migrated)
    }

    /** Decrypts only the small metadata envelope needed for the Credential Manager picker. */
    suspend fun listForRelyingParty(rpId: String): List<PasskeyDiscoveryEntry> = withContext(Dispatchers.IO) {
        val normalized = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(rpId))
        // A restored database carries portable metadata, not this phone's Keystore cache.
        // Once unlocked, rebuild the local discovery index before querying it.
        if (vaultSession.isUnlocked) rebuildDeviceDiscoveryCache()
        val index = PasskeyMetadataCipher.indexForRpId(normalized)
        val secure = dao.secureDiscoveryByRpIndex(index).mapNotNull { envelope ->
            val metadata = runCatching {
                PasskeyMetadataCipher.decrypt(envelope.recordId,
                    PasskeyMetadataCipher.Encrypted(envelope.metadataIv, envelope.encryptedMetadata))
            }.getOrNull() ?: return@mapNotNull null
            if (PasskeyMetadataCipher.indexForRpId(metadata.rpId) != index) return@mapNotNull null
            PasskeyDiscoveryEntry(envelope.recordId, metadata.rpId, metadata.rpName,
                metadata.userName, metadata.displayName, envelope.credentialId,
                envelope.createdAt, envelope.lastUsedAt)
        }
        // Migrate matching pre-v5 records in place, then re-read via the encrypted index.
        dao.legacyByRpId(normalized).forEach { legacy -> runCatching { migrateLegacyRow(legacy) } }
        if (secure.isEmpty()) dao.secureDiscoveryByRpIndex(index).mapNotNull { toDiscovery(it) }
        else secure + dao.secureDiscoveryByRpIndex(index).filter { envelope -> secure.none { it.id == envelope.recordId } }.mapNotNull { toDiscovery(it) }
    }

    suspend fun listAllMetadata(): List<PasskeyRecordEntity> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { row -> runCatching { getMetadata(row.id) }.getOrNull() }
    }

    /** Rebuilds only the device-local locked-discovery cache from portable Vault-key metadata. */
    suspend fun rebuildDeviceDiscoveryCache(): Int = withContext(Dispatchers.IO) {
        if (!vaultSession.isUnlocked) return@withContext 0
        var rebuilt = 0
        val key = vaultSession.copyKey()
        try {
            dao.getAll().forEach { row ->
                var metadata: PasskeyMetadataCipher.Metadata? = null
                try {
                    var portable = dao.portableMetadataById(row.id)
                    if (portable == null) {
                        // Upgrade older records that only had this phone's Keystore-encrypted cache.
                        runCatching { migrateLegacyRow(row) }
                        portable = dao.portableMetadataById(row.id) ?: return@forEach
                    }
                    val decoded = PasskeyPortableMetadataCipher.decrypt(
                        row.id, PasskeyPortableMetadataCipher.Encrypted(portable.iv, portable.ciphertext), key
                    )
                    metadata = decoded
                    val normalized = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(decoded.rpId))
                    val index = PasskeyMetadataCipher.indexForRpId(normalized)
                    val local = PasskeyMetadataCipher.encrypt(row.id, decoded)
                    dao.upsertSecureMetadata(PasskeySecureMetadataEntity(row.id, index, local.iv, local.ciphertext))
                    dao.upsert(row.copy(rpId = index, rpName = "", userName = "", displayName = "",
                        userHandle = byteArrayOf(), publicKeyCose = byteArrayOf()))
                    rebuilt++
                } catch (_: Exception) {
                    // One damaged Passkey must not prevent Vault unlock or cache rebuild for others.
                } finally {
                    metadata?.userHandle?.fill(0)
                    metadata?.publicKeyCose?.fill(0)
                }
            }
        } finally { key.fill(0) }
        rebuilt
    }

    /** Caller must wipe returned PKCS#8 bytes. */
    override suspend fun openPrivateKey(recordId: String, authorization: PasskeyAuthorization): ByteArray = withContext(Dispatchers.IO) {
        require(authorization.consumeFor(recordId)) { "Fresh biometric authorization required" }
        val record = requireNotNull(getMetadata(recordId)) { "Passkey not found" }
        val key = vaultSession.copyKey()
        try {
            PasskeyPrivateKeyCipher.decrypt(
                PasskeyPrivateKeyCipher.EncryptedPrivateKey(record.iv, record.encryptedPrivateKey),
                key, record.id, record.rpId
            )
        } finally { key.fill(0) }
    }

    override suspend fun markUsed(recordId: String, newSignCount: Long) = withContext(Dispatchers.IO) {
        require(newSignCount >= 0)
        dao.updateUsage(recordId, newSignCount, System.currentTimeMillis())
    }

    /** Read-only audit. It never deletes or rewrites damaged records; recovery must be explicit. */
    suspend fun auditIntegrity(): PasskeyIntegrityReport = withContext(Dispatchers.IO) {
        val rows = dao.integrityEnvelopes()
        val portableRows = dao.allPortableMetadata().associateBy { it.recordId }
        val rowIds = rows.mapTo(HashSet()) { it.recordId }
        val findings = ArrayList<PasskeyIntegrityFinding>(rows.size + portableRows.size)

        val auditKey = if (vaultSession.isUnlocked) vaultSession.copyKey() else null
        try {
        rows.forEach { row ->
            val portable = portableRows[row.recordId]
            if (portable == null) {
                val issue = if (PasskeyIntegrityPolicy.isBlindIndex(row.rpId))
                    PasskeyIntegrityIssue.SECURE_METADATA_MISSING
                else PasskeyIntegrityIssue.LEGACY_METADATA_NEEDS_MIGRATION
                findings += PasskeyIntegrityFinding(row.recordId, issue)
                return@forEach
            }
            if (auditKey == null) {
                findings += PasskeyIntegrityFinding(row.recordId, PasskeyIntegrityIssue.VAULT_LOCKED)
                return@forEach
            }
            if (!PasskeyIntegrityPolicy.isCredentialMaterialValid(
                    row.credentialIdLength, row.privateKeyCiphertextLength, row.privateKeyIvLength, row.signCount)) {
                findings += PasskeyIntegrityFinding(row.recordId, PasskeyIntegrityIssue.INVALID_CREDENTIAL_MATERIAL)
                return@forEach
            }
            var decoded: PasskeyMetadataCipher.Metadata? = null
            try {
                decoded = PasskeyPortableMetadataCipher.decrypt(row.recordId,
                    PasskeyPortableMetadataCipher.Encrypted(portable.iv, portable.ciphertext), auditKey)
                if (!PasskeyIntegrityPolicy.isMetadataContentValid(
                        decoded.userHandle.size, decoded.publicKeyCose.size)) {
                    findings += PasskeyIntegrityFinding(row.recordId, PasskeyIntegrityIssue.INVALID_METADATA_CONTENT)
                } else {
                    val normalized = WebAuthnOriginPolicy.normalizeRpId(decoded.rpId)
                    findings += PasskeyIntegrityFinding(row.recordId, if (normalized != null)
                        PasskeyIntegrityIssue.HEALTHY else PasskeyIntegrityIssue.METADATA_INDEX_MISMATCH)
                }
            } catch (error: Exception) {
                val issue = PasskeyIntegrityIssue.METADATA_AUTHENTICATION_FAILED
                findings += PasskeyIntegrityFinding(row.recordId, issue)
            } finally {
                decoded?.userHandle?.fill(0)
                decoded?.publicKeyCose?.fill(0)
            }
        }
        portableRows.keys.filterNot { it in rowIds }.forEach { orphanId ->
            findings += PasskeyIntegrityFinding(orphanId, PasskeyIntegrityIssue.ORPHANED_METADATA)
        }
        } finally { auditKey?.fill(0) }
        PasskeyIntegrityReport(rows.size, findings)
    }

    suspend fun delete(recordId: String) = withContext(Dispatchers.IO) {
        dao.deleteSecureMetadata(recordId)
        dao.deletePortableMetadata(recordId)
        dao.delete(recordId)
    }

    private suspend fun migrateLegacyRow(row: PasskeyRecordEntity) {
        if (dao.portableMetadataById(row.id) != null) return
        val existingLocal = dao.secureMetadataById(row.id)
        val metadata = if (existingLocal != null) {
            // Existing v5-v7 row: decrypt the device-local cache before creating portable copy.
            PasskeyMetadataCipher.decrypt(row.id,
                PasskeyMetadataCipher.Encrypted(existingLocal.iv, existingLocal.ciphertext))
        } else {
            // Older pre-v5 row stored metadata in clear columns; migrate then scrub them.
            val normalized = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(row.rpId))
            PasskeyMetadataCipher.Metadata(
                normalized, row.rpName, row.userName, row.displayName,
                row.userHandle.copyOf(), row.publicKeyCose.copyOf()
            )
        }
        val normalized = requireNotNull(WebAuthnOriginPolicy.normalizeRpId(metadata.rpId))
        val key = vaultSession.copyKey()
        try {
            val portable = PasskeyPortableMetadataCipher.encrypt(row.id, metadata, key)
            dao.upsertPortableMetadata(PasskeyPortableMetadataEntity(row.id, portable.iv, portable.ciphertext))
        } finally { key.fill(0) }
        if (existingLocal == null) {
            val encrypted = PasskeyMetadataCipher.encrypt(row.id, metadata)
            val index = PasskeyMetadataCipher.indexForRpId(normalized)
            dao.upsertSecureMetadata(PasskeySecureMetadataEntity(row.id, index, encrypted.iv, encrypted.ciphertext))
            dao.scrubLegacyMetadata(row.id, index)
        }
        metadata.userHandle.fill(0)
        metadata.publicKeyCose.fill(0)
    }

    private fun restorePortableMetadata(row: PasskeyRecordEntity, portable: PasskeyPortableMetadataEntity): PasskeyRecordEntity {
        val key = vaultSession.copyKey()
        val metadata = try {
            PasskeyPortableMetadataCipher.decrypt(row.id,
                PasskeyPortableMetadataCipher.Encrypted(portable.iv, portable.ciphertext), key)
        } finally { key.fill(0) }
        return row.copy(
            rpId = metadata.rpId, rpName = metadata.rpName,
            userName = metadata.userName, displayName = metadata.displayName,
            userHandle = metadata.userHandle, publicKeyCose = metadata.publicKeyCose
        )
    }

    private fun toDiscovery(envelope: PasskeyDiscoveryEnvelope): PasskeyDiscoveryEntry? = runCatching {
        val metadata = PasskeyMetadataCipher.decrypt(envelope.recordId,
            PasskeyMetadataCipher.Encrypted(envelope.metadataIv, envelope.encryptedMetadata))
        require(PasskeyMetadataCipher.indexForRpId(metadata.rpId) == envelope.rpIdIndex)
        PasskeyDiscoveryEntry(envelope.recordId, metadata.rpId, metadata.rpName,
            metadata.userName, metadata.displayName, envelope.credentialId,
            envelope.createdAt, envelope.lastUsedAt)
    }.getOrNull()
}
