package com.sboard.app.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.sboard.app.feature.tools.clipboard.SBoardClipboardWriter
import com.sboard.app.feature.sync.SyncAutomationScheduler
import com.sboard.app.tr
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val vaultCategories = listOf(
    "Пароль", "Банковская карта", "Контакт", "Личные данные",
    "Код 2FA", "Заметка", "Wi-Fi", "Лицензия", "Другое"
)

@Composable
fun VaultScreen(session: VaultSession) {
    val context = LocalContext.current
    val manager = remember(context) { MasterPasswordManager(context) }
    val recoveryManager = remember(context) { VaultRecoveryManager(context) }
    val biometricManager = remember(context) { BiometricVaultManager(context) }
    var hasMasterPassword by remember { mutableStateOf<Boolean?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.lock()
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.lock()
        }
    }

    LaunchedEffect(manager) {
        hasMasterPassword = manager.hasMasterPassword()
    }

    if (unlocked && session.isUnlocked) {
        VaultUnlockedScreen(
            session = session,
            masterPasswordManager = manager,
            recoveryManager = recoveryManager,
            biometricManager = biometricManager,
            onLock = {
                session.lock()
                unlocked = false
            }
        )
    } else {
        MasterPasswordGate(
            hasMasterPassword = hasMasterPassword,
            manager = manager,
            recoveryManager = recoveryManager,
            biometricManager = biometricManager,
            session = session,
            onUnlocked = {
                unlocked = true
                SyncAutomationScheduler(context).requestOnForeground()
            }
        )
    }
}

@Composable
private fun MasterPasswordGate(
    hasMasterPassword: Boolean?,
    manager: MasterPasswordManager,
    recoveryManager: VaultRecoveryManager,
    biometricManager: BiometricVaultManager,
    session: VaultSession,
    onUnlocked: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveryInput by remember { mutableStateOf("") }
    var biometricEnabled by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    LaunchedEffect(recoveryManager, biometricManager) {
        biometricEnabled = biometricManager.isEnabled()
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            if (hasMasterPassword == false) tr("Создайте мастер-пароль") else tr("Разблокируйте Vault"),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            if (hasMasterPassword == false) tr("Он защищает ключ вашего хранилища. Используйте уникальную фразу минимум из 12 символов.") else tr("Введите мастер-пароль для доступа к сохранённым данным."),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasMasterPassword == null) {
            androidx.compose.material3.CircularProgressIndicator()
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Мастер-пароль")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            if (hasMasterPassword == false) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Повторите мастер-пароль")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (hasMasterPassword == true && biometricEnabled && activity != null && biometricManager.canAuthenticate(context)) {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val cipher = biometricManager.prepareUnlockCipher()
                                    ?: throw IllegalStateException(tr("Биометрическая разблокировка недоступна"))
                                BiometricVaultManager.authenticate(
                                    activity,
                                    cipher,
                                    tr("Разблокировать sBoard"),
                                    onSuccess = { authenticatedCipher ->
                                        scope.launch {
                                            try {
                                                biometricManager.finishUnlock(authenticatedCipher, session)
                                                error = null
                                                onUnlocked()
                                            } catch (e: Exception) {
                                                error = e.message ?: tr("Не удалось открыть Vault")
                                            } finally { busy = false }
                                        }
                                    },
                                    onError = { message -> error = message; busy = false }
                                )
                            } catch (e: Exception) {
                                error = e.message ?: tr("Биометрия недоступна")
                                busy = false
                            }
                        }
                    }
                ) { Text(tr("Разблокировать биометрией")) }
            }
            if (hasMasterPassword == true) {
                OutlinedTextField(
                    value = recoveryInput,
                    onValueChange = { recoveryInput = it.trim(); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recovery Key") },
                    singleLine = true
                )
                TextButton(
                    enabled = !busy && recoveryInput.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                recoveryManager.recover(recoveryInput, session)
                                recoveryInput = ""
                                onUnlocked()
                            } catch (e: Exception) {
                                error = e.message ?: tr("Не удалось восстановить доступ")
                            } finally { busy = false }
                        }
                    }
                ) { Text(tr("Восстановить доступ по Recovery Key")) }
            }
            Button(
                enabled = !busy && password.isNotEmpty() && (hasMasterPassword || confirmation.isNotEmpty()),
                onClick = {
                    if (hasMasterPassword == false && password != confirmation) {
                        error = tr("Пароли не совпадают")
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                val chars = password.toCharArray()
                                if (hasMasterPassword == false) manager.createMasterPassword(chars, session)
                                else manager.unlock(chars, session)
                                password = ""
                                confirmation = ""
                                error = null
                                onUnlocked()
                            } catch (e: Exception) {
                                error = e.message ?: tr("Не удалось открыть хранилище")
                            } finally {
                                password = ""
                                confirmation = ""
                                busy = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) tr("Подождите…") else if (hasMasterPassword == false) tr("Создать защищённое хранилище") else tr("Разблокировать"))
            }
        }
    }
}

@Composable
private fun VaultUnlockedScreen(
    session: VaultSession,
    masterPasswordManager: MasterPasswordManager,
    recoveryManager: VaultRecoveryManager,
    biometricManager: BiometricVaultManager,
    onLock: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val repository = remember(context, session) { VaultRepository(context, session) }
    val clipboardWriter = remember(context) { SBoardClipboardWriter(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<VaultRecord>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VaultRecord?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasRecovery by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var showPasswordChange by remember { mutableStateOf(false) }
    val syncInboxManager = remember(context, session) { VaultSyncInboxManager(context, session) }
    var pendingSyncReviews by remember { mutableStateOf<List<VaultSyncInboxManager.PendingReview>>(emptyList()) }
    var syncReviewBusy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recoveryManager, biometricManager) {
        hasRecovery = recoveryManager.hasRecoveryKey()
        biometricEnabled = biometricManager.isEnabled()
    }

    suspend fun refreshSyncReviews() {
        try { pendingSyncReviews = syncInboxManager.pendingReviews() }
        catch (e: Exception) { error = e.message ?: tr("Не удалось загрузить изменения синхронизации") }
    }
    suspend fun refresh() {
        try { records = repository.getAll(); error = null }
        catch (e: Exception) { error = "${tr("Не удалось открыть хранилище")}: ${e.localizedMessage ?: tr("ошибка данных")}" }
    }
    LaunchedEffect(syncInboxManager) {
        // Safe automatic convergence: only previously absent record IDs are accepted.
        // Existing local records remain visible in the manual review panel.
        try { syncInboxManager.applyNonConflicting() } catch (_: Exception) { }
        refreshSyncReviews()
        refresh()
    }

    LaunchedEffect(repository) { refresh() }

    val filtered = records.filter {
        it.title.contains(query, true) || it.username.contains(query, true) || it.website.contains(query, true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Хранилище"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(tr("Локальные зашифрованные записи"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            IconButton(onClick = onLock) {
                Icon(Icons.Rounded.LockOpen, contentDescription = tr("Заблокировать Vault"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { selected = null; showEditor = true }) {
                Icon(Icons.Rounded.Add, contentDescription = tr("Добавить запись"), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasRecovery) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try { recoveryCode = recoveryManager.createRecoveryKey(session); hasRecovery = true }
                            catch (e: Exception) { error = e.message ?: tr("Не удалось создать Recovery Key") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Создать Recovery Key")) }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try { recoveryCode = recoveryManager.replaceRecoveryKey(session) }
                            catch (e: Exception) { error = e.message ?: tr("Не удалось обновить Recovery Key") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Обновить Recovery Key")) }
            }
            if (!biometricEnabled && activity != null && biometricManager.canAuthenticate(context)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                val cipher = biometricManager.prepareEnrollmentCipher()
                                BiometricVaultManager.authenticate(
                                    activity, cipher, tr("Включить биометрическую защиту"),
                                    onSuccess = { authenticatedCipher ->
                                        scope.launch {
                                            try { biometricManager.saveEnrollment(authenticatedCipher, session); biometricEnabled = true }
                                            catch (e: Exception) { error = e.message ?: tr("Не удалось включить биометрию") }
                                        }
                                    },
                                    onError = { message -> error = message }
                                )
                            } catch (e: Exception) { error = e.message ?: tr("Не удалось подготовить биометрию") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Включить биометрию")) }
            }
        }
        if (biometricEnabled) {
            TextButton(onClick = { scope.launch { biometricManager.disable(); biometricEnabled = false } }) {
                Text(tr("Отключить биометрию"))
            }
        }
        TextButton(onClick = { showPasswordChange = true }) {
            Text(tr("Сменить мастер-пароль"))
        }
        if (pendingSyncReviews.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Изменения с других устройств"), fontWeight = FontWeight.Bold)
                            Text("${pendingSyncReviews.size} · ${tr("требуют вашего решения")}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        TextButton(onClick = { scope.launch { refreshSyncReviews() } }) { Text(tr("Обновить")) }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pendingSyncReviews, key = { it.changeId }) { item ->
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (item.operation == "DELETE") tr("Удаление записи") else tr("Изменение записи"), fontWeight = FontWeight.SemiBold)
                                Text("${tr("ID записи")}: ${item.recordId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${tr("Устройство")}: ${item.deviceId.take(12)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.changedAt))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(tr("Содержимое зашифровано. Выберите, какую версию оставить."), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = syncReviewBusy == null, modifier = Modifier.weight(1f), onClick = {
                                        syncReviewBusy = item.changeId
                                        scope.launch {
                                            try { syncInboxManager.resolve(item.changeId, VaultSyncInboxManager.Decision.KEEP_LOCAL); refreshSyncReviews() }
                                            catch (e: Exception) { error = e.message ?: tr("Не удалось сохранить локальную версию") }
                                            finally { syncReviewBusy = null }
                                        }
                                    }) { Text(tr("Оставить мою")) }
                                    Button(enabled = syncReviewBusy == null, modifier = Modifier.weight(1f), onClick = {
                                        syncReviewBusy = item.changeId
                                        scope.launch {
                                            try { syncInboxManager.resolve(item.changeId, VaultSyncInboxManager.Decision.ACCEPT_REMOTE); refresh(); refreshSyncReviews() }
                                            catch (e: Exception) { error = e.message ?: tr("Не удалось принять облачную версию") }
                                            finally { syncReviewBusy = null }
                                        }
                                    }) { Text(tr("Принять из облака")) }
                                }
                                if (syncReviewBusy == item.changeId) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text(tr("Поиск по названию, логину или сайту")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }
        )
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Шифрование на устройстве"), fontWeight = FontWeight.SemiBold)
                    Text("AES-256-GCM · Android Keystore", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Key, null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (query.isBlank()) tr("Пока нет записей") else tr("Ничего не найдено"), fontWeight = FontWeight.SemiBold)
                Text(if (query.isBlank()) tr("Добавьте первый пароль или заметку") else tr("Попробуйте изменить запрос"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (query.isBlank()) Button(onClick = { selected = null; showEditor = true }) { Text(tr("Добавить запись")) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(filtered, key = { it.id }) { record ->
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { selected = record }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(record.title, fontWeight = FontWeight.SemiBold)
                                Text("${tr(record.category)} · ${record.username.ifBlank { record.website }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { scope.launch { repository.delete(record.id); refresh() } }) {
                                Icon(Icons.Rounded.Delete, contentDescription = tr("Удалить"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        VaultEditorDialog(
            initial = selected,
            onDismiss = { showEditor = false },
            onSave = { title, category, username, password, website, notes ->
                scope.launch {
                    try {
                        repository.save(selected?.id, title, category, username, password, website, notes)
                        showEditor = false
                        refresh()
                    } catch (e: Exception) {
                        error = "${tr("Не удалось сохранить запись")}: ${e.localizedMessage ?: tr("ошибка")}"
                    }
                }
            }
        )
    }
    selected?.takeIf { !showEditor }?.let { record ->
        VaultDetailDialog(
            record = record,
            onDismiss = { selected = null },
            onEdit = { showEditor = true },
            onDelete = { scope.launch { repository.delete(record.id); selected = null; refresh() } }
        )
    }

    if (showPasswordChange) {
        ChangeMasterPasswordDialog(
            onDismiss = { showPasswordChange = false },
            onSave = { newPassword ->
                scope.launch {
                    try {
                        masterPasswordManager.changeMasterPassword(newPassword.toCharArray(), session)
                        showPasswordChange = false
                        error = null
                    } catch (e: Exception) {
                        error = e.message ?: tr("Не удалось сменить мастер-пароль")
                    }
                }
            }
        )
    }

    recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = { recoveryCode = null },
            title = { Text(tr("Ваш Recovery Key")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Сохраните этот ключ в безопасном месте. Он показывается только сейчас и позволит восстановить Vault на другом устройстве."))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Text(code, modifier = Modifier.fillMaxWidth().padding(12.dp), fontWeight = FontWeight.Bold)
                    }
                    Text(tr("Не отправляйте его посторонним. Потеря Recovery Key означает потерю этого способа восстановления."), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboardWriter.copy(code, sensitive = true) }) { Text(tr("Скопировать")) }
            },
            dismissButton = { TextButton(onClick = { recoveryCode = null }) { Text(tr("Я сохранил ключ")) } }
        )
    }
}

@Composable
private fun ChangeMasterPasswordDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Сменить мастер-пароль")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Vault Key останется прежним — записи не потребуется перешифровывать."))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(tr("Новый пароль · минимум 12 символов")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; error = null },
                    label = { Text(tr("Повторите пароль")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    password.length < 12 -> error = tr("Минимум 12 символов")
                    password != confirmation -> error = tr("Пароли не совпадают")
                    else -> onSave(password)
                }
            }) { Text(tr("Сохранить")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun VaultEditorDialog(
    initial: VaultRecord?, onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var category by rememberSaveable(initial?.id) { mutableStateOf(initial?.category ?: "Пароль") }
    var username by rememberSaveable(initial?.id) { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable(initial?.id) { mutableStateOf(initial?.password.orEmpty()) }
    var website by rememberSaveable(initial?.id) { mutableStateOf(initial?.website.orEmpty()) }
    var notes by rememberSaveable(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("Новая запись") else tr("Изменить запись")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(tr("Название*")) }, singleLine = true)
                Column {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(category) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        vaultCategories.forEach { item -> DropdownMenuItem(text = { Text(tr(item)) }, onClick = { category = item; menu = false }) }
                    }
                }
                OutlinedTextField(username, { username = it }, label = { Text(tr("Логин / email")) }, singleLine = true)
                OutlinedTextField(
                    password, { password = it }, label = { Text(tr("Пароль / секрет")) }, singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } }
                )
                OutlinedTextField(website, { website = it }, label = { Text(tr("Сайт / адрес")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(notes, { notes = it }, label = { Text(tr("Заметка")) }, minLines = 2)
                if (category == "Код 2FA") {
                    Text(
                        tr("Для TOTP укажите Base32-секрет в поле «Пароль / секрет». При необходимости добавьте в заметку отдельной строкой: sboard-otp: digits=6;period=30;algorithm=SHA1"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = {
            if (title.isBlank()) validation = tr("Укажите название записи")
            else onSave(title.trim(), category, username, password, website, notes)
        }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } }
    )
}

@Composable
private fun VaultDetailDialog(record: VaultRecord, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var reveal by rememberSaveable(record.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine(tr("Категория"), record.category)
                DetailLine(tr("Логин"), record.username)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Пароль / секрет"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Text(if (reveal) record.password.ifBlank { "—" } else if (record.password.isBlank()) "—" else "••••••••", fontWeight = FontWeight.Medium)
                    }
                    IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) }
                }
                DetailLine(tr("Сайт"), record.website)
                DetailLine(tr("Заметка"), record.notes)
                Text("${tr("Изменено:")} ${DateFormat.getDateTimeInstance().format(Date(record.updatedAt))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text(tr("Изменить")) } },
        dismissButton = { Row { TextButton(onClick = onDelete) { Text(tr("Удалить")) }; TextButton(onClick = onDismiss) { Text(tr("Закрыть")) } } }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value.ifBlank { "—" }, fontWeight = FontWeight.Medium)
    }
}
