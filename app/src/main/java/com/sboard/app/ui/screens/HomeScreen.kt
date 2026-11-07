package com.sboard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sboard.app.core.i18n.tr
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.keyboard.SystemIntegration
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncState
import com.sboard.app.feature.sync.SyncStatus
import com.sboard.app.feature.tools.PasswordHealth
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.Tab
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val items by repo.items.collectAsState()
    val pending by repo.pendingSyncCount.collectAsState(0)
    val sync by SyncStatus.snapshot.collectAsState()
    val account by GoogleAccount.account.collectAsState()
    var keyboardOn by remember { mutableStateOf(true) }
    var autofillOn by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf<PasswordHealth.Report?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            keyboardOn = SystemIntegration.isKeyboardEnabled(context)
            autofillOn = SystemIntegration.isAutofillEnabled(context)
        }
    }
    LaunchedEffect(items) { health = withContext(Dispatchers.Default) { PasswordHealth.analyze(items) } }

    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> tr("Доброе утро")
        in 12..17 -> tr("Добрый день")
        in 18..22 -> tr("Добрый вечер")
        else -> tr("Доброй ночи")
    }

    ScreenList(Modifier.statusBarsPadding()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("sBoard", style = MaterialTheme.typography.labelLarge, color = palette.accent)
                    Text(account?.name?.takeIf { it.isNotBlank() }?.let { "$greeting, ${it.substringBefore(' ')}" } ?: greeting,
                        style = MaterialTheme.typography.headlineMedium, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(42.dp).clip(CircleShape).background(palette.surfaceHigh).clickable { nav.select(Tab.PROFILE) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, tr("Профиль"), tint = palette.accent)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(SBoardShapes.medium).background(palette.surface).clickable { nav.openVault() }.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = palette.textMuted)
                Spacer(Modifier.width(10.dp))
                Text(tr("Поиск по хранилищу"), color = palette.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SBCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Состояние защиты"), style = MaterialTheme.typography.titleMedium, color = palette.text)
                        Text(tr("Записей в хранилище: {0}", items.size), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    }
                    IconBadge(Icons.Rounded.Shield)
                }
                Divider()
                StatusLine(tr("Хранилище"), tr("Разблокировано"), palette.success)
                StatusLine("Google Drive", when {
                    account == null -> tr("Не подключён")
                    sync.state == SyncState.RUNNING -> tr("Синхронизация…")
                    pending > 0 -> tr("Ожидает отправки: {0}", pending)
                    sync.state == SyncState.SUCCESS -> tr("Синхронизировано")
                    sync.state == SyncState.OFFLINE -> tr("Нет сети, повторим")
                    else -> tr("Требует внимания")
                }, when {
                    account == null -> palette.textFaint
                    sync.state == SyncState.SUCCESS && pending == 0 -> palette.success
                    sync.state == SyncState.RUNNING || sync.state == SyncState.OFFLINE || pending > 0 -> palette.warning
                    else -> palette.danger
                })
                health?.let { h ->
                    if (h.total > 0) StatusLine(tr("Надёжность паролей"), "${h.score}%", if (h.weak.isEmpty() && h.reused.isEmpty()) palette.success else palette.warning)
                }
            }
        }
        if (!keyboardOn || !autofillOn || account == null) {
            item { SectionTitle(tr("Завершите настройку")) }
            if (!keyboardOn) item {
                SBCard(onClick = { SystemIntegration.openKeyboardSettings(context) }) {
                    ListRow(tr("Включите клавиатуру sBoard"), tr("Раскладки RU/EN, подсказки и панель sBoard Pass"), icon = Icons.Rounded.Keyboard)
                }
            }
            if (!autofillOn && SystemIntegration.isAutofillSupported(context)) item {
                SBCard(onClick = { SystemIntegration.openAutofillSettings(context) }) {
                    ListRow(tr("Включите автозаполнение"), tr("Подставляйте логины и пароли в приложения и браузеры"), icon = Icons.Rounded.AutoAwesome)
                }
            }
            if (account == null) item {
                SBCard(onClick = { nav.select(Tab.PROFILE); nav.push(Route.Sync) }) {
                    ListRow(tr("Подключите Google Drive"), tr("Зашифрованная синхронизация между устройствами"), icon = Icons.Rounded.CloudSync)
                }
            }
        }
        item { SectionTitle(tr("Быстро добавить")) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(ItemType.LOGIN, ItemType.CARD, ItemType.NOTE, ItemType.TOTP).forEach { type ->
                    Column(
                        Modifier.weight(1f).clip(SBoardShapes.large).background(palette.surface)
                            .clickable { nav.push(Route.ItemEdit(null, type)) }.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ItemBadge(type, "", size = 38.dp)
                        Text(type.label(), style = MaterialTheme.typography.labelMedium, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val favorites = items.filter { it.favorite }
        if (favorites.isNotEmpty()) {
            item { SectionTitle(tr("Избранное"), action = tr("Все"), onAction = { nav.openVault("favorites") }) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favorites, key = { it.id }) { item ->
                        Column(
                            Modifier.width(132.dp).clip(SBoardShapes.large).background(palette.surface)
                                .clickable { nav.push(Route.ItemDetail(item.id)) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ItemBadge(item.type, item.title, size = 36.dp)
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        val recent = items.sortedByDescending { it.updatedAt }.take(5)
        item { SectionTitle(tr("Недавние"), action = if (recent.isNotEmpty()) tr("Хранилище") else null, onAction = { nav.openVault() }) }
        if (recent.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.Lock, tr("Хранилище пустое"), tr("Добавьте первый пароль, карту или заметку — они будут зашифрованы на устройстве."),
                    tr("Добавить пароль")) { nav.push(Route.ItemEdit(null, ItemType.LOGIN)) }
            }
        } else item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                recent.forEach { item ->
                    ListRow(item.title, item.subtitle(), leading = { ItemBadge(item.type, item.title, 38.dp) }, onClick = { nav.push(Route.ItemDetail(item.id)) })
                }
            }
        }
    }
}
