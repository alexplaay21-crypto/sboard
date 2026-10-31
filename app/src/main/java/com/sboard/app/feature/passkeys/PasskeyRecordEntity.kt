package com.sboard.app.feature.passkeys

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Private key is AES-GCM ciphertext wrapped with the unlocked Vault Key. In schema v5, rpId
 * contains a keyed blind index and display metadata is held in PasskeySecureMetadataEntity.
 * credentialId remains available for WebAuthn matching; new rows leave legacy label fields empty.
 */
@Entity(
    tableName = "passkey_records",
    indices = [Index(value = ["rpId"])]
)
data class PasskeyRecordEntity(
    @PrimaryKey val id: String,
    val rpId: String,
    val rpName: String,
    val userName: String,
    val displayName: String,
    val userHandle: ByteArray,
    val credentialId: ByteArray,
    val publicKeyCose: ByteArray,
    val encryptedPrivateKey: ByteArray,
    val iv: ByteArray,
    val signCount: Long,
    val createdAt: Long,
    val lastUsedAt: Long
)


/** Narrow projection used by Credential Manager discovery while the Vault is locked.
 * It intentionally excludes public-key bytes, encrypted private-key ciphertext, and IV.
 */

/** Portable metadata encrypted with the Vault Key. This row travels with the encrypted Vault
 * backup and can be opened on another device after the user unlocks the Vault. */
@Entity(tableName = "passkey_portable_metadata")
data class PasskeyPortableMetadataEntity(
    @PrimaryKey val recordId: String,
    val iv: ByteArray,
    val ciphertext: ByteArray
)

@Entity(tableName = "passkey_secure_metadata", indices = [Index(value = ["rpIdIndex"])])
data class PasskeySecureMetadataEntity(
    @PrimaryKey val recordId: String,
    val rpIdIndex: String,
    val iv: ByteArray,
    val ciphertext: ByteArray
)

data class PasskeyDiscoveryEnvelope(
    val recordId: String,
    val credentialId: ByteArray,
    val metadataIv: ByteArray,
    val encryptedMetadata: ByteArray,
    val rpIdIndex: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

data class PasskeyIntegrityEnvelope(
    val recordId: String,
    val rpId: String,
    val credentialIdLength: Int,
    val privateKeyCiphertextLength: Int,
    val privateKeyIvLength: Int,
    val signCount: Long
)

data class PasskeyDiscoveryEntry(
    val id: String,
    val rpId: String,
    val rpName: String,
    val userName: String,
    val displayName: String,
    val credentialId: ByteArray,
    val createdAt: Long,
    val lastUsedAt: Long
)

@Dao
interface PasskeyRecordDao {
    @Query("""
        SELECT id, rpId, rpName, userName, displayName, credentialId, createdAt, lastUsedAt
        FROM passkey_records WHERE rpId = :rpId ORDER BY createdAt DESC
    """)
    suspend fun discoveryByRpId(rpId: String): List<PasskeyDiscoveryEntry>

    @Query("SELECT * FROM passkey_records WHERE rpId = :rpId ORDER BY createdAt DESC")
    suspend fun byRpId(rpId: String): List<PasskeyRecordEntity>

    @Query("SELECT * FROM passkey_records ORDER BY rpId, userName")
    suspend fun getAll(): List<PasskeyRecordEntity>

    @Query("SELECT * FROM passkey_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PasskeyRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSecureMetadata(metadata: PasskeySecureMetadataEntity)

    @Query("SELECT * FROM passkey_secure_metadata WHERE recordId = :id LIMIT 1")
    suspend fun secureMetadataById(id: String): PasskeySecureMetadataEntity?

    @Query("SELECT * FROM passkey_secure_metadata")
    suspend fun allSecureMetadata(): List<PasskeySecureMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPortableMetadata(metadata: PasskeyPortableMetadataEntity)

    @Query("SELECT * FROM passkey_portable_metadata WHERE recordId = :id LIMIT 1")
    suspend fun portableMetadataById(id: String): PasskeyPortableMetadataEntity?

    @Query("SELECT * FROM passkey_portable_metadata")
    suspend fun allPortableMetadata(): List<PasskeyPortableMetadataEntity>

    @Query("DELETE FROM passkey_portable_metadata WHERE recordId = :id")
    suspend fun deletePortableMetadata(id: String)

    @Query("""
        SELECT id AS recordId, rpId,
               length(credentialId) AS credentialIdLength,
               length(encryptedPrivateKey) AS privateKeyCiphertextLength,
               length(iv) AS privateKeyIvLength, signCount
        FROM passkey_records
    """)
    suspend fun integrityEnvelopes(): List<PasskeyIntegrityEnvelope>

    @Query("""
        SELECT p.id AS recordId, p.credentialId AS credentialId,
               m.iv AS metadataIv, m.ciphertext AS encryptedMetadata, m.rpIdIndex AS rpIdIndex,
               p.createdAt AS createdAt, p.lastUsedAt AS lastUsedAt
        FROM passkey_records p INNER JOIN passkey_secure_metadata m ON p.id = m.recordId
        WHERE m.rpIdIndex = :rpIdIndex ORDER BY p.createdAt DESC
    """)
    suspend fun secureDiscoveryByRpIndex(rpIdIndex: String): List<PasskeyDiscoveryEnvelope>

    @Query("UPDATE passkey_records SET rpId = :rpIdIndex, rpName = '', userName = '', displayName = '', userHandle = X'', publicKeyCose = X'' WHERE id = :id")
    suspend fun scrubLegacyMetadata(id: String, rpIdIndex: String)

    @Query("SELECT * FROM passkey_records WHERE rpId = :rpId ORDER BY createdAt DESC")
    suspend fun legacyByRpId(rpId: String): List<PasskeyRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PasskeyRecordEntity)

    @Transaction
    suspend fun upsertComplete(
        record: PasskeyRecordEntity,
        localMetadata: PasskeySecureMetadataEntity,
        portableMetadata: PasskeyPortableMetadataEntity
    ) {
        upsert(record)
        upsertSecureMetadata(localMetadata)
        upsertPortableMetadata(portableMetadata)
    }

    @Query("UPDATE passkey_records SET signCount = :newCount, lastUsedAt = :usedAt WHERE id = :id")
    suspend fun updateUsage(id: String, newCount: Long, usedAt: Long)

    @Query("DELETE FROM passkey_secure_metadata WHERE recordId = :id")
    suspend fun deleteSecureMetadata(id: String)

    @Query("DELETE FROM passkey_records WHERE id = :id")
    suspend fun delete(id: String)
}

