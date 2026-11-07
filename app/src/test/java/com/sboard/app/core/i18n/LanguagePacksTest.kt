package com.sboard.app.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagePacksTest {
    @Test fun splitsNumberedBatchAndKeepsPlaceholders() {
        val source = listOf("Save", "Updated: {0}", "Delete {0} of {1}", "Cancel")
        val translated = "[[0]] Speichern\n[[1]] Aktualisiert: {0}\n[[ 2 ]] Löschen\n[[3]] Abbrechen"
        val out = LanguagePacks.parseBatch(source, translated)
        assertEquals("Speichern", out[0])
        assertEquals("Aktualisiert: {0}", out[1])
        assertNull("lost placeholders fall back to English", out[2])
        assertEquals("Abbrechen", out[3])
    }

    @Test fun everyEnglishStringHasItsPlaceholders() {
        // Packs are translated from English, so English must keep the Russian placeholders.
        EnglishStrings.map.forEach { (ru, en) ->
            val p = Regex("""\{\d}""").findAll(ru).map { it.value }.toSet()
            p.forEach { assert(it in en) { "\"$en\" lacks $it" } }
        }
    }
}
