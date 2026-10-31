package com.sboard.app.feature.backup

/** Filters Drive entries before presenting them as restore choices. */
internal object BackupDiscoveryPolicy {
    data class Result(
        val usable: List<GoogleDriveBackupClient.DriveBackupFile>,
        val rejectedMetadataCount: Int
    )

    fun filter(files: List<GoogleDriveBackupClient.DriveBackupFile>): Result {
        val named = files.filter { BackupVersionPolicy.isRestorableFileName(it.name) }
        val usable = named.filter { file ->
            runCatching {
                DriveBackupDownloadPolicy.requireTrustedMetadata(file.size, file.md5Checksum)
            }.isSuccess
        }.sortedWith(compareByDescending<GoogleDriveBackupClient.DriveBackupFile> {
            BackupVersionPolicy.timestampFromFileName(it.name)
                ?: runCatching { java.time.Instant.parse(it.modifiedTime).toEpochMilli() }.getOrDefault(0L)
        }.thenByDescending { it.name })
        return Result(usable, named.size - usable.size)
    }
}
