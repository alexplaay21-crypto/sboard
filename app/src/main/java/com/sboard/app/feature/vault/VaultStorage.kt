package com.sboard.app.feature.vault

import com.sboard.app.feature.passkeys.PasskeyRecordEntity
import com.sboard.app.feature.passkeys.PasskeyRecordDao
import com.sboard.app.feature.passkeys.PasskeySecureMetadataEntity
import com.sboard.app.feature.passkeys.PasskeyPortableMetadataEntity
import com.sboard.app.feature.sync.DeviceIdentityStore
import com.sboard.app.feature.sync.SyncChangeEntity
import com.sboard.app.feature.sync.SyncInboxEntity
import com.sboard.app.feature.sync.SyncInboxDao

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val VAULT_DATABASE_SCHEMA_VERSION = 8

/** Only encrypted payloads are persisted. The entry title and secret fields are encrypted together. */
@Entity(tableName = "vault_records")
data class VaultRecordEntity(
    @PrimaryKey val id: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "vault_config")
data class VaultConfigEntity(
    @PrimaryKey val id: String = "master",
    val salt: ByteArray,
    val iv: ByteArray,
    val wrappedVaultKey: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int
)

@Dao
interface VaultConfigDao {
    @Query("SELECT * FROM vault_config WHERE id = 'master' LIMIT 1")
    suspend fun get(): VaultConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(config: VaultConfigEntity)
}

@Dao
interface VaultRecordDao {
    @Query("SELECT * FROM vault_records ORDER BY updatedAt DESC")
    suspend fun getAll(): List<VaultRecordEntity>

    @Query("SELECT * FROM vault_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VaultRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VaultRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncChange(change: SyncChangeEntity)

    @Transaction
    suspend fun insertWithJournal(record: VaultRecordEntity, change: SyncChangeEntity) {
        insert(record)
        insertSyncChange(change)
    }

    @Query("DELETE FROM vault_records WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun deleteWithJournal(id: String, change: SyncChangeEntity) {
        delete(id)
        insertSyncChange(change)
    }

    @Query("SELECT * FROM sync_change_journal WHERE deliveryState = 'PENDING' ORDER BY changedAt ASC LIMIT :limit")
    suspend fun getPendingSyncJournal(limit: Int = 5000): List<SyncChangeEntity>

    @Query("UPDATE sync_change_journal SET deliveryState = :state WHERE changeId = :changeId")
    suspend fun setSyncDeliveryState(changeId: String, state: String)

    @Query("UPDATE sync_change_journal SET encryptedEnvelope = :envelope WHERE changeId = :changeId AND encryptedEnvelope IS NULL")
    suspend fun cacheSyncEnvelopeIfAbsent(changeId: String, envelope: ByteArray)

    @Query("SELECT * FROM sync_change_journal WHERE changeId = :changeId LIMIT 1")
    suspend fun getSyncChange(changeId: String): SyncChangeEntity?

    @Query("SELECT * FROM sync_change_journal ORDER BY revision ASC, changedAt ASC LIMIT :limit")
    suspend fun getSyncJournal(limit: Int = 1000): List<SyncChangeEntity>
}

@Database(entities = [VaultRecordEntity::class, VaultConfigEntity::class, RecoveryEnvelopeEntity::class, BiometricEnvelopeEntity::class, PasskeyRecordEntity::class, PasskeySecureMetadataEntity::class, PasskeyPortableMetadataEntity::class, SyncChangeEntity::class, SyncInboxEntity::class], version = VAULT_DATABASE_SCHEMA_VERSION, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun records(): VaultRecordDao
    abstract fun config(): VaultConfigDao
    abstract fun recovery(): RecoveryEnvelopeDao
    abstract fun biometric(): BiometricEnvelopeDao
    abstract fun passkeys(): PasskeyRecordDao
    abstract fun syncInbox(): SyncInboxDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vault_config (
                        id TEXT NOT NULL PRIMARY KEY,
                        salt BLOB NOT NULL,
                        iv BLOB NOT NULL,
                        wrappedVaultKey BLOB NOT NULL,
                        memoryKiB INTEGER NOT NULL,
                        iterations INTEGER NOT NULL,
                        parallelism INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recovery_envelope (
                        id TEXT NOT NULL PRIMARY KEY,
                        salt BLOB NOT NULL,
                        iv BLOB NOT NULL,
                        ciphertext BLOB NOT NULL,
                        memoryKiB INTEGER NOT NULL,
                        iterations INTEGER NOT NULL,
                        parallelism INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS biometric_envelope (
                        id TEXT NOT NULL PRIMARY KEY,
                        iv BLOB NOT NULL,
                        ciphertext BLOB NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS passkey_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        rpId TEXT NOT NULL,
                        rpName TEXT NOT NULL,
                        userName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        userHandle BLOB NOT NULL,
                        credentialId BLOB NOT NULL,
                        publicKeyCose BLOB NOT NULL,
                        encryptedPrivateKey BLOB NOT NULL,
                        iv BLOB NOT NULL,
                        signCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_records_rpId ON passkey_records(rpId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS passkey_secure_metadata (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        rpIdIndex TEXT NOT NULL,
                        iv BLOB NOT NULL,
                        ciphertext BLOB NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_secure_metadata_rpIdIndex ON passkey_secure_metadata(rpIdIndex)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_change_journal (
                        changeId TEXT NOT NULL PRIMARY KEY,
                        recordId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        changedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_recordId_revision ON sync_change_journal(recordId, revision)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_deviceId_revision ON sync_change_journal(deviceId, revision)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_change_journal ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE sync_change_journal ADD COLUMN encryptedEnvelope BLOB")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_deliveryState ON sync_change_journal(deliveryState)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_inbox (
                        changeId TEXT NOT NULL PRIMARY KEY,
                        recordId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        changedAt INTEGER NOT NULL,
                        encryptedEnvelope BLOB NOT NULL,
                        receivedAt INTEGER NOT NULL,
                        state TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_inbox_deviceId_revision ON sync_inbox(deviceId, revision)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS passkey_portable_metadata (
                        recordId TEXT NOT NULL PRIMARY KEY,
                        iv BLOB NOT NULL,
                        ciphertext BLOB NOT NULL
                    )
                """.trimIndent())
            }
        }

        @Volatile private var INSTANCE: VaultDatabase? = null
        fun get(context: Context): VaultDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                "sboard_vault.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
        }

        /** Close the singleton before an authenticated database restore replaces the file. */
        @Synchronized
        fun closeForRestore() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

/** AES-256-GCM with per-record AAD. The key is supplied by the unlocked VaultSession. */
internal object VaultAtRestCipher {
    fun encrypt(id: String, plain: ByteArray, keyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(keyBytes.size == 32) { "Vault key must be 256-bit" }
        val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.iv to cipher.doFinal(plain)
    }

    fun decrypt(id: String, iv: ByteArray, encrypted: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == 32) { "Vault key must be 256-bit" }
        val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }
}

data class VaultRecord(
    val id: String,
    val title: String,
    val category: String,
    val username: String,
    val password: String,
    val website: String,
    val notes: String,
    val updatedAt: Long
)

class VaultRepository(context: Context, private val session: VaultSession) {
    private val appContext = context.applicationContext
    private val database = VaultDatabase.get(appContext)
    private val dao = database.records()
    private val passkeyDao = database.passkeys()
    private val deviceIdentity = DeviceIdentityStore(appContext)

    suspend fun getAll(): List<VaultRecord> = withContext(Dispatchers.IO) {
        dao.getAll().map { entity -> decode(entity) }
    }

    suspend fun save(
        id: String? = null,
        title: String,
        category: String,
        username: String,
        password: String,
        website: String,
        notes: String
    ) = withContext(Dispatchers.IO) {
        val recordId = id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("title", title)
            put("category", category)
            put("username", username)
            put("password", password)
            put("website", website)
            put("notes", notes)
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            val key = session.copyKey()
            try {
                val (iv, ciphertext) = VaultAtRestCipher.encrypt(recordId, json, key)
                val old = dao.getById(recordId)
                val revision = deviceIdentity.nextRevision()
                val record = VaultRecordEntity(recordId, iv, ciphertext, old?.createdAt ?: now, now)
                val change = SyncChangeEntity(
                    changeId = UUID.randomUUID().toString(),
                    recordId = recordId,
                    operation = "UPSERT",
                    deviceId = deviceIdentity.deviceId(),
                    revision = revision,
                    changedAt = now
                )
                dao.insertWithJournal(record, change)
            } finally {
                key.fill(0)
            }
        } finally {
            json.fill(0)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (dao.getById(id) != null) {
            val now = System.currentTimeMillis()
            val change = SyncChangeEntity(
                changeId = UUID.randomUUID().toString(),
                recordId = id,
                operation = "DELETE",
                deviceId = deviceIdentity.deviceId(),
                revision = deviceIdentity.nextRevision(),
                changedAt = now
            )
            dao.deleteWithJournal(id, change)
        }
    }

    /**
     * Performs an authenticated read-through of every encrypted Vault and Passkey payload.
     * This is intended for post-restore verification while the Vault is unlocked. It never
     * returns plaintext or secret material; only aggregate counts and failure counts escape.
     */
    suspend fun auditCryptographicIntegrity(): VaultCryptographicIntegrityReport = withContext(Dispatchers.IO) {
        val key = session.copyKey()
        try {
            VaultCryptographicIntegrityAuditor.audit(
                dao.getAll(), passkeyDao.getAll(), passkeyDao.allSecureMetadata(),
                passkeyDao.allPortableMetadata(), key
            )
        } finally {
            key.fill(0)
        }
    }

    /** Non-secret journal metadata retained for diagnostics. */
    suspend fun getLocalSyncJournal(limit: Int = 1000): List<SyncChangeEntity> =
        withContext(Dispatchers.IO) { dao.getSyncJournal(limit.coerceIn(1, 5000)) }

    private fun decode(entity: VaultRecordEntity): VaultRecord {
        val key = session.copyKey()
        val bytes = try {
            VaultAtRestCipher.decrypt(entity.id, entity.iv, entity.ciphertext, key)
        } finally {
            key.fill(0)
        }
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            VaultRecord(
                id = entity.id,
                title = json.optString("title"),
                category = json.optString("category"),
                username = json.optString("username"),
                password = json.optString("password"),
                website = json.optString("website"),
                notes = json.optString("notes"),
                updatedAt = entity.updatedAt
            )
        } finally {
            bytes.fill(0)
        }
    }
}
