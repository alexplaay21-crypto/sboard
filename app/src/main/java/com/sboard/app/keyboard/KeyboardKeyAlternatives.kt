package com.sboard.app.keyboard

/** Local, deterministic alternates shown above keys and on long press. */
object KeyboardKeyAlternatives {
    private val russian = mapOf(
        "й" to listOf("+"), "ц" to listOf("×"), "у" to listOf("÷"), "к" to listOf("="),
        "е" to listOf("ё"), "н" to listOf("/"), "г" to listOf("_"), "ш" to listOf("<"),
        "щ" to listOf(">"), "з" to listOf("["), "х" to listOf("]"),
        "ф" to listOf("!"), "ы" to listOf("@"), "в" to listOf("#"), "а" to listOf("₽"),
        "п" to listOf("%"), "р" to listOf("^"), "о" to listOf("&"), "л" to listOf("*"),
        "д" to listOf("("), "ж" to listOf(")"), "э" to listOf("~"),
        "я" to listOf("-"), "ч" to listOf("'"), "с" to listOf("\""), "м" to listOf(":"),
        "и" to listOf(";"), "т" to listOf(","), "ь" to listOf("ъ"), "б" to listOf("?"),
        "ю" to listOf("`"), "ё" to listOf("е")
    )
    private val english = mapOf(
        "q" to listOf("1"), "w" to listOf("2"), "e" to listOf("3"), "r" to listOf("4"),
        "t" to listOf("5"), "y" to listOf("6"), "u" to listOf("7"), "i" to listOf("8"),
        "o" to listOf("9"), "p" to listOf("0"), "a" to listOf("@"), "s" to listOf("$"),
        "d" to listOf("_"), "f" to listOf("&"), "g" to listOf("-"), "h" to listOf("+"),
        "j" to listOf("("), "k" to listOf(")"), "l" to listOf("/"), "z" to listOf("!"),
        "x" to listOf("\""), "c" to listOf("'"), "v" to listOf(":"), "b" to listOf(";"),
        "n" to listOf("?"), "m" to listOf(",")
    )

    fun forKey(key: String, isRussian: Boolean): List<String> {
        val normalized = key.lowercase()
        return (if (isRussian) russian else english)[normalized].orEmpty()
    }
}
