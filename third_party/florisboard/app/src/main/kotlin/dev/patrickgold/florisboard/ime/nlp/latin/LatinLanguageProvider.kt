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
        // Here we initialize our provider, set up all things which are not language dependent.
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
        val language = subtype.primaryLocale.language
        if (language != "en" && language != "ru") return emptyList()
        val originalQuery = content.currentWordText.ifBlank { content.composingText }
        val query = originalQuery.lowercase(Locale.ROOT)
        if (query.isBlank()) return emptyList()
        return wordData.withLock { dictionaries ->
            val dictionary = dictionaries[language] ?: return@withLock emptyList()
            val maxFrequency = if (language == "ru") RankedWordList.MAX_RANK else 255
            LatinWordEngine.suggest(query, dictionary, maxCandidateCount)
                .filter { allowPossiblyOffensive || it.word !in BLOCKED_WORDS }
                .map { match ->
                    WordSuggestionCandidate(
                        text = LatinWordEngine.preserveCase(originalQuery, match.word),
                        confidence = (match.frequency.toDouble() / maxFrequency.toDouble()).coerceIn(0.0, 1.0),
                        isEligibleForAutoCommit = false,
                        sourceProvider = this,
                    )
                }
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
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it[subtype.primaryLocale.language]?.keys?.toList().orEmpty() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock {
            val language = subtype.primaryLocale.language
            val dictionary = it[language] ?: return@withLock 0.0
            val maxFrequency = if (language == "ru") RankedWordList.MAX_RANK else 255
            dictionary[word.lowercase(Locale.ROOT)]?.toDouble()?.div(maxFrequency.toDouble()) ?: 0.0
        }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
