package com.sboard.app.feature.backup

import java.io.IOException

/** Fail-closed verification for a completed Google Drive upload. */
internal object BackupIntegrityPolicy {
    fun requireMatch(
        expectedSize: Long,
        actualSize: Long?,
        expectedMd5: String,
        actualMd5: String?
    ) {
        if (expectedSize <= 0L || actualSize == null || actualSize != expectedSize) {
            throw IOException("Размер загруженной резервной копии не совпадает с исходным файлом")
        }
        val normalizedExpected = expectedMd5.lowercase()
        val normalizedActual = actualMd5?.lowercase()
        if (!normalizedExpected.matches(Regex("[0-9a-f]{32}")) || normalizedActual == null ||
            !normalizedActual.matches(Regex("[0-9a-f]{32}")) || normalizedExpected != normalizedActual
        ) {
            throw IOException("Контрольная сумма загруженной резервной копии не совпадает")
        }
    }
}
