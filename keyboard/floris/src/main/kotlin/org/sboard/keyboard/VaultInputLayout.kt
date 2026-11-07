package org.sboard.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
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
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * sBoard Smart Bar panel: inserts logins, emails, phones, names, addresses, OTP codes and
 * passwords from the Vault into the focused field. Sensitive values require an explicit
 * confirmation and, when configured, biometric authentication.
 */
@Composable
fun VaultInputLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val provider = SBoardVaultBridge.provider
    val scope = rememberCoroutineScope()
    val unlocked by (provider?.unlocked ?: remember { MutableStateFlow(false) }).collectAsState()
    val targetPackage = editorInstance.activeInfo.packageName
    var entries by remember { mutableStateOf<List<KeyboardVaultEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Pair<KeyboardVaultEntry, KeyboardVaultField>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun t(key: String) = provider?.text(key) ?: key

    LaunchedEffect(unlocked, targetPackage) {
        loading = true
        entries = if (unlocked && provider != null) runCatching { provider.entries(targetPackage, "") }.getOrDefault(emptyList()) else emptyList()
        loading = false
    }

    fun close() { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT }

    fun insert(entry: KeyboardVaultEntry, field: KeyboardVaultField) {
        val p = provider ?: return
        if (field.sensitive && p.requiresStrongConfirmation()) {
            p.requestConfirmedInsert(context, entry.id, field.key, targetPackage)
            close()
            return
        }
        scope.launch {
            val value = p.value(entry.id, field.key)
            if (value.isNullOrEmpty()) { message = t("empty_value"); return@launch }
            editorInstance.commitText(value)
            close()
        }
    }

    SnyggColumn(FlorisImeUi.ClipboardContent.elementName,
        modifier = modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight()),
    ) {
        SnyggRow(FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sizeModifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f)
            SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { close() }, modifier = sizeModifier) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggText(FlorisImeUi.ClipboardHeaderText.elementName, modifier = Modifier.weight(1f), text = "sPass")
            if (unlocked && provider != null) {
                SnyggIconButton(FlorisImeUi.ClipboardHeaderButton.elementName, onClick = { provider.lock() }, modifier = sizeModifier) {
                    SnyggIcon(imageVector = Icons.Default.Lock)
                }
            }
        }
        when {
            provider == null || !provider.enabled -> Centered(t("disabled_title"), t("disabled_message"), null) {}
            !unlocked -> Centered(t("locked_title"), t("locked_message"), t("unlock")) { provider.requestUnlock(context) }
            loading -> Centered(t("loading"), "", null) {}
            entries.isEmpty() -> Centered(t("empty_title"), t("empty_message"), null) {}
            else -> {
                val pending = confirm
                if (pending != null) {
                    SnyggColumn(FlorisImeUi.ClipboardClearAllDialog.elementName, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        SnyggText(FlorisImeUi.ClipboardClearAllDialogMessage.elementName,
                            text = t("confirm_insert").replace("{0}", pending.second.label).replace("{1}", pending.first.title))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            SnyggButton(FlorisImeUi.ClipboardClearAllDialogButton.elementName, onClick = { confirm = null }) {
                                SnyggText(text = t("cancel"))
                            }
                            SnyggButton(FlorisImeUi.ClipboardClearAllDialogButton.elementName, onClick = {
                                confirm = null; insert(pending.first, pending.second)
                            }) { SnyggText(text = t("insert")) }
                        }
                    }
                }
                message?.let { SnyggText(FlorisImeUi.ClipboardHistoryLockedMessage.elementName, text = it) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        SnyggColumn(FlorisImeUi.ClipboardItem.elementName, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SnyggIcon(imageVector = if (entry.suggested) Icons.Default.Key else Icons.Default.LockOpen)
                                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                    SnyggText(text = entry.title)
                                    if (entry.subtitle.isNotBlank()) SnyggText(FlorisImeUi.ClipboardItemTimestamp.elementName, text = entry.subtitle)
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(entry.fields, key = { it.key }) { field ->
                                    SnyggChip(FlorisImeUi.ClipboardFilterChip.elementName,
                                        onClick = { if (field.sensitive) confirm = entry to field else insert(entry, field) },
                                        imageVector = if (field.sensitive) Icons.Default.Visibility else null,
                                        text = field.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(title: String, message: String, action: String?, onAction: () -> Unit) {
    SnyggColumn(FlorisImeUi.ClipboardContent.elementName,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnyggText(FlorisImeUi.ClipboardHistoryLockedTitle.elementName, text = title)
        if (message.isNotBlank()) SnyggText(FlorisImeUi.ClipboardHistoryLockedMessage.elementName, text = message)
        if (action != null) {
            SnyggButton(FlorisImeUi.ClipboardHistoryDisabledButton.elementName, onClick = onAction) { SnyggText(text = action) }
        }
    }
}
