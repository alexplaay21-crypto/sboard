package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.feature.keyboard.SystemIntegration
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.Route
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.SBoardKeySound
import org.sboard.keyboard.SBoardTypingPrefs
import org.sboard.keyboard.SBoardUserModel
import org.sboard.keyboard.SBoardVoice
import org.sboard.keyboard.SBoardKeyboardSettings as K

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onFinished: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Text("$title: ${label(v)}", style = MaterialTheme.typography.titleSmall, color = palette.text)
    Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onFinished(v) },
        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
}

private fun <T> pref(get: () -> T, default: T) = runCatching(get).getOrDefault(default)

/** Keyboard tab: status, a test field and the settings sections (Samsung Keyboard structure). */
@Composable
fun KeyboardScreen(nav: Navigator) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf<List<K.Language>>(emptyList()) }
    var test by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = SystemIntegration.isKeyboardEnabled(context)
            selected = SystemIntegration.isKeyboardSelected(context)
            languages = runCatching { K.languages(context) }.getOrDefault(emptyList())
        }
    }
    fun open(id: String) = nav.push(Route.KeyboardSection(id))

    ScreenList(Modifier.statusBarsPadding().imePadding()) {
        item { ScreenHeader(tr("Клавиатура"), tr("Скорость, точность и удобство")) }
        item {
            SBCard {
                StatusLine(tr("Включена в системе"), if (enabled) tr("Да") else tr("Нет"), if (enabled) palette.success else palette.danger)
                StatusLine(tr("Используется сейчас"), if (selected) tr("Да") else tr("Нет"), if (selected) palette.success else palette.warning)
                if (!enabled) PrimaryButton(tr("Включить клавиатуру sBoard"), { SystemIntegration.openKeyboardSettings(context) }, icon = Icons.Rounded.Keyboard)
                else if (!selected) PrimaryButton(tr("Выбрать sBoard"), { SystemIntegration.showKeyboardPicker(context) }, icon = Icons.Rounded.SwapHoriz)
                else SecondaryButton(tr("Сменить клавиатуру"), { SystemIntegration.showKeyboardPicker(context) }, icon = Icons.Rounded.SwapHoriz)
            }
        }
        item { SBTextField(test, { test = it }, tr("Попробуйте клавиатуру здесь"), singleLine = false, minLines = 2) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                val active = languages.filter { it.enabled }.joinToString(", ") { it.name }
                ListRow(tr("Языки и раскладки"), active.ifBlank { tr("Русский, английский и другие языки") }, icon = Icons.Rounded.Language, onClick = { open("languages") })
                ListRow(tr("Умный ввод"), tr("Подсказки, автозамена, заглавные буквы, личный словарь"), icon = Icons.Rounded.AutoFixHigh, onClick = { open("typing") })
                ListRow(tr("Жесты и нажатия"), tr("Свайп-ввод, пробел, удержание, Backspace"), icon = Icons.Rounded.Gesture, onClick = { open("gestures") })
                ListRow(tr("Звук и вибрация"), tr("Звук клавиш, громкость, сила вибрации"), icon = Icons.AutoMirrored.Rounded.VolumeUp, onClick = { open("feedback") })
                ListRow(tr("Вид и размер"), tr("Высота, ряд цифр, режим одной руки, расстояние между клавишами"), icon = Icons.Rounded.Straighten, onClick = { open("layout") })
                ListRow(tr("Тема клавиатуры"), tr("Цвета, форма клавиш, размеры значков"), icon = Icons.Rounded.Palette, onClick = { nav.push(Route.KeyboardTheme) })
                ListRow(tr("Панель инструментов"), tr("sPass, эмодзи, буфер обмена, переводчик"), icon = Icons.Rounded.ViewDay, onClick = { open("toolbar") })
                ListRow(tr("Голосовой ввод"), tr("Google или встроенное распознавание"), icon = Icons.Rounded.Mic, onClick = { open("voice") })
                ListRow(tr("Личный словарь и сокращения"), tr("Свои слова и быстрые фразы"), icon = Icons.AutoMirrored.Rounded.MenuBook, onClick = { open("dictionary") })
            }
        }
        item { Text(tr("Клавиатура основана на открытом движке FlorisBoard (Apache-2.0). Набранный текст обрабатывается локально и никуда не отправляется."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
    }
}

/** One settings section of the keyboard (opened from [KeyboardScreen]). */
@Composable
fun KeyboardSectionScreen(nav: Navigator, id: String) {
    val title = when (id) {
        "languages" -> tr("Языки и раскладки"); "typing" -> tr("Умный ввод"); "gestures" -> tr("Жесты и нажатия")
        "feedback" -> tr("Звук и вибрация"); "layout" -> tr("Вид и размер"); "toolbar" -> tr("Панель инструментов")
        "voice" -> tr("Голосовой ввод"); "dictionary" -> tr("Личный словарь и сокращения"); "add_language" -> tr("Добавить язык")
        else -> tr("Клавиатура")
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title, { nav.pop() })
        when (id) {
            "languages" -> LanguagesSection(nav)
            "add_language" -> AddLanguageSection(nav)
            "typing" -> TypingSection(nav)
            "gestures" -> GesturesSection()
            "feedback" -> FeedbackSection()
            "layout" -> LayoutSection()
            "toolbar" -> ToolbarSection()
            "voice" -> VoiceSection()
            "dictionary" -> DictionarySection()
        }
    }
}

@Composable
private fun LanguagesSection(nav: Navigator) {
    val context = LocalContext.current
    var languages by remember { mutableStateOf<List<K.Language>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { languages = runCatching { K.languages(context) }.getOrDefault(emptyList()) }
    }
    ScreenList(bottomPadding = 40.dp) {
        message?.let { item { Banner(it, Tone.INFO) } }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                val enabled = languages.filter { it.enabled }
                if (languages.isEmpty()) Text(tr("Список языков появится после первого запуска клавиатуры."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                enabled.forEach { lang ->
                    SwitchRow(lang.name, lang.tag, true, Icons.Rounded.Language) { on ->
                        message = if (!K.setLanguageEnabled(context, lang.tag, on)) tr("Должен остаться хотя бы один язык") else null
                        languages = runCatching { K.languages(context) }.getOrDefault(languages)
                    }
                }
                PrimaryButton(tr("Добавить язык"), { nav.push(Route.KeyboardSection("add_language")) }, icon = Icons.Rounded.Add)
            }
        }
        item {
            Text(tr("Сменить язык: кнопка с глобусом слева от пробела. Проведите пальцем по пробелу — двигается курсор."),
                style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
        }
    }
}

@Composable
private fun AddLanguageSection(nav: Navigator) {
    val context = LocalContext.current
    var languages by remember { mutableStateOf(runCatching { K.languages(context) }.getOrDefault(emptyList())) }
    var query by remember { mutableStateOf("") }
    val visible = languages.filter { !it.enabled && (query.isBlank() || it.name.contains(query, true) || it.tag.contains(query, true)) }
    ScreenList(bottomPadding = 40.dp) {
        item { SBTextField(query, { query = it }, tr("Поиск языка")) }
        if (visible.isEmpty()) item { Text(tr("Ничего не найдено"), style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
        items(visible, key = { it.tag }) { lang ->
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                ListRow(lang.name, lang.tag, icon = Icons.Rounded.Language, trailing = { Icon(Icons.Rounded.AddCircle, null, tint = palette.accent) }, onClick = {
                    if (K.setLanguageEnabled(context, lang.tag, true)) nav.pop()
                    else languages = runCatching { K.languages(context) }.getOrDefault(languages)
                })
            }
        }
    }
}

@Composable
private fun TypingSection(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var suggestions by remember { mutableStateOf(pref(K::suggestions, true)) }
    var autoCorrect by remember { mutableStateOf(SBoardUserModel.autoCorrectEnabled) }
    var nextWord by remember { mutableStateOf(SBoardUserModel.nextWordEnabled) }
    var learn by remember { mutableStateOf(SBoardUserModel.learningEnabled) }
    var autoCaps by remember { mutableStateOf(pref(K::autoCapitalization, true)) }
    var capsMemory by remember { mutableStateOf(pref(K::capsLockMemory, false)) }
    var doubleSpace by remember { mutableStateOf(pref(K::doubleSpacePeriod, true)) }
    var autoSpace by remember { mutableStateOf(pref(K::autoSpacePunctuation, false)) }
    var emojiSuggest by remember { mutableStateOf(pref(K::emojiSuggestions, true)) }
    var learned by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        SBoardUserModel.init(context)
        autoCorrect = SBoardUserModel.autoCorrectEnabled; nextWord = SBoardUserModel.nextWordEnabled; learn = SBoardUserModel.learningEnabled
        learned = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SBoardUserModel.learnedCount() }
    }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                SwitchRow(tr("Предиктивный ввод"), tr("Подсказки слов над клавиатурой"), suggestions, Icons.Rounded.Spellcheck) { suggestions = it; scope.launch { K.setSuggestions(it) } }
                SwitchRow(tr("Автозамена"), tr("Исправлять очевидные опечатки при нажатии пробела"), autoCorrect, Icons.Rounded.AutoFixHigh, enabled = suggestions) { autoCorrect = it; SBoardUserModel.setAutoCorrect(context, it) }
                SwitchRow(tr("Следующее слово"), tr("Предлагать слово, которое вы обычно пишете дальше"), nextWord, Icons.Rounded.Insights, enabled = suggestions) { nextWord = it; SBoardUserModel.setNextWord(context, it) }
                SwitchRow(tr("Запоминать мои слова"), tr("Частые слова поднимаются выше в подсказках. Только на устройстве, никогда в полях паролей"), learn, Icons.Rounded.School) { learn = it; SBoardUserModel.setLearning(context, it) }
                SwitchRow(tr("Подсказки эмодзи"), null, emojiSuggest, Icons.Rounded.EmojiEmotions) { emojiSuggest = it; scope.launch { K.setEmojiSuggestions(it) } }
            }
        }
        item {
            SBCard {
                SwitchRow(tr("Заглавная в начале предложения"), tr("Выключите, если хотите всегда начинать со строчной"), autoCaps, Icons.Rounded.TextFields) { autoCaps = it; scope.launch { K.setAutoCapitalization(it) } }
                SwitchRow(tr("Запоминать Caps Lock"), tr("Caps Lock остаётся включённым при переходе в другое поле"), capsMemory, Icons.Rounded.KeyboardCapslock) { capsMemory = it; scope.launch { K.setCapsLockMemory(it) } }
                SwitchRow(tr("Точка двойным пробелом"), null, doubleSpace, Icons.Rounded.SpaceBar) { doubleSpace = it; scope.launch { K.setDoubleSpacePeriod(it) } }
                SwitchRow(tr("Пробел после знаков препинания"), null, autoSpace, Icons.Rounded.FormatTextdirectionLToR) { autoSpace = it; scope.launch { K.setAutoSpacePunctuation(it) } }
            }
        }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Личный словарь и сокращения"), tr("Свои слова и быстрые фразы"), icon = Icons.AutoMirrored.Rounded.MenuBook, onClick = { nav.push(Route.KeyboardSection("dictionary")) })
                ListRow(tr("Забыть выученные слова"), tr("Выучено слов: {0}", learned), icon = Icons.Rounded.DeleteSweep, onClick = { confirmClear = true })
            }
        }
        item { Text(tr("Длительное нажатие на подсказку удаляет выученное слово."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
    }
    if (confirmClear) ConfirmDialog(tr("Забыть выученные слова?"), tr("Подсказки вернутся к стандартному словарю."), tr("Забыть"),
        onConfirm = { SBoardUserModel.clear(); learned = 0; confirmClear = false }, onDismiss = { confirmClear = false }, danger = true)
}

@Composable
private fun GesturesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var swipe by remember { mutableStateOf(pref(K::swipeTyping, true)) }
    var swipeTrail by remember { mutableStateOf(pref(K::swipeTrail, true)) }
    var spaceSwipe by remember { mutableStateOf(pref(K::spaceSwipe, "cursor")) }
    var spaceLong by remember { mutableStateOf(pref(K::spaceLongPress, "none")) }
    var deleteWords by remember { mutableStateOf(pref(K::deleteSwipeWords, false)) }
    var keyPopup by remember { mutableStateOf(pref(K::keyPopup, true)) }
    var symbolHints by remember { mutableStateOf(pref(K::symbolHints, true)) }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                SwitchRow(tr("Непрерывный ввод (свайп)"), tr("Ведите пальцем по буквам — получится слово"), swipe, Icons.Rounded.Gesture) { swipe = it; scope.launch { K.setSwipeTyping(it) } }
                if (swipe) SwitchRow(tr("След пальца"), null, swipeTrail, Icons.Rounded.Timeline) { swipeTrail = it; scope.launch { K.setSwipeTrail(it) } }
            }
        }
        item { SectionTitle(tr("Пробел")) }
        item {
            SBCard {
                Text(tr("Свайп по пробелу"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                SegmentedControl(listOf("cursor" to tr("Курсор"), "language" to tr("Смена языка")), spaceSwipe, { spaceSwipe = it; scope.launch { K.setSpaceSwipe(it) } })
                Text(tr("Удержание пробела"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                SegmentedControl(listOf("none" to tr("Ничего"), "languages" to tr("Языки"), "keyboards" to tr("Клавиатуры")), spaceLong, { spaceLong = it; scope.launch { K.setSpaceLongPress(it) } })
            }
        }
        item { SectionTitle(tr("Нажатия")) }
        item {
            SBCard {
                SwitchRow(tr("Увеличение клавиши при нажатии"), tr("Предпросмотр символа над пальцем"), keyPopup, Icons.Rounded.ZoomIn) { keyPopup = it; scope.launch { K.setKeyPopup(it) } }
                SwitchRow(tr("Символы на клавишах"), tr("Удержание буквы вводит символ над ней, как на Samsung"), symbolHints, Icons.Rounded.Tag) { symbolHints = it; scope.launch { K.setSymbolHints(it) } }
                SliderRow(tr("Задержка удержания"), pref(K::longPressDelay, 300).toFloat(), 150f..800f, { tr("{0} мс", it.toInt()) }) { scope.launch { K.setLongPressDelay(it.toInt()) } }
                SliderRow(tr("Скорость удаления (Backspace)"), SBoardTypingPrefs.backspaceSpeed.toFloat(), 50f..200f, { "${it.toInt()}%" }) { SBoardTypingPrefs.setBackspaceSpeed(context, it.toInt()) }
                SwitchRow(tr("Свайп влево по ⌫ удаляет слова"), tr("Иначе — по одному символу"), deleteWords, Icons.AutoMirrored.Rounded.Backspace) { deleteWords = it; scope.launch { K.setDeleteSwipeWords(it) } }
            }
        }
    }
}

@Composable
private fun FeedbackSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sound by remember { mutableStateOf(pref(K::soundEnabled, true)) }
    var style by remember { mutableStateOf(SBoardKeySound.style(context)) }
    var volume by remember { mutableIntStateOf(pref(K::soundVolume, 50)) }
    var vibration by remember { mutableStateOf(pref(K::vibrationEnabled, true)) }
    var strength by remember { mutableIntStateOf(pref(K::vibrationStrength, 50)) }
    var duration by remember { mutableIntStateOf(pref(K::vibrationDuration, 50)) }
    fun preview() = SBoardKeySound.play(context, SBoardKeySound.Kind.STANDARD, volume / 100f)
    fun vibratePreview() {
        val vibrator = context.getSystemService(android.os.Vibrator::class.java) ?: return
        runCatching {
            val amplitude = if (vibrator.hasAmplitudeControl()) (255 * strength / 100).coerceIn(1, 255) else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration.toLong(), amplitude))
        }
    }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                SwitchRow(tr("Звук клавиш"), tr("Мягкий щелчок; молчит в беззвучном режиме"), sound, Icons.AutoMirrored.Rounded.VolumeUp) { sound = it; scope.launch { K.setSoundEnabled(it) } }
                if (sound) {
                    Text(tr("Звук"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                    SegmentedControl(SBoardKeySound.STYLES.map { it to when (it) { "soft" -> tr("Мягкий"); "click" -> tr("Щелчок"); "wood" -> tr("Дерево"); else -> tr("Капля") } }, style, {
                        style = it; SBoardKeySound.setStyle(context, it); preview()
                    })
                    SliderRow(tr("Громкость"), volume.toFloat(), 5f..100f, { "${it.toInt()}%" }) { volume = it.toInt(); scope.launch { K.setSoundVolume(it.toInt()) }; preview() }
                }
            }
        }
        item {
            SBCard {
                SwitchRow(tr("Вибрация клавиш"), null, vibration, Icons.Rounded.Vibration) { vibration = it; scope.launch { K.setVibrationEnabled(it) } }
                if (vibration) {
                    SliderRow(tr("Сила вибрации"), strength.toFloat(), 1f..100f, { "${it.toInt()}%" }) {
                        strength = it.toInt(); scope.launch { K.setVibrationStrength(it.toInt()) }; vibratePreview()
                    }
                    SliderRow(tr("Длительность"), duration.toFloat(), 5f..100f, { tr("{0} мс", it.toInt()) }) {
                        duration = it.toInt(); scope.launch { K.setVibrationDuration(it.toInt()) }; vibratePreview()
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutSection() {
    val scope = rememberCoroutineScope()
    var numberRow by remember { mutableStateOf(pref(K::numberRow, true)) }
    var oneHanded by remember { mutableStateOf(pref(K::oneHanded, false)) }
    var oneHandedRight by remember { mutableStateOf(pref(K::oneHandedRight, true)) }
    var themeMode by remember { mutableStateOf(pref(K::theme, "system")) }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                Text(tr("Тема"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                SegmentedControl(listOf("system" to tr("Системная"), "light" to tr("Светлая"), "dark" to tr("Тёмная")), themeMode, { themeMode = it; scope.launch { K.setTheme(it) } })
            }
        }
        item {
            SBCard {
                SliderRow(tr("Высота клавиатуры"), pref(K::heightPercent, 100).toFloat(), 70f..140f, { "${it.toInt()}%" }) { scope.launch { K.setHeightPercent(it.toInt()) } }
                SliderRow(tr("Высота в альбомной ориентации и играх"), pref(K::landscapeHeightPercent, 78).toFloat(), 55f..120f, { "${it.toInt()}%" }) { scope.launch { K.setLandscapeHeightPercent(it.toInt()) } }
                SliderRow(tr("Расстояние между клавишами"), pref(K::keySpacing, 2f), 0f..6f, { "%.1f".format(it) }) { scope.launch { K.setKeySpacing(it) } }
                SliderRow(tr("Размер шрифта клавиш"), pref(K::fontSizePercent, 100).toFloat(), 70f..140f, { "${it.toInt()}%" }) { scope.launch { K.setFontSizePercent(it.toInt()) } }
                SwitchRow(tr("Ряд цифр"), tr("Отдельный верхний ряд с цифрами"), numberRow, Icons.Rounded.Pin) { numberRow = it; scope.launch { K.setNumberRow(it) } }
            }
        }
        item { SectionTitle(tr("Режим одной руки")) }
        item {
            SBCard {
                SwitchRow(tr("Режим одной руки"), tr("Клавиатура сужается и прижимается к краю"), oneHanded, Icons.Rounded.PanTool) { oneHanded = it; scope.launch { K.setOneHanded(it) } }
                if (oneHanded) {
                    SegmentedControl(listOf("left" to tr("Слева"), "right" to tr("Справа")), if (oneHandedRight) "right" else "left", {
                        oneHandedRight = it == "right"; scope.launch { K.setOneHandedRight(oneHandedRight) }
                    })
                    SliderRow(tr("Ширина"), pref(K::oneHandedWidth, 87).toFloat(), 70f..90f, { "${it.toInt()}%" }) { scope.launch { K.setOneHandedWidth(it.toInt()) } }
                }
            }
        }
    }
}

@Composable
private fun ToolbarSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var smartbar by remember { mutableStateOf(pref(K::smartbar, true)) }
    var clipHistory by remember { mutableStateOf(pref(K::clipboardHistory, true)) }
    var excludeCodes by remember { mutableStateOf(org.sboard.keyboard.SBoardClipboard.excludeCodes) }
    var keepMinutes by remember { mutableIntStateOf(pref(K::clipboardKeepMinutes, 0)) }
    var message by remember { mutableStateOf<String?>(null) }
    ScreenList(bottomPadding = 40.dp) {
        message?.let { item { Banner(it, Tone.SUCCESS) } }
        item {
            SBCard {
                SwitchRow(tr("Строка инструментов"), tr("Подсказки и быстрые действия: эмодзи, sPass, буфер, переводчик, голос, одна рука"), smartbar, Icons.Rounded.ViewDay) { smartbar = it; scope.launch { K.setSmartbar(it) } }
                SecondaryButton(tr("Сбросить порядок кнопок"), { scope.launch { K.resetToolbar(); message = tr("Порядок кнопок восстановлен") } }, icon = Icons.Rounded.RestartAlt)
                Text(tr("Чтобы изменить порядок кнопок, нажмите «⋯» на панели клавиатуры и выберите «Изменить». Размер значков меняется в редакторе темы."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
        item { SectionTitle("sPass") }
        item {
            SBCard {
                SwitchRow(tr("sPass в клавиатуре"), tr("Вставка логинов, email, телефонов, адресов, паролей и OTP из хранилища"), AppSettings.keyboardVaultBar.value, Icons.Rounded.Key) { AppSettings.setKeyboardVaultBar(it) }
                SwitchRow(tr("Биометрия перед вставкой секретов"), tr("Пароли, коды и карты вставляются только после подтверждения"), AppSettings.biometricForSensitive.value, Icons.Rounded.Fingerprint) { AppSettings.setBiometricForSensitive(it) }
                var offerSave by remember { mutableStateOf(org.sboard.keyboard.SBoardPasswordCapture.enabled) }
                SwitchRow(tr("Предлагать сохранить пароль"), tr("После входа в Telegram, Gmail, игры и другие приложения, где пароль набран клавиатурой sBoard"), offerSave, Icons.Rounded.Save) {
                    offerSave = it; org.sboard.keyboard.SBoardPasswordCapture.setEnabled(context, it)
                }
            }
        }
        item { SectionTitle(tr("Буфер обмена")) }
        item {
            SBCard {
                SwitchRow(tr("История буфера обмена"), null, clipHistory, Icons.Rounded.ContentPaste) { clipHistory = it; scope.launch { K.setClipboardHistory(it) } }
                SwitchRow(tr("Не сохранять коды и пароли"), tr("Секреты и одноразовые коды не попадают в историю"), excludeCodes, Icons.Rounded.VisibilityOff) { excludeCodes = it; SBoardTypingPrefs.setExcludeCodes(context, it) }
                Text(tr("Хранить записи"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                FilterChipRow(listOf("0" to tr("Всегда"), "60" to tr("1 час"), "1440" to tr("1 день"), "10080" to tr("7 дней")), keepMinutes.toString()) {
                    keepMinutes = it.toInt(); scope.launch { K.setClipboardKeepMinutes(keepMinutes) }
                }
                Text(tr("Закреплённые записи не удаляются автоматически."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
    }
}

@Composable
private fun VoiceSection() {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(SBoardVoice.engine(context)) }
    var googleAvailable by remember { mutableStateOf(false) }
    var builtinAvailable by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            googleAvailable = SBoardVoice.voiceImeAvailable(context)
            builtinAvailable = SBoardVoice.builtinAvailable(context)
            micGranted = SBoardVoice.hasMicPermission(context)
        }
    }
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { micGranted = it }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                Text(tr("Распознавание"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                SegmentedControl(listOf("auto" to tr("Авто"), "google" to "Google", "builtin" to tr("Встроенное")), engine, { engine = it; SBoardVoice.setEngine(context, it) })
                Text(tr("«Google» — голосовой ввод Google (лучшее качество, нужно приложение Google или Gboard). «Встроенное» — текст появляется прямо в sBoard, через службу распознавания телефона."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                StatusLine(tr("Голосовой ввод Google"), if (googleAvailable) tr("Доступен") else tr("Не включён"), if (googleAvailable) palette.success else palette.warning)
                StatusLine(tr("Служба распознавания"), if (builtinAvailable) tr("Доступна") else tr("Нет"), if (builtinAvailable) palette.success else palette.danger)
                StatusLine(tr("Доступ к микрофону"), if (micGranted) tr("Разрешён") else tr("Не разрешён"), if (micGranted) palette.success else palette.warning)
                if (!micGranted) SecondaryButton(tr("Разрешить микрофон"), { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }, icon = Icons.Rounded.Mic)
                if (!googleAvailable) SecondaryButton(tr("Включить голосовой ввод Google"), { SystemIntegration.openKeyboardSettings(context) }, icon = Icons.Rounded.Settings)
            }
        }
    }
}

@Composable
private fun DictionarySection() {
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<K.DictionaryWord>>(emptyList()) }
    var word by remember { mutableStateOf("") }
    var shortcut by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { words = runCatching { K.dictionaryWords() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }
    ScreenList(bottomPadding = 40.dp) {
        item {
            SBCard {
                Text(tr("Слово появится в подсказках. Сокращение заменяется полной фразой: например, «др» → «С днём рождения!»."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                SBTextField(word, { word = it; error = null }, tr("Слово или фраза"))
                SBTextField(shortcut, { shortcut = it; error = null }, tr("Сокращение (необязательно)"))
                AnimatedBanner(error, Tone.DANGER)
                PrimaryButton(tr("Добавить"), {
                    scope.launch {
                        runCatching { K.addDictionaryWord(word, shortcut, null) }
                            .onSuccess { word = ""; shortcut = ""; reload() }
                            .onFailure { error = tr("Не удалось добавить слово") }
                    }
                }, enabled = word.isNotBlank(), icon = Icons.Rounded.Add)
            }
        }
        if (words.size > 8) item { SBTextField(query, { query = it }, tr("Поиск")) }
        val visible = words.filter { query.isBlank() || it.word.contains(query, true) || it.shortcut.orEmpty().contains(query, true) }
        if (words.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.MenuBook, tr("Словарь пуст"), tr("Добавьте имена, термины и частые фразы.")) }
        items(visible, key = { it.id }) { entry ->
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                ListRow(entry.word, entry.shortcut?.let { tr("Сокращение: {0}", it) }, icon = Icons.Rounded.Abc,
                    trailing = { IconButton(onClick = { scope.launch { K.deleteDictionaryWord(entry.id); reload() } }) { Icon(Icons.Rounded.Delete, tr("Удалить"), tint = palette.textMuted) } })
            }
        }
    }
}
