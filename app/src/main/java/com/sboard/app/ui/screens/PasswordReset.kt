package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sboard.app.core.i18n.tr
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncEngine
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.ui.components.ListRow
import com.sboard.app.ui.components.SBTextField
import com.sboard.app.ui.components.StrengthBar
import com.sboard.app.ui.rememberGoogleAuthorizer
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

/**
 * Sets a new master password while the Vault is unlocked (no old password needed). Used after
 * unlocking with biometrics, the Recovery Key or the Google account when the password is forgotten.
 */
@Composable
fun NewMasterPasswordDialog(mandatory: Boolean, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = remember { VaultKeyManager(context) }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val strength = remember(next) { PasswordStrength.evaluate(next) }
    AlertDialog(onDismissRequest = { if (!busy && !mandatory) onDone(false) }, containerColor = palette.surface,
        title = { Text(tr("Новый мастер-пароль")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Старый пароль вводить не нужно: хранилище уже открыто. Новый пароль заменит старый на всех устройствах после синхронизации."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                SBTextField(next, { next = it; error = null }, tr("Новый мастер-пароль"), password = true)
                if (next.isNotEmpty()) StrengthBar(strength.score, strength.label)
                SBTextField(confirm, { confirm = it; error = null }, tr("Повторите новый пароль"), password = true, isError = confirm.isNotEmpty() && confirm != next)
                error?.let { Text(it, color = palette.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && next.length >= VaultKeyManager.MIN_PASSWORD_LENGTH && next == confirm && strength.score >= 2, onClick = {
                scope.launch {
                    busy = true
                    try { keys.resetMasterPassword(next.toCharArray()); next = ""; confirm = ""; onDone(true) }
                    catch (e: Exception) { error = e.message ?: tr("Не удалось изменить пароль") }
                    busy = false
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent) else Text(tr("Сохранить"), color = palette.accent) }
        },
        dismissButton = if (mandatory) null else ({ TextButton(enabled = !busy, onClick = { onDone(false) }) { Text(tr("Отмена"), color = palette.textMuted) } }),
    )
}

/**
 * "Forgot the master password?" — lists every way to open the Vault without it. After any of them
 * succeeds the user sets a new master password.
 */
@Composable
fun ForgotPasswordDialog(
    biometricAvailable: Boolean,
    recoveryKeyAvailable: Boolean,
    onUseRecoveryKey: () -> Unit,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val authorize = rememberGoogleAuthorizer(onToken = { token ->
        scope.launch {
            busy = true; error = null
            try {
                val engine = SyncEngine(context)
                val cloud = engine.inspectCloud(token) ?: error(tr("В этом аккаунте Google нет копии хранилища"))
                if (!engine.canRecoverWithGoogle(cloud)) error(tr("Восстановление через Google не было включено на этом хранилище"))
                engine.restoreWithGoogle(cloud)
                runCatching { engine.refreshProfile(token) }
                markGoogleConnected(context)
                com.sboard.app.feature.sync.SyncScheduler.ensurePeriodic(context)
                onUnlocked()
            } catch (e: Exception) { error = e.message ?: tr("Не удалось восстановить через Google") }
            busy = false
        }
    }, onError = { error = it })

    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = palette.surface,
        title = { Text(tr("Забыли мастер-пароль?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Откройте хранилище другим способом и задайте новый пароль — старый вводить не нужно."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                if (biometricAvailable) ListRow(tr("Биометрия"), tr("Отпечаток или лицо на этом устройстве"), icon = Icons.Rounded.Fingerprint, onClick = {
                    val host = activity ?: return@ListRow
                    scope.launch {
                        busy = true
                        when (val r = Biometrics.unlock(host)) {
                            AuthResult.Success -> onUnlocked()
                            is AuthResult.Failed -> error = r.message
                            AuthResult.Cancelled -> Unit
                        }
                        busy = false
                    }
                })
                if (recoveryKeyAvailable) ListRow(tr("Recovery Key"), tr("Ключ из 43 символов, сохранённый при настройке"), icon = Icons.Rounded.Key, onClick = onUseRecoveryKey)
                ListRow(tr("Аккаунт Google"), tr("Если включено «Восстановление через Google» — просто войдите в аккаунт"), icon = Icons.Rounded.CloudSync,
                    onClick = { if (!busy) authorize() })
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = palette.accent)
                error?.let { Text(it, color = palette.danger, style = MaterialTheme.typography.bodySmall) }
                if (!biometricAvailable && !recoveryKeyAvailable) Text(
                    tr("Без биометрии, Recovery Key и восстановления через Google открыть хранилище невозможно: так устроено сквозное шифрование."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Закрыть"), color = palette.textMuted) } },
    )
}

/** Marks the Google account as connected after a recovery sign-in so sync continues. */
internal fun markGoogleConnected(context: android.content.Context) {
    GoogleAccount.account.value?.let { GoogleAccount.markConnected(context, it.email, it.name) }
}
