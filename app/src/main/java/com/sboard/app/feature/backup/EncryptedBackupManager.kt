package com.sboard.app.feature.backup

import com.sboard.app.tr

import android.content.Context
import com.sboard.app.SBoardDatabaseBackupObserver
import com.sboard.app.feature.vault.VaultDatabase
import com.sboard.app.feature.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated, chunked database backup.
 * The recovery code is used only to derive a backup key; it is never persisted.
 * Each chunk has an independent GCM tag and authenticated position metadata.
 */
class EncryptedBackupManager(private val context: Context) {
    suspend fun createBackup(recoveryKey: String, destination: File) = withContext(Dispatchers.IO) {
        BackupRestorePreflightPolicy.requireRecoveryKey(recoveryKey)
        val database = VaultDatabase.get(context.applicationContext)
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst()) { tr("Не удалось проверить состояние SQLite WAL") }
            val busy = cursor.getInt(0)
            val logFrames = cursor.getInt(1)
            val checkpointedFrames = cursor.getInt(2)
            check(busy == 0 && (logFrames < 0 || logFrames == checkpointedFrames)) {
                tr("Не удалось полностью подготовить базу к резервному копированию")
            }
        }

        val source = context.getDatabasePath(DATABASE_NAME)
        check(source.isFile && source.length() > 0L) { tr("Файл хранилища не найден") }
        check(source.length() <= MAX_BACKUP_BYTES) { tr("Размер хранилища превышает лимит резервной копии") }
        destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val temp = File(destination.parentFile ?: context.cacheDir, destination.name + ".tmp")
        try {
            encryptFile(source, temp, recoveryKey)
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(
                temp.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * Restores only after every chunk authenticates and the SQLite header is valid.
     * Caller must ensure no other component is using the Vault session/database.
     */
    suspend fun restoreBackup(
        recoveryKey: String,
        backup: File,
        session: VaultSession
    ) = withContext(Dispatchers.IO) {
        BackupRestorePreflightPolicy.requireRecoveryKey(recoveryKey)
        require(backup.isFile && BackupRestorePreflightPolicy.isValidEncryptedBackupSize(backup.length())) {
            tr("Файл резервной копии отсутствует или имеет недопустимый размер")
        }
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val parent = databaseFile.parentFile ?: throw IOException(tr("Не удалось определить каталог базы данных"))
        check(parent.exists() || parent.mkdirs())
        // Resolve a transaction left by a previous process before starting another restore.
        RestoreCrashRecovery.recover(databaseFile)
        // Staging, journal and rollback files stay beside the database (same filesystem).
        val temp = File(parent, ".sboard_restore_${System.nanoTime()}.db.tmp")
        val rollback = File(parent, ".sboard_restore_${System.nanoTime()}.db.rollback")
        var markerWritten = false
        try {
            decryptFile(backup, temp, recoveryKey)
            FileOutputStream(temp, true).use { it.fd.sync() }
            validateSqliteFile(temp)
            RestoreDatabaseValidator.validate(temp)

            // Preserve the current committed state before closing Room. No live Vault files are
            // touched until authentication, SQLite integrity and schema checks have succeeded.
            checkpointExistingDatabase(databaseFile)
            session.lock()
            VaultDatabase.closeForRestore()
            val wal = File(databaseFile.path + "-wal")
            val shm = File(databaseFile.path + "-shm")
            if (wal.exists() && !wal.delete()) throw IOException(tr("Не удалось закрыть WAL перед восстановлением"))
            if (shm.exists() && !shm.delete()) throw IOException(tr("Не удалось закрыть SHM перед восстановлением"))

            val hadOldDatabase = databaseFile.isFile
            if (hadOldDatabase) {
                Files.copy(databaseFile.toPath(), rollback.toPath(), StandardCopyOption.REPLACE_EXISTING)
                FileOutputStream(rollback, true).use { it.fd.sync() }
                RestoreDatabaseValidator.validate(rollback)
            }
            RestoreCrashRecovery.writeMarker(databaseFile, if (hadOldDatabase) rollback else null, hadOldDatabase)
            markerWritten = true

            Files.move(temp.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // Validate SQLite and then open it through Room while the rollback marker still
            // exists. Room performs its own schema/hash validation; the master-key envelope
            // must also be present so the restored Vault can actually be unlocked.
            RestoreDatabaseValidator.validate(databaseFile)
            val restoredDatabase = VaultDatabase.get(context.applicationContext)
            if (restoredDatabase.config().get() == null) {
                throw IOException(tr("В резервной копии отсутствует конфигурация мастер-пароля"))
            }

            // Only a fully opened Room database with a master-password envelope is committed.
            // The process-start recovery marker remains until all validation above succeeds.
            // Attach observers while rollback is still possible. If this step fails, the catch
            // path can still restore the previous file and rebind observers to that database.
            SBoardDatabaseBackupObserver.onDatabaseReplaced(context.applicationContext)
            val marker = File(parent, ".sboard_restore.transaction")
            if (!marker.delete()) throw IOException(tr("Не удалось завершить журнал восстановления"))
            markerWritten = false
            if (rollback.exists() && !rollback.delete()) throw IOException(tr("Не удалось удалить временную копию восстановления"))
        } catch (failure: Exception) {
            if (markerWritten) {
                try {
                    // Room may already have opened the candidate database. Close its singleton
                    // before replacing the file with the rollback copy.
                    VaultDatabase.closeForRestore()
                    RestoreCrashRecovery.recover(databaseFile)
                    markerWritten = false
                    SBoardDatabaseBackupObserver.onDatabaseReplaced(context.applicationContext)
                } catch (recoveryFailure: Exception) {
                    failure.addSuppressed(recoveryFailure)
                }
            }
            throw failure
        } finally {
            temp.delete()
            // Never delete rollback material while a durable transaction marker still exists.
            if (!markerWritten && rollback.exists()) rollback.delete()
        }
    }

    /** Flushes committed WAL pages before the current database is copied for rollback. */
    private fun checkpointExistingDatabase(databaseFile: File) {
        if (!databaseFile.isFile) return
        val database = VaultDatabase.get(context.applicationContext)
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException(tr("Не удалось проверить состояние SQLite WAL перед восстановлением"))
            }
            RestoreCheckpointPolicy.requireComplete(
                busy = cursor.getInt(0),
                logFrames = cursor.getInt(1),
                checkpointedFrames = cursor.getInt(2)
            )
        }
    }

    private fun encryptFile(source: File, destination: File, recoveryKey: String) {
        val length = source.length()
        val countLong = (length + CHUNK_SIZE - 1L) / CHUNK_SIZE
        require(countLong in 1..MAX_CHUNKS.toLong()) { tr("Недопустимое количество блоков") }
        val count = countLong.toInt()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        val key = deriveKey(recoveryKey, salt)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { out ->
                out.write(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(CHUNK_SIZE)
                out.writeLong(length)
                out.writeInt(count)
                out.write(salt)
                out.write(noncePrefix)

                BufferedInputStream(FileInputStream(source)).use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    try {
                        for (index in 0 until count) {
                            val expected = minOf(CHUNK_SIZE.toLong(), length - index.toLong() * CHUNK_SIZE).toInt()
                            readFully(input, buffer, expected)
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(noncePrefix, index)))
                            cipher.updateAAD(aad(length, count, index, expected, salt, noncePrefix))
                            val encrypted = cipher.doFinal(buffer, 0, expected)
                            out.writeInt(expected)
                            out.write(encrypted)
                            encrypted.fill(0)
                            buffer.fill(0, 0, expected)
                        }
                        check(input.read() == -1) { tr("Исходный файл изменился во время резервного копирования") }
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
        } finally {
            key.fill(0)
            salt.fill(0)
            noncePrefix.fill(0)
        }
    }

    private fun decryptFile(backup: File, destination: File, recoveryKey: String) {
        DataInputStream(BufferedInputStream(FileInputStream(backup))).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(MessageDigest.isEqual(magic, MAGIC)) { tr("Это не резервная копия sBoard") }
            val version = input.readInt()
            require(version == FORMAT_VERSION) { tr("Версия резервной копии не поддерживается") }
            val chunkSize = input.readInt()
            require(chunkSize == CHUNK_SIZE) { tr("Неподдерживаемый размер блока") }
            val length = input.readLong()
            val count = input.readInt()
            require(length in 1..MAX_BACKUP_BYTES && count in 1..MAX_CHUNKS) { tr("Повреждённый заголовок резервной копии") }
            require(count.toLong() == (length + CHUNK_SIZE - 1L) / CHUNK_SIZE) { tr("Некорректная структура блоков") }
            val salt = ByteArray(SALT_SIZE).also(input::readFully)
            val noncePrefix = ByteArray(NONCE_PREFIX_SIZE).also(input::readFully)
            val key = deriveKey(recoveryKey, salt)
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { out ->
                    var written = 0L
                    for (index in 0 until count) {
                        val expected = minOf(CHUNK_SIZE.toLong(), length - written).toInt()
                        val declared = input.readInt()
                        require(declared == expected) { tr("Повреждённый блок резервной копии") }
                        val encrypted = ByteArray(declared + GCM_TAG_SIZE)
                        input.readFully(encrypted)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(noncePrefix, index)))
                        cipher.updateAAD(aad(length, count, index, declared, salt, noncePrefix))
                        val plain = try {
                            cipher.doFinal(encrypted)
                        } catch (_: Exception) {
                            throw IOException(tr("Неверный Recovery Key или повреждённая резервная копия"))
                        }
                        try {
                            require(plain.size == declared) { tr("Неверная длина блока") }
                            out.write(plain)
                            written += plain.size
                        } finally {
                            encrypted.fill(0)
                            plain.fill(0)
                        }
                    }
                    require(written == length && input.read() == -1) { tr("Резервная копия обрезана или содержит лишние данные") }
                }
            } finally {
                key.fill(0)
                salt.fill(0)
                noncePrefix.fill(0)
            }
        }
    }

    private fun deriveKey(recoveryKey: String, salt: ByteArray): ByteArray {
        val password = recoveryKey.toByteArray(Charsets.US_ASCII)
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(KDF_MEMORY_KIB)
                .withIterations(KDF_ITERATIONS)
                .withParallelism(KDF_PARALLELISM)
                .build()
            ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password, it) }
        } finally {
            password.fill(0)
        }
    }

    private fun validateSqliteFile(file: File) {
        require(file.length() >= SQLITE_HEADER.size) { tr("Файл базы данных слишком мал") }
        val header = ByteArray(SQLITE_HEADER.size)
        DataInputStream(FileInputStream(file)).use { it.readFully(header) }
        require(MessageDigest.isEqual(header, SQLITE_HEADER)) { tr("Расшифрованный файл не является базой SQLite") }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw IOException(tr("Исходный файл неожиданно закончился"))
            offset += read
        }
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(prefix).putInt(index).array()

    private fun aad(length: Long, count: Int, index: Int, chunkLength: Int, salt: ByteArray, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 4 + 8 + 4 + 4 + 4 + SALT_SIZE + NONCE_PREFIX_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).putInt(FORMAT_VERSION).putLong(length).putInt(count)
            .putInt(index).putInt(chunkLength).put(salt).put(prefix).array()

    private companion object {
        const val DATABASE_NAME = "sboard_vault.db"
        const val FORMAT_VERSION = 1
        const val CHUNK_SIZE = 1024 * 1024
        const val MAX_CHUNKS = 256
        const val MAX_BACKUP_BYTES = BackupRestorePreflightPolicy.MAX_BACKUP_BYTES
        const val MAX_BACKUP_OVERHEAD = BackupRestorePreflightPolicy.MAX_BACKUP_OVERHEAD
        const val SALT_SIZE = 16
        const val NONCE_PREFIX_SIZE = 8
        const val GCM_TAG_SIZE = 16
        const val KDF_MEMORY_KIB = 64 * 1024
        const val KDF_ITERATIONS = 3
        const val KDF_PARALLELISM = 1
        val MAGIC = "SBOARDENC".toByteArray(Charsets.US_ASCII)
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val random = SecureRandom()
    }
}
