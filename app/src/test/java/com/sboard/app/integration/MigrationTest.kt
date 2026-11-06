package com.sboard.app.integration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.sboard.app.core.crypto.AesGcm
import com.sboard.app.core.crypto.Argon2Kdf
import com.sboard.app.core.crypto.KdfParams
import com.sboard.app.core.crypto.randomBytes
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultDatabase
import com.sboard.app.data.VaultMigrations
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.VaultKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades a database created by sBoard schema v8 (with a legacy record) to v9 and migrates the record. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class MigrationTest {
    private lateinit var context: Context
    private val password = "legacy master password"

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); simulateNewDevice(context) }
    @After fun tearDown() = simulateNewDevice(context)

    private fun createV8Database(vaultKey: ByteArray) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(VaultDatabase.FILE_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS vault_records (id TEXT NOT NULL PRIMARY KEY, iv BLOB NOT NULL, ciphertext BLOB NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    listOf(VaultMigrations.MIGRATION_1_2, VaultMigrations.MIGRATION_2_3, VaultMigrations.MIGRATION_3_4, VaultMigrations.MIGRATION_4_5,
                        VaultMigrations.MIGRATION_5_6, VaultMigrations.MIGRATION_6_7, VaultMigrations.MIGRATION_7_8).forEach { it.migrate(db) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        val salt = randomBytes(16)
        val derived = Argon2Kdf.derive(password.toByteArray(), salt, KdfParams.DEFAULT)
        val wrapped = AesGcm.encrypt(derived, vaultKey, "sBoard:VaultKey:v1".toByteArray())
        db.execSQL("INSERT INTO vault_config(id, salt, iv, wrappedVaultKey, memoryKiB, iterations, parallelism) VALUES('master', ?, ?, ?, ?, ?, ?)",
            arrayOf(salt, wrapped.iv, wrapped.ciphertext, 65536, 3, 1))
        val json = """{"title":"Old mail","category":"Пароль","username":"me@old.example","password":"legacy-pw","website":"old.example","notes":""}"""
        val record = AesGcm.encrypt(vaultKey, json.toByteArray(), "rec-1".toByteArray())
        db.execSQL("INSERT INTO vault_records(id, iv, ciphertext, createdAt, updatedAt) VALUES('rec-1', ?, ?, 10, 20)", arrayOf(record.iv, record.ciphertext))
        db.execSQL("INSERT INTO sync_change_journal(changeId, recordId, operation, deviceId, revision, changedAt) VALUES('c', 'rec-1', 'UPSERT', 'd', 1, 1)")
        helper.close()
    }

    @Test fun v8DatabaseUpgradesAndLegacyRecordIsReencrypted() = runBlocking {
        createV8Database(randomBytes(32))
        val keys = VaultKeyManager(context)
        // Room runs MIGRATION_8_9 and validates the v9 schema on first access.
        assertEquals(true, keys.hasVault())
        keys.unlockWithPassword(password.toCharArray())
        val items = VaultRepository.get(context).snapshot()
        assertEquals(1, items.size)
        assertEquals(ItemType.LOGIN, items.single().type)
        assertEquals("legacy-pw", items.single().payload.login!!.password)
        val db = VaultDatabase.get(context).openHelper.readableDatabase
        val legacyLeft = db.query("SELECT name FROM sqlite_master WHERE name IN ('vault_records','sync_change_journal')").use { it.count }
        assertEquals(0, legacyLeft)
        VaultSessionProvider.session.lock()
        assertFalse(VaultSessionProvider.session.isUnlocked)
    }
}
