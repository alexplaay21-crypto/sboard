package com.sboard.app.feature.backup

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Recovers an interrupted database replacement before Room is allowed to open the Vault.
 * The journal contains only file names and a SHA-256 digest, never secrets or keys.
 */
internal object RestoreCrashRecovery {
    private const val MARKER_NAME = ".sboard_restore.transaction"
    private const val ROLLBACK_PREFIX = ".sboard_restore_"
    private const val ROLLBACK_SUFFIX = ".db.rollback"

    fun recover(context: Context) = recover(context.getDatabasePath("sboard_vault.db"))

    fun recover(databaseFile: File) {
        val parent = databaseFile.parentFile ?: throw IOException("Не удалось определить каталог базы данных")
        val marker = File(parent, MARKER_NAME)
        if (!marker.exists()) return

        val lines = try {
            marker.readLines(StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("Не удалось прочитать журнал восстановления sBoard", error)
        }
        if (lines.size != 4 || lines[0] != "1" || lines[1] !in setOf("true", "false")) {
            throw IOException("Журнал восстановления sBoard повреждён; база не будет открыта")
        }

        val hadOldDatabase = lines[1] == "true"
        val rollbackName = lines[2]
        val expectedDigest = lines[3]
        val rollback = if (rollbackName.isNotEmpty()) File(parent, rollbackName) else null
        if (rollback != null && (rollback.parentFile?.canonicalFile != parent.canonicalFile ||
                !rollback.name.startsWith(ROLLBACK_PREFIX) || !rollback.name.endsWith(ROLLBACK_SUFFIX))) {
            throw IOException("Недопустимый путь файла отката sBoard")
        }

        if (hadOldDatabase) {
            val backup = rollback ?: throw IOException("В журнале восстановления отсутствует путь отката")
            if (backup.isFile) {
                if (expectedDigest.isBlank() || sha256(backup) != expectedDigest) {
                    throw IOException("Контрольная сумма резервной базы не совпадает; автоматическое восстановление остановлено")
                }
                RestoreDatabaseValidator.validate(backup)
                Files.move(backup.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                // A previous recovery attempt may already have atomically put the old DB back.
                if (!databaseFile.isFile || expectedDigest.isBlank() || sha256(databaseFile) != expectedDigest) {
                    throw IOException("Не найдена безопасная копия исходной базы; автоматическое восстановление остановлено")
                }
                RestoreDatabaseValidator.validate(databaseFile)
            }
        } else {
            // There was no prior Vault (fresh install): undo a partially completed first restore.
            if (databaseFile.exists() && !databaseFile.delete()) {
                throw IOException("Не удалось отменить незавершённое первое восстановление sBoard")
            }
        }

        deleteSidecar(File(databaseFile.path + "-wal"))
        deleteSidecar(File(databaseFile.path + "-shm"))
        if (!marker.delete()) throw IOException("Не удалось завершить журнал восстановления sBoard")
        rollback?.let { if (it.exists() && !it.delete()) throw IOException("Не удалось удалить временную копию восстановления") }
    }

    fun writeMarker(databaseFile: File, rollback: File?, hadOldDatabase: Boolean) {
        val parent = databaseFile.parentFile ?: throw IOException("Не удалось определить каталог базы данных")
        val marker = File(parent, MARKER_NAME)
        val markerTemp = File(parent, "$MARKER_NAME.tmp")
        val rollbackName = rollback?.name.orEmpty()
        val digest = if (hadOldDatabase && rollback != null) sha256(rollback) else ""
        val contents = listOf("1", hadOldDatabase.toString(), rollbackName, digest).joinToString("\n")
        try {
            FileOutputStream(markerTemp).use { stream ->
                stream.write(contents.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(markerTemp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            markerTemp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteSidecar(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Не удалось удалить временный файл SQLite")
    }
}
