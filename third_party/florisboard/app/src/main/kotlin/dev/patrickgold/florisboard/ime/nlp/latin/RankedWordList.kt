/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import java.util.Locale

/** Converts an ordered word list into the compact scores expected by the Latin provider. */
internal object RankedWordList {
    fun parse(text: String): Map<String, Int> {
        val words = linkedMapOf<String, Int>()
        var rank = 0
        text.lineSequence().forEach { rawWord ->
            val word = rawWord.trim().lowercase(Locale.ROOT)
            if (word.length !in 2..40 || word.any { !it.isLetter() }) return@forEach
            if (word !in words) {
                words[word] = (10_000 - rank).coerceAtLeast(1)
                rank++
            }
        }
        return words
    }
}
