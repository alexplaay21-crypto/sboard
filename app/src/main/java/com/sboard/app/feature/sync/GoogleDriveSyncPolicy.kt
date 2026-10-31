package com.sboard.app.feature.sync

/** Pure naming and bounds policy shared by the Drive transport and its tests. */
object GoogleDriveSyncPolicy {
    const val FILE_PREFIX = "sboard_sync_v1_"
    const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024

    fun fileName(changeId: String): String {
        require(changeId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "Invalid change identifier" }
        return "$FILE_PREFIX$changeId.sbsync"
    }

    fun changeIdFromFileName(name: String): String? {
        if (!isSyncFileName(name)) return null
        return name.removePrefix(FILE_PREFIX).removeSuffix(".sbsync")
    }

    fun isSyncFileName(name: String): Boolean =
        name.length in (FILE_PREFIX.length + 8 + 7)..(FILE_PREFIX.length + 128 + 7) &&
            name.startsWith(FILE_PREFIX) && name.endsWith(".sbsync") &&
            name.removePrefix(FILE_PREFIX).removeSuffix(".sbsync").matches(Regex("[A-Za-z0-9_-]{8,128}"))
}
