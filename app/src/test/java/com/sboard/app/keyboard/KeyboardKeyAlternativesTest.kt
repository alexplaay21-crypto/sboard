package com.sboard.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardKeyAlternativesTest {
    @Test fun russianAlternateSymbolsMatchReferenceRows() {
        assertEquals(listOf("ё"), KeyboardKeyAlternatives.forKey("е", isRussian = true))
        assertEquals(listOf("₽"), KeyboardKeyAlternatives.forKey("А", isRussian = true))
        assertEquals(listOf("ъ"), KeyboardKeyAlternatives.forKey("ь", isRussian = true))
    }

    @Test fun englishAlternateSymbolsAreCaseInsensitive() {
        assertEquals(listOf("@"), KeyboardKeyAlternatives.forKey("A", isRussian = false))
        assertEquals(listOf("?"), KeyboardKeyAlternatives.forKey("n", isRussian = false))
    }

    @Test fun unmappedKeysHaveNoAlternates() {
        assertTrue(KeyboardKeyAlternatives.forKey("⌫", isRussian = true).isEmpty())
    }
}
