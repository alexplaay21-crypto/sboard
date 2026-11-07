package com.sboard.app.integration

import android.app.Application
import android.content.Context
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.VaultDatabase
import com.sboard.app.data.VaultRepository
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Plain application for JVM integration tests (the keyboard runtime is not needed here). */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.initialize(this)
        SBoardI18n.initialize(this)
    }
}

/** Simulates a fresh installation on another device: new database, new device id, locked session. */
fun simulateNewDevice(context: Context) {
    VaultSessionProvider.session.lock()
    VaultRepository.resetForTests()
    VaultDatabase.resetForTests()
    context.deleteDatabase(VaultDatabase.FILE_NAME)
    context.getSharedPreferences("sboard_device", Context.MODE_PRIVATE).edit().clear().commit()
}

/** In-memory Google Drive v3 (appDataFolder subset) used by the sync end-to-end tests. */
class FakeDrive : Dispatcher() {
    class File(val id: String, var bytes: ByteArray, var version: Long, var modified: Long, val properties: MutableMap<String, String> = mutableMapOf())
    val files = LinkedHashMap<String, File>()
    private var nextId = 1
    var requests = 0

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        requests++
        val url = request.requestUrl!!
        val path = url.encodedPath
        if (request.getHeader("Authorization") != "Bearer test-token") return MockResponse().setResponseCode(401)
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> json(JSONObject().put("files", JSONArray().apply {
                files.values.sortedByDescending { it.modified }.forEach { put(meta(it)) }
            }))
            request.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                val file = files[path.substringAfterLast('/')] ?: return MockResponse().setResponseCode(404)
                if (url.queryParameter("alt") == "media") MockResponse().setBody(Buffer().write(file.bytes)) else json(meta(file))
            }
            request.method == "POST" && path == "/upload/drive/v3/files" -> {
                val (metadata, bytes) = multipart(request)
                val file = File("f${nextId++}", bytes, 1, System.nanoTime())
                applyProperties(file, metadata)
                files[file.id] = file
                json(meta(file))
            }
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                val file = files[path.substringAfterLast('/')] ?: return MockResponse().setResponseCode(404)
                val (metadata, bytes) = multipart(request)
                file.bytes = bytes; file.version++; file.modified = System.nanoTime()
                applyProperties(file, metadata)
                json(meta(file))
            }
            request.method == "DELETE" && path.startsWith("/drive/v3/files/") -> {
                files.remove(path.substringAfterLast('/')); MockResponse().setResponseCode(204)
            }
            else -> MockResponse().setResponseCode(400)
        }
    }

    private fun meta(f: File) = JSONObject().put("id", f.id).put("version", f.version.toString())
        .put("modifiedTime", f.modified.toString()).put("size", f.bytes.size.toString())
        .put("md5Checksum", MessageDigest.getInstance("MD5").digest(f.bytes).joinToString("") { "%02x".format(it) })
        .put("appProperties", JSONObject(f.properties as Map<*, *>))

    /** Drive semantics: listed properties are merged in, null values remove a property. */
    private fun applyProperties(file: File, metadata: JSONObject) {
        val props = metadata.optJSONObject("appProperties") ?: return
        props.keys().forEach { key -> if (props.isNull(key)) file.properties.remove(key) else file.properties[key] = props.getString(key) }
    }

    private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    /** Splits a multipart/related upload into its JSON metadata and media parts. */
    private fun multipart(request: RecordedRequest): Pair<JSONObject, ByteArray> {
        val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=").trim('"')
        val body = request.body.readByteArray().toString(Charsets.ISO_8859_1)
        val parts = body.split("--$boundary").map { it.trim('\r', '\n') }.filter { it.isNotEmpty() && it != "--" }
        val metadata = JSONObject(String(parts[0].substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        val mediaPart = parts[1]
        val content = mediaPart.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        return metadata to content.toByteArray(Charsets.ISO_8859_1)
    }
}
