package com.sboard.app.feature.autofill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.data.DomainMatcher
import com.sboard.app.data.ItemType
import com.sboard.app.data.LoginData
import com.sboard.app.data.VaultItemPayload
import com.sboard.app.data.VaultRepository
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.components.*
import com.sboard.app.ui.screens.UnlockPanel
import com.sboard.app.ui.theme.SBoardTheme
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

/** "Save password to sBoard?" — shown after the user submitted a login form. */
class AutofillSaveActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = intent.getStringExtra(EXTRA_TOKEN)?.let(PendingSaves::take)
        if (entry == null) { finish(); return }
        val host = entry.webDomain?.let(DomainMatcher::normalizeHost)
        val appName = entry.packageName?.let { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        setContent {
            SBoardTheme {
                val unlocked by VaultSessionProvider.session.unlocked.collectAsState()
                Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
                    if (!unlocked) UnlockPanel(title = tr("Сохранить в sBoard"), subtitle = tr("Разблокируйте хранилище, чтобы сохранить пароль"), onUnlocked = {})
                    else SaveForm(entry, host, appName)
                }
            }
        }
    }

    @Composable
    private fun SaveForm(entry: PendingSaves.Entry, host: String?, appName: String?) {
        val repo = remember { VaultRepository.get(this) }
        val items by repo.items.collectAsState()
        var title by remember { mutableStateOf(host ?: appName ?: entry.packageName.orEmpty()) }
        var username by remember { mutableStateOf(entry.username) }
        var busy by remember { mutableStateOf(false) }
        val existing = items.firstOrNull { item ->
            val login = item.payload.login ?: return@firstOrNull false
            item.type == ItemType.LOGIN && login.username.equals(username, ignoreCase = true) &&
                ((host != null && login.urls.any { DomainMatcher.matches(it, host) }) || (entry.packageName != null && entry.packageName in login.packageNames))
        }
        val unchanged = existing?.payload?.login?.password == entry.password
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Save, size = 56.dp)
            Text(if (existing != null) tr("Обновить пароль?") else tr("Сохранить пароль?"), style = MaterialTheme.typography.headlineSmall, color = palette.text)
            Text(host ?: appName ?: "", style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
            if (existing == null) SBTextField(title, { title = it }, tr("Название"))
            SBTextField(username, { username = it }, tr("Логин"))
            FieldRow(tr("Пароль"), entry.password, sensitive = true)
            if (unchanged) Banner(tr("Этот пароль уже сохранён."), Tone.SUCCESS)
            PrimaryButton(if (existing != null) tr("Обновить") else tr("Сохранить"), {
                lifecycleScope.launch {
                    busy = true
                    if (existing != null) {
                        val login = existing.payload.login!!
                        repo.save(existing.id, existing.payload.copy(login = login.copy(password = entry.password, passwordChangedAt = System.currentTimeMillis())))
                    } else {
                        repo.save(null, VaultItemPayload(type = ItemType.LOGIN, title = title.ifBlank { host ?: "Login" },
                            login = LoginData(username = username, password = entry.password, urls = listOfNotNull(host),
                                packageNames = listOfNotNull(entry.packageName), passwordChangedAt = System.currentTimeMillis())))
                    }
                    finish()
                }
            }, enabled = !busy && !unchanged, loading = busy)
            SecondaryButton(tr("Не сейчас"), { finish() })
        }
    }

    companion object {
        const val EXTRA_TOKEN = "sboard.autofill.save_token"
        fun intent(context: Context, token: String): Intent = Intent(context, AutofillSaveActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }
}
