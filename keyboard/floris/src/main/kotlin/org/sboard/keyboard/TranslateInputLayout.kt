package org.sboard.keyboard

import android.view.inputmethod.ExtractedTextRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Translator panel (Samsung Keyboard style): translates the selected text — or the whole text of
 * the current field — and replaces it with the translation. Text leaves the device only on tap.
 */
@Composable
fun TranslateInputLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val translator = SBoardVaultBridge.translator
    val scope = rememberCoroutineScope()
    val initial = remember { translator?.loadPair() ?: ("ru" to "en") }
    var from by remember { mutableStateOf(initial.first) }
    var to by remember { mutableStateOf(initial.second) }
    var picking by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    fun t(key: String) = translator?.text(key) ?: key
    fun name(code: String) = translator?.languages?.firstOrNull { it.first == code }?.second ?: code
    fun close() { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT }

    /** Returns the text to translate and whether it is a selection. */
    fun sourceText(): Pair<String, Boolean>? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) return selected to true
        val full = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        return full?.takeIf { it.isNotBlank() }?.let { it to false }
    }

    fun translate() {
        val tr = translator ?: return
        val (text, _) = sourceText() ?: run { status = t("translate_empty"); return }
        busy = true; status = t("translate_busy"); result = null
        scope.launch {
            runCatching { tr.translate(text.take(500), from, to) }
                .onSuccess { result = it; status = null }
                .onFailure { status = it.message ?: t("translate_failed") }
            busy = false
        }
    }

    fun apply(replace: Boolean) {
        val translated = result ?: return
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (replace) {
            val (_, isSelection) = sourceText() ?: (null to false)
            if (!isSelection) {
                val full = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0
                ic.beginBatchEdit(); ic.setSelection(0, full); ic.commitText(translated, 1); ic.endBatchEdit()
            } else ic.commitText(translated, 1)
        } else ic.commitText(translated, 1)
        close()
    }

    SnyggColumn(FlorisImeUi.ClipboardContent.elementName, modifier = modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight())) {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically) {
            val size = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { close() }, modifier = size) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName, onClick = { picking = "from" }, text = name(from))
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = {
                val f = from; from = to; to = f; translator?.savePair(from, to); result = null
            }, modifier = size) { SnyggIcon(imageVector = Icons.Default.SwapHoriz) }
            SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName, onClick = { picking = "to" }, text = name(to))
        }
        if (translator == null) {
            SnyggText(FlorisImeUi.ClipboardHistoryLockedMessage.elementName, text = "Translator unavailable")
            return@SnyggColumn
        }
        val target = picking
        if (target != null) {
            LazyRow(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(translator.languages, key = { it.first }) { (code, label) ->
                    SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName,
                        attributes = mapOf("state" to if ((target == "from" && code == from) || (target == "to" && code == to)) "active" else "inactive"),
                        onClick = {
                            if (target == "from") from = code else to = code
                            translator.savePair(from, to); picking = null; result = null
                        }, text = label)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SnyggText(FlorisImeUi.ClipboardHistoryLockedMessage.elementName, text = t("translate_hint"))
            status?.let { SnyggText(FlorisImeUi.ClipboardHistoryLockedMessage.elementName, text = it) }
            result?.let { SnyggText(FlorisImeUi.ClipboardItem.elementName, modifier = Modifier.fillMaxWidth(), text = it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (result == null) {
                    SnyggButton(FlorisImeUi.ClipboardHistoryDisabledButton.elementName, onClick = { translate() }, enabled = !busy) {
                        SnyggText(text = t("translate_action"))
                    }
                } else {
                    SnyggButton(FlorisImeUi.ClipboardClearAllDialogButton.elementName, onClick = { apply(replace = false) }) { SnyggText(text = t("translate_insert")) }
                    SnyggButton(FlorisImeUi.ClipboardHistoryDisabledButton.elementName, onClick = { apply(replace = true) }) { SnyggText(text = t("translate_replace")) }
                }
            }
        }
    }
}
