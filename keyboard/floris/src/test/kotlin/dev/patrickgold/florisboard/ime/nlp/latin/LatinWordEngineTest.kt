package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatinWordEngineTest {
    @Test
    fun `prefix suggestions prefer higher dictionary frequency and exclude exact word`() {
        val matches = LatinWordEngine.suggest("hel", mapOf("hello" to 220, "help" to 120, "hel" to 255), 3)

        assertEquals(listOf("hello", "help"), matches.map { it.word })
    }

    @Test
    fun `single insertion deletion substitution and adjacent transposition are corrected`() {
        val words = mapOf("hello" to 255, "world" to 220, "form" to 180)

        assertEquals("hello", LatinWordEngine.suggest("hellp", words, 3).first().word)
        assertEquals("hello", LatinWordEngine.suggest("helo", words, 3).first().word)
        assertEquals("hello", LatinWordEngine.suggest("hlelo", words, 3).first().word)
        assertEquals("form", LatinWordEngine.suggest("from", words, 3).first().word)
        assertEquals("hello", LatinWordEngine.correct("helo", words, 3).first().word)
    }

    @Test
    fun `preserves initial and all-uppercase typing style`() {
        assertEquals("Hello", LatinWordEngine.preserveCase("Hellp", "hello"))
        assertEquals("HELLO", LatinWordEngine.preserveCase("HELLP", "hello"))
        assertEquals("hello", LatinWordEngine.preserveCase("hellp", "hello"))
    }

    @Test
    fun `does not fuzzy match short input or exceed result limit`() {
        val words = mapOf("and" to 100, "any" to 90, "ant" to 80, "answer" to 70)

        assertTrue(LatinWordEngine.suggest("ad", words, 4).isEmpty())
        assertEquals(2, LatinWordEngine.suggest("an", words, 2).size)
        assertTrue(LatinWordEngine.suggest("qwerty", words, 5).isEmpty())
    }
}
