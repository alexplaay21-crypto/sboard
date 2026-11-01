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
}
