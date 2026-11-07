package com.sboard.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val VAULT_DATABASE_VERSION = 9

/** Password-wrapped Vault key (Argon2id → AES-256-GCM). Table layout unchanged since schema v2. */
@Entity(tableName = "vault_config")
data class VaultConfigEntity(
    @PrimaryKey val id: String = "master",
    val salt: ByteArray,
    val iv: ByteArray,
    val wrappedVaultKey: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
)

/** Recovery-Key-wrapped Vault key. */
@Entity(tableName = "recovery_envelope")
data class RecoveryEnvelopeEntity(
    @PrimaryKey val id: String = "recovery",
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
)

/** Vault key wrapped by a biometric-bound Android Keystore key (device-local, never synced). */
@Entity(tableName = "biometric_envelope")
data class BiometricEnvelopeEntity(
    @PrimaryKey val id: String = "biometric",
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * One encrypted Vault item. Only the id and sync timestamps are stored in clear; title, type
 * and every field are inside [ciphertext]. [contentHash] identifies the encrypted version and
 * [baseHash] is the version last confirmed in Google Drive (null = never synced).
 */
@Entity(tableName = "vault_items", indices = [Index("updatedAt")])
data class VaultItemEntity(
    @PrimaryKey val id: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val modifiedBy: String,
    val contentHash: String,
    val baseHash: String?,
)

/** Previous encrypted versions of an item (edits, deletions and sync conflicts). Device-local. */
@Entity(tableName = "item_history", indices = [Index("itemId")])
data class ItemHistoryEntity(
    @PrimaryKey(autoGenerate = true) val historyId: Long = 0,
    val itemId: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val itemUpdatedAt: Long,
    val savedAt: Long,
    val reason: String,
)

@Entity(tableName = "vault_meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String,
)

@Dao
interface VaultConfigDao {
    @Query("SELECT * FROM vault_config WHERE id = 'master' LIMIT 1")
    suspend fun get(): VaultConfigEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(config: VaultConfigEntity)
    @Query("DELETE FROM vault_config")
    suspend fun clear()
}

@Dao
interface RecoveryEnvelopeDao {
    @Query("SELECT * FROM recovery_envelope WHERE id = 'recovery' LIMIT 1")
    suspend fun get(): RecoveryEnvelopeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: RecoveryEnvelopeEntity)
    @Query("DELETE FROM recovery_envelope")
    suspend fun delete()
}

@Dao
interface BiometricEnvelopeDao {
    @Query("SELECT * FROM biometric_envelope WHERE id = 'biometric' LIMIT 1")
    suspend fun get(): BiometricEnvelopeEntity?
    @Query("SELECT COUNT(*) FROM biometric_envelope")
    fun observeCount(): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BiometricEnvelopeEntity)
    @Query("DELETE FROM biometric_envelope")
    suspend fun delete()
}

@Dao
interface VaultItemDao {
    @Query("SELECT * FROM vault_items")
    fun observeAll(): Flow<List<VaultItemEntity>>
    @Query("SELECT * FROM vault_items")
    suspend fun getAll(): List<VaultItemEntity>
    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun get(id: String): VaultItemEntity?
    @Upsert
    suspend fun upsert(entity: VaultItemEntity)
    @Upsert
    suspend fun upsertAll(entities: List<VaultItemEntity>)
    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun hardDelete(id: String)
    @Query("DELETE FROM vault_items")
    suspend fun clear()
    @Query("UPDATE vault_items SET baseHash = :hash WHERE id = :id AND contentHash = :hash")
    suspend fun markSynced(id: String, hash: String)
    @Query("UPDATE vault_items SET baseHash = NULL")
    suspend fun markAllUnsynced()
    @Query("SELECT COUNT(*) FROM vault_items WHERE baseHash IS NULL OR baseHash != contentHash")
    fun observePendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM vault_items WHERE baseHash IS NULL OR baseHash != contentHash")
    suspend fun pendingCount(): Int
    @Query("SELECT COUNT(*) FROM vault_items WHERE deleted = 0")
    suspend fun liveCount(): Int
    @Query("DELETE FROM vault_items WHERE deleted = 1 AND updatedAt < :cutoff AND baseHash = contentHash")
    suspend fun purgeSyncedTombstones(cutoff: Long)
}

@Dao
interface ItemHistoryDao {
    @Insert
    suspend fun insert(entity: ItemHistoryEntity)
    @Query("SELECT * FROM item_history WHERE itemId = :itemId ORDER BY savedAt DESC")
    suspend fun forItem(itemId: String): List<ItemHistoryEntity>
    @Query("SELECT * FROM item_history WHERE historyId = :historyId LIMIT 1")
    suspend fun get(historyId: Long): ItemHistoryEntity?
    @Query("""DELETE FROM item_history WHERE itemId = :itemId AND historyId NOT IN
        (SELECT historyId FROM item_history WHERE itemId = :itemId ORDER BY savedAt DESC LIMIT :keep)""")
    suspend fun trim(itemId: String, keep: Int)
    @Query("DELETE FROM item_history WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: String)
    @Query("DELETE FROM item_history")
    suspend fun clear()
    @Query("SELECT COUNT(*) FROM item_history WHERE reason = 'conflict' AND savedAt > :since")
    fun observeConflictsSince(since: Long): Flow<Int>
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM vault_meta WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?
    @Query("SELECT value FROM vault_meta WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<String?>
    @Upsert
    suspend fun put(entity: MetaEntity)
    @Query("DELETE FROM vault_meta WHERE `key` = :key")
    suspend fun delete(key: String)
}

suspend fun MetaDao.put(key: String, value: String) = put(MetaEntity(key, value))

@Database(
    entities = [
        VaultConfigEntity::class, RecoveryEnvelopeEntity::class, BiometricEnvelopeEntity::class,
        VaultItemEntity::class, ItemHistoryEntity::class, MetaEntity::class,
    ],
    version = VAULT_DATABASE_VERSION,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun config(): VaultConfigDao
    abstract fun recovery(): RecoveryEnvelopeDao
    abstract fun biometric(): BiometricEnvelopeDao
    abstract fun items(): VaultItemDao
    abstract fun history(): ItemHistoryDao
    abstract fun meta(): MetaDao

    companion object {
        const val FILE_NAME = "sboard_vault.db"

        @Volatile private var instance: VaultDatabase? = null

        fun get(context: Context): VaultDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, FILE_NAME)
                .addMigrations(*VaultMigrations.ALL)
                .build()
                .also { instance = it }
        }

        /** Test hook: closes the singleton so the next [get] opens the database file again. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() = synchronized(this) { instance?.close(); instance = null }
    }
}

/**
 * Historical migrations are kept verbatim so that every released schema can reach v9.
 * v9 replaces the record/journal/backup design with encrypted, sync-aware items.
 * Legacy `vault_records` rows are kept until [LegacyVaultMigrator] re-encrypts them after unlock.
 */
object VaultMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS vault_config (id TEXT NOT NULL PRIMARY KEY, salt BLOB NOT NULL, iv BLOB NOT NULL, wrappedVaultKey BLOB NOT NULL, memoryKiB INTEGER NOT NULL, iterations INTEGER NOT NULL, parallelism INTEGER NOT NULL)")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS recovery_envelope (id TEXT NOT NULL PRIMARY KEY, salt BLOB NOT NULL, iv BLOB NOT NULL, ciphertext BLOB NOT NULL, memoryKiB INTEGER NOT NULL, iterations INTEGER NOT NULL, parallelism INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS biometric_envelope (id TEXT NOT NULL PRIMARY KEY, iv BLOB NOT NULL, ciphertext BLOB NOT NULL)")
        }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS passkey_records (id TEXT NOT NULL PRIMARY KEY, rpId TEXT NOT NULL, rpName TEXT NOT NULL, userName TEXT NOT NULL, displayName TEXT NOT NULL, userHandle BLOB NOT NULL, credentialId BLOB NOT NULL, publicKeyCose BLOB NOT NULL, encryptedPrivateKey BLOB NOT NULL, iv BLOB NOT NULL, signCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_records_rpId ON passkey_records(rpId)")
        }
    }
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS passkey_secure_metadata (recordId TEXT NOT NULL PRIMARY KEY, rpIdIndex TEXT NOT NULL, iv BLOB NOT NULL, ciphertext BLOB NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_secure_metadata_rpIdIndex ON passkey_secure_metadata(rpIdIndex)")
        }
    }
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_change_journal (changeId TEXT NOT NULL PRIMARY KEY, recordId TEXT NOT NULL, operation TEXT NOT NULL, deviceId TEXT NOT NULL, revision INTEGER NOT NULL, changedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_recordId_revision ON sync_change_journal(recordId, revision)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_deviceId_revision ON sync_change_journal(deviceId, revision)")
        }
    }
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_change_journal ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE sync_change_journal ADD COLUMN encryptedEnvelope BLOB")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_journal_deliveryState ON sync_change_journal(deliveryState)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_inbox (changeId TEXT NOT NULL PRIMARY KEY, recordId TEXT NOT NULL, operation TEXT NOT NULL, deviceId TEXT NOT NULL, revision INTEGER NOT NULL, changedAt INTEGER NOT NULL, encryptedEnvelope BLOB NOT NULL, receivedAt INTEGER NOT NULL, state TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_inbox_deviceId_revision ON sync_inbox(deviceId, revision)")
        }
    }
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS passkey_portable_metadata (recordId TEXT NOT NULL PRIMARY KEY, iv BLOB NOT NULL, ciphertext BLOB NOT NULL)")
        }
    }
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `vault_items` (`id` TEXT NOT NULL, `iv` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `modifiedBy` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `baseHash` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_updatedAt` ON `vault_items` (`updatedAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `item_history` (`historyId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` TEXT NOT NULL, `iv` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `itemUpdatedAt` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `reason` TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_history_itemId` ON `item_history` (`itemId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `vault_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            // The change journal / inbox / passkey discovery caches are superseded by sboard_backup.enc sync.
            // Passkey creation was never enabled in schema v8, so passkey tables hold no user credentials.
            db.execSQL("DROP TABLE IF EXISTS sync_change_journal")
            db.execSQL("DROP TABLE IF EXISTS sync_inbox")
            db.execSQL("DROP TABLE IF EXISTS passkey_secure_metadata")
            db.execSQL("DROP TABLE IF EXISTS passkey_portable_metadata")
            db.execSQL("DROP TABLE IF EXISTS passkey_records")
            // Legacy plaintext-free records stay in vault_records until the first unlock re-encrypts them.
            db.execSQL("CREATE TABLE IF NOT EXISTS vault_records (id TEXT NOT NULL PRIMARY KEY, iv BLOB NOT NULL, ciphertext BLOB NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("INSERT OR REPLACE INTO vault_meta(`key`, `value`) VALUES('legacy_records_pending', '1')")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
    )
}
