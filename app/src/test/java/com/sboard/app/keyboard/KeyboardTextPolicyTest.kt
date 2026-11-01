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
}
