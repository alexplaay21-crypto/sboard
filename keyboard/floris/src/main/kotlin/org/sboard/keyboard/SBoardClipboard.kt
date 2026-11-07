package org.sboard.keyboard

import android.content.Context
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Text entry of the keyboard clipboard history, as shown in sBoard → Tools → Clipboard. */
data class ClipEntry(val id: Long, val text: String, val pinned: Boolean, val createdAt: Long, val sensitive: Boolean)

/** Facade over the keyboard's clipboard history so that the app UI can manage it. */
object SBoardClipboard {
    fun history(context: Context): Flow<List<ClipEntry>> {
        val manager by context.clipboardManager()
        return manager.historyFlow.map { history ->
            history.all.filter { it.type == ItemType.TEXT && !it.text.isNullOrEmpty() }
                .map { ClipEntry(it.id, it.text.orEmpty(), it.isPinned, it.creationTimestampMs, it.isSensitive) }
        }
    }

    private fun find(context: Context, id: Long) = context.clipboardManager().value.historyFlow.value.all.firstOrNull { it.id == id }

    fun delete(context: Context, id: Long) { find(context, id)?.let { context.clipboardManager().value.deleteClip(it, onlyIfUnpinned = false) } }
    fun setPinned(context: Context, id: Long, pinned: Boolean) {
        val manager = context.clipboardManager().value
        find(context, id)?.let { if (pinned) manager.pinClip(it) else manager.unpinClip(it) }
    }
    fun clearUnpinned(context: Context) = context.clipboardManager().value.clearHistory()
    fun add(context: Context, text: String) = context.clipboardManager().value.addNewPlaintext(text)

    @Volatile var excludeCodes: Boolean = true

    /** One-time codes (4–8 digits) are kept out of the history when [excludeCodes] is on. */
    fun looksLikeSecret(text: String?): Boolean {
        if (!excludeCodes || text == null) return false
        val t = text.trim()
        return t.length in 4..9 && t.replace(" ", "").replace("-", "").let { it.length in 4..8 && it.all(Char::isDigit) }
    }
}
