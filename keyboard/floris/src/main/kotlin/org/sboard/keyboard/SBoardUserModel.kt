package org.sboard.keyboard

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale

/**
 * On-device personal language model: how often the user types each word and which word usually
 * follows another (for next-word suggestions). Never filled in password or incognito fields,
 * stored only in the app's private storage, and can be cleared from the settings.
 */
object SBoardUserModel {
    private const val MAX_WORDS = 20_000
    private const val MAX_PAIRS = 40_000

    private class Model(val words: HashMap<String, Int> = HashMap(), val pairs: HashMap<String, Int> = HashMap())

    private val models = HashMap<String, Model>()
    private var dir: File? = null
    private val dirty = HashSet<String>()
    private var lastSave = 0L
    private var lastLearned: String? = null
    private var lastLearnedLength = 0

    private fun prefs(context: Context) = context.getSharedPreferences("sboard_keyboard_setup", Context.MODE_PRIVATE)

    @Volatile var learningEnabled = true
        private set
    @Volatile var autoCorrectEnabled = true
        private set
    @Volatile var nextWordEnabled = true
        private set

    fun init(context: Context) {
        dir = File(context.filesDir, "sboard_user_model").apply { mkdirs() }
        val p = prefs(context)
        learningEnabled = p.getBoolean("learn_words", true)
        autoCorrectEnabled = p.getBoolean("auto_correct", true)
        nextWordEnabled = p.getBoolean("next_word", true)
    }

    fun setLearning(context: Context, value: Boolean) { learningEnabled = value; prefs(context).edit().putBoolean("learn_words", value).apply() }
    fun setAutoCorrect(context: Context, value: Boolean) { autoCorrectEnabled = value; prefs(context).edit().putBoolean("auto_correct", value).apply() }
    fun setNextWord(context: Context, value: Boolean) { nextWordEnabled = value; prefs(context).edit().putBoolean("next_word", value).apply() }

    @Synchronized
    private fun model(language: String): Model = models.getOrPut(language) { load(language) }

    private fun file(language: String) = dir?.let { File(it, "$language.bin") }

    private fun load(language: String): Model {
        val m = Model()
        val f = file(language) ?: return m
        if (!f.exists()) return m
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != 1) return m
                repeat(input.readInt()) { m.words[input.readUTF()] = input.readInt() }
                repeat(input.readInt()) { m.pairs[input.readUTF()] = input.readInt() }
            }
        }
        return m
    }

    @Synchronized
    private fun save(language: String) {
        val m = models[language] ?: return
        val f = file(language) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(1)
                out.writeInt(m.words.size); m.words.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
                out.writeInt(m.pairs.size); m.pairs.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
            }
            tmp.renameTo(f)
        }
    }

    @Synchronized
    fun flush() {
        dirty.toList().forEach { save(it) }
        dirty.clear()
        lastSave = System.currentTimeMillis()
    }

    private val tokenRegex = Regex("""[\p{L}\p{M}'’-]+""")

    /**
     * Learns from the text before the cursor: the last completed word (followed by a separator) and
     * the pair it forms with the word before it. Each word is counted once.
     */
    @Synchronized
    fun learnFromContext(language: String, textBeforeCursor: String) {
        if (!learningEnabled) return
        // Text got shorter (message sent, field cleared, word deleted): the same position may be
        // reached again with new words, so forget the dedupe key.
        if (textBeforeCursor.length < lastLearnedLength) { lastLearned = null; lastLearnedLength = 0 }
        if (textBeforeCursor.isEmpty()) return
        val last = textBeforeCursor.last()
        if (last.isLetter() || last == '\'' || last == '’') return // still typing the current word
        val tail = textBeforeCursor.takeLast(120)
        val tokens = tokenRegex.findAll(tail).map { it.value.lowercase(Locale.ROOT).trim('-', '\'') }.filter { it.length in 2..40 }.toList()
        if (tokens.isEmpty()) return
        // Identify the position by the text length so each completed word is learned once.
        val key = "${textBeforeCursor.length}:${tokens.takeLast(2).joinToString(" ")}"
        if (key == lastLearned) return
        lastLearned = key
        lastLearnedLength = textBeforeCursor.length
        val m = model(language)
        val word = tokens.last()
        m.words[word] = (m.words[word] ?: 0) + 1
        if (tokens.size >= 2) {
            val sentenceBreak = Regex("""[.!?]\s*$""").containsMatchIn(tail.substringBeforeLast(word, ""))
            if (!sentenceBreak) {
                val pair = tokens[tokens.size - 2] + " " + word
                m.pairs[pair] = (m.pairs[pair] ?: 0) + 1
            }
        }
        trim(m)
        dirty += language
        if (System.currentTimeMillis() - lastSave > 20_000) flush()
    }

    private fun trim(m: Model) {
        if (m.words.size > MAX_WORDS) m.words.entries.sortedBy { it.value }.take(m.words.size - MAX_WORDS).forEach { m.words.remove(it.key) }
        if (m.pairs.size > MAX_PAIRS) m.pairs.entries.sortedBy { it.value }.take(m.pairs.size - MAX_PAIRS).forEach { m.pairs.remove(it.key) }
    }

    @Synchronized
    fun count(language: String, word: String): Int = model(language).words[word.lowercase(Locale.ROOT)] ?: 0

    /** Learned words starting with [prefix], most used first. */
    @Synchronized
    fun complete(language: String, prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = prefix.lowercase(Locale.ROOT)
        return model(language).words.entries.asSequence()
            .filter { it.key.length > p.length && it.key.startsWith(p) && it.value >= 2 }
            .sortedByDescending { it.value }.take(limit).map { it.key to it.value }.toList()
    }

    /** Words that usually follow [previous]. */
    @Synchronized
    fun nextWords(language: String, previous: String, limit: Int): List<Pair<String, Int>> {
        if (!nextWordEnabled) return emptyList()
        val prefix = previous.lowercase(Locale.ROOT) + " "
        return model(language).pairs.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value }.take(limit)
            .map { it.key.substring(prefix.length) to it.value }.toList()
    }

    @Synchronized
    fun forget(language: String, word: String) {
        val m = model(language)
        val w = word.lowercase(Locale.ROOT)
        m.words.remove(w)
        m.pairs.keys.removeAll { it.startsWith("$w ") || it.endsWith(" $w") }
        dirty += language
    }

    /** Number of learned words over all languages (for the settings screen). */
    @Synchronized
    fun learnedCount(): Int {
        dir?.listFiles()?.forEach { f -> val lang = f.name.removeSuffix(".bin"); if (f.name.endsWith(".bin")) model(lang) }
        return models.values.sumOf { it.words.size }
    }

    @Synchronized
    fun clear() {
        models.clear(); dirty.clear(); lastLearned = null
        dir?.listFiles()?.forEach { it.delete() }
    }
}
