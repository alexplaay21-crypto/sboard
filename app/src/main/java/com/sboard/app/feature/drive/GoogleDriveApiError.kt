package com.sboard.app.feature.drive

import org.json.JSONObject

/** Converts Drive API HTTP errors into actionable, token-free diagnostics. */
object GoogleDriveApiError {
    fun describe(operation: String, status: Int, responseBody: String?): String {
        val error = runCatching { JSONObject(responseBody.orEmpty()).optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
            ?.takeIf { it.isNotBlank() } ?: error?.optString("status")?.takeIf { it.isNotBlank() }
        val serverMessage = error?.optString("message")?.trim()?.take(220)?.takeIf { it.isNotBlank() }
        val action = when {
            status == 401 || reason == "authError" || reason == "invalidCredentials" -> "Google authorization expired or was revoked; reconnect Google Drive."
            reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> "Enable Google Drive API in the linked Google Cloud project."
            reason == "insufficientPermissions" -> "Google did not grant drive.appdata; reconnect and approve the requested access."
            reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" || status == 429 -> "Google rate limit reached; sBoard will retry."
            status == 403 -> "Check the Google account, Drive API enablement, and drive.appdata authorization."
            else -> serverMessage ?: "Check the network and Google Drive authorization."
        }
        val reasonPart = reason?.let { ", $it" }.orEmpty()
        return "Google Drive $operation failed (HTTP $status$reasonPart). $action" +
            if (serverMessage != null && action != serverMessage) " Google: $serverMessage" else ""
    }
}
