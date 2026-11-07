package org.sboard.keyboard

import android.view.inputmethod.ExtractedTextRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/** Legacy full-panel mode: switch to the live bar instead. */
@Composable
fun TranslateInputLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    LaunchedEffect(Unit) {
        SBoardVaultBridge.translateBarVisible.value = true
        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
    }
}

private fun fieldText(): String? {
    val ic = FlorisImeService.currentInputConnection() ?: return null
    ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
    return ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
}

/**
 * Samsung-Keyboard-style live translation: type in your language as usual, the translation appears
 * above the keys (debounced while typing). Tap it or ↵ to replace the field text with the translation.
 */
@Composable
fun TranslateBar() {
    val translator = SBoardVaultBridge.translator ?: return
    val initial = remember { translator.loadPair() }
    var from by remember { mutableStateOf(initial.first) }
    var to by remember { mutableStateOf(initial.second) }
    var picking by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(translator.text("translate_hint")) }
    var applied by remember { mutableStateOf<String?>(null) }

    // Watch the field and translate after a short pause in typing.
    LaunchedEffect(Unit) {
        while (true) {
            val current = fieldText().orEmpty()
            if (current != source) { source = current }
            delay(350)
        }
    }
    LaunchedEffect(source, from, to) {
        if (source.isBlank() || source == applied) { result = ""; status = translator.text("translate_hint"); return@LaunchedEffect }
        delay(550)
        status = translator.text("translate_busy")
        runCatching { translator.translate(source, from, to) }
            .onSuccess { result = it; status = "" }
            .onFailure { status = it.message ?: translator.text("translate_failed") }
    }
    fun apply() {
        val ic = FlorisImeService.currentInputConnection() ?: return
        if (result.isBlank()) return
        val selected = ic.getSelectedText(0)
        ic.beginBatchEdit()
        if (selected.isNullOrEmpty()) {
            val length = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0
            ic.setSelection(0, length)
        }
        ic.commitText(result, 1)
        ic.endBatchEdit()
        applied = result; source = result; result = ""
    }
    fun name(code: String) = if (code == "auto") translator.languages.first().second else code.substringBefore('-').uppercase()

    Column(Modifier.fillMaxWidth()) {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier.fillMaxWidth().heightIn(min = FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically) {
            val size = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
            SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName, onClick = { picking = if (picking == "from") null else "from" }, text = name(from))
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = {
                if (from != "auto") { val f = from; from = to; to = f; translator.savePair(from, to) }
            }) { SnyggIcon(imageVector = Icons.Default.SwapHoriz) }
            SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName, onClick = { picking = if (picking == "to") null else "to" }, text = name(to))
            SnyggText(FlorisImeUi.ClipboardHeaderText.elementName,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).clickable { apply() },
                text = result.ifBlank { status })
            if (result.isNotBlank()) {
                SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = { apply() }) {
                    SnyggIcon(imageVector = Icons.AutoMirrored.Filled.KeyboardReturn)
                }
            }
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, modifier = size, onClick = { SBoardVaultBridge.translateBarVisible.value = false }) {
                SnyggIcon(imageVector = Icons.Default.Close)
            }
        }
        val target = picking
        if (target != null) {
            LazyRow(Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(translator.languages.filter { target == "from" || it.first != "auto" }, key = { it.first }) { (code, label) ->
                    SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName,
                        attributes = mapOf("state" to if ((target == "from" && code == from) || (target == "to" && code == to)) "active" else "inactive"),
                        onClick = {
                            if (target == "from") from = code else to = code
                            translator.savePair(from, to); picking = null
                        }, text = label)
                }
            }
        }
    }
}
