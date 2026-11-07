package com.sboard.app.ui.screens

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sboard.app.BuildConfig
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.DeviceIdentity
import com.sboard.app.data.VaultItem
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.keyboard.SystemIntegration
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncScheduler
import com.sboard.app.feature.sync.SyncStatus
import com.sboard.app.feature.tools.PasswordHealth
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.feature.vault.VaultWiper
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfileScreen(nav: Navigator) {
    val context = LocalContext.current
    val account by GoogleAccount.account.collectAsState()
    var autofillOn by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { autofillOn = SystemIntegration.isAutofillEnabled(context) } }
    ScreenList(Modifier.statusBarsPadding()) {
        item { ScreenHeader(tr("Профиль"), tr("Аккаунт, безопасность и настройки")) }
        item {
            SBCard(onClick = { nav.push(Route.Sync) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.AccountCircle, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account?.name?.takeIf { it.isNotBlank() } ?: if (account != null) tr("Аккаунт Google") else tr("Локальный профиль"), style = MaterialTheme.typography.titleMedium, color = palette.text)
                        Text(account?.email?.takeIf { it.isNotBlank() } ?: if (account != null) tr("Google Drive подключён") else tr("Войдите через Google для синхронизации"), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = palette.textFaint)
                }
                Text(tr("Аккаунт Google нужен только для синхронизации. Он не открывает хранилище — для этого используется отдельный мастер-пароль."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
        item { SectionTitle(tr("Защита")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Безопасность"), tr("Мастер-пароль, биометрия, автоблокировка, Recovery Key"), icon = Icons.Rounded.Security, onClick = { nav.push(Route.Security) })
                ListRow(tr("Синхронизация"), tr("Google Drive · sboard_backup.enc"), icon = Icons.Rounded.CloudSync, onClick = { nav.push(Route.Sync) })
                ListRow(tr("Устройства"), tr("Где используется ваше хранилище"), icon = Icons.Rounded.Devices, onClick = { nav.push(Route.Devices) })
            }
        }
        item { SectionTitle(tr("Интеграция с Android")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Автозаполнение"), if (autofillOn) tr("sBoard выбран службой автозаполнения") else tr("Не включено — нажмите, чтобы выбрать sBoard"),
                    icon = Icons.Rounded.AutoAwesome, onClick = { SystemIntegration.openAutofillSettings(context) })
                if (SystemIntegration.passkeysSupported()) {
                    ListRow(tr("Passkeys и пароли"), tr("Включите sBoard в «Пароли, ключи доступа и аккаунты»"), icon = Icons.Rounded.Fingerprint,
                        onClick = { SystemIntegration.openCredentialProviderSettings(context) })
                } else {
                    ListRow(tr("Passkeys"), tr("Требуется Android 14 или новее"), icon = Icons.Rounded.Fingerprint, trailing = null)
                }
            }
        }
        item { SectionTitle(tr("Приложение")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Оформление и язык"), tr("Светлая и тёмная тема, русский и английский"), icon = Icons.Rounded.Palette, onClick = { nav.push(Route.Appearance) })
                ListRow(tr("О приложении"), "sBoard ${BuildConfig.VERSION_NAME}", icon = Icons.Rounded.Info, onClick = { nav.push(Route.About) })
            }
        }
        item { SecondaryButton(tr("Заблокировать сейчас"), { VaultSessionProvider.session.lock() }, icon = Icons.Rounded.Lock) }
    }
}

private val autoLockOptions get() = listOf(0 to tr("Сразу"), 60 to tr("1 мин"), 300 to tr("5 мин"), 900 to tr("15 мин"), 3600 to tr("1 час"), -1 to tr("Никогда"))
private val clipboardOptions get() = listOf(20 to "20 s", 45 to "45 s", 90 to "90 s", 0 to tr("Никогда"))

@Composable
fun SecurityScreen(nav: Navigator) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val keys = remember { VaultKeyManager(context) }
    val biometricEnabled by Biometrics.observeEnabled(context).collectAsState(false)
    var hasRecovery by remember { mutableStateOf(false) }
    var newRecovery by remember { mutableStateOf<String?>(null) }
    var showChange by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmRecoveryReplace by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var hasGoogleRecovery by remember { mutableStateOf(false) }
    var confirmGoogleRecovery by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasRecovery = keys.hasRecoveryKey(); hasGoogleRecovery = keys.hasGoogleRecovery() }

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Безопасность"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            message?.let { (text, tone) -> item { Banner(text, tone) } }
            item { SectionTitle(tr("Вход")) }
            item {
                SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    ListRow(tr("Сменить мастер-пароль"), tr("Ключ хранилища перешифровывается, записи не меняются"), icon = Icons.Rounded.Password, onClick = { showChange = true })
                    ListRow(tr("Не помню текущий пароль"), tr("Подтвердите личность биометрией или кодом экрана и задайте новый"), icon = Icons.Rounded.LockReset, onClick = {
                        val host = activity ?: return@ListRow
                        scope.launch {
                            when (val r = Biometrics.confirm(host, tr("Сброс мастер-пароля"), tr("Подтвердите, что это вы"))) {
                                AuthResult.Success -> showReset = true
                                is AuthResult.Failed -> message = r.message to Tone.DANGER
                                AuthResult.Cancelled -> Unit
                            }
                        }
                    })
                    SwitchRow(tr("Вход по биометрии"), if (Biometrics.canUseStrong(context)) tr("Ключ защищён Android Keystore") else tr("На устройстве нет надёжной биометрии"),
                        biometricEnabled, Icons.Rounded.Fingerprint, enabled = Biometrics.canUseStrong(context)) { on ->
                        scope.launch {
                            if (on) {
                                val host = activity ?: return@launch
                                when (val r = Biometrics.enable(host)) {
                                    AuthResult.Success -> message = tr("Вход по биометрии включён") to Tone.SUCCESS
                                    is AuthResult.Failed -> message = r.message to Tone.DANGER
                                    AuthResult.Cancelled -> Unit
                                }
                            } else Biometrics.disable(context)
                        }
                    }
                    SwitchRow(tr("Подтверждать вставку секретов"), tr("Биометрия или код экрана перед автозаполнением и вставкой из клавиатуры"),
                        AppSettings.biometricForSensitive.value, Icons.Rounded.VerifiedUser) { AppSettings.setBiometricForSensitive(it) }
                }
            }
            item { SectionTitle(tr("Автоблокировка")) }
            item {
                SBCard {
                    Text(tr("Блокировать хранилище после ухода из приложения"), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    FilterChipRow(autoLockOptions.map { it.first.toString() to it.second }, AppSettings.autoLockSeconds.value.toString()) { AppSettings.setAutoLock(it.toInt()) }
                    Text(tr("При выключении экрана хранилище блокируется сразу (кроме режима «Никогда»)."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                }
            }
            item { SectionTitle(tr("Конфиденциальность")) }
            item {
                SBCard {
                    Text(tr("Очищать скопированные секреты через"), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    FilterChipRow(clipboardOptions.map { it.first.toString() to it.second }, AppSettings.clipboardClearSeconds.value.toString()) { AppSettings.setClipboardClear(it.toInt()) }
                    SwitchRow(tr("Защита экрана"), tr("Запрет скриншотов и скрытие содержимого в списке приложений"), AppSettings.screenSecurity.value, Icons.Rounded.ScreenLockPortrait) {
                        AppSettings.setScreenSecurity(it); (activity as? SecureActivity)?.applyScreenSecurity()
                    }
                }
            }
            item { SectionTitle(tr("Восстановление через Google")) }
            item {
                SBCard {
                    SwitchRow(tr("Восстановление через аккаунт Google"), tr("Если забыты и мастер-пароль, и Recovery Key — войдите в Google, и sBoard откроет хранилище"),
                        hasGoogleRecovery, Icons.Rounded.CloudSync) { on ->
                        if (on) confirmGoogleRecovery = true
                        else scope.launch { keys.disableGoogleRecovery(); hasGoogleRecovery = false; message = tr("Восстановление через Google выключено") to Tone.INFO }
                    }
                    Text(tr("Ключ восстановления хранится в скрытой папке sBoard в вашем Google Drive вместе с зашифрованной копией. Удобно, но тогда безопасность хранилища равна безопасности аккаунта Google — включите в нём двухэтапную проверку."),
                        style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                    if (hasGoogleRecovery && !GoogleAccount.isConnected(context)) Banner(tr("Подключите Google Drive в разделе «Синхронизация», чтобы ключ был загружен."), Tone.WARNING)
                }
            }
            item { SectionTitle(tr("Recovery Key")) }
            item {
                SBCard {
                    Text(if (hasRecovery) tr("Recovery Key создан. Он позволяет открыть хранилище без мастер-пароля, в том числе на новом устройстве.")
                        else tr("Recovery Key не создан. Без него забытый мастер-пароль восстановить нельзя."),
                        style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    newRecovery?.let { code ->
                        SBCard(color = palette.surfaceHigh) { Text(VaultKeyManager.formatRecoveryKey(code), fontFamily = FontFamily.Monospace, color = palette.text, style = MaterialTheme.typography.titleSmall) }
                        SecondaryButton(tr("Скопировать"), { SensitiveClipboard.copy(context, code, true) }, icon = Icons.Rounded.ContentCopy)
                        Banner(tr("Ключ показывается один раз. Сохраните его вне телефона."), Tone.WARNING)
                    }
                    PrimaryButton(if (hasRecovery) tr("Создать новый Recovery Key") else tr("Создать Recovery Key"), {
                        if (hasRecovery) confirmRecoveryReplace = true
                        else scope.launch { newRecovery = keys.createRecoveryKey(); hasRecovery = true }
                    }, icon = Icons.Rounded.Key)
                    if (hasRecovery) SecondaryButton(tr("Удалить Recovery Key"), { scope.launch { keys.removeRecoveryKey(); hasRecovery = false; newRecovery = null } }, danger = true)
                }
            }
            item { SectionTitle(tr("Опасная зона")) }
            item { SecondaryButton(tr("Удалить все данные с устройства"), { confirmWipe = true }, icon = Icons.Rounded.DeleteForever, danger = true) }
            item { Text(tr("Шифрование: AES-256-GCM. Ключ из мастер-пароля: Argon2id (64 МиБ, 3 прохода). Биометрический ключ хранится в Android Keystore и не покидает устройство."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
        }
    }
    if (showChange) ChangePasswordDialog(keys, onDone = { ok -> showChange = false; if (ok) message = tr("Мастер-пароль изменён. Новый пароль применится на всех устройствах после синхронизации.") to Tone.SUCCESS })
    if (showReset) NewMasterPasswordDialog(mandatory = false) { ok -> showReset = false; if (ok) message = tr("Мастер-пароль изменён") to Tone.SUCCESS }
    if (confirmGoogleRecovery) ConfirmDialog(tr("Включить восстановление через Google?"),
        tr("Любой, кто войдёт в ваш аккаунт Google в приложении sBoard, сможет открыть хранилище. Используйте надёжный пароль Google и двухэтапную проверку."),
        tr("Включить"), onConfirm = {
            confirmGoogleRecovery = false
            scope.launch {
                runCatching { keys.enableGoogleRecovery() }
                    .onSuccess { hasGoogleRecovery = true; message = tr("Восстановление через Google включено. Ключ будет загружен при синхронизации.") to Tone.SUCCESS }
                    .onFailure { message = (it.message ?: tr("Не удалось включить")) to Tone.DANGER }
            }
        }, onDismiss = { confirmGoogleRecovery = false })
    if (confirmRecoveryReplace) ConfirmDialog(tr("Заменить Recovery Key?"), tr("Старый ключ перестанет работать."), tr("Заменить"),
        onConfirm = { confirmRecoveryReplace = false; scope.launch { newRecovery = keys.createRecoveryKey() } }, onDismiss = { confirmRecoveryReplace = false })
    if (confirmWipe) ConfirmDialog(tr("Удалить все данные?"), tr("Хранилище, ключи и история будут удалены с этого устройства. Копия в Google Drive останется, и её можно восстановить мастер-паролем."),
        tr("Удалить"), danger = true, onConfirm = {
            confirmWipe = false
            scope.launch { VaultWiper.wipeLocal(context); (context as? Activity)?.recreate() }
        }, onDismiss = { confirmWipe = false })
}

@Composable
private fun ChangePasswordDialog(keys: VaultKeyManager, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val strength = remember(next) { PasswordStrength.evaluate(next) }
    AlertDialog(onDismissRequest = { if (!busy) onDone(false) }, containerColor = palette.surface,
        title = { Text(tr("Сменить мастер-пароль")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SBTextField(current, { current = it; error = null }, tr("Текущий мастер-пароль"), password = true)
                SBTextField(next, { next = it; error = null }, tr("Новый мастер-пароль"), password = true)
                if (next.isNotEmpty()) StrengthBar(strength.score, strength.label)
                SBTextField(confirm, { confirm = it; error = null }, tr("Повторите новый пароль"), password = true, isError = confirm.isNotEmpty() && confirm != next)
                error?.let { Text(it, color = palette.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && next.length >= VaultKeyManager.MIN_PASSWORD_LENGTH && next == confirm && strength.score >= 2 && current.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    try { keys.changeMasterPassword(current.toCharArray(), next.toCharArray()); onDone(true) }
                    catch (e: Exception) { error = e.message ?: tr("Не удалось изменить пароль") }
                    busy = false
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent) else Text(tr("Сменить"), color = palette.accent) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(false) }) { Text(tr("Отмена"), color = palette.textMuted) } })
}

@Composable
fun DevicesScreen(nav: Navigator) {
    val context = LocalContext.current
    val sync by SyncStatus.snapshot.collectAsState()
    val selfId = remember { DeviceIdentity.id(context) }
    var revoke by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Устройства"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { Text(tr("Устройства, которые синхронизируют это хранилище через Google Drive. Список обновляется при синхронизации."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
            val devices = sync.devices.ifEmpty { listOf(com.sboard.app.feature.sync.SyncDevice(selfId, DeviceIdentity.displayName(), sync.lastSuccessAt)) }
            items(devices.sortedByDescending { it.id == selfId }, key = { it.id }) { d ->
                SBCard {
                    ListRow(d.name + if (d.id == selfId) " · " + tr("это устройство") else "",
                        when {
                            d.revoked -> tr("Отключено")
                            d.lastSyncAt > 0 -> tr("Синхронизация: {0}", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.lastSyncAt)))
                            else -> tr("Ещё не синхронизировалось")
                        }, icon = if (d.id == selfId) Icons.Rounded.PhoneAndroid else Icons.Rounded.Devices, trailing = null)
                    if (d.id != selfId && !d.revoked && GoogleAccount.isConnected(context)) {
                        TextButton(onClick = { revoke = d.id }) { Text(tr("Отключить устройство"), color = palette.danger) }
                    }
                }
            }
            item { Banner(tr("Отключённое устройство перестанет синхронизироваться при следующем подключении. Если устройство утеряно, также смените мастер-пароль."), Tone.INFO) }
        }
    }
    revoke?.let { id ->
        ConfirmDialog(tr("Отключить устройство?"), tr("Оно больше не будет получать изменения из Google Drive."), tr("Отключить"), danger = true,
            onConfirm = { revoke = null; SyncScheduler.requestNow(context, revokeDeviceId = id) }, onDismiss = { revoke = null })
    }
}

@Composable
fun AppearanceScreen(nav: Navigator) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Оформление и язык"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { SectionTitle(tr("Тема")) }
            item {
                SBCard {
                    SegmentedControl(listOf(AppSettings.THEME_SYSTEM to tr("Системная"), AppSettings.THEME_LIGHT to tr("Светлая"), AppSettings.THEME_DARK to tr("Тёмная")),
                        AppSettings.themeMode.value, { AppSettings.setTheme(it) })
                    Text(tr("Тему клавиатуры можно выбрать отдельно в разделе «Клавиатура»."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                }
            }
            item { SectionTitle(tr("Цветовая палитра")) }
            item {
                SBCard {
                    SegmentedControl(listOf("night" to tr("Лунная ночь"), "graphite" to tr("Графит и золото")), AppSettings.appPalette.value, { AppSettings.setAppPalette(it) })
                    Text(tr("«Лунная ночь» — глубокий индиго с лавандовым свечением и розовыми акцентами."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
                }
            }
            item { SectionTitle(tr("Язык интерфейса")) }
            item {
                SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    ListRow(languageLabel(SBoardI18n.currentMode()), tr("Язык меню и подсказок приложения"), icon = Icons.Rounded.Translate, onClick = { nav.push(Route.AppLanguage) })
                }
            }
        }
    }
}

@Composable
fun AboutScreen(nav: Navigator) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("О приложении"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item {
                SBCard {
                    ListRow("sBoard", tr("Версия {0} ({1})", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), icon = Icons.Rounded.Shield, trailing = null)
                    StatusLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", palette.accent)
                    StatusLine(tr("Устройство"), DeviceIdentity.displayName(), palette.accent)
                    StatusLine(tr("Сборка"), if (BuildConfig.DEBUG) "debug" else "release", palette.accent)
                }
            }
            item {
                SBCard {
                    Text(tr("Как защищены данные"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                    listOf(
                        tr("Каждая запись шифруется AES-256-GCM на устройстве."),
                        tr("Ключ хранилища защищён мастер-паролем через Argon2id и, по желанию, биометрией через Android Keystore."),
                        tr("В Google Drive загружается только зашифрованный файл sboard_backup.enc в скрытую папку приложения."),
                        tr("Данные приложения исключены из резервных копий Android."),
                        tr("Набранный на клавиатуре текст не отправляется в сеть. Переводчик работает онлайн только по вашему запросу."),
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                }
            }
            item {
                SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    ListRow(tr("Лицензии открытого ПО"), "FlorisBoard (Apache-2.0), BouncyCastle, ZXing, zxcvbn4j, OkHttp", icon = Icons.Rounded.Description, onClick = {
                        val intent = android.content.Intent(context, dev.patrickgold.florisboard.app.FlorisAppActivity::class.java).apply {
                            action = android.content.Intent.ACTION_VIEW
                            data = android.net.Uri.parse("ui://florisboard/settings/about/third-party-licenses")
                        }
                        runCatching { context.startActivity(intent) }
                    })
                }
            }
        }
    }
}

@Composable
fun PasswordHealthScreen(nav: Navigator) {
    val context = LocalContext.current
    val items by VaultRepository.get(context).items.collectAsState()
    var report by remember { mutableStateOf<PasswordHealth.Report?>(null) }
    LaunchedEffect(items) { report = withContext(Dispatchers.Default) { PasswordHealth.analyze(items) } }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Аудит паролей"), { nav.pop() })
        val r = report
        if (r == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }; return@Column }
        ScreenList(bottomPadding = 40.dp) {
            item {
                SBCard {
                    Text("${r.score}%", style = MaterialTheme.typography.displaySmall, color = if (r.score >= 80) palette.success else palette.warning)
                    Text(tr("Проверено паролей: {0}", r.total), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    StatusLine(tr("Слабые"), "${r.weak.size}", if (r.weak.isEmpty()) palette.success else palette.danger)
                    StatusLine(tr("Повторяются"), "${r.reused.size}", if (r.reused.isEmpty()) palette.success else palette.warning)
                    StatusLine(tr("Старше года"), "${r.old.size}", if (r.old.isEmpty()) palette.success else palette.warning)
                }
            }
            fun section(title: String, list: List<VaultItem>) {
                if (list.isEmpty()) return
                item { SectionTitle(title) }
                item {
                    SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        list.distinctBy { it.id }.forEach { item ->
                            ListRow(item.title, item.subtitle(), leading = { ItemBadge(item.type, item.title, 36.dp) }, onClick = { nav.push(Route.ItemEdit(item.id, item.type)) })
                        }
                    }
                }
            }
            section(tr("Слабые пароли"), r.weak)
            section(tr("Повторяющиеся пароли"), r.reused)
            section(tr("Давно не менялись"), r.old)
            if (r.total > 0 && r.weak.isEmpty() && r.reused.isEmpty()) item { Banner(tr("Отлично! Слабых и повторяющихся паролей нет."), Tone.SUCCESS) }
        }
    }
}


/** Name of an interface language choice ("Системный (Deutsch)", "Русский", ...). */
fun languageLabel(mode: String): String = when (mode) {
    SBoardI18n.MODE_SYSTEM -> tr("Системный") + " · " + com.sboard.app.feature.tools.Translator.nativeName(SBoardI18n.systemLanguage())
    else -> com.sboard.app.feature.tools.Translator.nativeName(mode)
}

/**
 * Interface language: Russian and English are built in; every translator language is offered as a
 * machine-translated language pack that is downloaded once (only interface texts are sent).
 */
@Composable
fun AppLanguageList(onPicked: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = SBoardI18n.currentMode()
    val options = listOf(SBoardI18n.MODE_SYSTEM) + com.sboard.app.feature.tools.Translator.CODES
    val visible = options.filter { code ->
        query.isBlank() || languageLabel(code).contains(query, true) || com.sboard.app.feature.tools.Translator.displayName(code).contains(query, true)
    }
    fun choose(code: String) {
        val lang = if (code == SBoardI18n.MODE_SYSTEM) SBoardI18n.systemLanguage() else code
        if (SBoardI18n.hasPack(lang) || lang !in com.sboard.app.feature.tools.Translator.CODES) { SBoardI18n.setLanguage(context, code); onPicked(); return }
        scope.launch {
            downloading = code; progress = 0f; error = null
            val ok = runCatching { com.sboard.app.core.i18n.LanguagePacks.download(context, lang) { progress = it } }.getOrDefault(false)
            downloading = null
            if (ok) { SBoardI18n.setLanguage(context, code); onPicked() }
            else error = tr("Не удалось загрузить язык. Проверьте интернет и попробуйте ещё раз.")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SBTextField(query, { query = it }, tr("Поиск языка"))
        AnimatedBanner(error, Tone.DANGER)
        downloading?.let {
            Text(tr("Загружаем перевод интерфейса… {0}%", (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = palette.accent)
        }
        visible.forEach { code ->
            val lang = if (code == SBoardI18n.MODE_SYSTEM) SBoardI18n.systemLanguage() else code
            val subtitle = when {
                code == SBoardI18n.MODE_SYSTEM -> tr("Как в настройках телефона")
                lang == "ru" || lang == "en" -> tr("Встроенный")
                SBoardI18n.hasPack(lang) -> tr("Загружен · машинный перевод")
                else -> tr("Машинный перевод · загрузится один раз")
            }
            ListRow(languageLabel(code), subtitle, icon = if (code == current) Icons.Rounded.CheckCircle else Icons.Rounded.Language,
                titleColor = if (code == current) palette.accent else palette.text,
                trailing = null, onClick = { if (downloading == null) choose(code) })
        }
    }
}

@Composable
fun AppLanguageScreen(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Язык интерфейса"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { AppLanguageList(onPicked = { nav.pop() }) } }
            item { Text(tr("Русский и английский встроены. Остальные языки переводятся автоматически: при выборе sBoard один раз отправляет в переводчик только тексты меню — никаких ваших данных."),
                style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
        }
    }
}
