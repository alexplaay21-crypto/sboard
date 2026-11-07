package com.sboard.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.sboard.app.core.i18n.SBoardI18n
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.feature.sync.GoogleAccount
import com.sboard.app.feature.sync.SyncEngine
import com.sboard.app.feature.sync.SyncScheduler
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.feature.vault.AuthResult
import com.sboard.app.feature.vault.Biometrics
import com.sboard.app.feature.vault.VaultKeyManager
import com.sboard.app.ui.components.*
import com.sboard.app.ui.rememberGoogleAuthorizer
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Step { WELCOME, CREATE, RECOVERY, RESTORE, FINISH }

/** First run: create a new Vault or restore an existing one from Google Drive. */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    var restored by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding()) {
        AnimatedContent(step, transitionSpec = {
            (slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(240))) togetherWith (slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(160)))
        }, label = "onboarding") { s ->
            when (s) {
                Step.WELCOME -> WelcomeStep(onCreate = { step = Step.CREATE }, onRestore = { step = Step.RESTORE })
                Step.CREATE -> CreateVaultStep(onBack = { step = Step.WELCOME }, onCreated = { step = Step.RECOVERY })
                Step.RECOVERY -> RecoveryKeyStep(onDone = { step = Step.FINISH })
                Step.RESTORE -> RestoreStep(onBack = { step = Step.WELCOME }, onRestored = { restored = true; step = Step.FINISH })
                Step.FINISH -> FinishStep(restored, onFinished)
            }
        }
    }
}

@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
    )
}

@Composable
private fun WelcomeStep(onCreate: () -> Unit, onRestore: () -> Unit) {
    val context = LocalContext.current
    StepColumn {
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(22.dp)).background(palette.accentSoft), contentAlignment = Alignment.Center) {
            Text("s", color = palette.accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Text("sBoard", style = MaterialTheme.typography.displaySmall, color = palette.text)
        Text(tr("Клавиатура, пароли и личные данные — в одном защищённом месте."), style = MaterialTheme.typography.bodyLarge, color = palette.textMuted)
        Spacer(Modifier.height(6.dp))
        listOf(
            Icons.Rounded.Keyboard to (tr("Умная клавиатура") to tr("Русская и английская раскладки, подсказки, эмодзи и вставка данных из хранилища")),
            Icons.Rounded.Lock to (tr("Хранилище паролей") to tr("Пароли, карты, заметки, коды 2FA и passkeys под AES-256")),
            Icons.Rounded.AutoAwesome to (tr("Автозаполнение") to tr("Заполнение форм в приложениях и браузерах с подтверждением")),
            Icons.Rounded.CloudSync to (tr("Синхронизация") to tr("Зашифрованная копия в Google Drive для всех ваших устройств")),
        ).forEach { (icon, texts) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(texts.first, style = MaterialTheme.typography.titleSmall, color = palette.text)
                    Text(texts.second, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        var pickLanguage by remember { mutableStateOf(false) }
        SecondaryButton(languageLabel(SBoardI18n.currentMode()), { pickLanguage = true }, icon = Icons.Rounded.Translate)
        if (pickLanguage) androidx.compose.material3.AlertDialog(onDismissRequest = { pickLanguage = false }, containerColor = palette.surface,
            title = { Text(tr("Язык интерфейса")) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { AppLanguageList(onPicked = { pickLanguage = false }) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { pickLanguage = false }) { Text(tr("Закрыть"), color = palette.textMuted) } })
        PrimaryButton(tr("Создать хранилище"), onCreate, icon = Icons.Rounded.Add)
        SecondaryButton(tr("Восстановить из Google Drive"), onRestore, icon = Icons.Rounded.CloudDownload)
    }
}

@Composable
private fun CreateVaultStep(onBack: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    val strength = remember(password) { PasswordStrength.evaluate(password) }
    val valid = password.length >= VaultKeyManager.MIN_PASSWORD_LENGTH && password == confirm && strength.score >= 2 && acknowledged
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Мастер-пароль"), onBack)
        StepColumn {
            Text(tr("Мастер-пароль шифрует всё хранилище. Он не связан с паролем аккаунта Google и не хранится нигде — ни на устройстве, ни в облаке."),
                style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
            SBTextField(password, { password = it; error = null }, tr("Мастер-пароль"), password = true)
            if (password.isNotEmpty()) {
                StrengthBar(strength.score, "${strength.label} · ${tr("взлом")}: ${strength.crackTime}")
                strength.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.warning) }
            }
            SBTextField(confirm, { confirm = it; error = null }, tr("Повторите мастер-пароль"), password = true,
                isError = confirm.isNotEmpty() && confirm != password,
                supporting = if (confirm.isNotEmpty() && confirm != password) tr("Пароли не совпадают") else null)
            Banner(tr("Используйте фразу из нескольких слов, минимум 10 символов. Не используйте пароль от Google или других сервисов."), Tone.INFO)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it }, colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent))
                Text(tr("Я понимаю, что без мастер-пароля или Recovery Key доступ к данным восстановить невозможно."),
                    style = MaterialTheme.typography.bodySmall, color = palette.text)
            }
            AnimatedBanner(error, Tone.DANGER)
            PrimaryButton(tr("Создать хранилище"), {
                scope.launch {
                    busy = true
                    try {
                        VaultKeyManager(context).createVault(password.toCharArray())
                        password = ""; confirm = ""
                        onCreated()
                    } catch (e: Exception) { error = e.message ?: tr("Не удалось создать хранилище") }
                    busy = false
                }
            }, enabled = valid, loading = busy)
        }
    }
}

@Composable
private fun RecoveryKeyStep(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    StepColumn {
        Spacer(Modifier.height(16.dp))
        IconBadge(Icons.Rounded.Key, size = 56.dp)
        Text(tr("Recovery Key"), style = MaterialTheme.typography.headlineSmall, color = palette.text)
        Text(tr("Резервный ключ откроет хранилище, если вы забудете мастер-пароль. Его можно создать позже в настройках безопасности."),
            style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
        val current = code
        if (current == null) {
            PrimaryButton(tr("Создать Recovery Key"), { scope.launch { code = VaultKeyManager(context).createRecoveryKey() } }, icon = Icons.Rounded.Key)
            SecondaryButton(tr("Пропустить"), onDone)
        } else {
            SBCard(color = palette.surfaceHigh) {
                Text(VaultKeyManager.formatRecoveryKey(current), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = palette.text)
            }
            SecondaryButton(if (copied) tr("Скопировано") else tr("Скопировать"), {
                SensitiveClipboard.copy(context, current, sensitive = true); copied = true
            }, icon = Icons.Rounded.ContentCopy)
            Banner(tr("Запишите ключ на бумаге или сохраните в надёжном месте вне телефона. Буфер обмена будет очищен автоматически."), Tone.WARNING)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saved, { saved = it }, colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.onAccent))
                Text(tr("Я сохранил Recovery Key"), style = MaterialTheme.typography.bodyMedium, color = palette.text)
            }
            PrimaryButton(tr("Продолжить"), { code = null; onDone() }, enabled = saved)
        }
    }
}

@Composable
private fun RestoreStep(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { SyncEngine(context) }
    var token by remember { mutableStateOf<String?>(null) }
    var cloud by remember { mutableStateOf<SyncEngine.CloudVault?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var secret by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var askNewPassword by remember { mutableStateOf(false) }
    if (askNewPassword) NewMasterPasswordDialog(mandatory = true) { askNewPassword = false; onRestored() }

    val authorize = rememberGoogleAuthorizer(onToken = { t ->
        token = t
        scope.launch {
            busy = true; error = null; message = tr("Ищем хранилище в Google Drive…")
            try {
                engine.refreshProfile(t)
                cloud = engine.inspectCloud(t)
                searched = true
                message = null
            } catch (e: Exception) { error = e.message ?: tr("Не удалось прочитать Google Drive"); message = null }
            busy = false
        }
    }, onError = { error = it })

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Восстановление"), onBack)
        StepColumn {
            Text(tr("Войдите в тот же аккаунт Google, что и на первом устройстве. sBoard найдёт зашифрованный файл sboard_backup.enc в скрытой папке приложения."),
                style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
            val account by GoogleAccount.account.collectAsState()
            account?.let { a ->
                SBCard { ListRow(a.name.ifBlank { a.email }, a.email, icon = Icons.Rounded.AccountCircle, trailing = null) }
            }
            if (token == null || cloud == null) {
                PrimaryButton(if (token == null) tr("Войти через Google") else tr("Повторить поиск"), authorize, loading = busy, icon = Icons.AutoMirrored.Rounded.Login)
            }
            message?.let { Banner(it) }
            AnimatedBanner(error, Tone.DANGER)
            if (searched && cloud == null && !busy) {
                Banner(tr("В этом аккаунте Google нет хранилища sBoard. Создайте новое хранилище или войдите в другой аккаунт."), Tone.WARNING)
            }
            cloud?.let { c ->
                SBCard {
                    ListRow(tr("Найдено хранилище sBoard"),
                        tr("Обновлено: {0}", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.parsed.header.writtenAt))),
                        icon = Icons.Rounded.CloudDone, trailing = null)
                }
                SBTextField(secret, { secret = it; error = null }, tr("Мастер-пароль или Recovery Key"), password = true, onDone = {})
                PrimaryButton(tr("Восстановить"), {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val count = engine.restore(c, secret)
                            secret = ""
                            GoogleAccount.markConnected(context, GoogleAccount.account.value?.email.orEmpty(), GoogleAccount.account.value?.name.orEmpty())
                            SyncScheduler.ensurePeriodic(context)
                            message = tr("Восстановлено записей: {0}", count)
                            onRestored()
                        } catch (e: Exception) { error = e.message ?: tr("Не удалось восстановить") }
                        busy = false
                    }
                }, enabled = secret.isNotEmpty(), loading = busy, icon = Icons.Rounded.Restore)
                if (engine.canRecoverWithGoogle(c)) {
                    SecondaryButton(tr("Не помню пароль — восстановить через Google"), {
                        scope.launch {
                            busy = true; error = null
                            try {
                                val count = engine.restoreWithGoogle(c)
                                GoogleAccount.markConnected(context, GoogleAccount.account.value?.email.orEmpty(), GoogleAccount.account.value?.name.orEmpty())
                                SyncScheduler.ensurePeriodic(context)
                                message = tr("Восстановлено записей: {0}", count)
                                askNewPassword = true
                            } catch (e: Exception) { error = e.message ?: tr("Не удалось восстановить через Google") }
                            busy = false
                        }
                    }, icon = Icons.Rounded.CloudSync, enabled = !busy)
                }
                Text(tr("Ключ расшифровывается только на этом устройстве. Google не получает мастер-пароль."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
    }
}

@Composable
private fun FinishStep(restored: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var biometricOn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val account by GoogleAccount.account.collectAsState()
    val authorize = rememberGoogleAuthorizer(onToken = { t ->
        scope.launch {
            connecting = true
            try {
                SyncEngine(context).refreshProfile(t)
                if (GoogleAccount.account.value == null) GoogleAccount.markConnected(context, "", "")
                when (val r = SyncEngine(context).sync(t)) {
                    is SyncEngine.Outcome.Failure -> error = r.message
                    else -> Unit
                }
                SyncScheduler.ensurePeriodic(context)
            } catch (e: Exception) { error = e.message }
            connecting = false
        }
    }, onError = { error = it })
    StepColumn {
        Spacer(Modifier.height(24.dp))
        IconBadge(Icons.Rounded.CheckCircle, size = 60.dp)
        Text(if (restored) tr("Хранилище восстановлено") else tr("Хранилище готово"), style = MaterialTheme.typography.headlineSmall, color = palette.text)
        Text(tr("Настройте удобный вход и синхронизацию. Всё это можно изменить позже в профиле."), style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
        if (Biometrics.canUseStrong(context)) {
            SBCard {
                SwitchRow(tr("Вход по биометрии"), tr("Отпечаток пальца или лицо вместо мастер-пароля"), biometricOn, Icons.Rounded.Fingerprint) { on ->
                    val host = activity ?: return@SwitchRow
                    scope.launch {
                        if (on) when (val r = Biometrics.enable(host)) {
                            AuthResult.Success -> biometricOn = true
                            is AuthResult.Failed -> error = r.message
                            AuthResult.Cancelled -> Unit
                        } else { Biometrics.disable(context); biometricOn = false }
                    }
                }
            }
        }
        if (!restored) {
            SBCard {
                ListRow(
                    if (account != null) tr("Google Drive подключён") else tr("Синхронизация через Google Drive"),
                    account?.email?.takeIf { it.isNotBlank() } ?: tr("Зашифрованная копия для восстановления на другом устройстве"),
                    icon = Icons.Rounded.CloudSync,
                    trailing = { if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.accent) },
                    onClick = if (account == null && !connecting) authorize else null,
                )
            }
        }
        AnimatedBanner(error, Tone.DANGER)
        Spacer(Modifier.weight(1f))
        PrimaryButton(tr("Начать пользоваться"), onFinished)
    }
}
