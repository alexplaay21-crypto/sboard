package com.sboard.app.keyboard

/** Pure text handling shared by the IME and unit tests. */
object KeyboardTextPolicy {
    fun currentWord(textBeforeCursor: String?): String =
        textBeforeCursor.orEmpty().takeLastWhile { it.isLetter() || it == '\'' || it == '’' }

    fun preserveInitialCase(original: String, replacement: String): String {
        if (original.firstOrNull()?.isUpperCase() == true && replacement.isNotEmpty()) {
            return replacement.replaceFirstChar { it.uppercase() }
        }
        return replacement
    }

    /** Number of UTF-16 code units occupied by the last Unicode code point. */
    fun utf16UnitsForLastCodePoint(text: String?): Int {
        val value = text.orEmpty()
        if (value.isEmpty()) return 0
        val last = value.last()
        return if (last.isLowSurrogate() && value.length > 1 && value[value.lastIndex - 1].isHighSurrogate()) 2 else 1
    }

    /** UTF-16 length of the final user-perceived emoji/text cluster in a short cursor window. */
    fun utf16UnitsForLastGrapheme(text: String?): Int {
        val value = text.orEmpty()
        if (value.isEmpty()) return 0
        var start = value.offsetByCodePoints(value.length, -1)
        var current = value.codePointAt(start)
        var regionalCount = if (isRegionalIndicator(current)) 1 else 0
        while (start > 0) {
            val previousStart = value.offsetByCodePoints(start, -1)
            val previous = value.codePointAt(previousStart)
            val joins = isExtender(current) || current == 0x200D || previous == 0x200D ||
                (isRegionalIndicator(previous) && regionalCount == 1)
            if (!joins) break
            start = previousStart
            current = previous
            if (isRegionalIndicator(current)) regionalCount++
        }
        return value.length - start
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF
    private fun isExtender(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() || codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF || codePoint in 0x1F3FB..0x1F3FF || codePoint == 0x20E3
    }

    /** Backspace repeat accelerates smoothly, with a bounded minimum interval. */
    fun backspaceRepeatDelayMs(repeatIndex: Int): Long {
        val index = repeatIndex.coerceAtLeast(0)
        return (110L - index * 8L).coerceAtLeast(35L)
    }
}
