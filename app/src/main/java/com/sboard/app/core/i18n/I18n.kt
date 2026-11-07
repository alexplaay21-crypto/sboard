package com.sboard.app.core.i18n

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/**
 * Runtime UI language switch. Russian is the source language, English is built in, and every other
 * translator language is available as a language pack: the English strings are machine-translated
 * once (only interface texts are sent, never user data) and stored on the device.
 * Reading [tr] inside Compose subscribes to language changes, so the UI updates instantly.
 */
object SBoardI18n {
    const val MODE_SYSTEM = "system"
    const val MODE_RU = "ru"
    const val MODE_EN = "en"
    private const val PREFS = "sboard_settings"
    private const val KEY = "language"

    private val mode = mutableStateOf(MODE_SYSTEM)
    /** Bumped when a language pack finishes loading so Compose re-reads strings. */
    private val packRevision = mutableIntStateOf(0)
    @Volatile private var pack: Map<String, String> = emptyMap()
    @Volatile private var packLanguage: String? = null
    @Volatile private var initialized = false
    private var filesDir: File? = null
    private var assets: android.content.res.AssetManager? = null
    private var bundled: Set<String> = emptySet()

    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        filesDir = app.filesDir
        attachAssets(app)
        mode.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, MODE_SYSTEM) ?: MODE_SYSTEM
        initialized = true
        loadPack(effectiveLanguage())
    }

    fun currentMode(): String = mode.value

    private fun attachAssets(app: Context) {
        assets = app.assets
        bundled = runCatching { app.assets.list("i18n").orEmpty().map { it.removeSuffix(".json") }.toSet() }.getOrDefault(emptySet())
    }

    fun setLanguage(context: Context, value: String) {
        filesDir = context.applicationContext.filesDir
        if (assets == null) attachAssets(context.applicationContext)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        mode.value = value
        loadPack(effectiveLanguage())
    }

    /** System language as a translator code (zh keeps its script region). */
    fun systemLanguage(): String {
        val l = Locale.getDefault()
        return when (l.language) {
            "zh" -> if (l.country == "TW" || l.country == "HK") "zh-TW" else "zh-CN"
            "in" -> "id"
            "iw" -> "he"
            "nb", "nn" -> "no"
            else -> l.language
        }
    }

    /** Language the interface is shown in ("ru", "en" or a pack language). */
    fun effectiveLanguage(): String = mode.value.takeIf { it != MODE_SYSTEM } ?: systemLanguage()

    /** The active language if it needs a language pack that is not on the device yet. */
    fun missingPack(): String? {
        val lang = effectiveLanguage()
        return if (hasPack(lang)) null else lang
    }

    fun isEnglish(): Boolean = effectiveLanguage() == MODE_EN

    private fun packFile(lang: String): File? = filesDir?.let { File(File(it, "i18n"), "$lang.json") }

    /** Built into the APK (all translator languages) or downloaded later. */
    fun hasPack(lang: String) = lang == MODE_RU || lang == MODE_EN || lang in bundled || packFile(lang)?.exists() == true

    private fun loadPack(lang: String) {
        fun parse(text: String) = Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
        pack = if (lang == MODE_RU || lang == MODE_EN) emptyMap() else {
            // Bundled pack first, then strings downloaded later on top (newer interface texts).
            val builtIn = runCatching { assets?.open("i18n/$lang.json")?.bufferedReader()?.use { parse(it.readText()) } }.getOrNull().orEmpty()
            val downloaded = runCatching { packFile(lang)?.takeIf { it.exists() }?.let { parse(it.readText()) } }.getOrNull().orEmpty()
            builtIn + downloaded
        }
        packLanguage = lang
        packRevision.intValue++
    }

    /** Saves a downloaded pack (Russian source → translated text) and activates it if selected. */
    fun savePack(lang: String, strings: Map<String, String>) {
        val file = packFile(lang) ?: return
        file.parentFile?.mkdirs()
        file.writeText(JsonObject(strings.mapValues { JsonPrimitive(it.value) }).toString())
        if (effectiveLanguage() == lang) loadPack(lang)
    }

    fun deletePack(lang: String) {
        packFile(lang)?.delete()
        if (effectiveLanguage() == lang) loadPack(lang)
    }

    /** Languages close enough to Russian that it is the better fallback while a pack downloads. */
    private val russianFallback = setOf("be", "uk", "kk", "tg", "uz", "ky")

    fun translate(ru: String): String {
        packRevision.intValue // Compose dependency on pack loading
        return when (val lang = effectiveLanguage()) {
            MODE_RU -> ru
            MODE_EN -> EnglishStrings.map[ru] ?: ru
            else -> {
                if (packLanguage == lang) pack[ru]?.let { return it }
                if (lang in russianFallback) ru else EnglishStrings.map[ru] ?: ru
            }
        }
    }
}

/** Translate a Russian UI string to the active language. */
fun tr(ru: String): String = SBoardI18n.translate(ru)

/** Translate and substitute positional arguments `{0}`, `{1}` ... */
fun tr(ru: String, vararg args: Any?): String {
    var text = SBoardI18n.translate(ru)
    args.forEachIndexed { index, value -> text = text.replace("{$index}", value.toString()) }
    return text
}
