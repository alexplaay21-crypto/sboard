package com.sboard.app.feature.tools

import android.content.Context
import com.sboard.app.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Online translator with selectable engine: Google Translate (default, auto-detects the source
 * language), DeepL (needs the user's own API key) and MyMemory (fallback). Text is sent only on
 * explicit request; this is the only network tool and is separate from the offline Vault.
 */
object Translator {
    data class Language(val code: String, val name: String)

    const val AUTO = "auto"
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DEEPL = "deepl"
    const val ENGINE_MYMEMORY = "mymemory"
    const val MAX_CHARS = 3000
    private const val PREFS = "sboard_translator"

    val languages: List<Language> get() = listOf(
        Language("ru", tr("Русский")), Language("en", tr("Английский")), Language("de", tr("Немецкий")),
        Language("fr", tr("Французский")), Language("es", tr("Испанский")), Language("it", tr("Итальянский")),
        Language("uk", tr("Украинский")), Language("tr", tr("Турецкий")), Language("zh-CN", tr("Китайский")),
        Language("ja", tr("Японский")), Language("ko", tr("Корейский")), Language("ar", tr("Арабский")),
        Language("pt", tr("Португальский")), Language("pl", tr("Польский")), Language("kk", tr("Казахский")),
        Language("uz", tr("Узбекский")), Language("tg", tr("Таджикский")), Language("az", tr("Азербайджанский")),
        Language("hy", tr("Армянский")), Language("ka", tr("Грузинский")), Language("be", tr("Белорусский")),
    )

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    fun engine(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("engine", ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    fun setEngine(context: Context, value: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("engine", value).apply()
    fun deeplKey(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deepl_key", "").orEmpty()
    fun setDeeplKey(context: Context, value: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("deepl_key", value.trim()).apply()

    /** Translates with the chosen engine; falls back to the other free engine on network/service errors. */
    suspend fun translate(context: Context, text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { tr("Введите текст") }
        val input = text.take(MAX_CHARS)
        val engine = engine(context)
        try {
            when (engine) {
                ENGINE_DEEPL -> deepl(input, from, to, deeplKey(context))
                ENGINE_MYMEMORY -> myMemory(input, from, to)
                else -> google(input, from, to)
            }
        } catch (e: Exception) {
            if (engine == ENGINE_DEEPL && deeplKey(context).isBlank()) throw IOException(tr("Укажите ключ DeepL API в настройках переводчика"))
            // Second engine as fallback so translation keeps working when one service is unavailable.
            runCatching { if (engine == ENGINE_GOOGLE) myMemory(input, if (from == AUTO) "en" else from, to) else google(input, from, to) }
                .getOrElse { throw e }
        }
    }

    /** Backwards-compatible entry used by older call sites (Google engine). */
    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) { google(text.take(MAX_CHARS), from, to) }

    private fun google(text: String, from: String, to: String): String {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("sl", from).addQueryParameter("tl", to)
            .addQueryParameter("dt", "t").addQueryParameter("q", text).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("Сервис перевода недоступен (HTTP {0})", response.code))
            val root = JSONArray(response.body!!.string())
            val parts = root.getJSONArray(0)
            return buildString { for (i in 0 until parts.length()) parts.optJSONArray(i)?.optString(0)?.let(::append) }
        }
    }

    private fun deeplLang(code: String, target: Boolean) = when {
        code == "en" && target -> "EN-US"
        code == "pt" && target -> "PT-BR"
        code.startsWith("zh") -> "ZH"
        else -> code.uppercase()
    }

    private fun deepl(text: String, from: String, to: String, key: String): String {
        if (key.isBlank()) throw IOException(tr("Укажите ключ DeepL API в настройках переводчика"))
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val form = FormBody.Builder().add("text", text).add("target_lang", deeplLang(to, true)).apply {
            if (from != AUTO) add("source_lang", deeplLang(from, false))
        }.build()
        val request = Request.Builder().url("$host/v2/translate").header("Authorization", "DeepL-Auth-Key $key").post(form).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403) throw IOException(tr("DeepL отклонил ключ API"))
            if (response.code == 456) throw IOException(tr("Лимит DeepL исчерпан"))
            if (!response.isSuccessful) throw IOException(tr("Сервис перевода недоступен (HTTP {0})", response.code))
            return JSONObject(response.body!!.string()).getJSONArray("translations").getJSONObject(0).getString("text")
        }
    }

    private fun myMemory(text: String, from: String, to: String): String {
        val url = "https://api.mymemory.translated.net/get".toHttpUrl().newBuilder()
            .addQueryParameter("q", text.take(500)).addQueryParameter("langpair", "${if (from == AUTO) "en" else from}|$to").build()
        client.newCall(Request.Builder().url(url).header("User-Agent", "sBoard-Android").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("Сервис перевода недоступен (HTTP {0})", response.code))
            val json = JSONObject(response.body!!.string())
            if (json.optInt("responseStatus", 200) != 200) throw IOException(json.optString("responseDetails").ifBlank { tr("Сервис перевода вернул ошибку") })
            return android.text.Html.fromHtml(json.getJSONObject("responseData").getString("translatedText"), 0).toString()
        }
    }
}
