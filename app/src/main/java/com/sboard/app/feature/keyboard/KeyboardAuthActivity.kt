package com.sboard.app.feature.keyboard

import androidx.lifecycle.withStateAtLeast
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.components.*
import com.sboard.app.ui.screens.UnlockPanel
import com.sboard.app.ui.theme.SBoardTheme
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.SBoardVaultBridge

/**
 * Unlock / biometric confirmation for the keyboard. An approved value is queued and typed into the
 * same app as soon as its text field regains focus; it never touches the clipboard.
 */
class KeyboardAuthActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        val entryId = intent.getStringExtra(EXTRA_ENTRY)
        val fieldKey = intent.getStringExtra(EXTRA_FIELD)
        val target = intent.getStringExtra(EXTRA_TARGET)
        setContent {
            SBoardTheme {
                val unlocked by VaultSessionProvider.session.unlocked.collectAsState()
                var error by remember { mutableStateOf<String?>(null) }
                var needPassword by remember { mutableStateOf(false) }
                // Only the system biometric prompt is shown over the current app; the value is typed
                // into the field right after. The full unlock screen appears only when biometrics
                // are not set up (master password needed).
                LaunchedEffect(Unit) {
                    lifecycle.withStateAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) {}
                    if (!VaultSessionProvider.session.isUnlocked) {
                        if (Biometrics.isEnabled(this@KeyboardAuthActivity) && Biometrics.canUseStrong(this@KeyboardAuthActivity)) {
                            when (val r = Biometrics.unlock(this@KeyboardAuthActivity)) {
                                AuthResult.Success -> if (mode == MODE_CONFIRM && entryId != null && fieldKey != null) queueAndFinish(entryId, fieldKey, target) else finish()
                                AuthResult.Cancelled -> finish()
                                is AuthResult.Failed -> { error = r.message; needPassword = true }
                            }
                        } else needPassword = true
                    } else if (mode == MODE_UNLOCK) finish()
                    else error = confirm(entryId, fieldKey, target)
                }
                if (needPassword && !unlocked) {
                    Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.96f)).systemBarsPadding(), contentAlignment = Alignment.Center) {
                        UnlockPanel(title = "sPass", subtitle = tr("Разблокируйте sPass для вставки данных"), autoBiometric = false, onUnlocked = {
                            if (mode == MODE_CONFIRM && entryId != null && fieldKey != null) queueAndFinish(entryId, fieldKey, target) else finish()
                        })
                    }
                } else if (error != null) {
                    Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = 0.9f)).systemBarsPadding(), contentAlignment = Alignment.Center) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(Icons.Rounded.Key, size = 60.dp)
                            AnimatedBanner(error, Tone.DANGER)
                            PrimaryButton(tr("Повторить"), { lifecycleScope.launch { error = confirm(entryId, fieldKey, target) } })
                            SecondaryButton(tr("Отмена"), { finish() })
                        }
                    }
                }
            }
        }
    }

    private suspend fun confirm(entryId: String?, fieldKey: String?, target: String?): String? {
        if (entryId == null || fieldKey == null) { finish(); return null }
        val item = VaultRepository.get(this).get(entryId)
        return when (val r = Biometrics.confirm(this, tr("Вставить из «{0}»", item?.title.orEmpty()), tr("sBoard вставит значение в текущее поле"))) {
            AuthResult.Success -> { queueAndFinish(entryId, fieldKey, target); null }
            AuthResult.Cancelled -> { finish(); null }
            is AuthResult.Failed -> r.message
        }
    }

    private fun queueAndFinish(entryId: String, fieldKey: String, target: String?) {
        lifecycleScope.launch {
            val item = VaultRepository.get(this@KeyboardAuthActivity).get(entryId)
            KeyboardVaultProviderImpl.valueOf(item, fieldKey)?.let { SBoardVaultBridge.queue(it, target) }
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"; const val EXTRA_ENTRY = "entry"; const val EXTRA_FIELD = "field"; const val EXTRA_TARGET = "target"
        const val MODE_UNLOCK = "unlock"; const val MODE_CONFIRM = "confirm"
        fun intent(context: Context, mode: String, entryId: String?, fieldKey: String?, target: String?): Intent =
            Intent(context, KeyboardAuthActivity::class.java).putExtra(EXTRA_MODE, mode).putExtra(EXTRA_ENTRY, entryId)
                .putExtra(EXTRA_FIELD, fieldKey).putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
