package com.sboard.app.feature.backup

import com.sboard.app.tr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import com.sboard.app.feature.drive.GoogleDriveApiError
import kotlinx.coroutines.withContext

/**
 * Minimal Google Drive API v3 transport for the encrypted sBoard backup.
 * Authentication is deliberately injected: the caller must obtain a short-lived
 * OAuth access token with the drive.appdata scope and must never persist it here.
 *
 * Legacy uploadOrReplace keeps one canonical file for compatibility. The versioned
 * API below creates immutable snapshot files and never overwrites an earlier version.
 */
class GoogleDriveBackupClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
) {
    suspend fun uploadOrReplace(encryptedBackup: File, accessToken: String): DriveBackupFile =
        withContext(Dispatchers.IO) {
            validateInput(encryptedBackup, accessToken)
            val existing = findLatest(accessToken)
            val sessionUrl = startResumableUpload(
                accessToken = accessToken,
                existingFileId = existing?.id,
                file = encryptedBackup
            )
            uploadInChunks(sessionUrl, encryptedBackup, accessToken)
        }

    /** Creates an immutable, uniquely named backup snapshot in Drive appDataFolder. */
    suspend fun uploadVersioned(
        encryptedBackup: File,
        accessToken: String,
        createdAtEpochMillis: Long = System.currentTimeMillis()
    ): DriveBackupFile = withContext(Dispatchers.IO) {
        validateInput(encryptedBackup, accessToken)
        val expectedSize = encryptedBackup.length()
        val expectedMd5 = md5Hex(encryptedBackup)
        val versionName = BackupVersionPolicy.newFileName(createdAtEpochMillis)
        val sessionUrl = startResumableUpload(
            accessToken = accessToken,
            existingFileId = null,
            file = encryptedBackup,
            backupName = versionName
        )
        val uploaded = uploadInChunks(sessionUrl, encryptedBackup, accessToken, backupName = versionName)
        BackupIntegrityPolicy.requireMatch(
            expectedSize = expectedSize,
            actualSize = uploaded.size,
            expectedMd5 = expectedMd5,
            actualMd5 = uploaded.md5Checksum
        )
        uploaded
    }

    /** Lists all versioned snapshots. It never deletes or silently replaces anything. */
    suspend fun listVersions(accessToken: String): List<DriveBackupFile> = withContext(Dispatchers.IO) {
        validateToken(accessToken)
        val results = mutableListOf<DriveBackupFile>()
        var pageToken: String? = null
        var pagesRead = 0
        do {
            pagesRead++
            val builder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("www.googleapis.com")
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "(name contains '$VERSIONED_PREFIX' or name = '$BACKUP_NAME') and 'appDataFolder' in parents and trashed = false")
                .addQueryParameter("orderBy", "name desc")
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,files(id,name,modifiedTime,size,md5Checksum)")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val request = Request.Builder().url(builder.build())
                .header("Authorization", "Bearer $accessToken").get().build()
            val next: String?
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw driveError(tr("Не удалось получить историю резервных копий"), response.code, response.body?.string())
                val json = JSONObject(response.body?.string().orEmpty())
                val files = json.optJSONArray("files")
                if (files != null) for (index in 0 until files.length()) {
                    val item = files.getJSONObject(index)
                    val name = item.optString("name")
                    if (!BackupVersionPolicy.isRestorableFileName(name)) continue
                    results += DriveBackupFile(
                        id = item.getString("id"),
                        name = name,
                        modifiedTime = item.optString("modifiedTime").ifBlank { null },
                        size = item.optString("size").toLongOrNull(),
                        md5Checksum = item.optString("md5Checksum").ifBlank { null }
                    )
                }
                next = json.optString("nextPageToken").ifBlank { null }
            }
            pageToken = next
            // Bound API work and memory even if an account contains an unexpectedly large history.
        } while (pageToken != null && results.size < MAX_VERSION_LIST && pagesRead < MAX_VERSION_PAGES)
        results.sortedWith(
            compareByDescending<DriveBackupFile> {
                BackupVersionPolicy.timestampFromFileName(it.name)
                    ?: runCatching { java.time.Instant.parse(it.modifiedTime).toEpochMilli() }.getOrDefault(0L)
            }.thenByDescending { it.name }
        )
    }

    /**
     * Deletes one explicitly selected snapshot. The client refuses to remove the last
     * version visible in Drive, so the user always retains at least one recovery point.
     */
    suspend fun deleteVersion(file: DriveBackupFile, accessToken: String): Int = withContext(Dispatchers.IO) {
        validateToken(accessToken)
        require(BackupVersionPolicy.isVersionedFileName(file.name)) { tr("Выбран недопустимый файл резервной копии") }
        require(file.id.matches(Regex("[A-Za-z0-9_-]{5,256}"))) { tr("Некорректный идентификатор резервной копии") }
        val current = listVersions(accessToken)
        if (current.none { it.id == file.id }) throw IOException(tr("Эта версия уже отсутствует в Google Drive"))
        if (!BackupVersionDeletionPolicy.canDelete(current.size)) {
            throw IOException(tr("Нельзя удалить последнюю резервную копию"))
        }
        val request = Request.Builder()
            .url("$DRIVE_FILES/${file.id}")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 204 && !response.isSuccessful) {
                throw driveError(tr("Не удалось удалить резервную копию"), response.code, response.body?.string())
            }
        }
        val remaining = listVersions(accessToken)
        if (remaining.isEmpty()) throw IOException(tr("Google Drive не подтвердил сохранение оставшейся резервной копии"))
        remaining.size
    }

    /** Downloads one explicitly selected snapshot by its Drive file ID. */
    suspend fun downloadVersion(file: DriveBackupFile, destination: File, accessToken: String): DriveBackupFile =
        withContext(Dispatchers.IO) {
            validateToken(accessToken)
            require(BackupVersionPolicy.isRestorableFileName(file.name)) { tr("Выбран недопустимый файл резервной копии") }
            require(file.id.matches(Regex("[A-Za-z0-9_-]{5,256}"))) { tr("Некорректный идентификатор резервной копии") }
            // Every restorable snapshot, including the legacy canonical file, must carry Drive size and MD5 metadata.
            DriveBackupDownloadPolicy.requireTrustedMetadata(file.size, file.md5Checksum)
            downloadById(file, destination, accessToken)
            file
        }

    suspend fun downloadLatest(destination: File, accessToken: String): DriveBackupFile? =
        withContext(Dispatchers.IO) {
            validateToken(accessToken)
            val metadata = findLatest(accessToken) ?: return@withContext null
            downloadById(metadata, destination, accessToken)
            metadata
        }

    private fun downloadById(metadata: DriveBackupFile, destination: File, accessToken: String) {
            val url = "$DRIVE_FILES/${metadata.id}?alt=media"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw driveError(tr("Не удалось скачать резервную копию"), response.code, response.body?.string())
                val body = response.body ?: throw IOException(tr("Google Drive вернул пустой файл"))
                destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
                val temp = File(destination.parentFile ?: destination.absoluteFile.parentFile, destination.name + ".download.tmp")
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_BACKUP_BYTES) throw IOException(tr("Резервная копия превышает допустимый размер"))
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                            buffer.fill(0)
                        }
                    }
                    if (!temp.isFile || temp.length() == 0L) throw IOException(tr("Google Drive вернул пустую резервную копию"))
                    metadata.size?.let { expectedSize ->
                        if (temp.length() != expectedSize) throw IOException(tr("Размер резервной копии не совпадает с данными Google Drive"))
                    }
                    metadata.md5Checksum?.let { expected ->
                        val actual = md5Hex(temp)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            throw IOException(tr("Контрольная сумма резервной копии не совпадает"))
                        }
                    }
                    Files.move(
                        temp.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } finally {
                    temp.delete()
                }
            }
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun findLatest(accessToken: String): DriveBackupFile? {
        val query = "name = '$BACKUP_NAME' and 'appDataFolder' in parents and trashed = false"
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", query)
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "10")
            .addQueryParameter("fields", "files(id,name,modifiedTime,size,md5Checksum)")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw driveError(tr("Не удалось найти резервную копию в Google Drive"), response.code, response.body?.string())
            val json = JSONObject(response.body?.string().orEmpty())
            val files = json.optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            val item = files.getJSONObject(0)
            return DriveBackupFile(
                id = item.getString("id"),
                name = item.optString("name", BACKUP_NAME),
                modifiedTime = item.optString("modifiedTime").ifBlank { null },
                size = item.optString("size").toLongOrNull(),
                md5Checksum = item.optString("md5Checksum").ifBlank { null }
            )
        }
    }

    private fun startResumableUpload(
        accessToken: String,
        existingFileId: String?,
        file: File,
        backupName: String = BACKUP_NAME
    ): String {
        val metadata = if (existingFileId == null) {
            JSONObject()
                .put("name", backupName)
                .put("mimeType", "application/octet-stream")
                .put("parents", org.json.JSONArray().put("appDataFolder"))
        } else JSONObject().put("name", backupName)

        val endpoint = if (existingFileId == null) {
            "$DRIVE_UPLOAD?uploadType=resumable&fields=id,name,modifiedTime,size,md5Checksum"
        } else {
            "$DRIVE_UPLOAD/$existingFileId?uploadType=resumable&fields=id,name,modifiedTime,size,md5Checksum"
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", file.length().toString())
            .header("Content-Type", "application/json; charset=UTF-8")
            .method(if (existingFileId == null) "POST" else "PATCH", metadata.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code !in 200..299) throw driveError(tr("Не удалось начать загрузку в Google Drive"), response.code, response.body?.string())
            return response.header("Location") ?: throw IOException(tr("Google Drive не вернул адрес загрузки"))
        }
    }

    private fun uploadInChunks(
        sessionUrl: String,
        file: File,
        accessToken: String,
        backupName: String = BACKUP_NAME
    ): DriveBackupFile {
        val total = file.length()
        var offset = 0L
        RandomAccessFile(file, "r").use { input ->
            while (offset < total) {
                val chunkLength = minOf(UPLOAD_CHUNK_BYTES, total - offset).toInt()
                val chunk = ByteArray(chunkLength)
                input.seek(offset)
                input.readFully(chunk)
                val end = offset + chunkLength - 1
                val request = Request.Builder()
                    .url(sessionUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Length", chunkLength.toString())
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(chunk.toRequestBody(BINARY))
                    .build()
                val response = http.newCall(request).execute()
                chunk.fill(0)
                response.use {
                    when (it.code) {
                        200, 201 -> {
                            val json = JSONObject(it.body?.string().orEmpty())
                            return DriveBackupFile(
                                id = json.getString("id"),
                                name = json.optString("name", backupName),
                                modifiedTime = json.optString("modifiedTime").ifBlank { null },
                                size = json.optString("size").toLongOrNull() ?: total,
                                md5Checksum = json.optString("md5Checksum").ifBlank { null }
                            )
                        }
                        308 -> {
                            val range = it.header("Range")
                            offset = if (range == null) 0L else {
                                val last = range.substringAfterLast('-').toLongOrNull()
                                    ?: throw IOException(tr("Google Drive вернул некорректный диапазон загрузки"))
                                last + 1L
                            }
                        }
                        else -> throw driveError(tr("Не удалось загрузить резервную копию в Google Drive"), it.code, it.body?.string())
                    }
                }
            }
        }
        throw IOException(tr("Google Drive не подтвердил завершение загрузки"))
    }

    private fun validateInput(file: File, token: String) {
        validateToken(token)
        require(file.isFile && file.length() in 1..MAX_BACKUP_BYTES) {
            tr("Файл резервной копии отсутствует или превышает допустимый размер")
        }
    }

    private fun validateToken(token: String) {
        require(token.isNotBlank() && token.length < 8192 && !token.contains('\n')) {
            tr("Отсутствует действительный OAuth access token")
        }
    }

    private fun driveError(message: String, status: Int, responseBody: String? = null): Exception {
        val diagnostic = GoogleDriveApiError.describe(message, status, responseBody)
        return if (status == 401 || status == 403) SecurityException(diagnostic) else IOException(diagnostic)
    }

    data class DriveBackupFile(
        val id: String,
        val name: String,
        val modifiedTime: String?,
        val size: Long?,
        val md5Checksum: String? = null
    )

    companion object {
        const val BACKUP_NAME = "sboard_backup.enc"
        const val VERSIONED_PREFIX = "sboard_backup_v1_"
        private const val MAX_VERSION_LIST = 1000
        private const val MAX_VERSION_PAGES = 10
        private const val MAX_BACKUP_BYTES = 258L * 1024L * 1024L
        private const val UPLOAD_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
    }
}
