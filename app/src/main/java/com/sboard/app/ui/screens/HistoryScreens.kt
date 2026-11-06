package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.data.HistoryEntry
import com.sboard.app.data.VaultRepository
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun reasonLabel(reason: String) = when (reason) {
    "edit" -> tr("Изменение")
    "delete" -> tr("Перед удалением")
    "conflict" -> tr("Конфликт синхронизации")
    "restore" -> tr("Перед восстановлением")
    else -> reason
}

@Composable
fun ItemHistoryScreen(nav: Navigator, id: String) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var pendingRestore by remember { mutableStateOf<HistoryEntry?>(null) }
    LaunchedEffect(id) { entries = repo.history(id) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("История изменений"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { Text(tr("Предыдущие версии хранятся только на этом устройстве в зашифрованном виде (до 15 на запись)."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
            val list = entries
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Rounded.History, tr("Истории пока нет"), tr("Версии появятся после изменения записи.")) }
            items(list.orEmpty(), key = { it.historyId }) { entry -> HistoryCard(entry) { pendingRestore = entry } }
        }
    }
    pendingRestore?.let { entry ->
        ConfirmDialog(tr("Восстановить эту версию?"), tr("Текущая версия сохранится в истории."), tr("Восстановить"),
            onConfirm = { pendingRestore = null; scope.launch { repo.restoreFromHistory(entry.historyId); nav.pop() } },
            onDismiss = { pendingRestore = null })
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onRestore: () -> Unit) {
    SBCard {
        ListRow(entry.payload.title, reasonLabel(entry.reason) + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt)),
            leading = { ItemBadge(entry.payload.type, entry.payload.title, 38.dp) }, trailing = null)
        entry.payload.login?.let { FieldRow(tr("Логин"), it.username); FieldRow(tr("Пароль"), it.password, sensitive = true) }
        if (entry.payload.notes.isNotBlank()) FieldRow(tr("Заметки"), entry.payload.notes, sensitive = true)
        TextButton(onClick = onRestore) { Text(tr("Восстановить"), color = palette.accent) }
    }
}

@Composable
fun TrashScreen(nav: Navigator) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Pair<String, HistoryEntry>>?>(null) }
    LaunchedEffect(Unit) { entries = repo.recentlyDeleted() }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Недавно удалённые"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            val list = entries
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Rounded.DeleteOutline, tr("Корзина пуста"), tr("Удалённые записи можно вернуть отсюда.")) }
            items(list.orEmpty(), key = { it.second.historyId }) { (itemId, entry) ->
                SBCard {
                    ListRow(entry.payload.title, tr("Удалено {0}", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt))),
                        leading = { ItemBadge(entry.payload.type, entry.payload.title, 38.dp) }, trailing = null)
                    TextButton(onClick = {
                        scope.launch { repo.restoreFromHistory(entry.historyId); nav.replace(Route.ItemDetail(itemId)) }
                    }) { Text(tr("Восстановить"), color = palette.accent) }
                }
            }
        }
    }
}
