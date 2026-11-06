package com.sboard.app.feature.autofill

import android.app.Activity
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.VaultSessionProvider
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.data.DomainMatcher
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItem
import com.sboard.app.data.VaultRepository
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.ui.SecureActivity
import com.sboard.app.ui.components.*
import com.sboard.app.ui.screens.UnlockPanel
import com.sboard.app.ui.theme.SBoardTheme
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch

/** Confirms (and if needed unlocks) before any value is handed to the Autofill framework. */
class AutofillActivity : SecureActivity() {
    private var form: ParsedForm? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val structure: AssistStructure? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        val parsed = structure?.let { FieldParser.parse(it) }
        if (parsed == null || parsed.fields.isEmpty()) { setResult(Activity.RESULT_CANCELED); finish(); return }
        form = parsed
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PICK
        val targetId = intent.getStringExtra(EXTRA_ITEM_ID)
        setContent {
            SBoardTheme {
                val unlocked by VaultSessionProvider.session.unlocked.collectAsState()
                var justUnlocked by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding()) {
                    if (!unlocked) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            UnlockPanel(title = tr("Автозаполнение sBoard"), subtitle = parsed.webDomain ?: appLabel(parsed.packageName),
                                onUnlocked = { justUnlocked = true })
                        }
                    } else Picker(parsed, if (mode == MODE_CONFIRM) targetId else null, justUnlocked)
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    @Composable
    private fun Picker(form: ParsedForm, targetId: String?, justUnlocked: Boolean) {
        val repo = remember { VaultRepository.get(this) }
        val items by repo.items.collectAsState()
        var query by remember { mutableStateOf("") }
        var link by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        val fillable = items.filter { it.type in setOf(ItemType.LOGIN, ItemType.CARD, ItemType.IDENTITY, ItemType.TOTP) && AutofillDatasets.values(it, form).isNotEmpty() }
        val matches = AutofillDatasets.matching(fillable, form).map { it.id }.toSet()
        val target = targetId?.let { id -> items.firstOrNull { it.id == id } }
        val list = (if (target != null) listOf(target) else fillable.sortedByDescending { it.id in matches })
            .filter { query.isBlank() || it.payload.searchText().contains(query.lowercase()) }
        val site = form.webDomain ?: appLabel(form.packageName)
        ScreenList(Modifier.fillMaxSize(), bottomPadding = 32.dp) {
            item { ScreenHeader(tr("Заполнить с sBoard"), site) }
            item { Banner(tr("Выберите запись. Данные будут переданы только в поля этого экрана."), Tone.INFO, Icons.Rounded.AutoAwesome) }
            if (target == null) item { SBTextField(query, { query = it }, tr("Поиск")) }
            error?.let { item { Banner(it, Tone.DANGER) } }
            if (list.isEmpty()) item { EmptyState(Icons.Rounded.SearchOff, tr("Нет подходящих записей"), tr("Добавьте логин в sBoard, и он появится здесь.")) }
            items(list, key = { it.id }) { item ->
                SBCard(onClick = { lifecycleScope.launch { error = fill(item, form, justUnlocked, link && item.id !in matches) } }) {
                    ListRow(item.title, item.subtitle(), leading = { ItemBadge(item.type, item.title, 40.dp) }, trailing = {
                        if (item.id in matches) Text(tr("подходит"), style = MaterialTheme.typography.labelSmall, color = palette.accent)
                    })
                }
            }
            if (target == null && list.any { it.type == ItemType.LOGIN && it.id !in matches }) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link, { link = it }, colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent))
                    Text(tr("Запомнить выбор для «{0}»", site), style = MaterialTheme.typography.bodySmall, color = palette.text)
                }
            }
            item { SecondaryButton(tr("Отмена"), { setResult(Activity.RESULT_CANCELED); finish() }) }
        }
    }

    private suspend fun fill(item: VaultItem, form: ParsedForm, justUnlocked: Boolean, link: Boolean): String? {
        val sensitive = item.type != ItemType.IDENTITY
        if (sensitive && AppSettings.biometricForSensitive.value && !justUnlocked) {
            when (val r = Biometrics.confirm(this, tr("Заполнить «{0}»", item.title), form.webDomain ?: form.packageName)) {
                AuthResult.Success -> Unit
                AuthResult.Cancelled -> return null
                is AuthResult.Failed -> return r.message
            }
        }
        if (link && item.type == ItemType.LOGIN) {
            val login = item.payload.login!!
            val updated = if (form.webDomain != null) {
                val host = DomainMatcher.normalizeHost(form.webDomain)
                if (host != null && login.urls.none { DomainMatcher.matches(it, host) }) login.copy(urls = login.urls + host) else login
            } else if (!AutofillDatasets.isBrowser(form.packageName) && form.packageName !in login.packageNames) {
                login.copy(packageNames = login.packageNames + form.packageName)
            } else login
            if (updated != login) VaultRepository.get(this).save(item.id, item.payload.copy(login = updated), snapshot = false)
        }
        val dataset = AutofillDatasets.filledDataset(this, item, form) ?: return tr("Для этой формы нет подходящих полей")
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
        return null
    }

    companion object {
        const val EXTRA_MODE = "sboard.autofill.mode"
        const val EXTRA_ITEM_ID = "sboard.autofill.item"
        const val MODE_UNLOCK_PICK = "unlock_pick"
        const val MODE_PICK = "pick"
        const val MODE_CONFIRM = "confirm"

        fun intent(context: Context, mode: String, itemId: String?): Intent =
            Intent(context, AutofillActivity::class.java).putExtra(EXTRA_MODE, mode).apply { if (itemId != null) putExtra(EXTRA_ITEM_ID, itemId) }

        fun intentSender(context: Context, mode: String, itemId: String?, requestCode: Int): IntentSender =
            PendingIntent.getActivity(context, requestCode, intent(context, mode, itemId),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }
}
