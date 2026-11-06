package com.sboard.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItem
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.otp.TotpCodes
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(nav: Navigator) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val items by repo.items.collectAsState()
    val corrupted by repo.corruptedCount.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var copiedId by remember { mutableStateOf<String?>(null) }
    val now = rememberNow()
    val filters = listOf("all" to tr("Все"), "favorites" to tr("Избранное")) +
        ItemType.entries.map { it.name to it.pluralLabel() }
    val query = nav.vaultQuery.trim().lowercase()
    val visible = items.filter { item ->
        (nav.vaultFilter == "all" || (nav.vaultFilter == "favorites" && item.favorite) || item.type.name == nav.vaultFilter) &&
            (query.isEmpty() || item.payload.searchText().contains(query))
    }

    Box(Modifier.fillMaxSize()) {
        ScreenList(Modifier.statusBarsPadding()) {
            item {
                ScreenHeader(tr("Хранилище"), tr("Записей: {0}", items.size)) {
                    IconButton(onClick = { VaultSessionProvider.session.lock() }) { Icon(Icons.Rounded.Lock, tr("Заблокировать"), tint = palette.textMuted) }
                    IconButton(onClick = { nav.push(Route.Trash) }) { Icon(Icons.Rounded.DeleteOutline, tr("Недавно удалённые"), tint = palette.textMuted) }
                }
            }
            item {
                SBTextField(nav.vaultQuery, { nav.vaultQuery = it }, tr("Поиск"),
                    trailing = { if (nav.vaultQuery.isNotEmpty()) IconButton(onClick = { nav.vaultQuery = "" }) { Icon(Icons.Rounded.Close, tr("Очистить"), tint = palette.textMuted) } else Icon(Icons.Rounded.Search, null, tint = palette.textMuted) })
            }
            item { FilterChipRow(filters, nav.vaultFilter) { nav.vaultFilter = it } }
            if (corrupted > 0) item { Banner(tr("Не удалось расшифровать записей: {0}. Они не изменены и не отправляются в облако.", corrupted), Tone.WARNING) }
            if (visible.isEmpty()) {
                item {
                    if (items.isEmpty()) EmptyState(Icons.Rounded.Lock, tr("Здесь пока пусто"), tr("Пароли, карты, заметки, коды 2FA и passkeys будут храниться в зашифрованном виде."), tr("Добавить запись")) { showAdd = true }
                    else EmptyState(Icons.Rounded.SearchOff, tr("Ничего не найдено"), tr("Попробуйте другой запрос или фильтр."))
                }
            }
            items(visible, key = { it.id }) { item ->
                Box(Modifier.animateItem()) {
                    VaultRow(item, now, copied = copiedId == item.id,
                        onCopyCode = { code -> SensitiveClipboard.copy(context, code, true); copiedId = item.id },
                        onClick = { nav.push(Route.ItemDetail(item.id)) })
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            containerColor = palette.accent, contentColor = palette.onAccent, shape = SBoardShapes.large,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 96.dp),
        ) { Icon(Icons.Rounded.Add, tr("Добавить")) }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = palette.surface) {
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Новая запись"), style = MaterialTheme.typography.titleLarge, color = palette.text, modifier = Modifier.padding(bottom = 8.dp))
                ItemType.entries.filter { it != ItemType.PASSKEY }.forEach { type ->
                    ListRow(type.label(), when (type) {
                        ItemType.LOGIN -> tr("Логин, пароль, сайт и код 2FA")
                        ItemType.CARD -> tr("Номер, срок действия, CVV и PIN")
                        ItemType.IDENTITY -> tr("Имя, email, телефон, адрес, документы")
                        ItemType.NOTE -> tr("Любой текст под шифрованием")
                        ItemType.TOTP -> tr("Одноразовые коды по QR или ключу")
                        ItemType.PASSKEY -> ""
                    }, leading = { ItemBadge(type, "", 38.dp) }, onClick = { showAdd = false; nav.push(Route.ItemEdit(null, type)) })
                }
                Text(tr("Passkeys создаются автоматически, когда сайт или приложение предлагает войти без пароля."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textFaint, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun VaultRow(item: VaultItem, now: Long, copied: Boolean, onCopyCode: (String) -> Unit, onClick: () -> Unit) {
    val totp = item.payload.totp ?: item.payload.login?.totp
    Row(
        Modifier.fillMaxWidth().clip(SBoardShapes.large).background(palette.surface).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemBadge(item.type, item.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.favorite) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Star, null, tint = palette.accent, modifier = Modifier.size(14.dp)) }
            }
            Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (totp != null && totp.secret.isNotBlank() && item.type == ItemType.TOTP) {
            val code = TotpCodes.current(totp, now)
            if (code != null) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.clip(SBoardShapes.small).clickable { onCopyCode(code.value) }.padding(4.dp)) {
                    Text(if (copied) tr("Скопировано") else TotpCodes.format(code.value), style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace, color = palette.accent)
                }
                Spacer(Modifier.width(6.dp))
                CountdownRing(code.secondsLeft, code.period)
            }
        }
    }
}
