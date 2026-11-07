package org.sboard.keyboard

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.ime.media.emoji.EmojiData
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistoryHelper
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Emoji search: the query is typed with the normal letter keys (they go into the search instead of
 * the text field) and matching emoji are shown above the keys, in the keyboard language.
 */
object SBoardEmojiSearch {
    val active = MutableStateFlow(false)
    val query = MutableStateFlow("")

    fun open(context: Context) {
        query.value = ""
        active.value = true
        context.keyboardManager().value.activeState.imeUiMode = ImeUiMode.TEXT
    }
    fun close() { active.value = false; query.value = "" }
    fun type(text: String) { query.value = (query.value + text).take(40) }
    fun backspace() { query.value = query.value.dropLast(1) }

    /** Emoji whose name or keywords match [q] (word start first). */
    fun search(all: List<Emoji>, q: String): List<Emoji> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        return all.asSequence().mapNotNull { e ->
            val words = e.keywords + e.name.split(' ')
            when {
                words.any { it.equals(query, true) } -> e to 3
                words.any { it.startsWith(query, true) } -> e to 2
                e.name.contains(query, true) || e.keywords.any { it.contains(query, true) } -> e to 1
                else -> null
            }
        }.sortedByDescending { it.second }.map { it.first }.take(60).toList()
    }
}

@Composable
fun EmojiSearchBar() {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val query by SBoardEmojiSearch.query.collectAsState()
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<Emoji>>(emptyList()) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            val locale = context.subtypeManager().value.activeSubtype.primaryLocale
            val local = EmojiData.get(context, locale).bySkinTone[EmojiSkinTone.DEFAULT].orEmpty()
            // English keywords too, so "heart" works in every language.
            val english = EmojiData.get(context, "ime/media/emoji/en.txt").bySkinTone[EmojiSkinTone.DEFAULT].orEmpty()
            local + english
        }
    }
    val results = remember(query, all) { SBoardEmojiSearch.search(all, query).distinctBy { it.value } }
    val ru = java.util.Locale.getDefault().language == "ru"
    Column(Modifier.fillMaxWidth()) {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName, modifier = Modifier.fillMaxWidth().heightIn(min = FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically) {
            val size = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
            SnyggIcon(imageVector = Icons.Default.Search, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
            SnyggText(FlorisImeUi.ClipboardHeaderText.elementName, modifier = Modifier.weight(1f),
                text = query.ifEmpty { if (ru) "Поиск эмодзи: наберите слово" else "Search emoji: type a word" } + if (query.isNotEmpty()) "▏" else "")
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = {
                SBoardEmojiSearch.close()
                context.keyboardManager().value.activeState.imeUiMode = ImeUiMode.MEDIA
            }) { SnyggIcon(imageVector = Icons.Default.Close) }
        }
        LazyRow(Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight * 1.2f), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            items(results, key = { it.value }) { emoji ->
                Text(emoji.value, fontSize = 28.sp, modifier = Modifier.clickable {
                    editorInstance.commitText(emoji.value)
                    scope.launch { val prefs by FlorisPreferenceStore; EmojiHistoryHelper.markEmojiUsed(prefs, emoji) }
                }.padding(horizontal = 6.dp))
            }
        }
    }
}
