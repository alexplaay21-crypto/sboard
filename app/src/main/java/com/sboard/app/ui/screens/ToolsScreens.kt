package com.sboard.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import androidx.lifecycle.repeatOnLifecycle
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.data.ItemType
import com.sboard.app.feature.otp.OtpAuthUri
import com.sboard.app.feature.tools.PasswordStrength
import com.sboard.app.feature.tools.Translator
import com.sboard.app.feature.tools.generator.PasswordGenerator
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.components.*
import com.sboard.app.ui.rememberQrScanner
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.ClipEntry
import org.sboard.keyboard.SBoardClipboard
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

@Composable
fun ToolsScreen(nav: Navigator) {
    var scanError by remember { mutableStateOf<String?>(null) }
    val scan = rememberQrScanner { content ->
        if (runCatching { OtpAuthUri.parse(content) }.isSuccess) { scanError = null; nav.push(Route.ItemEdit(null, ItemType.TOTP, content)) }
        else scanError = tr("QR-код не похож на код 2FA (otpauth://totp)")
    }
    ScreenList(Modifier.statusBarsPadding()) {
        item { ScreenHeader(tr("Инструменты"), tr("Полезные функции sBoard")) }
        item { OtpCodesCard() }
        item { SectionTitle(tr("Безопасность")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Генератор паролей"), tr("Случайные пароли и парольные фразы"), icon = Icons.Rounded.AutoFixHigh, onClick = { nav.push(Route.Generator) })
                ListRow(tr("Проверка надёжности"), tr("Оценка пароля без отправки в сеть"), icon = Icons.Rounded.Speed, onClick = { nav.push(Route.Strength) })
                ListRow(tr("Аудит паролей"), tr("Слабые, повторяющиеся и старые пароли"), icon = Icons.Rounded.HealthAndSafety, onClick = { nav.push(Route.PasswordHealth) })
                ListRow(tr("QR-сканер 2FA"), tr("Добавить код аутентификатора по QR"), icon = Icons.Rounded.QrCodeScanner, onClick = scan)
            }
        }
        scanError?.let { item { Banner(it, Tone.WARNING) } }
        item { SectionTitle(tr("Текст")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Буфер обмена"), tr("История клавиатуры и очистка буфера"), icon = Icons.Rounded.ContentPaste, onClick = { nav.push(Route.Clipboard) })
                ListRow(tr("Переводчик"), tr("Онлайн-перевод текста · требуется интернет"), icon = Icons.Rounded.Translate, onClick = { nav.push(Route.Translator) })
            }
        }
    }
}

private val WORDS = listOf(
    "amber", "anchor", "apple", "arrow", "atlas", "autumn", "badge", "bamboo", "banner", "basil", "beacon", "birch", "bison", "blossom",
    "breeze", "bridge", "bronze", "cabin", "cactus", "candle", "canyon", "carbon", "castle", "cedar", "cello", "chalk", "cherry", "cinder",
    "cliff", "clover", "cobalt", "comet", "copper", "coral", "cosmos", "cotton", "crane", "crystal", "dawn", "delta", "desert", "dune",
    "eagle", "ember", "falcon", "fern", "fjord", "flame", "forest", "fossil", "galaxy", "garnet", "glacier", "granite", "harbor", "hazel",
    "horizon", "island", "ivory", "jade", "jasmine", "jungle", "kayak", "lagoon", "lantern", "lemon", "lotus", "lunar", "maple", "marble",
    "meadow", "meteor", "mint", "mosaic", "nebula", "nectar", "noble", "oasis", "ocean", "olive", "onyx", "orbit", "orchid", "otter",
    "pebble", "pepper", "pine", "planet", "plume", "prism", "quartz", "raven", "reef", "ripple", "river", "rocket", "saffron", "sapphire",
    "shadow", "sierra", "silver", "sky", "solar", "spruce", "storm", "summit", "thunder", "tiger", "timber", "topaz", "tulip", "tundra",
    "valley", "velvet", "violet", "willow", "winter", "zephyr",
)

@Composable
fun GeneratorScreen(nav: Navigator) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("password") }
    var length by remember { mutableFloatStateOf(20f) }
    var words by remember { mutableFloatStateOf(5f) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val random = remember { SecureRandom() }
    fun generate(): String = if (mode == "password") {
        if (!(upper || lower || digits || symbols)) "" else PasswordGenerator.generate(length.toInt(), upper, lower, digits, symbols)
    } else (1..words.toInt()).joinToString("-") { WORDS[random.nextInt(WORDS.size)].replaceFirstChar { c -> c.uppercase() } } + "-" + random.nextInt(100)
    var value by remember { mutableStateOf(generate()) }
    fun regenerate() { value = generate(); copied = false }
    val strength = remember(value) { PasswordStrength.evaluate(value) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Генератор паролей"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { SegmentedControl(listOf("password" to tr("Пароль"), "phrase" to tr("Фраза")), mode, { mode = it; regenerate() }) }
            item {
                SBCard {
                    Text(value.ifEmpty { tr("Выберите хотя бы один набор символов") }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = palette.text)
                    StrengthBar(strength.score, "${strength.label} · ${tr("взлом")}: ${strength.crackTime}")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(tr("Обновить"), { regenerate() }, Modifier.weight(1f), icon = Icons.Rounded.Refresh)
                        PrimaryButton(if (copied) tr("Скопировано") else tr("Скопировать"), {
                            if (value.isNotEmpty()) { SensitiveClipboard.copy(context, value, true); copied = true }
                        }, Modifier.weight(1f), icon = Icons.Rounded.ContentCopy)
                    }
                }
            }
            item {
                SBCard {
                    if (mode == "password") {
                        Text(tr("Длина: {0}", length.toInt()), color = palette.text, style = MaterialTheme.typography.titleSmall)
                        Slider(length, { length = it; regenerate() }, valueRange = 8f..64f, colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
                        SwitchRow(tr("Заглавные буквы (A–Z)"), null, upper) { upper = it; regenerate() }
                        SwitchRow(tr("Строчные буквы (a–z)"), null, lower) { lower = it; regenerate() }
                        SwitchRow(tr("Цифры (0–9)"), null, digits) { digits = it; regenerate() }
                        SwitchRow(tr("Символы (!@#…)"), null, symbols) { symbols = it; regenerate() }
                    } else {
                        Text(tr("Слов: {0}", words.toInt()), color = palette.text, style = MaterialTheme.typography.titleSmall)
                        Slider(words, { words = it; regenerate() }, valueRange = 3f..10f, steps = 6, colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
                        Text(tr("Фразы из случайных слов легко запомнить и сложно подобрать — хороший вариант для мастер-пароля."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    }
                }
            }
            item { Text(tr("Пароли создаются криптографически стойким генератором прямо на устройстве. Буфер обмена очищается автоматически."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
        }
    }
}

@Composable
fun StrengthScreen(nav: Navigator) {
    var input by remember { mutableStateOf("") }
    val result = remember(input) { PasswordStrength.evaluate(input) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Проверка надёжности"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item { SBTextField(input, { input = it }, tr("Введите пароль"), password = true, monospace = true) }
            if (input.isNotEmpty()) item {
                SBCard {
                    StrengthBar(result.score, result.label)
                    StatusLine(tr("Время подбора"), result.crackTime, palette.accent)
                    StatusLine(tr("Длина"), "${input.length}", palette.accent)
                    StatusLine(tr("Энтропия (оценка)"), tr("{0} бит", (result.guessesLog10 * 3.32).toInt()), palette.accent)
                    result.warning?.let { Banner(it, Tone.WARNING) }
                    result.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                }
            }
            item { Text(tr("Проверка выполняется локально (zxcvbn). Пароль никуда не отправляется и не сохраняется."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
        }
    }
}

@Composable
fun TranslatorScreen(nav: Navigator) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(Translator.engine(context)) }
    var deeplKey by remember { mutableStateOf(Translator.deeplKey(context)) }
    var from by remember { mutableStateOf(Translator.AUTO) }
    var to by remember { mutableStateOf("en") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    val names = mapOf(Translator.AUTO to tr("Определить язык")) + Translator.languages.associate { it.code to it.name }
    // Live translation while typing (debounced).
    LaunchedEffect(input, from, to, engine) {
        if (input.isBlank()) { output = ""; error = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(500)
        busy = true; error = null
        try { output = Translator.translate(context, input, from, to) } catch (e: Exception) {
            error = if (e is java.net.UnknownHostException) tr("Нет подключения к интернету") else e.message ?: tr("Не удалось перевести")
        }
        busy = false
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(tr("Переводчик"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item {
                SegmentedControl(listOf(Translator.ENGINE_GOOGLE to "Google", Translator.ENGINE_DEEPL to "DeepL", Translator.ENGINE_MYMEMORY to "MyMemory"), engine, {
                    engine = it; Translator.setEngine(context, it)
                })
            }
            if (engine == Translator.ENGINE_DEEPL) item {
                SBCard {
                    SBTextField(deeplKey, { deeplKey = it; Translator.setDeeplKey(context, it) }, tr("Ключ DeepL API"), password = true)
                    Text(tr("Бесплатный ключ (500 000 символов в месяц) выдаётся на deepl.com/pro-api. Ключ хранится только на этом устройстве."),
                        style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(names[from] ?: from, { pickFor = "from" }, Modifier.weight(1f))
                    IconButton(onClick = { if (from != Translator.AUTO) { val t = from; from = to; to = t; input = output.ifEmpty { input } } }) {
                        Icon(Icons.Rounded.SwapHoriz, tr("Поменять"), tint = palette.accent)
                    }
                    SecondaryButton(names[to] ?: to, { pickFor = "to" }, Modifier.weight(1f))
                }
            }
            item { SBTextField(input, { if (it.length <= Translator.MAX_CHARS) input = it }, tr("Текст"), singleLine = false, minLines = 4, supporting = "${input.length}/${Translator.MAX_CHARS}") }
            item { AnimatedBanner(error, Tone.DANGER) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.accent) }
            if (output.isNotEmpty()) item {
                SBCard {
                    Text(output, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                    SecondaryButton(tr("Скопировать"), { SensitiveClipboard.copy(context, output, false) }, icon = Icons.Rounded.ContentCopy)
                }
            }
            item { Text(tr("Онлайн-функция: текст отправляется выбранному сервису перевода. Не переводите пароли и личные данные. В клавиатуре переводчик открывается кнопкой на панели инструментов."),
                style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
        }
    }
    pickFor?.let { target ->
        AlertDialog(onDismissRequest = { pickFor = null }, containerColor = palette.surface, title = { Text(tr("Язык")) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(names.entries.filter { target == "from" || it.key != Translator.AUTO }.toList()) { (code, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable { if (target == "from") from = code else to = code; pickFor = null }.padding(vertical = 12.dp),
                            color = if ((target == "from" && from == code) || (target == "to" && to == code)) palette.accent else palette.text)
                    }
                }
            }, confirmButton = {})
    }
}

@Composable
fun ClipboardScreen(nav: Navigator) {
    val context = LocalContext.current
    val history by remember { SBoardClipboard.history(context) }.collectAsState(emptyList())
    var current by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { current = readClipboard(context) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Буфер обмена"), { nav.pop() })
        ScreenList(bottomPadding = 40.dp) {
            item {
                SBCard {
                    Text(tr("Сейчас в буфере"), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
                    Text(current?.takeIf { it.isNotEmpty() } ?: tr("Пусто"), style = MaterialTheme.typography.bodyLarge, color = palette.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    SecondaryButton(tr("Очистить буфер"), { SensitiveClipboard.clearNow(context); current = "" }, icon = Icons.Rounded.ClearAll)
                }
            }
            item { SectionTitle(tr("История клавиатуры sBoard"), action = if (history.any { !it.pinned }) tr("Очистить") else null, onAction = { confirmClear = true }) }
            if (history.isEmpty()) item { EmptyState(Icons.Rounded.ContentPaste, tr("История пуста"), tr("Скопированный текст появится здесь, когда вы пользуетесь клавиатурой sBoard. Пароли из хранилища в историю не попадают.")) }
            items(history, key = { it.id }) { entry -> ClipRow(context, entry) }
        }
    }
    if (confirmClear) ConfirmDialog(tr("Очистить историю?"), tr("Закреплённые элементы останутся."), tr("Очистить"), danger = true,
        onConfirm = { SBoardClipboard.clearUnpinned(context); confirmClear = false }, onDismiss = { confirmClear = false })
}

@Composable
private fun ClipRow(context: Context, entry: ClipEntry) {
    SBCard(padding = PaddingValues(12.dp)) {
        Text(if (entry.sensitive) "••••••••" else entry.text, style = MaterialTheme.typography.bodyMedium, color = palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt)), style = MaterialTheme.typography.labelSmall, color = palette.textFaint, modifier = Modifier.weight(1f))
            IconButton(onClick = { SBoardClipboard.setPinned(context, entry.id, !entry.pinned) }) {
                Icon(if (entry.pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, tr("Закрепить"), tint = if (entry.pinned) palette.accent else palette.textFaint)
            }
            IconButton(onClick = { SensitiveClipboard.copy(context, entry.text, entry.sensitive) }) { Icon(Icons.Rounded.ContentCopy, tr("Скопировать"), tint = palette.accent) }
            IconButton(onClick = { SBoardClipboard.delete(context, entry.id) }) { Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = palette.textMuted) }
        }
    }
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return@runCatching ""
    val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
    if (sensitive) "••••••••" else clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}.getOrNull()


/** Automatic one-time codes from SMS, messengers and mail notifications. */
@Composable
private fun OtpCodesCard() {
    val context = LocalContext.current
    var access by remember { mutableStateOf(com.sboard.app.feature.otpinbox.OtpInbox.hasAccess(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            access = com.sboard.app.feature.otpinbox.OtpInbox.hasAccess(context)
        }
    }
    val latest by com.sboard.app.feature.otpinbox.OtpInbox.latest.collectAsState()
    val now = rememberNow()
    SBCard {
        ListRow(tr("Коды подтверждения"), tr("Автоматически из SMS, WhatsApp, Telegram, Gmail и других приложений"), icon = Icons.Rounded.Sms, trailing = null)
        if (!access) {
            Banner(tr("Разрешите sBoard читать уведомления — так коды будут копироваться и подставляться автоматически. Текст сообщений не сохраняется."), Tone.INFO)
            PrimaryButton(tr("Разрешить доступ к уведомлениям"), { com.sboard.app.feature.otpinbox.OtpInbox.openAccessSettings(context) }, icon = Icons.Rounded.NotificationsActive)
        } else {
            com.sboard.app.core.settings.AppSettings.let { s ->
                SwitchRow(tr("Находить коды в уведомлениях"), null, s.otpFromNotifications.value, Icons.Rounded.Pin) { s.setOtpFromNotifications(it) }
                SwitchRow(tr("Копировать код автоматически"), tr("Код сразу попадает в буфер — вставьте его кнопкой на клавиатуре"), s.otpAutoCopy.value, Icons.Rounded.ContentCopy) { s.setOtpAutoCopy(it) }
            }
            val code = latest?.takeIf { now - it.receivedAt < 10 * 60_000 }
            if (code != null) FieldRow(tr("Последний код · {0}", code.sourceApp), code.code, monospace = true)
            else Text(tr("Новые коды появятся здесь, в автозаполнении и в буфере обмена."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        }
    }
}
