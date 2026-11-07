package com.sboard.app.feature.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DriveAuthException(message: String) : IOException(message)
class DriveHttpException(val status: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = status == 429 || status >= 500
}

/** Minimal Google Drive v3 REST client restricted to the hidden appDataFolder space. */
class DriveClient(
    private val http: OkHttpClient = defaultClient,
    private val api: String = API,
    private val upload: String = UPLOAD,
    private val userInfoUrl: String = "https://www.googleapis.com/oauth2/v3/userinfo",
) {
    data class RemoteFile(val id: String, val version: Long, val modifiedTime: String?, val size: Long, val md5: String?, val properties: Map<String, String> = emptyMap())

    suspend fun findBackups(token: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("name = '${BackupFormat.FILE_NAME}' and trashed = false", "UTF-8")
        val url = "$api/files?spaces=appDataFolder&q=$q&orderBy=modifiedTime%20desc&pageSize=10" +
            "&fields=files(id,version,modifiedTime,size,md5Checksum,appProperties)"
        execute(token, Request.Builder().url(url).get(), "list").use { response ->
            val files = JSONObject(response.body!!.string()).optJSONArray("files") ?: return@withContext emptyList()
            (0 until files.length()).map { parseFile(files.getJSONObject(it)) }
        }
    }

    suspend fun metadata(token: String, fileId: String): RemoteFile = withContext(Dispatchers.IO) {
        execute(token, Request.Builder().url("$api/files/$fileId?fields=id,version,modifiedTime,size,md5Checksum,appProperties").get(), "metadata").use {
            parseFile(JSONObject(it.body!!.string()))
        }
    }

    /** Downloads the file and verifies Drive's MD5 checksum (transport integrity). */
    suspend fun download(token: String, file: RemoteFile): ByteArray = withContext(Dispatchers.IO) {
        execute(token, Request.Builder().url("$api/files/${file.id}?alt=media").get(), "download").use { response ->
            val bytes = response.body!!.bytes()
            val md5 = file.md5
            if (md5 != null) {
                val actual = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                if (!actual.equals(md5, ignoreCase = true)) throw IOException("Downloaded backup does not match Drive checksum")
            }
            bytes
        }
    }

    /**
     * [extra] app properties travel with the single backup file (private to sBoard's OAuth client);
     * a null value removes the property.
     */
    suspend fun create(token: String, content: ByteArray, generation: Long, extra: Map<String, String?> = emptyMap()): RemoteFile = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", BackupFormat.FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("mimeType", MIME)
            .put("appProperties", properties(generation, extra.filterValues { it != null }))
        val body = multipart(metadata, content)
        execute(token, Request.Builder().url("$upload/files?uploadType=multipart&fields=id,version,modifiedTime,size,md5Checksum,appProperties").post(body), "upload").use {
            parseFile(JSONObject(it.body!!.string()))
        }
    }

    /** Replaces the content of the single backup file in place (no new copies are created). */
    suspend fun update(token: String, fileId: String, content: ByteArray, generation: Long, extra: Map<String, String?> = emptyMap()): RemoteFile = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("appProperties", properties(generation, extra))
        val body = multipart(metadata, content)
        execute(token, Request.Builder().url("$upload/files/$fileId?uploadType=multipart&fields=id,version,modifiedTime,size,md5Checksum,appProperties").patch(body), "update").use {
            parseFile(JSONObject(it.body!!.string()))
        }
    }

    suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
        execute(token, Request.Builder().url("$api/files/$fileId").delete(), "delete").close()
    }

    /** Basic Google profile for the Profile screen (requires userinfo scopes). */
    suspend fun userInfo(token: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            execute(token, Request.Builder().url(userInfoUrl).get(), "profile").use {
                val json = JSONObject(it.body!!.string())
                json.optString("email") to json.optString("name")
            }
        }.getOrNull()
    }

    private fun properties(generation: Long, extra: Map<String, String?>): JSONObject {
        val json = JSONObject().put("sboardFormat", BackupFormat.FORMAT_VERSION.toString()).put("generation", generation.toString())
        extra.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        return json
    }

    private fun multipart(metadata: JSONObject, content: ByteArray) = MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
        .addPart(content.toRequestBody(MIME.toMediaType()))
        .build()

    private fun parseFile(json: JSONObject) = RemoteFile(
        id = json.getString("id"),
        version = json.optString("version", "0").toLongOrNull() ?: 0L,
        modifiedTime = json.optString("modifiedTime").takeIf { it.isNotBlank() },
        size = json.optString("size", "0").toLongOrNull() ?: 0L,
        md5 = json.optString("md5Checksum").takeIf { it.isNotBlank() },
        properties = json.optJSONObject("appProperties")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty(),
    )

    private fun execute(token: String, builder: Request.Builder, operation: String): Response {
        val response = http.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
        if (response.isSuccessful) return response
        val body = runCatching { response.body?.string() }.getOrNull()
        val status = response.code
        response.close()
        if (status == 401) throw DriveAuthException("Google authorization expired")
        throw DriveHttpException(status, describe(operation, status, body))
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME = "application/octet-stream"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Converts Drive API errors into actionable, token-free messages. */
        fun describe(operation: String, status: Int, responseBody: String?): String {
            val error = runCatching { JSONObject(responseBody.orEmpty()).optJSONObject("error") }.getOrNull()
            val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: error?.optString("status")?.takeIf { it.isNotBlank() }
            val action = when {
                reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> "Enable the Google Drive API in the Google Cloud project of this app."
                reason == "insufficientPermissions" || reason == "PERMISSION_DENIED" -> "Reconnect Google Drive and allow access to app data."
                reason == "storageQuotaExceeded" -> "Google Drive storage is full."
                status == 429 || reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" -> "Google rate limit reached; sBoard will retry."
                status >= 500 -> "Google Drive is temporarily unavailable; sBoard will retry."
                else -> error?.optString("message")?.take(200) ?: "Check the network and Google authorization."
            }
            return "Google Drive $operation failed (HTTP $status${reason?.let { ", $it" } ?: ""}). $action"
        }
    }
}
