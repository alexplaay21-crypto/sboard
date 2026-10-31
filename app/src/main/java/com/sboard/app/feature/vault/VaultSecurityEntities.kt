package com.sboard.app.feature.vault

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "recovery_envelope")
data class RecoveryEnvelopeEntity(
    @PrimaryKey val id: String = "recovery",
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int
)

@Dao
interface RecoveryEnvelopeDao {
    @Query("SELECT * FROM recovery_envelope WHERE id = 'recovery' LIMIT 1")
    suspend fun get(): RecoveryEnvelopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: RecoveryEnvelopeEntity)

    @Query("DELETE FROM recovery_envelope WHERE id = 'recovery'")
    suspend fun delete()
}

@Entity(tableName = "biometric_envelope")
data class BiometricEnvelopeEntity(
    @PrimaryKey val id: String = "biometric",
    val iv: ByteArray,
    val ciphertext: ByteArray
)

@Dao
interface BiometricEnvelopeDao {
    @Query("SELECT * FROM biometric_envelope WHERE id = 'biometric' LIMIT 1")
    suspend fun get(): BiometricEnvelopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BiometricEnvelopeEntity)

    @Query("DELETE FROM biometric_envelope WHERE id = 'biometric'")
    suspend fun delete()
}
