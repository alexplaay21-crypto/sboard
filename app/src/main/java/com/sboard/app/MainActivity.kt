package com.sboard.app

import android.os.Bundle
import android.app.Activity
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.sboard.app.feature.backup.GoogleDriveBackupClient
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import com.sboard.app.feature.tools.clipboard.SBoardClipboardWriter
import com.sboard.app.feature.tools.generator.PasswordGenerator
import com.sboard.app.keyboard.KeyboardSettings
import com.sboard.app.feature.vault.VaultScreen
import com.sboard.app.feature.vault.VaultSession
import com.sboard.app.feature.backup.EncryptedBackupManager
import com.sboard.app.feature.backup.BackupAutomationScheduler
import com.sboard.app.feature.backup.BackupAutomationRuntime
import com.sboard.app.feature.backup.BackupAutomationSettingsStore
import com.sboard.app.feature.backup.BackupAutomationStatusStore
import com.sboard.app.feature.backup.BackupAutomationPolicy
import com.sboard.app.feature.backup.DeviceBoundBackupKeyStore
import com.sboard.app.feature.backup.DriveBackupRestoreCoordinator
import com.sboard.app.feature.backup.GoogleDriveOAuthManager
import com.sboard.app.feature.vault.VaultSessionProvider
import com.sboard.app.feature.sync.SyncAutomationScheduler
import com.sboard.app.feature.sync.SyncAutomationStatusStore
import com.sboard.app.feature.vault.MasterPasswordManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF111214)
private val SurfaceDark = Color(0xFF1A1C1F)
private val SurfaceVariantDark = Color(0xFF24262A)
private val Gold = Color(0xFFD5B94B)
private val Muted = Color(0xFF92949A)
private val Cream = Color(0xFFF1F0EA)

class MainActivity : FragmentActivity() {
    override fun onResume() {
        super.onResume()
        if (VaultSessionProvider.session.isUnlocked) {
            SyncAutomationScheduler(applicationContext).requestOnForeground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SBoardI18n.initialize(this)
        setContent { SBoardApp() }
    }
}

private enum class Tab(private val labelKey: String) {
    Home("Главная"), Keyboard("Клавиатура"), Vault("Хранилище"),
    Tools("Инструменты"), Profile("Профиль");
    val label: String get() = tr(labelKey)
}

@Composable
private fun SBoardApp() {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(Tab.Home) }
    var setupCheckComplete by rememberSaveable { mutableStateOf(false) }
    var firstRunRequired by rememberSaveable { mutableStateOf(false) }
    var existingVault by rememberSaveable { mutableStateOf(false) }
    var openRestoreAfterSetup by rememberSaveable { mutableStateOf(false) }
    val vaultSession = VaultSessionProvider.session

    LaunchedEffect(Unit) {
        val setupPrefs = context.getSharedPreferences("sboard_first_run_v1", android.content.Context.MODE_PRIVATE)
        val completed = setupPrefs.getBoolean("completed", false)
        val started = setupPrefs.getBoolean("started", false)
        val hasMaster = runCatching { MasterPasswordManager(context.applicationContext).hasMasterPassword() }.getOrDefault(false)
        existingVault = hasMaster && started
        firstRunRequired = !completed && (!hasMaster || started)
        // Do not force existing installations through key rotation; it could invalidate old backups.
        if (hasMaster && !started && !completed) setupPrefs.edit().putBoolean("completed", true).apply()
        setupCheckComplete = true
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = SurfaceDark, surfaceVariant = SurfaceVariantDark,
            primary = Gold, onPrimary = Ink, onBackground = Cream,
            onSurface = Cream, onSurfaceVariant = Muted, secondary = Gold
        )
    ) {
        if (!setupCheckComplete) {
            Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
        } else if (firstRunRequired && !openRestoreAfterSetup) {
            FirstRunSetupScreen(
                existingVault = existingVault,
                onComplete = {
                    context.getSharedPreferences("sboard_first_run_v1", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("started", false).putBoolean("completed", true).apply()
                    firstRunRequired = false
                },
                onRestore = {
                    openRestoreAfterSetup = true
                    selected = Tab.Profile
                }
            )
        } else Scaffold(
            containerColor = Ink,
            bottomBar = { BottomNavigation(selected) { selected = it } }
        ) { padding ->
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.padding(padding),
                label = "tab_transition"
            ) { tab ->
                when (tab) {
                    Tab.Home -> HomeScreen(onOpenVault = { selected = Tab.Vault }, onOpenTools = { selected = Tab.Tools })
                    Tab.Keyboard -> KeyboardScreen()
                    Tab.Vault -> VaultScreen(vaultSession)
                    Tab.Tools -> ToolsScreen()
                    Tab.Profile -> ProfileScreen(vaultSession, openRestoreAfterSetup)
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = SurfaceDark,
        contentColor = Cream,
        tonalElevation = 0.dp
    ) {
        val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Keyboard, Icons.Rounded.Lock,
            Icons.Rounded.Build, Icons.Rounded.Person)
        Tab.entries.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(icons[index], contentDescription = tab.label) },
                label = { Text(tab.label, fontSize = 10.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Gold, selectedTextColor = Gold,
                    indicatorColor = Color(0xFF343124),
                    unselectedIconColor = Muted, unselectedTextColor = Muted
                )
            )
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) } }
}

@Composable
private fun Header(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Cream)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun HomeScreen(onOpenVault: () -> Unit, onOpenTools: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("sBoard", color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(tr("Всё важное — в одном месте"), color = Muted, fontSize = 12.sp)
            }
            Surface(color = SurfaceVariantDark, shape = CircleShape) {
                Icon(Icons.Rounded.Shield, null, tint = Gold, modifier = Modifier.padding(11.dp))
            }
        }
        Header(tr("Добро пожаловать"))
        Surface(color = SurfaceDark, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Muted)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Cream, fontSize = 14.sp),
                    decorationBox = { inner -> if (query.isEmpty()) Text(tr("Поиск по хранилищу"), color = Muted); inner() }
                )
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Ваше пространство"), color = Cream, fontWeight = FontWeight.SemiBold)
                    Text(tr("Защита и резервное копирование"), color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Rounded.Security, null, tint = Gold)
            }
            HorizontalDivider(color = SurfaceVariantDark)
            StatusLine(tr("Локальное хранилище"), tr("Подготовлено"), true)
            StatusLine("Google Drive", tr("Не подключён"), false)
            StatusLine(tr("Последняя копия"), tr("Пока нет копий"), false)
        }
        Text(tr("Быстрые действия"), color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(tr("Пароль"), Icons.Rounded.Key, Modifier.weight(1f), onOpenVault)
            QuickAction(tr("Контакт"), Icons.Rounded.ContactPhone, Modifier.weight(1f), onOpenVault)
            QuickAction(tr("Заметка"), Icons.Rounded.NoteAdd, Modifier.weight(1f), onOpenVault)
        }
        Panel {
            Text(tr("Продолжить работу"), color = Cream, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Gold)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Инструменты sBoard"), color = Cream)
                    Text(tr("Генератор паролей и будущие помощники"), color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenTools) { Text(tr("Открыть")) }
            }
        }
    }
}

@Composable
private fun StatusLine(title: String, value: String, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (good) Gold else Muted, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(title, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Cream, fontSize = 12.sp)
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = SurfaceDark, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = Gold)
            Text(label, color = Cream, fontSize = 11.sp)
        }
    }
}

@Composable
private fun KeyboardScreen() {
    val context = LocalContext.current
    val keyboardPrefs = remember(context) { KeyboardSettings.preferences(context) }
    var haptics by rememberSaveable { mutableStateOf(keyboardPrefs.getBoolean(KeyboardSettings.KEY_HAPTICS, true)) }
    var numberRow by rememberSaveable { mutableStateOf(keyboardPrefs.getBoolean(KeyboardSettings.KEY_NUMBER_ROW, true)) }
    var keyHeight by rememberSaveable { mutableFloatStateOf(keyboardPrefs.getInt(KeyboardSettings.KEY_HEIGHT, 44).coerceIn(36, 56).toFloat()) }
    var theme by rememberSaveable { mutableStateOf(keyboardPrefs.getString(KeyboardSettings.KEY_THEME, "system") ?: "system") }
    var allowNetworkSpeech by rememberSaveable { mutableStateOf(keyboardPrefs.getBoolean(KeyboardSettings.KEY_NETWORK_SPEECH, false)) }
    var microphoneGranted by rememberSaveable {
        mutableStateOf(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
    }
    ScreenColumn {
        Header(tr("Клавиатура"), tr("Настройте ввод и внешний вид sBoard"))
        Panel {
            Text(tr("Статус клавиатуры"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(tr("Системная клавиатура sBoard доступна для включения в настройках Android."), color = Muted, fontSize = 13.sp)
            Button(
                onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Включить клавиатуру sBoard")) }
            OutlinedButton(
                onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showInputMethodPicker()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Выбрать клавиатуру sBoard")) }
        }
        Panel {
            Text(tr("Ввод текста"), color = Cream, fontWeight = FontWeight.SemiBold)
            SettingSwitch(tr("Числовой ряд"), numberRow) { numberRow = it; keyboardPrefs.edit().putBoolean(KeyboardSettings.KEY_NUMBER_ROW, it).apply() }
            Text("${tr("Высота клавиш")}: ${keyHeight.toInt()} dp", color = Muted, fontSize = 12.sp)
            Slider(value = keyHeight, onValueChange = { keyHeight = it }, onValueChangeFinished = {
                keyboardPrefs.edit().putInt(KeyboardSettings.KEY_HEIGHT, keyHeight.toInt().coerceIn(36, 56)).apply()
            }, valueRange = 36f..56f, steps = 19)
            SettingSwitch(tr("Вибрация клавиш"), haptics) { haptics = it; keyboardPrefs.edit().putBoolean(KeyboardSettings.KEY_HAPTICS, it).apply() }
        }
        Panel {
            Text(tr("Голосовой ввод"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(tr("Распознавание выполняется Android. sBoard не сохраняет аудио. По возможности используется офлайн-распознавание."), color = Muted, fontSize = 12.sp)
            OutlinedButton(
                onClick = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                enabled = !microphoneGranted,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (microphoneGranted) tr("Микрофон разрешён") else tr("Разрешить микрофон")) }
            SettingSwitch(tr("Разрешить системное сетевое распознавание"), allowNetworkSpeech) {
                allowNetworkSpeech = it
                keyboardPrefs.edit().putBoolean(KeyboardSettings.KEY_NETWORK_SPEECH, it).apply()
            }
            Text(tr("Если офлайн-распознавание недоступно, системный распознаватель может отправлять речь своему поставщику. Ввод запускается только нажатием на микрофон."), color = Muted, fontSize = 11.sp)
        }
        Panel {
            Text(tr("Внешний вид"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(tr("Тема клавиатуры"), color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to tr("Системная"), "dark" to tr("Тёмная"), "light" to tr("Светлая")).forEach { (value, label) ->
                    FilterChip(selected = theme == value, onClick = {
                        theme = value
                        keyboardPrefs.edit().putString(KeyboardSettings.KEY_THEME, value).apply()
                    }, label = { Text(label) })
                }
            }
            KeyboardPreview()
        }
    }
}

@Composable
private fun KeyboardPreview() {
    Panel {
        Text(tr("Предпросмотр"), color = Muted, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val keyboardRows = if (SBoardI18n.isEnglish()) {
                listOf("Q W E R T Y U I O P", "A S D F G H J K L", "⇧ Z X C V B N M ⌫")
            } else {
                listOf("Й Ц У К Е Н Г Ш Щ З Х", "Ф Ы В А П Р О Л Д Ж Э", "⇧ Я Ч С М И Т Ь Б Ю ⌫")
            }
            keyboardRows.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    line.split(" ").forEach { key ->
                        Surface(Modifier.weight(1f).height(34.dp), color = SurfaceVariantDark, shape = RoundedCornerShape(7.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(key, color = Cream, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsScreen() {
    var length by rememberSaveable { mutableFloatStateOf(18f) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboardWriter = remember(context) { SBoardClipboardWriter(context) }
    val hasGroup = upper || lower || digits || symbols

    ScreenColumn {
        Header(tr("Инструменты"), tr("Полезные функции sBoard"))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Password, null, tint = Gold)
                Text(tr("Генератор паролей"), color = Cream, fontWeight = FontWeight.SemiBold)
            }
            Text("${tr("Длина:")} ${length.toInt()} ${tr("символов")}", color = Muted, fontSize = 13.sp)
            Slider(value = length, onValueChange = { length = it }, valueRange = 8f..64f, steps = 55)
            SettingSwitch(tr("Заглавные буквы"), upper) { upper = it; password = "" }
            SettingSwitch(tr("Строчные буквы"), lower) { lower = it; password = "" }
            SettingSwitch(tr("Цифры"), digits) { digits = it; password = "" }
            SettingSwitch(tr("Спецсимволы"), symbols) { symbols = it; password = "" }
            Button(
                onClick = {
                    password = PasswordGenerator.generate(
                        length = length.toInt(),
                        uppercase = upper,
                        lowercase = lower,
                        numbers = digits,
                        symbols = symbols
                    )
                    error = null
                },
                enabled = hasGroup,
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Создать пароль")) }
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (password.isNotEmpty()) {
                Surface(color = SurfaceVariantDark, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        password,
                        Modifier.fillMaxWidth().padding(12.dp),
                        color = Cream
                    )
                }
                OutlinedButton(
                    onClick = { clipboardWriter.copy(password, sensitive = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Скопировать пароль"))
                }
                Text(
                    tr("Копирование выполняется только по нажатию. Не отправляйте пароль в историю Clipboard."),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(session: VaultSession, openRestoreInitially: Boolean = false) {
    val context = LocalContext.current
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showBackupSettings by rememberSaveable { mutableStateOf(false) }
    var showNewDeviceRestore by rememberSaveable { mutableStateOf(openRestoreInitially) }
    if (showBackupSettings) {
        BackupSettingsScreen(onBack = { showBackupSettings = false })
        return
    }
    if (showNewDeviceRestore) {
        RestoreOnNewDeviceScreen(
            onBack = { showNewDeviceRestore = false },
            onRestored = {
                context.getSharedPreferences("sboard_first_run_v1", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("started", false).putBoolean("completed", true).apply()
            }
        )
        return
    }
    if (showBackup) {
        BackupScreen(session = session, onBack = { showBackup = false })
        return
    }
    ScreenColumn {
        Header(tr("Профиль"), tr("Аккаунт и настройки приложения"))
        Panel {
            Text(tr("Язык приложения"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(tr("Выберите язык интерфейса sBoard"), color = Muted, fontSize = 12.sp)
            val selectedLanguage = SBoardI18n.currentMode()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedLanguage == "system",
                    onClick = { SBoardI18n.setLanguage(context, "system") },
                    label = { Text(tr("Как на устройстве")) }
                )
                FilterChip(
                    selected = selectedLanguage == "ru",
                    onClick = { SBoardI18n.setLanguage(context, "ru") },
                    label = { Text(tr("Русский")) }
                )
                FilterChip(
                    selected = selectedLanguage == "en",
                    onClick = { SBoardI18n.setLanguage(context, "en") },
                    label = { Text(tr("Английский")) }
                )
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = SurfaceVariantDark, shape = CircleShape) {
                    Icon(Icons.Rounded.Person, null, tint = Gold, modifier = Modifier.padding(16.dp).size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(tr("Локальный профиль"), color = Cream, fontWeight = FontWeight.SemiBold)
                    Text(tr("Аккаунт sBoard пока не подключён"), color = Muted, fontSize = 12.sp)
                }
            }
        }
        listOf(
            tr("Безопасность") to tr("Мастер-пароль, биометрия и Recovery Key"),
            tr("Устройства") to tr("Управление доверенными устройствами"),
            tr("Конфиденциальность") to tr("Разрешения и обработка данных"),
            tr("О приложении") to tr("sBoard 0.1.0 · ранняя сборка")
        ).forEach { (title, sub) ->
            Surface(color = SurfaceDark, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Cream, fontWeight = FontWeight.Medium)
                        Text(sub, color = Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                }
            }
        }
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable { showBackupSettings = true }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Автоматическое резервирование"), color = Cream, fontWeight = FontWeight.Medium)
                    Text(tr("Расписание, версии и защита фонового доступа"), color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                runCatching { context.startActivity(intent) }
            }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Автозаполнение паролей"), color = Cream, fontWeight = FontWeight.Medium)
                    Text(tr("Включить sBoard в настройках Android"), color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable { showBackup = true }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Резервное копирование"), color = Cream, fontWeight = FontWeight.Medium)
                    Text(tr("Создать или восстановить зашифрованную копию"), color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable { showNewDeviceRestore = true }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, null, tint = Gold, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Восстановить на новом устройстве"), color = Cream, fontWeight = FontWeight.Medium)
                    Text(tr("Подключите Google Drive и верните Vault из копии"), color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
    }
}


private fun googleAuthorizationFailure(error: Throwable): String {
    val apiError = error as? com.google.android.gms.common.api.ApiException
    return when (apiError?.statusCode) {
        com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR ->
            tr("Google OAuth отклонил приложение: проверьте Android OAuth-клиент, package name и SHA-1 сертификата установленного APK.")
        else -> error.message?.take(280) ?: tr("Не удалось авторизовать Google Drive")
    }
}

@Composable
private fun BackupSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { BackupAutomationSettingsStore(context) }
    val keyStore = remember(context) { DeviceBoundBackupKeyStore(context) }
    val scheduler = remember(context) { BackupAutomationScheduler(context) }
    val scope = rememberCoroutineScope()
    var automationDesired by remember { mutableStateOf(settings.isAutomationDesired()) }
    var keepVersions by remember { mutableIntStateOf(settings.keepVersions()) }
    var credentialConfigured by remember { mutableStateOf(keyStore.isConfigured()) }
    var driveConnected by remember { mutableStateOf(settings.isDriveConnected()) }
    var showCredentialDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var disconnectingDrive by remember { mutableStateOf(false) }
    var recoveryKeyInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    val backupStatusStore = remember(context) { BackupAutomationStatusStore(context) }
    val syncStatusStore = remember(context) { SyncAutomationStatusStore(context) }
    val driveClient = remember { GoogleDriveBackupClient() }
    var backupStatus by remember { mutableStateOf(backupStatusStore.snapshot()) }
    var syncStatus by remember { mutableStateOf(syncStatusStore.snapshot()) }
    fun refreshBackupStatus() { backupStatus = backupStatusStore.snapshot() }
    LaunchedEffect(syncStatusStore) {
        while (true) { syncStatus = syncStatusStore.snapshot(); delay(1_500) }
    }
    fun verifyAndConnectDrive(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            driveConnected = false
            statusMessage = tr("Google не выдал access token")
            statusError = true
            return
        }
        statusMessage = tr("Проверяем доступ к Google Drive…")
        statusError = false
        scope.launch {
            try {
                driveClient.listVersions(accessToken)
                driveConnected = true
                settings.setDriveConnected(true)
                if (automationDesired && credentialConfigured) {
                    scheduler.enableChangeBased()
                    scheduler.requestAfterImportantChange()
                }
                statusMessage = tr("Google Drive подключён. Токен не сохраняется в sBoard.")
                statusError = false
            } catch (failure: Exception) {
                driveConnected = false
                settings.setDriveConnected(false)
                scheduler.disable()
                statusMessage = failure.message ?: tr("Авторизация Google завершилась, но Drive API не подтвердил доступ. Проверьте, что Google Drive API включён и вы разрешили доступ.")
                statusError = true
            }
        }
    }
    val hostActivity = context as? Activity
    val driveOAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
            }.onSuccess { authorizationResult ->
                verifyAndConnectDrive(authorizationResult.accessToken)
            }.onFailure { error ->
                driveConnected = false
                settings.setDriveConnected(false)
                statusMessage = googleAuthorizationFailure(error)
                statusError = true
            }
        } else {
            statusMessage = tr("Подключение Google Drive отменено")
            statusError = true
        }
    }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("← Назад"), color = Gold) }
        }
        Header(tr("Автоматическое резервирование"), tr("Настройки фоновой синхронизации sBoard"))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Синхронизация между устройствами"), color = Cream, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { syncStatus = syncStatusStore.snapshot() }) { Text(tr("Обновить"), color = Gold) }
            }
            val syncLabel = when (syncStatus.result) {
                SyncAutomationStatusStore.RUNNING -> tr("Выполняется")
                SyncAutomationStatusStore.SUCCESS -> tr("Успешно")
                SyncAutomationStatusStore.ATTENTION -> tr("Требуется внимание")
                SyncAutomationStatusStore.FAILED -> tr("Ошибка")
                else -> tr("Ещё не синхронизировалась")
            }
            StatusLine(tr("Состояние"), syncLabel, syncStatus.result == SyncAutomationStatusStore.SUCCESS)
            syncStatus.lastSuccessAt?.let { time ->
                StatusLine(tr("Последняя синхронизация"), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time)), true)
            }
            syncStatus.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp) }
            OutlinedButton(onClick = {
                SyncAutomationScheduler(context).requestOnForeground()
                syncStatus = syncStatusStore.snapshot()
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("Синхронизировать сейчас")) }
            Text(tr("Новые изменения загружаются в зашифрованном виде. Полученные с другого устройства изменения пока требуют проверки перед применением."), color = Muted, fontSize = 11.sp)
        }
        Panel {
            Text(tr("Фоновое резервирование"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Резервная копия создаётся после добавления, изменения или удаления данных. Быстрые изменения объединяются в одну загрузку; при отсутствии сети задача ждёт подключения."),
                color = Muted, fontSize = 12.sp
            )
            if (driveConnected) {
                OutlinedButton(
                    onClick = { showDisconnectConfirm = true },
                    enabled = !disconnectingDrive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (disconnectingDrive) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(tr("Отключить Google Drive"))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        val host = hostActivity
                        if (host == null) {
                            statusMessage = tr("Не удалось открыть авторизацию Google")
                            statusError = true
                        } else {
                            val request = AuthorizationRequest.builder()
                                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                                .build()
                            Identity.getAuthorizationClient(host).authorize(request)
                                .addOnSuccessListener { authorizationResult ->
                                    if (authorizationResult.hasResolution()) {
                                        val pendingIntent = authorizationResult.pendingIntent
                                        if (pendingIntent == null) {
                                            statusMessage = tr("Google не вернул запрос подтверждения доступа")
                                            statusError = true
                                        } else driveOAuthLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        )
                                    } else {
                                        verifyAndConnectDrive(authorizationResult.accessToken)
                                    }
                                }
                                .addOnFailureListener { error ->
                                    statusMessage = googleAuthorizationFailure(error)
                                    statusError = true
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr("Подключить Google Drive")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Включить автоматическое резервирование"), color = Cream)
                    Text(
                        if (automationDesired) tr("Включено: резервирование после изменений") else tr("Выключено"),
                        color = Muted, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = automationDesired,
                    onCheckedChange = { enabled ->
                        if (enabled && !credentialConfigured) {
                            statusMessage = tr("Сначала сохраните Recovery Key с явным подтверждением")
                            statusError = true
                        } else if (enabled && !driveConnected) {
                            statusMessage = tr("Сначала подключите Google Drive")
                            statusError = true
                        } else {
                            automationDesired = enabled
                            settings.setAutomationDesired(enabled)
                            if (!enabled) scheduler.disable()
                            else if (BackupAutomationRuntime.executor != null) {
                                scheduler.enableChangeBased()
                                scheduler.requestAfterImportantChange()
                            }
                            statusMessage = if (enabled && BackupAutomationRuntime.executor == null)
                                tr("Настройка сохранена, но фоновый исполнитель пока не подключён")
                            else tr("Настройки резервирования обновлены")
                            statusError = false
                        }
                    }
                )
            }
            HorizontalDivider(color = SurfaceVariantDark)
            Text(tr("Режим: по событию — после каждого сохранения, изменения или удаления. Ежедневный таймер не используется."), color = Muted, fontSize = 12.sp)
            Text("${tr("Хранить версий")}: $keepVersions", color = Cream)
            Slider(
                value = keepVersions.toFloat(), onValueChange = { raw ->
                    keepVersions = raw.toInt().coerceIn(1, 100)
                    settings.setKeepVersions(keepVersions)
                }, valueRange = 1f..100f, steps = 98
            )
            Text(tr("Количество сохраняемых версий пока является настройкой-предпочтением; автоматическое удаление старых копий не включено."), color = Muted, fontSize = 11.sp)
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, null, tint = Gold)
                Column(Modifier.weight(1f)) {
                    Text(tr("Доступ к Recovery Key на этом устройстве"), color = Cream, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (credentialConfigured) tr("Зашифрованная копия ключа сохранена в Android Keystore")
                        else tr("Recovery Key не сохранён для фонового резервирования"),
                        color = Muted, fontSize = 11.sp
                    )
                }
            }
            Text(
                tr("Это дополнительная зашифрованная копия ключа, привязанная к устройству. Она не заменяет офлайн-копию Recovery Key. После переустановки приложения или потери устройства этот способ доступа может быть недоступен."),
                color = Muted, fontSize = 12.sp
            )
            if (!credentialConfigured) {
                Button(onClick = { recoveryKeyInput = ""; showCredentialDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Разрешить хранение Recovery Key на устройстве"))
                }
            } else {
                OutlinedButton(onClick = {
                    runCatching { keyStore.clear() }
                        .onSuccess {
                            credentialConfigured = false
                            automationDesired = false
                            settings.setAutomationDesired(false)
                            scheduler.disable()
                            statusMessage = tr("Сохранённые полномочия фонового резервирования удалены")
                            statusError = false
                        }
                        .onFailure {
                            statusMessage = tr("Не удалось удалить сохранённый ключ")
                            statusError = true
                        }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("Удалить сохранённый Recovery Key")) }
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Последняя резервная копия"), color = Cream, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { refreshBackupStatus() }) { Text(tr("Обновить"), color = Gold) }
            }
            val resultLabel = when (backupStatus.result) {
                BackupAutomationStatusStore.RESULT_RUNNING -> tr("Выполняется")
                BackupAutomationStatusStore.RESULT_SUCCESS -> tr("Успешно")
                BackupAutomationStatusStore.RESULT_NEEDS_ATTENTION -> tr("Требуется внимание")
                BackupAutomationStatusStore.RESULT_FAILED -> tr("Ошибка")
                else -> tr("Ещё не создавалась")
            }
            val resultOkay = backupStatus.result == BackupAutomationStatusStore.RESULT_SUCCESS
            StatusLine(tr("Состояние"), resultLabel, resultOkay)
            val lastSuccess = backupStatus.lastSuccessAt?.let {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: tr("Нет данных")
            val lastAttempt = backupStatus.lastAttemptAt?.let {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: tr("Нет данных")
            StatusLine(tr("Последняя успешная копия"), lastSuccess, backupStatus.lastSuccessAt != null)
            StatusLine(tr("Последняя попытка"), lastAttempt, backupStatus.lastAttemptAt != null)
            backupStatus.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp) }
            OutlinedButton(
                onClick = {
                    scheduler.requestManualBackup()
                    statusMessage = tr("Резервирование поставлено в очередь")
                    statusError = false
                    refreshBackupStatus()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Создать копию сейчас")) }
        }
        Panel {
            Text(tr("Состояние интеграции"), color = Cream, fontWeight = FontWeight.SemiBold)
            StatusLine(tr("Локальная защита ключа"), if (credentialConfigured) tr("Настроена") else tr("Не настроена"), credentialConfigured)
            StatusLine(tr("Фоновый исполнитель"), if (BackupAutomationRuntime.executor != null) tr("Подключён") else tr("Ожидает интеграции"), BackupAutomationRuntime.executor != null)
            StatusLine(tr("Авторизация Google Drive"), if (driveConnected) tr("Подключена") else tr("Не подключена"), driveConnected)
        }
        statusMessage?.let {
            Text(it, color = if (statusError) Color(0xFFFF8A80) else Gold, fontSize = 12.sp)
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { if (!disconnectingDrive) showDisconnectConfirm = false },
            title = { Text(tr("Отключить Google Drive?")) },
            text = {
                Text(
                    tr("sBoard отзовёт выданное Google разрешение. Фоновое резервирование остановится. Уже загруженные копии в Google Drive останутся, а локальный Vault и Recovery Key на этом устройстве не будут удалены."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(enabled = !disconnectingDrive, onClick = {
                    val host = hostActivity
                    if (host == null) {
                        statusMessage = tr("Не удалось получить Activity для отключения Google")
                        statusError = true
                        showDisconnectConfirm = false
                    } else {
                        disconnectingDrive = true
                        scope.launch {
                            try {
                                GoogleDriveOAuthManager().revokeDriveAccess(host)
                                settings.setDriveConnected(false)
                                settings.setAutomationDesired(false)
                                scheduler.disable()
                                driveConnected = false
                                automationDesired = false
                                statusMessage = tr("Google Drive отключён. Локальные данные и копии в Drive сохранены.")
                                statusError = false
                            } catch (failure: Exception) {
                                statusMessage = failure.message ?: tr("Не удалось отозвать разрешение Google. Подключение не изменено.")
                                statusError = true
                            } finally {
                                disconnectingDrive = false
                                showDisconnectConfirm = false
                            }
                        }
                    }
                }) { Text(tr("Отключить"), color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(enabled = !disconnectingDrive, onClick = { showDisconnectConfirm = false }) {
                    Text(tr("Отмена"))
                }
            }
        )
    }

    if (showCredentialDialog) {
        AlertDialog(
            onDismissRequest = { showCredentialDialog = false; recoveryKeyInput = "" },
            title = { Text(tr("Разрешить фоновый доступ к Recovery Key")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Введите Recovery Key. Он будет зашифрован отдельным ключом Android Keystore и сохранён только на этом устройстве. OAuth-токен здесь не сохраняется."), fontSize = 13.sp)
                    OutlinedTextField(
                        value = recoveryKeyInput,
                        onValueChange = { recoveryKeyInput = it.trim() },
                        label = { Text(tr("Recovery Key")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(tr("Не включайте эту опцию на чужом или общем устройстве."), color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = com.sboard.app.feature.backup.BackupCredentialPolicy.isValidRecoveryKey(recoveryKeyInput), onClick = {
                    runCatching { keyStore.saveForBackgroundBackup(recoveryKeyInput) }
                        .onSuccess {
                            credentialConfigured = true
                            statusMessage = tr("Recovery Key сохранён в зашифрованном виде на этом устройстве")
                            statusError = false
                        }
                        .onFailure {
                            statusMessage = tr("Не удалось сохранить Recovery Key")
                            statusError = true
                        }
                    recoveryKeyInput = ""
                    showCredentialDialog = false
                }) { Text(tr("Подтвердить")) }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialDialog = false; recoveryKeyInput = "" }) { Text(tr("Отмена")) }
            }
        )
    }
}

@Composable
private fun RestoreOnNewDeviceScreen(onBack: () -> Unit, onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val host = context as? Activity
    val scope = rememberCoroutineScope()
    val coordinator = remember(context) { DriveBackupRestoreCoordinator(context.applicationContext) }
    var accessToken by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<GoogleDriveBackupClient.DriveBackupFile>>(emptyList()) }
    var rejectedBackupCount by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<GoogleDriveBackupClient.DriveBackupFile?>(null) }
    var recoveryKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var restored by rememberSaveable { mutableStateOf(false) }

    fun loadBackups(token: String) {
        accessToken = token
        scope.launch {
            busy = true
            message = tr("Ищем резервные копии в Google Drive…")
            error = false
            try {
                val discovery = coordinator.discoverAvailableBackups(token)
                backups = discovery.backups
                rejectedBackupCount = discovery.rejectedMetadataCount
                selected = backups.firstOrNull()
                message = when {
                    backups.isNotEmpty() -> "${tr("Найдено резервных копий")}: ${backups.size}"
                    rejectedBackupCount > 0 -> tr("Копии найдены, но их метаданные не прошли проверку. Восстановление недоступно.")
                    else -> tr("В папке приложения Google Drive пока нет резервных копий sBoard")
                }
            } catch (failure: Exception) {
                message = failure.message ?: tr("Не удалось получить список резервных копий")
                error = true
            } finally {
                busy = false
            }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            message = tr("Подключение Google Drive отменено")
            error = true
        } else {
            runCatching {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
            }.onSuccess { token ->
                if (token.isNullOrBlank()) {
                    message = tr("Google не выдал access token")
                    error = true
                } else loadBackups(token)
            }.onFailure { failure ->
                message = failure.message ?: tr("Не удалось завершить авторизацию Google")
                error = true
            }
        }
    }

    fun connectGoogle() {
        val activity = host
        if (activity == null) {
            message = tr("Не удалось открыть авторизацию Google")
            error = true
            return
        }
        busy = true
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        busy = false
                        message = tr("Google не вернул запрос подтверждения доступа")
                        error = true
                    } else {
                        authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        busy = false
                    }
                } else {
                    val token = result.accessToken
                    busy = false
                    if (token.isNullOrBlank()) {
                        message = tr("Google не выдал access token")
                        error = true
                    } else loadBackups(token)
                }
            }
            .addOnFailureListener { failure ->
                busy = false
                message = failure.message ?: tr("Не удалось авторизовать Google Drive")
                error = true
            }
    }

    fun restoreChosenBackup() {
        val token = accessToken
        val version = selected
        if (token.isNullOrBlank() || version == null) {
            message = tr("Сначала подключите Google Drive и выберите резервную копию")
            error = true
            return
        }
        if (!recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            message = tr("Введите корректный Recovery Key")
            error = true
            return
        }
        scope.launch {
            busy = true
            message = tr("Проверяем и восстанавливаем зашифрованную копию…")
            error = false
            try {
                val result = coordinator.restoreSelectedBackup(
                    selected = version,
                    accessToken = token,
                    recoveryKey = recoveryKey,
                    session = VaultSessionProvider.session
                )
                restored = true
                onRestored()
                recoveryKey = ""
                message = "${tr("Восстановление завершено")}: ${result.fileName}. ${tr("Откройте вкладку Vault и войдите с прежним мастер-паролем.")}"
            } catch (failure: Exception) {
                message = failure.message ?: tr("Не удалось восстановить резервную копию")
                error = true
            } finally {
                busy = false
                confirmRestore = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = tr("Назад"), tint = Cream) }
            Column(Modifier.weight(1f)) {
                Text(tr("Восстановление на новом устройстве"), color = Cream, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(tr("Верните зашифрованный Vault из Google Drive"), color = Muted, fontSize = 12.sp)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, null, tint = Gold, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Ваши данные остаются зашифрованными"), color = Cream, fontWeight = FontWeight.SemiBold)
                    Text(tr("Google не получает Recovery Key. Он используется только локально для расшифровки."), color = Muted, fontSize = 11.sp)
                }
            }
        }
        Button(
            onClick = { connectGoogle() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Cloud, null)
            Spacer(Modifier.width(8.dp))
            Text(if (accessToken == null) tr("Подключить Google Drive") else tr("Обновить список копий"))
        }
        if (rejectedBackupCount > 0) {
            Text(
                "${tr("Пропущено копий с неполными или недопустимыми метаданными")}: $rejectedBackupCount",
                color = Color(0xFFFFC46B), fontSize = 11.sp
            )
        }
        if (backups.isNotEmpty()) {
            Text(tr("Выберите резервную копию"), color = Cream, fontWeight = FontWeight.SemiBold)
            backups.forEach { backup ->
                val isSelected = selected?.id == backup.id
                Surface(
                    color = if (isSelected) Color(0xFF343124) else SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = backup }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            null, tint = if (isSelected) Gold else Muted)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(backup.name, color = Cream, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (backup.name == GoogleDriveBackupClient.BACKUP_NAME) {
                                Text(tr("Старая резервная копия · доступна для восстановления"), color = Gold, fontSize = 10.sp)
                            }
                            Text(
                                buildString {
                                    append(backup.modifiedTime ?: tr("Дата неизвестна"))
                                    backup.size?.let { append(" · "); append(android.text.format.Formatter.formatShortFileSize(context, it)) }
                                },
                                color = Muted, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = recoveryKey,
                onValueChange = { recoveryKey = it.filterNot(Char::isWhitespace).take(64); error = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Recovery Key")) },
                placeholder = { Text(tr("Вставьте 43-символьный ключ")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(tr("Используйте Recovery Key, который вы сохранили при настройке старого устройства. Мастер-пароль сам по себе не заменяет этот ключ."), color = Muted, fontSize = 11.sp)
            Button(
                onClick = { confirmRestore = true },
                enabled = !busy && selected != null && recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Восстановить выбранную копию"))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (error) Color(0xFFFF8A80) else Gold, fontSize = 12.sp) }
        if (restored) {
            Panel {
                Text(tr("Следующий шаг"), color = Gold, fontWeight = FontWeight.SemiBold)
                Text(tr("Перейдите во вкладку Vault. Восстановленная база уже содержит ваш мастер-пароль и записи. Разблокируйте её прежним мастер-паролем."), color = Cream, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(tr("Восстановление заменяет локальную базу выбранной копией. Перед заменой sBoard проверяет ключ, шифрование и целостность SQLite."), color = Muted, fontSize = 10.sp)
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmRestore = false },
            title = { Text(tr("Заменить локальное хранилище?")) },
            text = { Text(tr("Текущая локальная база будет заменена выбранной резервной копией. Продолжайте только если хотите восстановить именно эту версию.")) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { restoreChosenBackup() }) { Text(tr("Восстановить"), color = Gold) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmRestore = false }) { Text(tr("Отмена")) }
            }
        )
    }
}

@Composable
private fun BackupScreen(session: VaultSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { EncryptedBackupManager(context.applicationContext) }
    var recoveryKey by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var pendingDriveBackup by remember { mutableStateOf<File?>(null) }
    var pendingDriveOperation by remember { mutableStateOf("upload") }
    var driveAccessToken by remember { mutableStateOf<String?>(null) }
    var driveVersions by remember { mutableStateOf<List<GoogleDriveBackupClient.DriveBackupFile>>(emptyList()) }
    var versionPendingRestore by remember { mutableStateOf<GoogleDriveBackupClient.DriveBackupFile?>(null) }
    var versionPendingDelete by remember { mutableStateOf<GoogleDriveBackupClient.DriveBackupFile?>(null) }
    val activity = context as? Activity
    val driveClient = remember { GoogleDriveBackupClient() }

    fun finishDriveUpload(accessToken: String) {
        val backupFile = pendingDriveBackup
        if (backupFile == null) {
            busy = false
            message = tr("Не найден подготовленный файл резервной копии")
            hasError = true
            return
        }
        scope.launch {
            try {
                val remote = driveClient.uploadVersioned(backupFile, accessToken)
                driveAccessToken = accessToken
                message = "${tr("Новая версия загружена в Google Drive")} · ${remote.name}"
                hasError = false
            } catch (e: Exception) {
                message = e.message ?: tr("Не удалось загрузить копию в Google Drive")
                hasError = true
            } finally {
                backupFile.delete()
                pendingDriveBackup = null
                busy = false
            }
        }
    }

    fun loadDriveHistory(accessToken: String) {
        driveAccessToken = accessToken
        scope.launch {
            busy = true
            try {
                driveVersions = driveClient.listVersions(accessToken)
                message = if (driveVersions.isEmpty()) tr("В Google Drive пока нет версий резервной копии")
                    else "${tr("Найдено версий")}: ${driveVersions.size}"
                hasError = false
            } catch (e: Exception) {
                message = e.message ?: tr("Не удалось получить историю резервных копий")
                hasError = true
            } finally {
                busy = false
            }
        }
    }

    fun deleteDriveVersion(version: GoogleDriveBackupClient.DriveBackupFile) {
        val token = driveAccessToken
        if (token.isNullOrBlank()) {
            message = tr("Сначала откройте историю Google Drive и подтвердите доступ")
            hasError = true
            return
        }
        scope.launch {
            busy = true
            try {
                val remaining = driveClient.deleteVersion(version, token)
                driveVersions = driveClient.listVersions(token)
                message = "${tr("Резервная копия удалена")}. ${tr("Осталось версий")}: $remaining"
                hasError = false
            } catch (e: Exception) {
                message = e.message ?: tr("Не удалось удалить резервную копию")
                hasError = true
            } finally {
                busy = false
            }
        }
    }

    fun restoreDriveVersion(version: GoogleDriveBackupClient.DriveBackupFile) {
        val token = driveAccessToken
        if (token.isNullOrBlank()) {
            message = tr("Сначала откройте историю Google Drive и подтвердите доступ")
            hasError = true
            return
        }
        if (!recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            message = tr("Введите корректный Recovery Key")
            hasError = true
            return
        }
        scope.launch {
            busy = true
            val temp = File(context.cacheDir, "sboard_drive_restore_${version.id}.enc")
            try {
                driveClient.downloadVersion(version, temp, token)
                manager.restoreBackup(recoveryKey, temp, session)
                SBoardDatabaseBackupObserver.install(context)
                BackupAutomationScheduler(context).requestAfterImportantChange()
                message = "${tr("Версия восстановлена")}: ${version.name}. ${tr("Повторно откройте Vault и разблокируйте его.")}"
                hasError = false
            } catch (e: Exception) {
                message = e.message ?: tr("Не удалось восстановить выбранную версию")
                hasError = true
            } finally {
                temp.delete()
                busy = false
            }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingDriveBackup?.delete()
            pendingDriveBackup = null
            busy = false
            message = tr("Авторизация Google Drive отменена")
            hasError = true
        } else {
            try {
                val authorizationResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(result.data)
                val token = authorizationResult.accessToken
                if (token.isNullOrBlank()) error(tr("Google не выдал access token"))
                if (pendingDriveOperation == "history") loadDriveHistory(token) else finishDriveUpload(token)
            } catch (e: Exception) {
                pendingDriveBackup?.delete()
                pendingDriveBackup = null
                busy = false
                message = e.message ?: tr("Не удалось завершить авторизацию Google")
                hasError = true
            }
        }
    }

    fun startGoogleDriveBackup() {
        pendingDriveOperation = "upload"
        val host = activity
        if (host == null) {
            message = tr("Не удалось получить Activity для авторизации Google")
            hasError = true
            return
        }
        if (!recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            message = tr("Введите корректный Recovery Key")
            hasError = true
            return
        }
        scope.launch {
            busy = true
            message = tr("Подготавливаем зашифрованную копию…")
            hasError = false
            val temp = File(context.cacheDir, "sboard_drive_backup.enc")
            try {
                manager.createBackup(recoveryKey, temp)
                pendingDriveBackup = temp
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                    .build()
                Identity.getAuthorizationClient(host).authorize(request)
                    .addOnSuccessListener { authorizationResult ->
                        if (authorizationResult.hasResolution()) {
                            val pendingIntent = authorizationResult.pendingIntent
                            if (pendingIntent == null) {
                                temp.delete()
                                pendingDriveBackup = null
                                busy = false
                                message = tr("Google не вернул запрос подтверждения доступа")
                                hasError = true
                            } else {
                                authorizationLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                )
                            }
                        } else {
                            val token = authorizationResult.accessToken
                            if (token.isNullOrBlank()) {
                                temp.delete()
                                pendingDriveBackup = null
                                busy = false
                                message = tr("Google не выдал access token")
                                hasError = true
                            } else finishDriveUpload(token)
                        }
                    }
                    .addOnFailureListener { error ->
                        temp.delete()
                        pendingDriveBackup = null
                        busy = false
                        message = error.message ?: tr("Не удалось авторизовать Google Drive")
                        hasError = true
                    }
            } catch (e: Exception) {
                temp.delete()
                pendingDriveBackup = null
                busy = false
                message = e.message ?: tr("Не удалось подготовить резервную копию")
                hasError = true
            }
        }
    }

    fun startGoogleDriveHistory() {
        pendingDriveOperation = "history"
        val host = activity
        if (host == null) {
            message = tr("Не удалось получить Activity для авторизации Google")
            hasError = true
            return
        }
        scope.launch {
            busy = true
            try {
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                    .build()
                Identity.getAuthorizationClient(host).authorize(request)
                    .addOnSuccessListener { authorizationResult ->
                        if (authorizationResult.hasResolution()) {
                            val pendingIntent = authorizationResult.pendingIntent
                            if (pendingIntent == null) {
                                busy = false
                                message = tr("Google не вернул запрос подтверждения доступа")
                                hasError = true
                            } else {
                                authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                            }
                        } else {
                            val token = authorizationResult.accessToken
                            if (token.isNullOrBlank()) {
                                busy = false
                                message = tr("Google не выдал access token")
                                hasError = true
                            } else loadDriveHistory(token)
                        }
                    }
                    .addOnFailureListener { error ->
                        busy = false
                        message = error.message ?: tr("Не удалось авторизовать Google Drive")
                        hasError = true
                    }
            } catch (e: Exception) {
                busy = false
                message = e.message ?: tr("Не удалось получить историю резервных копий")
                hasError = true
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val temp = File(context.cacheDir, "sboard_backup.enc")
                    manager.createBackup(recoveryKey, temp)
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    } ?: error(tr("Не удалось открыть файл для записи"))
                    temp.delete()
                    message = tr("Зашифрованная резервная копия создана. Храните Recovery Key отдельно от файла.")
                    hasError = false
                } catch (e: Exception) {
                    message = e.message ?: tr("Не удалось создать резервную копию")
                    hasError = true
                } finally {
                    busy = false
                }
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                val temp = File(context.cacheDir, "sboard_restore_input.enc")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error(tr("Не удалось прочитать выбранный файл"))
                    manager.restoreBackup(recoveryKey, temp, session)
                    SBoardDatabaseBackupObserver.install(context)
                    BackupAutomationScheduler(context).requestAfterImportantChange()
                    message = tr("Резервная копия восстановлена. Повторно откройте Vault и разблокируйте его.")
                    hasError = false
                } catch (e: Exception) {
                    message = e.message ?: tr("Не удалось восстановить резервную копию")
                    hasError = true
                } finally {
                    temp.delete()
                    busy = false
                }
            }
        }
    }

    versionPendingDelete?.let { selectedVersion ->
        AlertDialog(
            onDismissRequest = { versionPendingDelete = null },
            title = { Text(tr("Удалить резервную копию?")) },
            text = { Text("${selectedVersion.name}\n\n${tr("Выбранная версия будет безвозвратно удалена из папки приложения Google Drive. Последнюю копию удалить нельзя.")}") },
            confirmButton = {
                TextButton(onClick = {
                    versionPendingDelete = null
                    deleteDriveVersion(selectedVersion)
                }) { Text(tr("Удалить")) }
            },
            dismissButton = {
                TextButton(onClick = { versionPendingDelete = null }) { Text(tr("Отмена")) }
            }
        )
    }

    versionPendingRestore?.let { selectedVersion ->
        AlertDialog(
            onDismissRequest = { versionPendingRestore = null },
            title = { Text(tr("Восстановить эту версию?")) },
            text = { Text("${selectedVersion.name}\n\n${tr("Текущая база данных будет заменена после проверки резервной копии.")}") },
            confirmButton = {
                TextButton(onClick = {
                    versionPendingRestore = null
                    restoreDriveVersion(selectedVersion)
                }) { Text(tr("Восстановить")) }
            },
            dismissButton = {
                TextButton(onClick = { versionPendingRestore = null }) { Text(tr("Отмена")) }
            }
        )
    }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("← Назад")) }
            Spacer(Modifier.width(8.dp))
            Header(tr("Резервная копия"), tr("Зашифрованный перенос Vault"))
        }
        Panel {
            Text("Recovery Key", color = Cream, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Для создания и восстановления копии нужен Recovery Key, созданный в настройках Vault. Он не совпадает с мастер-паролем."),
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = recoveryKey,
                onValueChange = { recoveryKey = it.trim(); message = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("43-символьный Recovery Key")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = { exportPicker.launch("sboard_backup.enc") },
                enabled = !busy && recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Создать зашифрованную копию"))
            }
            OutlinedButton(
                onClick = { startGoogleDriveBackup() },
                enabled = !busy && activity != null && recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Cloud, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Загрузить зашифрованную копию в Google Drive"))
            }
            OutlinedButton(
                onClick = { startGoogleDriveHistory() },
                enabled = !busy && activity != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.History, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("История резервных копий Google Drive"))
            }
            if (driveVersions.isNotEmpty()) {
                Text(tr("Выберите версию для восстановления"), color = Cream, fontWeight = FontWeight.SemiBold)
                Text(tr("Удаление необратимо. Последняя резервная копия защищена."), color = Muted, fontSize = 11.sp)
                driveVersions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(version.name, maxLines = 1, color = Cream, fontSize = 12.sp)
                        Text("${version.size ?: "?"} bytes · ${version.modifiedTime ?: tr("дата неизвестна")}", color = Muted, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { versionPendingRestore = version },
                                enabled = !busy && recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")),
                                modifier = Modifier.weight(1f)
                            ) { Text(tr("Восстановить")) }
                            OutlinedButton(
                                onClick = { versionPendingDelete = version },
                                enabled = !busy && driveVersions.size > 1,
                                modifier = Modifier.weight(1f)
                            ) { Text(tr("Удалить"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/octet-stream", "application/*")) },
                enabled = !busy && recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Восстановить из файла"))
            }
            if (busy) CircularProgressIndicator()
            message?.let {
                Text(it, color = if (hasError) MaterialTheme.colorScheme.error else Gold, fontSize = 12.sp)
            }
        }
        Panel {
            Text(tr("Как это работает"), color = Cream, fontWeight = FontWeight.SemiBold)
            Text(
                tr("""• База данных шифруется отдельным ключом, полученным из Recovery Key через Argon2id.
• Каждый блок защищён AES-256-GCM.
• Перед восстановлением проверяются все теги подлинности и заголовок SQLite.
• Исходная база заменяется только после успешной проверки временного файла."""),
                color = Muted,
                fontSize = 12.sp
            )
            Text(tr("Храните Recovery Key отдельно от резервной копии. Потеря обоих означает потерю доступа к данным."), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
