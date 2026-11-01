package com.sboard.app.feature.sync

import android.content.Context

/** Stores only sanitized sync diagnostics and timestamps, never tokens, Vault data or keys. */
class SyncAutomationStatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun started(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_ATTEMPT, at).putString(KEY_RESULT, RUNNING).remove(KEY_ERROR).apply()
    }
    fun success(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_SUCCESS, at).putString(KEY_RESULT, SUCCESS).remove(KEY_ERROR).apply()
    }
    fun attention(message: String) { record(ATTENTION, message) }
    fun failure(message: String) { record(FAILED, message) }
    private fun record(result: String, message: String) {
        prefs.edit().putString(KEY_RESULT, result).putString(KEY_ERROR, sanitize(message)).apply()
    }
    fun snapshot() = Snapshot(
        prefs.getLong(KEY_ATTEMPT, 0L).takeIf { it > 0 },
        prefs.getLong(KEY_SUCCESS, 0L).takeIf { it > 0 },
        prefs.getString(KEY_RESULT, NEVER) ?: NEVER,
        prefs.getString(KEY_ERROR, null)
    )
    private fun sanitize(value: String) = value
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [redacted]")
        .replace(Regex("(?i)(access_token|refresh_token|recovery_key)=([^&\\s]+)"), "$1=[redacted]")
        .take(280)
    data class Snapshot(val lastAttemptAt: Long?, val lastSuccessAt: Long?, val result: String, val error: String?)
    companion object {
        const val NEVER = "never"; const val RUNNING = "running"; const val SUCCESS = "success"
        const val ATTENTION = "attention"; const val FAILED = "failed"
        private const val PREFS = "sboard_sync_status_v1"
        private const val KEY_ATTEMPT = "attempt"; private const val KEY_SUCCESS = "success_at"
        private const val KEY_RESULT = "result"; private const val KEY_ERROR = "error"
    }
}
