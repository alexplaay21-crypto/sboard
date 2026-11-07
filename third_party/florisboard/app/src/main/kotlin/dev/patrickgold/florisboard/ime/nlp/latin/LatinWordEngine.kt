/*
 * sBoard addition: deterministic offline English prefix and one-error ranking.
 * Uses the frequency map already bundled with FlorisBoard v0.5.2.
 */
package dev.patrickgold.florisboard.ime.nlp.latin

import java.util.Locale

internal object LatinWordEngine {
    data class Match(val word: String, val frequency: Int, val distance: Int)

    fun suggest(query: String, words: Map<String, Int>, limit: Int): List<Match> {
        val normalized = query.lowercase(Locale.ROOT)
        if (normalized.length < 2 || limit <= 0) return emptyList()

        val prefixMatches = words.asSequence()
            .filter { (word, _) -> word.length > normalized.length && word.startsWith(normalized) }
            .map { (word, frequency) -> Match(word, frequency, 0) }
            .sortedWith(compareByDescending<Match> { it.frequency }.thenBy { it.word.length }.thenBy { it.word })
            .take(limit)
            .toList()
        if (prefixMatches.isNotEmpty()) return prefixMatches
        if (normalized.length !in 3..24) return emptyList()

        return words.asSequence()
            .mapNotNull { (word, frequency) ->
                val distance = editDistanceAtMostOne(normalized, word) ?: return@mapNotNull null
                Match(word, frequency, distance)
            }
            .filter { it.word != normalized }
            .sortedWith(compareBy<Match> { it.distance }.thenByDescending { it.frequency }.thenBy { it.word })
            .take(limit)
            .toList()
    }

    fun correct(query: String, words: Map<String, Int>, limit: Int): List<Match> {
        val normalized = query.lowercase(Locale.ROOT)
        if (normalized.length !in 3..24 || limit <= 0) return emptyList()
        return words.asSequence()
            .mapNotNull { (word, frequency) ->
                val distance = editDistanceAtMostOne(normalized, word)
                when {
                    distance == null -> {
                        // sBoard: longer words may contain two slips onto neighbouring keys.
                        if (normalized.length >= 6 && word.length == normalized.length && neighbourMismatches(normalized, word) == 2) Match(word, frequency, 2) else null
                    }
                    distance == 0 -> null
                    else -> Match(word, frequency, distance)
                }
            }
            // A slip onto a neighbouring key is the most likely typo (as on Samsung's keyboard).
            .sortedWith(compareBy<Match> { it.distance }.thenBy { if (isNeighbourTypo(normalized, it.word)) 0 else 1 }
                .thenByDescending { it.frequency }.thenBy { it.word })
            .take(limit)
            .toList()
    }

    private val rows = listOf(
        "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "йцукенгшщзх", "фывапролджэ", "ячсмитьбю",
    )
    private val position: Map<Char, Pair<Int, Float>> = buildMap {
        rows.forEachIndexed { r, row ->
            val offset = when (r % 3) { 0 -> 0f; 1 -> 0.5f; else -> 1f } // staggered rows
            row.forEachIndexed { c, ch -> put(ch, (r to c + offset)) }
        }
        put('ё', get('е')!!); put('ъ', get('х')!!)
    }

    /** Whether [a] and [b] are on neighbouring keys (same keyboard family). */
    fun isNeighbour(a: Char, b: Char): Boolean {
        val pa = position[a] ?: return false
        val pb = position[b] ?: return false
        val sameFamily = (pa.first >= 4) == (pb.first >= 4)
        return sameFamily && kotlin.math.abs(pa.first - pb.first) <= 1 && kotlin.math.abs(pa.second - pb.second) <= 1.01f
    }

    private fun isNeighbourTypo(typed: String, word: String): Boolean =
        typed.length == word.length && typed.indices.count { typed[it] != word[it] }.let { it == 1 } &&
            typed.indices.first { typed[it] != word[it] }.let { isNeighbour(typed[it], word[it]) }

    /** Number of differing letters if all of them are neighbour-key slips, otherwise -1. */
    private fun neighbourMismatches(typed: String, word: String): Int {
        var n = 0
        for (i in typed.indices) if (typed[i] != word[i]) { if (!isNeighbour(typed[i], word[i])) return -1; n++ }
        return n
    }

    fun preserveCase(original: String, suggestion: String): String = when {
        original.isNotEmpty() && original.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase(Locale.ROOT)
        original.firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> suggestion
    }

    private fun editDistanceAtMostOne(left: String, right: String): Int? {
        if (left == right) return 0
        if (kotlin.math.abs(left.length - right.length) > 1) return null
        if (left.length == right.length) {
            var mismatch = -1
            for (index in left.indices) {
                if (left[index] == right[index]) continue
                if (mismatch == -1) mismatch = index else {
                    if (index != mismatch + 1 || left[mismatch] != right[index] || left[index] != right[mismatch]) return null
                    val suffixLength = left.length - index - 1
                    return if (left.regionMatches(index + 1, right, index + 1, suffixLength)) 1 else null
                }
            }
            return if (mismatch >= 0) 1 else 0
        }

        val shorter = if (left.length < right.length) left else right
        val longer = if (left.length > right.length) left else right
        var shortIndex = 0
        var longIndex = 0
        var skipped = false
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++
                longIndex++
            } else {
                if (skipped) return null
                skipped = true
                longIndex++
            }
        }
        return 1
    }
}
