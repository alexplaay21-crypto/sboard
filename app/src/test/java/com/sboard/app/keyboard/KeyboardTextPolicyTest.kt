package com.sboard.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardTextPolicyTest {
    @Test fun extractsOnlyCurrentWordForSuggestions() {
        assertEquals("привте", KeyboardTextPolicy.currentWord("Напиши привет привте"))
        assertEquals("hello", KeyboardTextPolicy.currentWord("Hi hello"))
        assertEquals("", KeyboardTextPolicy.currentWord("text, "))
    }

    @Test fun spellingReplacementKeepsInitialCapitalization() {
        assertEquals("Привет", KeyboardTextPolicy.preserveInitialCase("Привте", "привет"))
        assertEquals("hello", KeyboardTextPolicy.preserveInitialCase("helo", "hello"))
    }

    @Test fun backspaceCountsUtf16ForSupplementaryCharacter() {
        assertEquals(2, KeyboardTextPolicy.utf16UnitsForLastCodePoint("a😀"))
        assertEquals(1, KeyboardTextPolicy.utf16UnitsForLastCodePoint("ab"))
        assertEquals(0, KeyboardTextPolicy.utf16UnitsForLastCodePoint(""))
    }

    @Test fun backspaceRemovesWholeEmojiClusters() {
        assertEquals(2, KeyboardTextPolicy.utf16UnitsForLastGrapheme("a😀"))
        assertEquals(4, KeyboardTextPolicy.utf16UnitsForLastGrapheme("x👍🏽"))
        assertEquals(5, KeyboardTextPolicy.utf16UnitsForLastGrapheme("x👩‍💻"))
        assertEquals(4, KeyboardTextPolicy.utf16UnitsForLastGrapheme("x🇷🇺"))
    }

    @Test fun backspaceRepeatAcceleratesAndHasSafeFloor() {
        assertEquals(110L, KeyboardTextPolicy.backspaceRepeatDelayMs(0))
        assertEquals(70L, KeyboardTextPolicy.backspaceRepeatDelayMs(5))
        assertEquals(35L, KeyboardTextPolicy.backspaceRepeatDelayMs(100))
    }
}
