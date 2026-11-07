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

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.util.Locale

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
        private val BLOCKED_WORDS = setOf(
            "ass", "bastard", "bitch", "bullshit", "crap", "damn", "fuck", "fucking",
            "motherfucker", "shit", "shitty", "whore",
        )
    }

    private val appContext by context.appContext()

    private val wordData = guardedByLock { mutableMapOf<String, Map<String, Int>>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        org.sboard.keyboard.SBoardUserModel.init(appContext)
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        val language = subtype.primaryLocale.language
        if (language != "en" && language != "ru") return@withContext
        wordData.withLock { dictionaries ->
            if (language !in dictionaries) {
                dictionaries[language] = when (language) {
                    "en" -> {
                        // The upstream English frequency dictionary remains on-device.
                        val rawData = appContext.assets.readText("ime/dict/data.json")
                        Json.decodeFromString(wordDataSerializer, rawData)
                    }
                    "ru" -> {
                        // Modified for sBoard: ~50k ranked forms (FrequencyWords CC BY-SA 4.0 + the CC BY 2.5
                        // 10k list); see THIRD_PARTY_NOTICES. More word forms = better swipe and suggestions.
                        RankedWordList.parse(appContext.assets.readText("ime/dict/russian_words.txt"))
                    }
                    else -> emptyMap()
                }
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        if (isPrivateSession) return SpellingResult.unspecified()
        val language = subtype.primaryLocale.language
        if (language != "en" && language != "ru") return SpellingResult.unspecified()
        val normalized = word.lowercase(Locale.ROOT)
        if (org.sboard.keyboard.SBoardUserModel.count(language, normalized) >= 2) return SpellingResult.validWord()
        if (normalized.isBlank() || normalized.any { !it.isLetter() && it != '\'' && it != '’' }) {
            return SpellingResult.unspecified()
        }
        return wordData.withLock { dictionaries ->
            val dictionary = dictionaries[language] ?: return@withLock SpellingResult.unspecified()
            if (!allowPossiblyOffensive && normalized in BLOCKED_WORDS) return@withLock SpellingResult.unspecified()
            if (normalized in dictionary) return@withLock SpellingResult.validWord()
            val corrections = LatinWordEngine.correct(normalized, dictionary, maxSuggestionCount)
                .filter { allowPossiblyOffensive || it.word !in BLOCKED_WORDS }
                .map { LatinWordEngine.preserveCase(word, it.word) }
                .distinct()
            if (corrections.isEmpty()) SpellingResult.unspecified()
            else SpellingResult.typo(corrections.toTypedArray(), isHighConfidenceResult = false)
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (isPrivateSession) return emptyList()
        // Modified for sBoard: personal dictionary + shortcuts, learned words, next-word prediction
        // and conservative auto-correction, for every language (built-in dictionaries for RU/EN).
        val language = subtype.primaryLocale.language
        val textBefore = content.textBeforeSelection
        withContext(Dispatchers.Default) { org.sboard.keyboard.SBoardUserModel.learnFromContext(language, textBefore) }
        val originalQuery = content.currentWordText.ifBlank { content.composingText }
        val query = originalQuery.lowercase(Locale.ROOT)
        if (query.isBlank()) return nextWordSuggestions(language, textBefore, maxCandidateCount)
        val personal = personalDictionary(query, subtype)
        return wordData.withLock { dictionaries ->
            val dictionary = dictionaries[language] ?: emptyMap()
            val maxFrequency = if (language == "ru") RankedWordList.MAX_RANK else 255
            val known = query in dictionary || org.sboard.keyboard.SBoardUserModel.count(language, query) >= 2 ||
                personal.any { it.first.equals(query, ignoreCase = true) }
            val results = LinkedHashMap<String, WordSuggestionCandidate>()
            fun add(word: String, confidence: Double, autoCommit: Boolean = false) {
                val text = LatinWordEngine.preserveCase(originalQuery, word)
                if (!allowPossiblyOffensive && word.lowercase(Locale.ROOT) in BLOCKED_WORDS) return
                if (text.lowercase(Locale.ROOT) in results) return
                results[text.lowercase(Locale.ROOT)] = WordSuggestionCandidate(
                    text = text, confidence = confidence.coerceIn(0.0, 1.0),
                    isEligibleForAutoCommit = autoCommit, sourceProvider = this,
                )
            }
            // 1. Text shortcuts from the personal dictionary ("др" → "день рождения").
            personal.filter { it.second }.forEach { add(it.first, 1.0) }
            // 2. Auto-correction of an unknown word with one typo to a frequent dictionary word.
            if (!known && org.sboard.keyboard.SBoardUserModel.autoCorrectEnabled && query.length >= 4) {
                LatinWordEngine.correct(query, dictionary, 3).firstOrNull()?.let { best ->
                    val share = best.frequency.toDouble() / maxFrequency
                    if (share > 0.6) add(best.word, share, autoCommit = true)
                }
            }
            // 3. Words the user types often, then personal dictionary words, then the dictionary.
            org.sboard.keyboard.SBoardUserModel.complete(language, query, 3).forEach { (w, n) -> add(w, 0.5 + n / 200.0) }
            personal.filter { !it.second }.forEach { add(it.first, 0.9) }
            LatinWordEngine.suggest(query, dictionary, maxCandidateCount).forEach { match ->
                val boost = org.sboard.keyboard.SBoardUserModel.count(language, match.word) / 50.0
                add(match.word, match.frequency.toDouble() / maxFrequency + boost)
            }
            results.values.take(maxCandidateCount)
        }
    }

    /** Personal dictionary entries for [query]: (word, isShortcutMatch). */
    private fun personalDictionary(query: String, subtype: Subtype): List<Pair<String, Boolean>> = runCatching {
        val dao = dev.patrickgold.florisboard.ime.dictionary.DictionaryManager.default().let {
            it.loadUserDictionariesIfNecessary(); it.florisUserDictionaryDao()
        } ?: return emptyList()
        val shortcuts = dao.queryShortcut(query, subtype.primaryLocale).map { it.word to true }
        val words = dao.query(query, subtype.primaryLocale)
            .filter { it.word.lowercase(Locale.ROOT).startsWith(query) }
            .sortedByDescending { it.freq }.take(3).map { it.word to false }
        shortcuts + words
    }.getOrDefault(emptyList())

    private fun nextWordSuggestions(language: String, textBefore: String, limit: Int): List<SuggestionCandidate> {
        if (textBefore.isEmpty() || !textBefore.last().isWhitespace()) return emptyList()
        val previous = Regex("""([\p{L}'’-]+)\s*$""").find(textBefore.trimEnd())?.groupValues?.get(1) ?: return emptyList()
        val capitalize = Regex("""[.!?]\s*$""").containsMatchIn(textBefore)
        if (capitalize) return emptyList()
        return org.sboard.keyboard.SBoardUserModel.nextWords(language, previous, limit).map { (word, n) ->
            WordSuggestionCandidate(text = word, confidence = (0.3 + n / 50.0).coerceAtMost(1.0), isEligibleForAutoCommit = false, sourceProvider = this)
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        // Long-press on a suggestion forgets a learned word.
        org.sboard.keyboard.SBoardUserModel.forget(subtype.primaryLocale.language, candidate.text.toString())
        return true
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        // Swipe typing also knows the user's own words (learned + personal dictionary).
        val language = subtype.primaryLocale.language
        val own = org.sboard.keyboard.SBoardUserModel.complete(language, "", 3000).map { it.first } +
            runCatching { dev.patrickgold.florisboard.ime.dictionary.DictionaryManager.default().florisUserDictionaryDao()?.queryAll(subtype.primaryLocale)?.map { it.word.lowercase(Locale.ROOT) } }.getOrNull().orEmpty()
        return wordData.withLock { it[language]?.keys?.toList().orEmpty() } + own.filter { w -> w.length >= 2 && w.all { c -> c.isLetter() || c == '\'' } }
    }

    /**
     * Word prior for swipe typing, calibrated to a probability-like scale (Zipf's law): the Russian
     * list is rank-based, the English data uses a logarithmic 0..255 scale. Words the user types
     * often are boosted.
     */
    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val language = subtype.primaryLocale.language
        val normalized = word.lowercase(Locale.ROOT)
        val base = wordData.withLock {
            val dictionary = it[language]
            val value = dictionary?.get(normalized)
            when {
                value == null -> 0.002
                language == "ru" -> 300.0 / ((RankedWordList.MAX_RANK - value).coerceAtLeast(0) + 300.0)
                else -> Math.pow(10.0, (value - 255) / 51.0)
            }
        }
        val learned = org.sboard.keyboard.SBoardUserModel.count(language, normalized)
        return (base * (1.0 + learned / 3.0) + if (learned >= 2) 0.01 else 0.0).coerceIn(1e-6, 1.0)
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
