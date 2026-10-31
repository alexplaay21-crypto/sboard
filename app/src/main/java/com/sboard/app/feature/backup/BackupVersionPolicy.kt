package com.sboard.app.feature.backup

import java.security.SecureRandom

/** Pure naming and retention rules for immutable Drive backup snapshots. */
object BackupVersionPolicy {
    private const val PREFIX = "sboard_backup_v1_"
    private const val SUFFIX_LENGTH = 8
    private val random = SecureRandom()
    private val fileNamePattern = Regex("^${PREFIX}(\\d{13})_([0-9a-f]{$SUFFIX_LENGTH})\\.enc$")

    /** Timestamp is fixed-width so lexicographic ordering matches chronological ordering. */
    fun newFileName(createdAtEpochMillis: Long, randomBytes: ByteArray? = null): String {
        require(createdAtEpochMillis in 0L..9_999_999_999_999L) { "Invalid backup timestamp" }
        val suffixBytes = randomBytes?.copyOf() ?: ByteArray(4).also(random::nextBytes)
        require(suffixBytes.size == 4) { "Version suffix must contain exactly four bytes" }
        val suffix = suffixBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        suffixBytes.fill(0)
        return "${PREFIX}${createdAtEpochMillis.toString().padStart(13, '0')}_$suffix.enc"
    }

    fun isVersionedFileName(name: String): Boolean = fileNamePattern.matches(name)

    /** The canonical pre-versioning backup remains restorable for upgrades and device migration. */
    fun isRestorableFileName(name: String): Boolean =
        isVersionedFileName(name) || name == GoogleDriveBackupClient.BACKUP_NAME

    fun timestampFromFileName(name: String): Long? =
        fileNamePattern.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Returns a preview only. Deletion is deliberately a separate, explicit user action;
     * this policy never removes cloud backups automatically.
     */
    fun retentionPreview(names: List<String>, keepNewest: Int): RetentionPreview {
        require(keepNewest >= 1) { "At least one backup version must be retained" }
        val valid = names.mapNotNull { name -> timestampFromFileName(name)?.let { name to it } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
        return RetentionPreview(
            kept = valid.take(keepNewest).map { it.first },
            candidatesForManualDeletion = valid.drop(keepNewest).map { it.first },
            ignoredInvalidNames = names.filterNot(::isVersionedFileName)
        )
    }

    data class RetentionPreview(
        val kept: List<String>,
        val candidatesForManualDeletion: List<String>,
        val ignoredInvalidNames: List<String>
    )
}


/** Guard for explicit cloud-version deletion; at least one recovery point must remain. */
internal object BackupVersionDeletionPolicy {
    fun canDelete(currentVersionCount: Int): Boolean = currentVersionCount > 1
}
