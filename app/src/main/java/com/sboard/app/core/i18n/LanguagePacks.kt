package com.sboard.app.core.i18n

import android.content.Context
import com.sboard.app.feature.tools.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Builds interface language packs by machine-translating the built-in English strings with the
 * translator (Google). Only the app's own interface texts are sent — never Vault data. Strings are
 * sent in numbered batches; any string whose placeholders do not survive translation stays English.
 */
object LanguagePacks {
    private val lock = Mutex()

    /** Strings sent per request; keeps requests well under the translator's size limit. */
    private const val BATCH_CHARS = 1800

    suspend fun download(context: Context, lang: String, onProgress: (Float) -> Unit = {}): Boolean = lock.withLock {
        if (SBoardI18n.hasPack(lang)) return@withLock true
        withContext(Dispatchers.IO) {
            val source = EnglishStrings.map.entries.toList()
            val batches = mutableListOf<List<Map.Entry<String, String>>>()
            var current = mutableListOf<Map.Entry<String, String>>()
            var size = 0
            source.forEach { entry ->
                if (size + entry.value.length > BATCH_CHARS && current.isNotEmpty()) { batches += current; current = mutableListOf(); size = 0 }
                current += entry; size += entry.value.length + 8
            }
            if (current.isNotEmpty()) batches += current
            val result = java.util.concurrent.ConcurrentHashMap<String, String>()
            var done = 0
            val gate = Semaphore(3)
            val ok = runCatching {
                coroutineScope {
                    batches.map { batch ->
                        async {
                            gate.withPermit {
                                translateBatch(batch.map { it.value }, lang).forEachIndexed { i, text ->
                                    if (text != null) result[batch[i].key] = text
                                }
                                synchronized(this@LanguagePacks) { done++ }
                                onProgress(done.toFloat() / batches.size)
                            }
                        }
                    }.awaitAll()
                }
            }.isSuccess
            // Accept the pack when most strings came back; the rest falls back to English.
            if (!ok || result.size < source.size / 2) return@withContext false
            SBoardI18n.savePack(lang, result)
            true
        }
    }

    private val marker = Regex("""\[\[\s*(\d+)\s*]]""")

    internal fun translateBatch(texts: List<String>, lang: String): List<String?> {
        val joined = texts.mapIndexed { i, t -> "[[$i]] ${t.replace('\n', ' ')}" }.joinToString("\n")
        val translated = Translator.google(joined, "en", lang)
        return parseBatch(texts, translated)
    }

    /** Splits a translated numbered batch back into strings; checks `{0}`-style placeholders. */
    internal fun parseBatch(texts: List<String>, translated: String): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        val matches = marker.findAll(translated).toList()
        matches.forEachIndexed { m, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (index !in texts.indices) return@forEachIndexed
            val end = if (m + 1 < matches.size) matches[m + 1].range.first else translated.length
            val text = translated.substring(match.range.last + 1, end).trim()
            if (text.isEmpty()) return@forEachIndexed
            val placeholders = Regex("""\{\d}""").findAll(texts[index]).map { it.value }.toSet()
            if (placeholders.all { it in text }) out[index] = text
        }
        return out.toList()
    }
}
