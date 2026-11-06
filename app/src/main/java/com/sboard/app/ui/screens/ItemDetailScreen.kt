package com.sboard.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.otp.TotpCodes
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ItemDetailScreen(nav: Navigator, id: String) {
    val context = LocalContext.current
    val repo = remember { VaultRepository.get(context) }
    val items by repo.items.collectAsState()
    val item = items.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val now = rememberNow()

    Column(Modifier.fillMaxSize()) {
        TopBar(item?.title ?: "", onBack = { nav.pop() }) {
            if (item != null) {
                IconButton(onClick = { scope.launch { repo.setFavorite(id, !item.favorite) } }) {
                    Icon(if (item.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, tr("Избранное"), tint = if (item.favorite) palette.accent else palette.textMuted)
                }
                if (item.type != ItemType.PASSKEY) IconButton(onClick = { nav.push(Route.ItemEdit(id, item.type)) }) { Icon(Icons.Rounded.Edit, tr("Изменить"), tint = palette.text) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr("Ещё"), tint = palette.text) }
                    DropdownMenu(menu, { menu = false }, containerColor = palette.surfaceHigh) {
                        DropdownMenuItem(text = { Text(tr("История изменений")) }, leadingIcon = { Icon(Icons.Rounded.History, null) },
                            onClick = { menu = false; nav.push(Route.ItemHistory(id)) })
                        DropdownMenuItem(text = { Text(tr("Удалить"), color = palette.danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = palette.danger) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
        if (item == null) {
            EmptyState(Icons.Rounded.SearchOff, tr("Запись не найдена"), tr("Возможно, она была удалена на другом устройстве."))
            return@Column
        }
        val p = item.payload
        ScreenList(bottomPadding = 40.dp) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ItemBadge(item.type, item.title, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall, color = palette.text)
                        Text(item.type.label() + " · " + tr("изменено {0}", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.updatedAt))),
                            style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    }
                }
            }
            val totp = p.totp ?: p.login?.totp
            if (totp != null && totp.secret.isNotBlank()) item {
                val code = TotpCodes.current(totp, now)
                SBCard(onClick = { code?.let { SensitiveClipboard.copy(context, it.value, true) } }) {
                    Text(tr("Одноразовый код"), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
                    if (code == null) Text(tr("Неверный секретный ключ"), color = palette.danger)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(TotpCodes.format(code.value), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, color = palette.accent, modifier = Modifier.weight(1f))
                        CountdownRing(code.secondsLeft, code.period, 36.dp)
                    }
                    Text(tr("Нажмите, чтобы скопировать"), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                }
            }
            item {
                SBCard {
                    when (item.type) {
                        ItemType.LOGIN -> p.login?.let { l ->
                            FieldRow(tr("Логин"), l.username)
                            FieldRow(tr("Пароль"), l.password, sensitive = true)
                            if (l.password.isNotEmpty()) {
                                val s = remember(l.password) { PasswordStrength.evaluate(l.password) }
                                StrengthBar(s.score, s.label)
                            }
                            l.urls.forEach { url ->
                                FieldRow(tr("Сайт"), url, onOpen = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.contains("://")) url else "https://$url"))) }
                                })
                            }
                            l.packageNames.forEach { FieldRow(tr("Приложение"), it) }
                        }
                        ItemType.CARD -> p.card?.let { c ->
                            FieldRow(tr("Владелец"), c.cardholder)
                            FieldRow(tr("Номер карты"), c.number.chunked(4).joinToString(" "), sensitive = true, monospace = true)
                            FieldRow(tr("Срок действия"), listOf(c.expMonth, c.expYear).filter { it.isNotBlank() }.joinToString("/"))
                            FieldRow("CVV", c.cvv, sensitive = true)
                            FieldRow("PIN", c.pin, sensitive = true)
                            FieldRow(tr("Платёжная система"), c.brand)
                        }
                        ItemType.IDENTITY -> p.identity?.let { i ->
                            FieldRow(tr("Имя"), i.fullName)
                            FieldRow("Email", i.email)
                            FieldRow(tr("Телефон"), i.phone)
                            FieldRow(tr("Компания"), i.company)
                            FieldRow(tr("Адрес"), i.fullAddress)
                            FieldRow(tr("Дата рождения"), i.birthDate)
                            FieldRow(tr("Номер документа"), i.documentNumber, sensitive = true)
                        }
                        ItemType.TOTP -> p.totp?.let { t ->
                            FieldRow(tr("Сервис"), t.issuer)
                            FieldRow(tr("Аккаунт"), t.account)
                            FieldRow(tr("Секретный ключ"), t.secret, sensitive = true, monospace = true)
                            FieldRow(tr("Параметры"), "${t.algorithm} · ${t.digits} · ${t.period}s")
                        }
                        ItemType.PASSKEY -> p.passkey?.let { k ->
                            FieldRow(tr("Сайт"), k.rpId)
                            FieldRow(tr("Пользователь"), k.userName.ifBlank { k.userDisplayName })
                            FieldRow(tr("Последнее использование"), if (k.lastUsedAt > 0) DateFormat.getDateTimeInstance().format(Date(k.lastUsedAt)) else tr("Ещё не использовался"))
                            Text(tr("Закрытый ключ хранится только в зашифрованном виде и используется системным диспетчером учётных данных Android после вашего подтверждения."),
                                style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                        }
                        ItemType.NOTE -> Unit
                    }
                    if (p.notes.isNotBlank()) FieldRow(tr("Заметки"), p.notes, sensitive = item.type == ItemType.NOTE)
                    p.customFields.forEach { f -> FieldRow(f.name, f.value, sensitive = f.hidden) }
                }
            }
            item {
                Text(tr("Создано {0}", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAt))),
                    style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
    }
    if (confirmDelete) ConfirmDialog(
        tr("Удалить запись?"), tr("Запись будет удалена на всех синхронизированных устройствах. Её можно восстановить из «Недавно удалённых» на этом устройстве."),
        tr("Удалить"), danger = true,
        onConfirm = { confirmDelete = false; scope.launch { repo.delete(id); nav.pop() } },
        onDismiss = { confirmDelete = false },
    )
}
