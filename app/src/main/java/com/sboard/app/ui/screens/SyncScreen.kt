package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.sync.*
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.components.*
import com.sboard.app.ui.rememberGoogleAuthorizer
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class PendingAction { CONNECT, SYNC, REPLACE_CLOUD, DELETE_CLOUD, JOIN }

@Composable
fun SyncScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { SyncEngine(context) }
    val account by GoogleAccount.account.collectAsState()
    val status by SyncStatus.snapshot.collectAsState()
    val pending by VaultRepository.get(context).pendingSyncCount.collectAsState(0)
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var action by remember { mutableStateOf<PendingAction?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var joinCloud by remember { mutableStateOf<SyncEngine.CloudVault?>(null) }
    var joinSecret by remember { mutableStateOf("") }

    fun report(outcome: SyncEngine.Outcome) {
        message = when (outcome) {
            is SyncEngine.Outcome.Success -> tr("Синхронизировано: получено {0}, отправлено {1}", outcome.pulled, outcome.pushed) +
                (if (outcome.conflicts > 0) " · " + tr("конфликтов решено: {0}", outcome.conflicts) else "") to Tone.SUCCESS
            SyncEngine.Outcome.Locked -> tr("Хранилище заблокировано") to Tone.WARNING
            SyncEngine.Outcome.NeedsAuth -> tr("Нужно снова войти в Google") to Tone.WARNING
            is SyncEngine.Outcome.Retry -> outcome.message to Tone.WARNING
            is SyncEngine.Outcome.Failure -> outcome.message to Tone.DANGER
        }
    }

    val authorize = rememberGoogleAuthorizer(onToken = { token ->
        val requested = action ?: PendingAction.SYNC
        action = null
        scope.launch {
            busy = true; message = null
            try {
                when (requested) {
                    PendingAction.CONNECT, PendingAction.SYNC -> {
                        engine.refreshProfile(token)
                        if (GoogleAccount.account.value == null) GoogleAccount.markConnected(context, "", "")
                        val outcome = engine.sync(token)
                        report(outcome)
                        SyncScheduler.ensurePeriodic(context)
                    }
                    PendingAction.REPLACE_CLOUD -> { engine.replaceCloudWithLocal(token); message = tr("Облачная копия заменена данными этого устройства") to Tone.SUCCESS }
                    PendingAction.DELETE_CLOUD -> { engine.deleteCloudCopy(token); message = tr("Облачная копия удалена") to Tone.SUCCESS }
                    PendingAction.JOIN -> { joinCloud = engine.inspectCloud(token); if (joinCloud == null) message = tr("Облачная копия не найдена") to Tone.WARNING }
                }
            } catch (e: Exception) { message = (e.message ?: tr("Ошибка синхронизации")) to Tone.DANGER }
            busy = false
        }
    }, onError = { action = null; message = it to Tone.DANGER })

    fun run(a: PendingAction) { action = a; authorize() }

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Синхронизация"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item {
                SBCard {
                    ListRow(account?.name?.takeIf { it.isNotBlank() } ?: if (account != null) tr("Google Drive") else tr("Google Drive не подключён"),
                        account?.email?.takeIf { it.isNotBlank() } ?: tr("Файл sboard_backup.enc в скрытой папке приложения"), icon = Icons.Rounded.CloudSync, trailing = null)
                    if (account == null) PrimaryButton(tr("Войти через Google"), { run(PendingAction.CONNECT) }, loading = busy, icon = Icons.AutoMirrored.Rounded.Login)
                    else {
                        PrimaryButton(tr("Синхронизировать сейчас"), { run(PendingAction.SYNC) }, loading = busy || status.state == SyncState.RUNNING, icon = Icons.Rounded.Sync)
                    }
                }
            }
            message?.let { (text, tone) -> item { Banner(text, tone) } }
            if (account == null) item {
                val sha1 = remember { GoogleAccount.signingSha1(context) }
                SBCard {
                    Text(tr("Если вход в Google не работает"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                    Text(tr("Владелец приложения должен один раз создать в Google Cloud Console OAuth-клиент типа Android с этими данными и включить Google Drive API:"),
                        style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    FieldRow(tr("Имя пакета"), context.packageName, monospace = true)
                    FieldRow(tr("SHA-1 сертификата"), sha1, monospace = true)
                    Text(tr("Ошибка «DEVELOPER_ERROR (10)» означает, что такой клиент не создан или SHA-1 не совпадает. Пока аккаунт в режиме Testing, добавьте свой Google-аккаунт в Test users."),
                        style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                }
            }
            if (account != null) {
                item {
                    SBCard {
                        val (label, color) = when (status.state) {
                            SyncState.SUCCESS -> tr("Синхронизировано") to palette.success
                            SyncState.RUNNING -> tr("Выполняется…") to palette.accent
                            SyncState.PENDING_LOCKED -> tr("Ожидает разблокировки") to palette.warning
                            SyncState.NEEDS_AUTH -> tr("Нужен вход в Google") to palette.warning
                            SyncState.OFFLINE -> tr("Нет связи, повторим") to palette.warning
                            SyncState.VAULT_MISMATCH -> tr("В облаке другое хранилище") to palette.danger
                            SyncState.CORRUPTED -> tr("Облачная копия повреждена") to palette.danger
                            SyncState.REVOKED -> tr("Устройство отключено") to palette.danger
                            SyncState.ERROR -> tr("Ошибка") to palette.danger
                            SyncState.IDLE -> tr("Ещё не выполнялась") to palette.textFaint
                        }
                        StatusLine(tr("Состояние"), label, color)
                        StatusLine(tr("Последняя синхронизация"), if (status.lastSuccessAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastSuccessAt)) else "—", palette.accent)
                        StatusLine(tr("Ожидают отправки"), "$pending", if (pending == 0) palette.success else palette.warning)
                        StatusLine(tr("Записей в облаке"), "${status.remoteItems}", palette.accent)
                        StatusLine(tr("Версия облачной копии"), "${status.remoteGeneration}", palette.accent)
                        if (status.conflicts > 0) StatusLine(tr("Решено конфликтов"), "${status.conflicts}", palette.warning)
                        status.message?.takeIf { status.state != SyncState.SUCCESS }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                    }
                }
                if (status.state == SyncState.VAULT_MISMATCH) item {
                    SBCard {
                        Text(tr("В аккаунте Google уже есть другое хранилище sBoard (например, созданное на другом телефоне)."), style = MaterialTheme.typography.bodyMedium, color = palette.text)
                        PrimaryButton(tr("Объединить с облачным хранилищем"), { run(PendingAction.JOIN) }, icon = Icons.AutoMirrored.Rounded.MergeType)
                        Text(tr("Записи этого устройства будут перешифрованы ключом облачного хранилища. После этого используйте мастер-пароль облачного хранилища."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                        SecondaryButton(tr("Заменить облачную копию этим устройством"), { confirmReplace = true }, danger = true)
                    }
                }
                if (status.state == SyncState.CORRUPTED) item {
                    SBCard {
                        Text(tr("Файл в Google Drive не прошёл проверку целостности. sBoard не будет его перезаписывать автоматически."), style = MaterialTheme.typography.bodyMedium, color = palette.text)
                        SecondaryButton(tr("Заменить облачную копию этим устройством"), { confirmReplace = true }, danger = true)
                    }
                }
                item { SectionTitle(tr("Настройки")) }
                item {
                    SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        SwitchRow(tr("Автоматическая синхронизация"), tr("После изменений, при запуске и каждый час"), AppSettings.autoSync.value, Icons.Rounded.Autorenew) {
                            AppSettings.setAutoSync(it); SyncScheduler.ensurePeriodic(context)
                        }
                        SwitchRow(tr("Только по Wi-Fi"), tr("Не использовать мобильный интернет"), AppSettings.syncOnUnmeteredOnly.value, Icons.Rounded.Wifi) {
                            AppSettings.setSyncOnUnmeteredOnly(it); SyncScheduler.ensurePeriodic(context)
                        }
                    }
                }
                item {
                    SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        ListRow(tr("Заменить облачную копию"), tr("Загрузить данные этого устройства поверх облака"), icon = Icons.Rounded.CloudUpload, onClick = { confirmReplace = true })
                        ListRow(tr("Удалить облачную копию"), tr("Файл будет удалён из Google Drive"), icon = Icons.Rounded.CloudOff, onClick = { confirmDelete = true })
                        ListRow(tr("Выйти из Google"), tr("Синхронизация остановится, локальные данные сохранятся"), icon = Icons.AutoMirrored.Rounded.Logout, titleColor = palette.danger, onClick = { confirmDisconnect = true })
                    }
                }
            }
            item {
                SBCard {
                    Text(tr("Как это работает"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                    listOf(
                        tr("Все данные шифруются на устройстве до отправки. Google получает только зашифрованный файл."),
                        tr("Используется один файл sboard_backup.enc в скрытой папке приложения Google Drive — он обновляется на месте."),
                        tr("Добавление, изменение и удаление записей объединяются с изменениями других устройств. При конфликте сохраняется новая версия, а старая попадает в историю."),
                        tr("Без сети изменения ждут подключения и отправляются автоматически."),
                        tr("На новом устройстве войдите в тот же аккаунт Google и восстановите хранилище мастер-паролем."),
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                }
            }
        }
    }
    if (confirmReplace) ConfirmDialog(tr("Заменить облачную копию?"), tr("Данные в Google Drive будут заменены данными этого устройства. Другие устройства потребуют повторного подключения."),
        tr("Заменить"), danger = true, onConfirm = { confirmReplace = false; run(PendingAction.REPLACE_CLOUD) }, onDismiss = { confirmReplace = false })
    if (confirmDelete) ConfirmDialog(tr("Удалить облачную копию?"), tr("Локальные данные останутся. Другие устройства больше не смогут синхронизироваться."),
        tr("Удалить"), danger = true, onConfirm = { confirmDelete = false; run(PendingAction.DELETE_CLOUD) }, onDismiss = { confirmDelete = false })
    if (confirmDisconnect) ConfirmDialog(tr("Выйти из Google?"), tr("sBoard отзовёт доступ к Google Drive. Облачная копия и локальные данные сохранятся."),
        tr("Выйти"), danger = true, onConfirm = {
            confirmDisconnect = false
            scope.launch { GoogleAccount.revoke(context); SyncScheduler.cancelAll(context); SyncStatus.reset(context) }
        }, onDismiss = { confirmDisconnect = false })
    joinCloud?.let { cloud ->
        AlertDialog(onDismissRequest = { if (!busy) { joinCloud = null; joinSecret = "" } }, containerColor = palette.surface,
            title = { Text(tr("Объединить хранилища")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Введите мастер-пароль облачного хранилища (или его Recovery Key)."), color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
                    SBTextField(joinSecret, { joinSecret = it }, tr("Мастер-пароль облака"), password = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && joinSecret.isNotEmpty(), onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val moved = engine.joinCloudVault(cloud, joinSecret)
                            joinSecret = ""; joinCloud = null
                            report(engine.sync())
                            message = tr("Объединено записей: {0}. Теперь используйте мастер-пароль облачного хранилища.", moved) to Tone.SUCCESS
                        } catch (e: Exception) { message = (e.message ?: tr("Не удалось объединить")) to Tone.DANGER }
                        busy = false
                    }
                }) { Text(tr("Объединить"), color = palette.accent) }
            },
            dismissButton = { TextButton(onClick = { joinCloud = null; joinSecret = "" }) { Text(tr("Отмена"), color = palette.textMuted) } })
    }
}
