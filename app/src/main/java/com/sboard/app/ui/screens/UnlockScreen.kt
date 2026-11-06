package com.sboard.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sboard.app.core.i18n.tr
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

/**
 * Master-password / biometric / Recovery-Key unlock. Shared by the app, Autofill, Passkeys and the
 * keyboard confirmation activity.
 */
@Composable
fun UnlockPanel(
    title: String = tr("sBoard заблокирован"),
    subtitle: String = tr("Введите мастер-пароль, чтобы открыть хранилище"),
    autoBiometric: Boolean = true,
    onUnlocked: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val keys = remember { VaultKeyManager(context) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var recoveryMode by remember { mutableStateOf(false) }
    var hasRecovery by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    val offset by animateFloatAsState(if (shake % 2 == 1) 1f else 0f, label = "shake")

    fun biometric() {
        val host = activity ?: return
        scope.launch {
            busy = true
            when (val r = Biometrics.unlock(host)) {
                AuthResult.Success -> onUnlocked()
                is AuthResult.Failed -> error = r.message
                AuthResult.Cancelled -> Unit
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        biometricEnabled = Biometrics.isEnabled(context) && Biometrics.canUseStrong(context)
        hasRecovery = keys.hasRecoveryKey()
        if (biometricEnabled && autoBiometric) biometric()
    }

    fun submit() {
        if (password.isEmpty() || busy) return
        scope.launch {
            busy = true; error = null
            try {
                val chars = password.toCharArray()
                if (recoveryMode) keys.unlockWithRecoveryKey(password) else keys.unlockWithPassword(chars)
                password = ""
                onUnlocked()
            } catch (e: Exception) {
                error = e.message ?: tr("Не удалось разблокировать")
                shake++
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(78.dp).clip(CircleShape).background(palette.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = palette.accent, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.text, textAlign = TextAlign.Center)
        Text(if (recoveryMode) tr("Введите Recovery Key из 43 символов") else subtitle,
            style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.graphicsLayer { translationX = offset * 18f }) {
            SBTextField(
                value = password, onValueChange = { password = it; error = null },
                label = if (recoveryMode) tr("Recovery Key") else tr("Мастер-пароль"),
                password = !recoveryMode, monospace = recoveryMode, isError = error != null, onDone = { submit() },
            )
        }
        AnimatedBanner(error, Tone.DANGER)
        PrimaryButton(tr("Разблокировать"), { submit() }, enabled = password.isNotEmpty(), loading = busy)
        if (biometricEnabled && !recoveryMode) {
            SecondaryButton(tr("Войти по биометрии"), { biometric() }, icon = Icons.Rounded.Fingerprint, enabled = !busy)
        }
        if (hasRecovery) {
            TextButton(onClick = { recoveryMode = !recoveryMode; password = ""; error = null }) {
                Text(if (recoveryMode) tr("Использовать мастер-пароль") else tr("Забыли мастер-пароль? Recovery Key"), color = palette.accent)
            }
        }
        footer()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.Shield, null, tint = palette.textFaint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("AES-256-GCM · Argon2id · Android Keystore"), style = MaterialTheme.typography.labelSmall, color = palette.textFaint)
        }
    }
}
