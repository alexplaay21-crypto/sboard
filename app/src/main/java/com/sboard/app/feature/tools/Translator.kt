package com.sboard.app.feature.tools

import com.sboard.app.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Online translator (MyMemory public API, no account needed). This is the only network tool and it
 * is fully separate from the offline Vault: text is sent only when the user taps "Translate".
 */
object Translator {
    data class Language(val code: String, val name: String)

    val languages: List<Language> get() = listOf(
        Language("ru", tr("Русский")), Language("en", tr("Английский")), Language("de", tr("Немецкий")),
        Language("fr", tr("Французский")), Language("es", tr("Испанский")), Language("it", tr("Итальянский")),
        Language("uk", tr("Украинский")), Language("tr", tr("Турецкий")), Language("zh-CN", tr("Китайский")),
        Language("ja", tr("Японский")), Language("ko", tr("Корейский")), Language("ar", tr("Арабский")),
    )

    const val MAX_CHARS = 500

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { tr("Введите текст") }
        require(text.length <= MAX_CHARS) { tr("Не более {0} символов за раз", MAX_CHARS) }
        val url = "https://api.mymemory.translated.net/get".toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", "$from|$to")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "sBoard-Android").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("Сервис перевода недоступен (HTTP {0})", response.code))
            val json = JSONObject(response.body!!.string())
            val status = json.optInt("responseStatus", 200)
            if (status != 200) throw IOException(json.optString("responseDetails").ifBlank { tr("Сервис перевода вернул ошибку") })
            json.getJSONObject("responseData").getString("translatedText").let { android.text.Html.fromHtml(it, 0).toString() }
        }
    }
}
