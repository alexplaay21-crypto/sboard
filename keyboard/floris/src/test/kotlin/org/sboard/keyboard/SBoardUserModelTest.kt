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

class SBoardAutoCorrectRuleTest {
    @kotlin.test.Test fun secondIdenticalTypoIsKeptAndLearned() {
        SBoardUserModel.clear()
        assertTrue(SBoardUserModel.shouldAutoCorrect("xx", "превет"))
        SBoardUserModel.noteAutoCorrected("xx", "превет")
        // Typed exactly the same again: the user means it.
        assertTrue(!SBoardUserModel.shouldAutoCorrect("xx", "превет"))
        assertTrue(SBoardUserModel.count("xx", "превет") >= 2)
    }

    @kotlin.test.Test fun neighbourKeySlipsWinOverOtherEdits() {
        val dict = mapOf("привет" to 50_000, "пример" to 59_000, "hello" to 200, "jello" to 250)
        // "з" is next to "х"? no — "р"→"в" etc. Use a QWERTY example: "hrllo" (r next to e).
        assertEquals("hello", dev.patrickgold.florisboard.ime.nlp.latin.LatinWordEngine.correct("hrllo", dict, 3).first().word)
        assertTrue(dev.patrickgold.florisboard.ime.nlp.latin.LatinWordEngine.isNeighbour('е', 'н'))
        assertTrue(!dev.patrickgold.florisboard.ime.nlp.latin.LatinWordEngine.isNeighbour('й', 'м'))
    }
}
