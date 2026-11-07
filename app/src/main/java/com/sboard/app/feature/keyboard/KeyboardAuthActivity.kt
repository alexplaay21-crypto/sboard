package com.sboard.app.feature.keyboard

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
                Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
                    when {
                        !unlocked -> UnlockPanel(title = tr("sBoard Pass"), subtitle = tr("Разблокируйте хранилище для вставки данных"), onUnlocked = {
                            if (mode == MODE_CONFIRM && entryId != null && fieldKey != null) queueAndFinish(entryId, fieldKey, target) else finish()
                        })
                        mode == MODE_UNLOCK -> LaunchedEffect(Unit) { finish() }
                        else -> {
                            LaunchedEffect(Unit) { error = confirm(entryId, fieldKey, target) }
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                IconBadge(Icons.Rounded.Key, size = 60.dp)
                                Text(tr("Подтвердите вставку"), style = MaterialTheme.typography.headlineSmall, color = palette.text)
                                AnimatedBanner(error, Tone.DANGER)
                                PrimaryButton(tr("Подтвердить"), { lifecycleScope.launch { error = confirm(entryId, fieldKey, target) } })
                                SecondaryButton(tr("Отмена"), { finish() })
                            }
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
