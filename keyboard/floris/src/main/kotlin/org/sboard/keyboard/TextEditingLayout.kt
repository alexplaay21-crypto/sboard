package org.sboard.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextRotationNone
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Samsung-style text editing panel: cursor pad (hold to repeat), selection mode, select all,
 * copy / cut / paste, undo / redo and delete word. Everything goes through the normal key handling
 * (InputConnection), so it only does what the target editor supports.
 */
@Composable
fun TextEditingLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(keyboardManager.activeState.isManualSelectionMode) }
    val ru = java.util.Locale.getDefault().language == "ru"

    fun send(data: KeyData) {
        FlorisImeService.inputFeedbackController()?.keyPress(data)
        keyboardManager.inputEventDispatcher.sendDownUp(data)
    }

    @Composable
    fun RowScope.Tile(icon: ImageVector, label: String?, data: KeyData? = null, repeat: Boolean = false, active: Boolean = false, weight: Float = 1f, onTap: (() -> Unit)? = null) {
        SnyggBox(
            elementName = FlorisImeUi.SmartbarActionTile.elementName,
            attributes = mapOf("state" to if (active) "active" else "inactive"),
            modifier = Modifier.weight(weight).fillMaxHeight().padding(3.dp),
            clickAndSemanticsModifier = Modifier.pointerInput(data, onTap) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    var job: Job? = null
                    if (onTap != null) onTap() else if (data != null) {
                        send(data)
                        if (repeat) job = scope.launch {
                            delay(380)
                            while (isActive) { send(data); delay(55) }
                        }
                    }
                    waitForUpOrCancellation()
                    job?.cancel()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SnyggIcon(imageVector = icon)
                if (label != null) SnyggText(FlorisImeUi.SmartbarActionTileText.elementName, text = label)
            }
        }
    }

    SnyggColumn(FlorisImeUi.ClipboardContent.elementName, modifier = modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight())) {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName, modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically) {
            val size = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT }, modifier = size) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggText(FlorisImeUi.ClipboardHeaderText.elementName, modifier = Modifier.weight(1f),
                text = if (ru) "Редактирование текста" else "Text editing")
        }
        val rowModifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Tile(Icons.Default.FirstPage, if (ru) "Начало" else "Home", TextKeyData.MOVE_START_OF_LINE)
            Tile(Icons.Default.KeyboardArrowUp, null, TextKeyData.ARROW_UP, repeat = true)
            Tile(Icons.Default.LastPage, if (ru) "Конец" else "End", TextKeyData.MOVE_END_OF_LINE)
            Tile(Icons.Default.ContentCopy, if (ru) "Копировать" else "Copy", TextKeyData.CLIPBOARD_COPY, weight = 1.3f)
        }
        Row(rowModifier) {
            Tile(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, TextKeyData.ARROW_LEFT, repeat = true)
            Tile(Icons.Default.TextRotationNone, if (ru) "Выделить" else "Select", active = selecting, onTap = {
                send(TextKeyData.CLIPBOARD_SELECT); selecting = keyboardManager.activeState.isManualSelectionMode
            })
            Tile(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, TextKeyData.ARROW_RIGHT, repeat = true)
            Tile(Icons.Default.ContentCut, if (ru) "Вырезать" else "Cut", TextKeyData.CLIPBOARD_CUT, weight = 1.3f)
        }
        Row(rowModifier) {
            Tile(Icons.Default.SelectAll, if (ru) "Всё" else "All", TextKeyData.CLIPBOARD_SELECT_ALL)
            Tile(Icons.Default.KeyboardArrowDown, null, TextKeyData.ARROW_DOWN, repeat = true)
            Tile(Icons.AutoMirrored.Filled.Backspace, if (ru) "Слово" else "Word", TextKeyData.DELETE_WORD, repeat = true)
            Tile(Icons.Default.ContentPaste, if (ru) "Вставить" else "Paste", TextKeyData.CLIPBOARD_PASTE, weight = 1.3f)
        }
        Row(rowModifier) {
            Tile(Icons.AutoMirrored.Filled.Undo, if (ru) "Отменить" else "Undo", TextKeyData.UNDO)
            Tile(Icons.AutoMirrored.Filled.Redo, if (ru) "Повторить" else "Redo", TextKeyData.REDO)
            Tile(Icons.AutoMirrored.Filled.ArrowBack, if (ru) "Буквы" else "ABC", weight = 1.3f + 1f, onTap = {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            })
        }
    }
}
