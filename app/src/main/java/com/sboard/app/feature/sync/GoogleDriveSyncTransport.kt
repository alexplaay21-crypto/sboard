package com.sboard.app.feature.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Drive appDataFolder transport for encrypted per-change envelopes.
 * The caller supplies a short-lived OAuth token with drive.appdata scope.
 * No Vault plaintext, Vault key, Recovery Key, or OAuth token is persisted here.
 *
 * A stable filename based on changeId makes uploads idempotent across retries.
 */
class GoogleDriveSyncTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    data class RemoteChange(
        val id: String,
        val name: String,
        val size: Long?,
        val modifiedTime: String?
    )

    suspend fun listChanges(accessToken: String): List<RemoteChange> = withContext(Dispatchers.IO) {
        validateToken(accessToken)
        val result = mutableListOf<RemoteChange>()
        var pageToken: String? = null
        var pages = 0
        do {
            pages++
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https").host("www.googleapis.com")
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name contains '${GoogleDriveSyncPolicy.FILE_PREFIX}' and 'appDataFolder' in parents and trashed = false")
                .addQueryParameter("orderBy", "name")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size,modifiedTime)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
            val next: String?
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Google Drive list failed (${response.code})")
                val json = JSONObject(response.body?.string().orEmpty())
                val files = json.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    val name = item.optString("name")
                    if (!GoogleDriveSyncPolicy.isSyncFileName(name)) continue
                    result += RemoteChange(
                        id = item.getString("id"),
                        name = name,
                        size = item.optString("size").toLongOrNull(),
                        modifiedTime = item.optString("modifiedTime").ifBlank { null }
                    )
                }
                next = json.optString("nextPageToken").ifBlank { null }
            }
            pageToken = next
        } while (pageToken != null && pages < MAX_LIST_PAGES && result.size < MAX_REMOTE_CHANGES)
        result.sortedBy { it.name }
    }

    /** Uploads one encrypted envelope. Repeating the same changeId never creates a second file. */
    suspend fun uploadChange(changeId: String, envelope: ByteArray, accessToken: String): RemoteChange =
        withContext(Dispatchers.IO) {
            validateToken(accessToken)
            require(envelope.isNotEmpty() && envelope.size <= GoogleDriveSyncPolicy.MAX_ENVELOPE_BYTES) {
                "Invalid encrypted sync envelope size"
            }
            val name = GoogleDriveSyncPolicy.fileName(changeId)
            val existing = findByName(name, accessToken)
            if (existing != null) {
                val downloaded = downloadChange(existing, accessToken)
                try {
                    if (!downloaded.contentEquals(envelope)) throw IOException("A different payload already exists for this changeId")
                } finally {
                    downloaded.fill(0)
                }
                return@withContext existing
            }
            val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder"))
            val boundary = "sboard_${changeId.replace("-", "")}_boundary"
            val bodyBytes = multipartRelated(metadata.toString().toByteArray(Charsets.UTF_8), envelope, boundary)
            val request = Request.Builder()
                .url("$DRIVE_FILES?uploadType=multipart&fields=id,name,size,modifiedTime")
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "multipart/related; boundary=$boundary")
                .post(bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Google Drive upload failed (${response.code})")
                    val json = JSONObject(response.body?.string().orEmpty())
                    val uploaded = RemoteChange(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        size = json.optString("size").toLongOrNull(),
                        modifiedTime = json.optString("modifiedTime").ifBlank { null }
                    )
                    if (uploaded.name != name) throw IOException("Google Drive returned an unexpected sync filename")
                    uploaded.size?.let { if (it != envelope.size.toLong()) throw IOException("Google Drive upload size mismatch") }
                    uploaded
                }
            } finally {
                bodyBytes.fill(0)
            }
        }

    suspend fun downloadChange(remote: RemoteChange, accessToken: String): ByteArray = withContext(Dispatchers.IO) {
        validateToken(accessToken)
        require(remote.id.matches(Regex("[A-Za-z0-9_-]{5,256}"))) { "Invalid Google Drive file ID" }
        require(GoogleDriveSyncPolicy.isSyncFileName(remote.name)) { "Not an sBoard sync file" }
        val request = Request.Builder().url("$DRIVE_FILES/${remote.id}?alt=media")
            .header("Authorization", "Bearer $accessToken").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive download failed (${response.code})")
            val body = response.body ?: throw IOException("Google Drive returned an empty response")
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > GoogleDriveSyncPolicy.MAX_ENVELOPE_BYTES) {
                bytes.fill(0)
                throw IOException("Invalid downloaded sync envelope size")
            }
            remote.size?.let { if (it != bytes.size.toLong()) { bytes.fill(0); throw IOException("Downloaded sync envelope size mismatch") } }
            bytes
        }
    }

    private fun findByName(name: String, token: String): RemoteChange? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("www.googleapis.com")
            .addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$escaped' and 'appDataFolder' in parents and trashed = false")
            .addQueryParameter("pageSize", "2")
            .addQueryParameter("fields", "files(id,name,size,modifiedTime)")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive lookup failed (${response.code})")
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: return null
            if (files.length() > 1) throw IOException("Duplicate sync change identifiers found in Google Drive")
            if (files.length() == 0) return null
            val item = files.getJSONObject(0)
            return RemoteChange(item.getString("id"), item.getString("name"), item.optString("size").toLongOrNull(), item.optString("modifiedTime").ifBlank { null })
        }
    }

    private fun multipartRelated(metadata: ByteArray, payload: ByteArray, boundary: String): ByteArray {
        val output = ByteArrayOutputStream(metadata.size + payload.size + 256)
        output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
        output.write(metadata)
        output.write("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        output.write(payload)
        output.write("\r\n--$boundary--".toByteArray())
        metadata.fill(0)
        return output.toByteArray()
    }

    private fun validateToken(token: String) {
        require(token.isNotBlank() && token.length <= 8192 && !token.contains('\n') && !token.contains('\r')) {
            "Invalid OAuth access token"
        }
    }

    companion object {
        private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val MAX_LIST_PAGES = 20
        private const val MAX_REMOTE_CHANGES = 20_000
    }
}
