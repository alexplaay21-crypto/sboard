package com.sboard.app.feature.tools

import com.nulabinc.zxcvbn.Zxcvbn
import com.sboard.app.core.i18n.tr

/** zxcvbn-based strength estimation (offline). */
object PasswordStrength {
    data class Result(val score: Int, val label: String, val crackTime: String, val warning: String?, val suggestions: List<String>, val guessesLog10: Double)

    private val engine by lazy { Zxcvbn() }

    fun evaluate(password: String, userInputs: List<String> = emptyList()): Result {
        if (password.isEmpty()) return Result(0, tr("Пусто"), "—", null, emptyList(), 0.0)
        val m = engine.measure(password.take(128), userInputs)
        val seconds = m.crackTimeSeconds.offlineSlowHashing1e4perSecond
        val warning = m.feedback.warning?.takeIf { it.isNotBlank() }?.let { translateFeedback(it) }
        return Result(
            score = m.score.coerceIn(0, 4),
            label = when (m.score) { 0 -> tr("Очень слабый"); 1 -> tr("Слабый"); 2 -> tr("Средний"); 3 -> tr("Надёжный"); else -> tr("Очень надёжный") },
            crackTime = humanTime(seconds),
            warning = warning,
            suggestions = m.feedback.suggestions.orEmpty().map(::translateFeedback),
            guessesLog10 = m.guessesLog10,
        )
    }

    fun humanTime(seconds: Double): String = when {
        seconds < 1 -> tr("мгновенно")
        seconds < 60 -> tr("{0} сек.", seconds.toInt())
        seconds < 3600 -> tr("{0} мин.", (seconds / 60).toInt())
        seconds < 86400 -> tr("{0} ч.", (seconds / 3600).toInt())
        seconds < 86400 * 365 -> tr("{0} дн.", (seconds / 86400).toInt())
        seconds < 86400 * 365 * 100 -> tr("{0} лет", (seconds / (86400 * 365)).toInt())
        else -> tr("столетия")
    }

    private fun translateFeedback(text: String): String = when {
        text.contains("top-10", true) || text.contains("top 10", true) -> tr("Это один из самых распространённых паролей")
        text.contains("top-100", true) || text.contains("very common", true) -> tr("Это очень распространённый пароль")
        text.contains("common password", true) -> tr("Похож на распространённый пароль")
        text.contains("Repeats like", true) -> tr("Повторы символов легко угадать")
        text.contains("Sequences like", true) -> tr("Последовательности вроде abc или 6543 легко угадать")
        text.contains("Straight rows", true) || text.contains("keyboard patterns", true) || text.contains("Short keyboard", true) -> tr("Ряды клавиш на клавиатуре легко угадать")
        text.contains("Dates", true) || text.contains("years", true) -> tr("Даты и годы легко угадать")
        text.contains("Names and surnames", true) || text.contains("name by itself", true) -> tr("Имена и фамилии легко угадать")
        text.contains("Add another word", true) -> tr("Добавьте ещё одно-два слова. Необычные слова лучше")
        text.contains("Use a few words", true) -> tr("Используйте несколько слов, избегайте распространённых фраз")
        text.contains("No need for symbols", true) -> tr("Символы и цифры не обязательны, важнее длина")
        text.contains("Avoid repeated", true) -> tr("Избегайте повторяющихся слов и символов")
        text.contains("Avoid sequences", true) -> tr("Избегайте последовательностей")
        text.contains("Avoid recent years", true) || text.contains("Avoid years", true) -> tr("Избегайте недавних лет и связанных с вами дат")
        text.contains("Capitalization", true) -> tr("Заглавные буквы мало помогают")
        text.contains("Reversed words", true) -> tr("Слова наоборот легко угадать")
        text.contains("substitutions", true) -> tr("Замены вроде @ вместо a мало помогают")
        else -> text
    }
}
