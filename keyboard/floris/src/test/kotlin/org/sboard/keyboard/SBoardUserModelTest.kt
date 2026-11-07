package org.sboard.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

class SBoardUserModelTest {
    @BeforeTest fun reset() = SBoardUserModel.clear()

    private fun type(text: String) {
        // Simulates the editor content after every character, as the keyboard sees it.
        for (i in 1..text.length) SBoardUserModel.learnFromContext("xx", text.substring(0, i))
    }

    @Test fun learnsEachCompletedWordOnce() {
        type("привет мир ")
        assertEquals(1, SBoardUserModel.count("xx", "привет"))
        assertEquals(1, SBoardUserModel.count("xx", "мир"))
        // Suggestions are requested many times for the same text: still counted once.
        repeat(3) { SBoardUserModel.learnFromContext("xx", "привет мир ") }
        assertEquals(1, SBoardUserModel.count("xx", "привет"))
        // A new message with the same words counts again.
        type("привет ")
        assertEquals(2, SBoardUserModel.count("xx", "привет"))
    }

    @Test fun predictsTheNextWordFromPairs() {
        repeat(3) { type("доброе утро. ") }
        type("доброе вечер ")
        val next = SBoardUserModel.nextWords("xx", "доброе", 3).map { it.first }
        assertEquals("утро", next.first())
        assertTrue("вечер" in next)
        // No pair across a sentence end.
        assertTrue(SBoardUserModel.nextWords("xx", "утро", 3).none { it.first == "доброе" })
    }

    @Test fun completesFrequentWordsAndForgets() {
        repeat(2) { type("sboard ") }
        assertEquals("sboard", SBoardUserModel.complete("xx", "sb", 3).single().first)
        SBoardUserModel.forget("xx", "sboard")
        assertEquals(0, SBoardUserModel.count("xx", "sboard"))
    }
}
