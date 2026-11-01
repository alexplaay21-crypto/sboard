package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankedWordListTest {
    @Test
    fun `normalizes Russian words and assigns stronger scores to earlier entries`() {
        val words = RankedWordList.parse("И\nв\nНе\nне\nпривет\n")

        assertEquals(listOf("не", "привет"), words.keys.toList())
        assertTrue(words.getValue("не") > words.getValue("привет"))
    }

    @Test
    fun `rejects punctuation and one-letter fragments`() {
        assertEquals(mapOf("слово" to 10_000), RankedWordList.parse("а\nслово!\nслово\n"))
    }

    @Test
    fun `Russian ranked words feed prefix and spelling suggestions`() {
        val dictionary = RankedWordList.parse("привет\nпривести\n")

        assertEquals("привет", LatinWordEngine.suggest("прив", dictionary, 2).first().word)
        assertEquals("привет", LatinWordEngine.correct("превет", dictionary, 2).first().word)
    }
}
