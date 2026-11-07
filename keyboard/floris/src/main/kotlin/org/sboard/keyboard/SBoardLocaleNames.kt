package org.sboard.keyboard

import java.util.Locale

/** Short language labels: "English US", "Deutsch AT", "Русский" — no long country names. */
object SBoardLocaleNames {
    fun short(languageTag: String, displayIn: Locale = Locale.getDefault()): String {
        val locale = Locale.forLanguageTag(languageTag)
        val name = locale.getDisplayLanguage(displayIn).replaceFirstChar { it.titlecase(displayIn) }
        val country = locale.country.takeIf { it.length == 2 }
        val variant = languageTag.split('-').drop(if (country != null) 2 else 1).firstOrNull { it.length > 3 }
        return listOfNotNull(name.ifBlank { languageTag }, country, variant).joinToString(" ")
    }
}
