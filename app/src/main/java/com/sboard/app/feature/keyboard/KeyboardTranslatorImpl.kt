package com.sboard.app.feature.keyboard

import android.content.Context
import com.sboard.app.core.i18n.tr
import com.sboard.app.feature.tools.Translator
import org.sboard.keyboard.KeyboardTranslator

class KeyboardTranslatorImpl(context: Context) : KeyboardTranslator {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("sboard_keyboard_translate", Context.MODE_PRIVATE)
    override val languages: List<Pair<String, String>> get() = listOf(Translator.AUTO to tr("Авто")) + Translator.languages.map { it.code to it.name }
    override suspend fun translate(text: String, from: String, to: String): String = Translator.translate(app, text, from, to)
    override fun loadPair(): Pair<String, String> = (prefs.getString("from", Translator.AUTO) ?: Translator.AUTO) to (prefs.getString("to", "en") ?: "en")
    override fun savePair(from: String, to: String) { prefs.edit().putString("from", from).putString("to", to).apply() }
    override fun text(key: String): String = when (key) {
        "translate_hint" -> tr("Пишите как обычно — перевод появится здесь")
        "translate_action" -> tr("Перевести")
        "translate_busy" -> tr("Переводим…")
        "translate_failed" -> tr("Не удалось перевести")
        "translate_empty" -> tr("В поле нет текста для перевода")
        "translate_insert" -> tr("Вставить")
        "translate_replace" -> tr("Заменить")
        else -> key
    }
}
