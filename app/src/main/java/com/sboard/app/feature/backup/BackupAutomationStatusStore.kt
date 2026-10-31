package com.sboard.app.feature.backup

import android.content.Context

/** Non-secret operational history for the backup screen. Never stores tokens, keys or record data. */
class BackupAutomationStatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordStarted(reason: String, at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT, at)
            .putString(KEY_LAST_REASON, reason.take(40))
            .putString(KEY_LAST_RESULT, RESULT_RUNNING)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordSuccess(at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, at)
            .putString(KEY_LAST_RESULT, RESULT_SUCCESS)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordSkipped(message: String = "") {
        prefs.edit()
            .putString(KEY_LAST_RESULT, RESULT_NEEDS_ATTENTION)
            .putString(KEY_LAST_ERROR, safeMessage(message))
            .apply()
    }

    fun recordFailure(message: String) {
        prefs.edit()
            .putString(KEY_LAST_RESULT, RESULT_FAILED)
            .putString(KEY_LAST_ERROR, safeMessage(message))
            .apply()
    }

    fun snapshot(): Snapshot = Snapshot(
        lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L },
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastReason = prefs.getString(KEY_LAST_REASON, null),
        result = prefs.getString(KEY_LAST_RESULT, RESULT_NEVER) ?: RESULT_NEVER,
        error = prefs.getString(KEY_LAST_ERROR, null)
    )

    private fun safeMessage(value: String): String = value
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [redacted]")
        .replace(Regex("(?i)(access_token|refresh_token|recovery_key)=([^&\\s]+)"), "$1=[redacted]")
        .take(240)

    data class Snapshot(
        val lastAttemptAt: Long?,
        val lastSuccessAt: Long?,
        val lastReason: String?,
        val result: String,
        val error: String?
    )

    companion object {
        const val RESULT_NEVER = "never"
        const val RESULT_RUNNING = "running"
        const val RESULT_SUCCESS = "success"
        const val RESULT_NEEDS_ATTENTION = "needs_attention"
        const val RESULT_FAILED = "failed"
        private const val PREFS = "sboard_backup_status_v1"
        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_REASON = "last_reason"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
