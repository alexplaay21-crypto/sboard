package com.sboard.app.feature.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import com.sboard.app.feature.vault.VAULT_DATABASE_SCHEMA_VERSION

/** Performs a read-only SQLite integrity and schema compatibility check before restore. */
internal object RestoreCompatibilityPolicy {
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_SUPPORTED_SCHEMA_VERSION = VAULT_DATABASE_SCHEMA_VERSION
    fun isSupportedSchema(version: Int): Boolean = version in MIN_SUPPORTED_SCHEMA_VERSION..MAX_SUPPORTED_SCHEMA_VERSION
}

internal object RestoreDatabaseValidator {

    data class Result(
        val schemaVersion: Int,
        val integrityOk: Boolean,
        val vaultRecordCount: Int,
        val passkeyCount: Int,
        val syncJournalCount: Int,
        val syncInboxCount: Int
    )

    fun validate(file: File): Result {
        if (!file.isFile || file.length() < 100L) throw IOException("Восстанавливаемая база SQLite слишком мала")
        val database = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (error: Exception) {
            throw IOException("Не удалось открыть восстановленную базу SQLite", error)
        }
        database.use { db ->
            val integrity = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true) && !cursor.moveToNext()
            }
            if (!integrity) throw IOException("Проверка SQLite quick_check обнаружила повреждение")

            val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Не удалось прочитать версию схемы SQLite")
                cursor.getInt(0)
            }
            if (!RestoreCompatibilityPolicy.isSupportedSchema(version)) {
                throw IOException("Версия базы данных $version не поддерживается этой версией sBoard")
            }

            val tables = tableNames(db)
            requireTable(tables, "vault_records")
            requireColumns(db, "vault_records", setOf("id", "iv", "ciphertext", "createdAt", "updatedAt"))
            val vaultRecordCount = countRows(db, "vault_records")
            requireNoRows(db, """
                SELECT COUNT(*) FROM vault_records
                WHERE id IS NULL OR length(trim(id)) = 0 OR iv IS NULL OR length(iv) < 12
                   OR ciphertext IS NULL OR length(ciphertext) < 16 OR createdAt < 0 OR updatedAt < 0
            """.trimIndent(), "Обнаружена повреждённая запись Vault")

            if (version >= 2) {
                requireTable(tables, "vault_config")
                requireColumns(db, "vault_config", setOf("id", "salt", "iv", "wrappedVaultKey", "memoryKiB", "iterations", "parallelism"))
                requireNoRows(db, """
                    SELECT COUNT(*) FROM vault_config
                    WHERE id IS NULL OR length(trim(id)) = 0 OR salt IS NULL OR length(salt) < 16
                       OR length(salt) != 16 OR iv IS NULL OR length(iv) != 12
                       OR wrappedVaultKey IS NULL OR length(wrappedVaultKey) != 48
                       OR memoryKiB < 32768 OR memoryKiB > 262144 OR iterations < 2 OR iterations > 10
                       OR parallelism < 1 OR parallelism > 4
                """.trimIndent(), "Обнаружена повреждённая конфигурация Vault")
                requireNoRows(db, "SELECT CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END FROM vault_config WHERE id = 'master'", "В резервной копии отсутствует конфигурация мастер-пароля")
            }

            if (version >= 3) {
                requireTable(tables, "recovery_envelope")
                requireColumns(db, "recovery_envelope", setOf("id", "salt", "iv", "ciphertext", "memoryKiB", "iterations", "parallelism"))
                requireNoRows(db, """
                    SELECT COUNT(*) FROM recovery_envelope
                    WHERE id IS NULL OR length(trim(id)) = 0 OR salt IS NULL OR length(salt) < 16
                       OR length(salt) != 16 OR iv IS NULL OR length(iv) != 12 OR ciphertext IS NULL OR length(ciphertext) < 16
                       OR memoryKiB < 32768 OR memoryKiB > 262144 OR iterations < 2 OR iterations > 10
                       OR parallelism < 1 OR parallelism > 4
                """.trimIndent(), "Обнаружен повреждённый Recovery Key envelope")
                requireTable(tables, "biometric_envelope")
                requireColumns(db, "biometric_envelope", setOf("id", "iv", "ciphertext"))
                requireNoRows(db, "SELECT COUNT(*) FROM biometric_envelope WHERE iv IS NULL OR length(iv) < 12 OR ciphertext IS NULL OR length(ciphertext) < 16", "Обнаружена повреждённая биометрическая конфигурация")
            }

            val passkeyCount = if (version >= 4) {
                requireTable(tables, "passkey_records")
                requireColumns(db, "passkey_records", setOf("id", "rpId", "credentialId", "encryptedPrivateKey", "iv", "signCount", "createdAt", "lastUsedAt"))
                requireNoRows(db, """
                    SELECT COUNT(*) FROM passkey_records
                    WHERE id IS NULL OR length(trim(id)) = 0 OR rpId IS NULL OR length(trim(rpId)) = 0
                       OR credentialId IS NULL OR length(credentialId) = 0
                       OR encryptedPrivateKey IS NULL OR length(encryptedPrivateKey) < 16
                       OR iv IS NULL OR length(iv) < 12 OR signCount < 0 OR createdAt < 0 OR lastUsedAt < 0
                """.trimIndent(), "Обнаружена повреждённая Passkey-запись")
                countRows(db, "passkey_records")
            } else 0

            if (version >= 5) {
                requireTable(tables, "passkey_secure_metadata")
                requireColumns(db, "passkey_secure_metadata", setOf("recordId", "rpIdIndex", "iv", "ciphertext"))
                requireNoRows(db, "SELECT COUNT(*) FROM passkey_secure_metadata WHERE recordId IS NULL OR length(trim(recordId)) = 0 OR rpIdIndex IS NULL OR length(trim(rpIdIndex)) = 0 OR iv IS NULL OR length(iv) < 12 OR ciphertext IS NULL OR length(ciphertext) < 16", "Обнаружены повреждённые защищённые метаданные Passkey")
                requireNoRows(db, "SELECT COUNT(*) FROM passkey_secure_metadata m LEFT JOIN passkey_records p ON p.id = m.recordId WHERE p.id IS NULL", "Обнаружены метаданные Passkey без соответствующей записи")
            }

            if (version >= 8) {
                requireTable(tables, "passkey_portable_metadata")
                requireColumns(db, "passkey_portable_metadata", setOf("recordId", "iv", "ciphertext"))
                requireNoRows(db, "SELECT COUNT(*) FROM passkey_portable_metadata WHERE recordId IS NULL OR length(trim(recordId)) = 0 OR iv IS NULL OR length(iv) != 12 OR ciphertext IS NULL OR length(ciphertext) < 16", "Обнаружены повреждённые переносимые метаданные Passkey")
                requireNoRows(db, "SELECT COUNT(*) FROM passkey_portable_metadata m LEFT JOIN passkey_records p ON p.id = m.recordId WHERE p.id IS NULL", "Обнаружены переносимые метаданные Passkey без соответствующей записи")
            }

            val syncJournalCount = if (version >= 6) {
                requireTable(tables, "sync_change_journal")
                val journalColumns = if (version >= 7) {
                    setOf("changeId", "recordId", "operation", "deviceId", "revision", "changedAt", "deliveryState", "encryptedEnvelope")
                } else {
                    setOf("changeId", "recordId", "operation", "deviceId", "revision", "changedAt")
                }
                requireColumns(db, "sync_change_journal", journalColumns)
                val journalInvalidRows = if (version >= 7) """
                    SELECT COUNT(*) FROM sync_change_journal
                    WHERE changeId IS NULL OR length(trim(changeId)) = 0 OR recordId IS NULL OR length(trim(recordId)) = 0
                       OR operation NOT IN ('UPSERT', 'DELETE') OR deviceId IS NULL OR length(trim(deviceId)) = 0
                       OR revision < 0 OR changedAt < 0 OR deliveryState NOT IN ('PENDING', 'SENT', 'SUPERSEDED')
                       OR (encryptedEnvelope IS NOT NULL AND length(encryptedEnvelope) < 16)
                """.trimIndent() else """
                    SELECT COUNT(*) FROM sync_change_journal
                    WHERE changeId IS NULL OR length(trim(changeId)) = 0 OR recordId IS NULL OR length(trim(recordId)) = 0
                       OR operation NOT IN ('UPSERT', 'DELETE') OR deviceId IS NULL OR length(trim(deviceId)) = 0
                       OR revision < 0 OR changedAt < 0
                """.trimIndent()
                requireNoRows(db, journalInvalidRows, "Обнаружено повреждение журнала синхронизации")
                countRows(db, "sync_change_journal")
            } else 0

            val syncInboxCount = if (version >= 7) {
                requireTable(tables, "sync_inbox")
                requireColumns(db, "sync_inbox", setOf("changeId", "recordId", "operation", "deviceId", "revision", "changedAt", "encryptedEnvelope", "receivedAt", "state"))
                requireNoRows(db, """
                    SELECT COUNT(*) FROM sync_inbox
                    WHERE changeId IS NULL OR length(trim(changeId)) = 0 OR recordId IS NULL OR length(trim(recordId)) = 0
                       OR operation NOT IN ('UPSERT', 'DELETE') OR deviceId IS NULL OR length(trim(deviceId)) = 0
                       OR revision < 0 OR changedAt < 0 OR encryptedEnvelope IS NULL OR length(encryptedEnvelope) = 0
                       OR receivedAt < 0 OR state NOT IN ('PENDING_REVIEW', 'RESOLVED_REMOTE', 'RESOLVED_LOCAL')
                """.trimIndent(), "Обнаружено повреждение входящих изменений синхронизации")
                countRows(db, "sync_inbox")
            } else 0

            return Result(
                schemaVersion = version,
                integrityOk = true,
                vaultRecordCount = vaultRecordCount,
                passkeyCount = passkeyCount,
                syncJournalCount = syncJournalCount,
                syncInboxCount = syncInboxCount
            )
        }
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table'", null
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun requireTable(tables: Set<String>, name: String) {
        if (name !in tables) throw IOException("В базе отсутствует обязательная таблица $name")
    }

    private fun requireColumns(db: SQLiteDatabase, table: String, required: Set<String>) {
        val actual = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }
        val missing = required - actual
        if (missing.isNotEmpty()) throw IOException("В таблице $table отсутствуют поля: ${missing.sorted().joinToString()}")
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM `$table`", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun requireNoRows(db: SQLiteDatabase, sql: String, message: String) {
        val invalidCount = db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (invalidCount != 0L) throw IOException(message)
    }
}

/** Pure validation for SQLite's PRAGMA wal_checkpoint(TRUNCATE) result. */
internal object RestoreCheckpointPolicy {
    fun isComplete(busy: Int, logFrames: Int, checkpointedFrames: Int): Boolean =
        busy == 0 && (
            (logFrames == -1 && checkpointedFrames == -1) ||
                (logFrames >= 0 && logFrames == checkpointedFrames)
            )

    fun requireComplete(busy: Int, logFrames: Int, checkpointedFrames: Int) {
        if (!isComplete(busy, logFrames, checkpointedFrames)) {
            throw IOException("Не удалось полностью сохранить последние изменения SQLite WAL")
        }
    }
}
