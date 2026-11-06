package com.sboard.app.core.i18n

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Runtime UI language switch (Russian source strings, English translations).
 * Reading [tr] inside Compose subscribes to language changes, so the UI updates instantly.
 */
object SBoardI18n {
    const val MODE_SYSTEM = "system"
    const val MODE_RU = "ru"
    const val MODE_EN = "en"
    private const val PREFS = "sboard_settings"
    private const val KEY = "language"

    private val mode = mutableStateOf(MODE_SYSTEM)
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        mode.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, MODE_SYSTEM) ?: MODE_SYSTEM
        initialized = true
    }

    fun currentMode(): String = mode.value

    fun setLanguage(context: Context, value: String) {
        require(value in setOf(MODE_SYSTEM, MODE_RU, MODE_EN))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        mode.value = value
    }

    fun isEnglish(): Boolean = when (mode.value) {
        MODE_EN -> true
        MODE_RU -> false
        else -> Locale.getDefault().language != "ru" && Locale.getDefault().language != "be" &&
            Locale.getDefault().language != "uk" && Locale.getDefault().language != "kk"
    }

    fun translate(ru: String): String = if (isEnglish()) EnglishStrings.map[ru] ?: ru else ru
}

/** Translate a Russian UI string to the active language. */
fun tr(ru: String): String = SBoardI18n.translate(ru)

/** Translate and substitute positional arguments `{0}`, `{1}` ... */
fun tr(ru: String, vararg args: Any?): String {
    var text = SBoardI18n.translate(ru)
    args.forEachIndexed { index, value -> text = text.replace("{$index}", value.toString()) }
    return text
}
