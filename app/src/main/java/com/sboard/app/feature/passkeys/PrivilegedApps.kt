package com.sboard.app.feature.passkeys

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Allowlist of browsers that may pass a web origin to a credential provider. The official list is
 * published by Google (the same list used by Google Password Manager); a minimal bundled copy is
 * the fallback when the device is offline. An unknown browser simply gets no passkey (fail closed).
 */
object PrivilegedApps {
    private const val URL = "https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"
    private const val CACHE = "privileged_apps.json"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    }

    suspend fun allowlist(context: Context): String = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, CACHE)
        if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS) {
            runCatching {
                client.newCall(Request.Builder().url(URL).get().build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body!!.string()
                        require(JSONObject(body).getJSONArray("apps").length() > 0)
                        cache.writeText(body)
                    }
                }
            }
        }
        cache.takeIf { it.exists() }?.readText() ?: context.assets.open("privileged_browsers.json").bufferedReader().use { it.readText() }
    }
}
