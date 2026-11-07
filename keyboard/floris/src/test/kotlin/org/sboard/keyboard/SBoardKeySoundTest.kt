package org.sboard.keyboard

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class SBoardKeySoundTest {
    @Test fun synthesisedClicksAreShortQuietAndValidWav() {
        SBoardKeySound.STYLES.forEach { style ->
            SBoardKeySound.Kind.entries.forEach { kind ->
                val samples = SBoardKeySound.synth(style, kind)
                assertTrue(samples.size in 400..3000, "$style/$kind too long") // 10–70 ms
                val peak = samples.maxOf { kotlin.math.abs(it.toInt()) }
                assertTrue(peak in 2000..(0.6 * Short.MAX_VALUE).toInt(), "$style/$kind clipping or silent: $peak")
                // Decays: the last 10 % is much quieter than the peak.
                val tail = samples.takeLast(samples.size / 10).maxOf { kotlin.math.abs(it.toInt()) }
                assertTrue(tail < peak / 3, "$style/$kind does not decay")
                val wav = SBoardKeySound.wav(samples)
                assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
                assertEquals(44 + samples.size * 2, wav.size)
            }
        }
    }

    @Test fun backspaceSpeedIsBounded() {
        assertEquals(100, SBoardTypingPrefs.backspaceSpeed)
    }

    @Test fun oneTimeCodesStayOutOfClipboardHistory() {
        assertTrue(SBoardClipboard.looksLikeSecret("482 913"))
        assertTrue(SBoardClipboard.looksLikeSecret("123456"))
        assertTrue(!SBoardClipboard.looksLikeSecret("Встреча в 18:00"))
        assertTrue(!SBoardClipboard.looksLikeSecret("https://example.com"))
    }
}
