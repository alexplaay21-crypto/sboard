package com.sboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sboard.app.feature.tools.clipboard.SBoardClipboardWriter
import com.sboard.app.feature.vault.MasterPasswordManager
import com.sboard.app.feature.vault.VaultRecoveryManager
import com.sboard.app.feature.vault.VaultSessionProvider
import kotlinx.coroutines.launch

/** First-run security setup. Recovery Key is shown once and never persisted in plaintext. */
@Composable
internal fun FirstRunSetupScreen(
    existingVault: Boolean,
    onComplete: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterManager = remember(context) { MasterPasswordManager(context.applicationContext) }
    val recoveryManager = remember(context) { VaultRecoveryManager(context.applicationContext) }
    val clipboard = remember(context) { SBoardClipboardWriter(context) }

    // Secrets deliberately use remember, not rememberSaveable: never put them in saved UI state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var keyStep by rememberSaveable { mutableStateOf(false) }
    var vaultExists by remember(existingVault) { mutableStateOf(existingVault) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text("sBoard", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                if (keyStep) tr("Сохраните Recovery Key") else if (vaultExists) tr("Завершите защиту хранилища") else tr("Добро пожаловать в sBoard"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (keyStep) tr("Этот ключ понадобится для восстановления зашифрованной копии на другом устройстве. Сохраните его отдельно от телефона.")
                else if (vaultExists) tr("Хранилище уже существует. Введите прежний мастер-пароль, чтобы выпустить новый Recovery Key и завершить первоначальную настройку.")
                else tr("Сначала создадим локальное хранилище и ключ восстановления. Подключение Google Drive можно настроить после этого в профиле."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!keyStep) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(256); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Мастер-пароль")) },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Lock, null) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (!vaultExists) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.take(256); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Повторите мастер-пароль")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                Text(tr("Минимум 12 символов. Не используйте пароль от Google или других сайтов."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !busy && password.length >= 12 && (vaultExists || password == confirmation),
                    onClick = {
                        context.getSharedPreferences("sboard_first_run_v1", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("started", true).commit()
                        val enteredPassword = password
                        password = ""
                        confirmation = ""
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                if (vaultExists) {
                                    masterManager.unlock(enteredPassword.toCharArray(), VaultSessionProvider.session)
                                    recoveryKey = recoveryManager.replaceRecoveryKey(VaultSessionProvider.session)
                                } else {
                                    masterManager.createMasterPassword(enteredPassword.toCharArray(), VaultSessionProvider.session)
                                    vaultExists = true
                                    recoveryKey = recoveryManager.createRecoveryKey(VaultSessionProvider.session)
                                }
                                keyStep = true
                            } catch (failure: Exception) {
                                error = failure.message ?: tr("Не удалось подготовить защищённое хранилище")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator() else Text(if (vaultExists) tr("Проверить пароль и создать Recovery Key") else tr("Создать защищённое хранилище"))
                }
                OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Восстановить существующий Vault из Google Drive"))
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary)
                            Text(tr("Recovery Key — 43 символа"), fontWeight = FontWeight.SemiBold)
                        }
                        Text(recoveryKey, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = {
                            clipboard.copy(recoveryKey, sensitive = true)
                            copied = true
                        }, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Rounded.ContentCopy, null)
                            Text(if (copied) tr("Скопировано — уберите ключ в надёжное место") else tr("Скопировать Recovery Key"))
                        }
                    }
                }
                Text(tr("Важно: мастер-пароль и Recovery Key — разные секреты. Google получает только зашифрованный файл, но не этот ключ. Если потерять Recovery Key и устройство, восстановить данные может быть невозможно."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(tr("Я сохранил Recovery Key отдельно и смогу найти его позже."), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onComplete, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Завершить настройку"))
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
