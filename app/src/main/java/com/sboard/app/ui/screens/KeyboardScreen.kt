package com.sboard.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.settings.AppSettings
import com.sboard.app.feature.keyboard.SystemIntegration
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.SBoardKeyboardSettings as K

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, onFinished: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Text("$title: ${label(v)}", style = MaterialTheme.typography.titleSmall, color = palette.text)
    Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onFinished(v) },
        colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
}

@Composable
fun KeyboardScreen(nav: com.sboard.app.ui.Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf<List<K.Language>>(emptyList()) }
    var showAllLanguages by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = SystemIntegration.isKeyboardEnabled(context)
            selected = SystemIntegration.isKeyboardSelected(context)
            languages = runCatching { K.languages(context) }.getOrDefault(emptyList())
        }
    }
    fun <T> pref(get: () -> T, default: T) = runCatching(get).getOrDefault(default)
    var themeMode by remember { mutableStateOf(pref(K::theme, "system")) }
    var sound by remember { mutableStateOf(pref(K::soundEnabled, true)) }
    var vibration by remember { mutableStateOf(pref(K::vibrationEnabled, true)) }
    var numberRow by remember { mutableStateOf(pref(K::numberRow, true)) }
    var symbolHints by remember { mutableStateOf(pref(K::symbolHints, true)) }
    var suggestions by remember { mutableStateOf(pref(K::suggestions, true)) }
    var swipe by remember { mutableStateOf(pref(K::swipeTyping, true)) }
    var swipeTrail by remember { mutableStateOf(pref(K::swipeTrail, true)) }
    var autoCaps by remember { mutableStateOf(pref(K::autoCapitalization, true)) }
    var doubleSpace by remember { mutableStateOf(pref(K::doubleSpacePeriod, true)) }
    var autoSpace by remember { mutableStateOf(pref(K::autoSpacePunctuation, false)) }
    var keyPopup by remember { mutableStateOf(pref(K::keyPopup, true)) }
    var emojiSuggest by remember { mutableStateOf(pref(K::emojiSuggestions, true)) }
    var clipHistory by remember { mutableStateOf(pref(K::clipboardHistory, true)) }
    var smartbar by remember { mutableStateOf(pref(K::smartbar, true)) }
    var oneHanded by remember { mutableStateOf(pref(K::oneHanded, false)) }
    var test by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

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
        message?.let { item { Banner(it, Tone.INFO) } }

        item { SectionTitle(tr("Языки и раскладки")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                val visible = if (showAllLanguages) languages else languages.filter { it.enabled }
                if (languages.isEmpty()) Text(tr("Список языков появится после первого запуска клавиатуры."), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                visible.forEach { lang ->
                    SwitchRow(lang.name, lang.tag, lang.enabled, Icons.Rounded.Language) { on ->
                        if (!K.setLanguageEnabled(context, lang.tag, on)) message = tr("Должен остаться хотя бы один язык")
                        else message = null
                        languages = runCatching { K.languages(context) }.getOrDefault(languages)
                    }
                }
                TextButton(onClick = { showAllLanguages = !showAllLanguages }) {
                    Text(if (showAllLanguages) tr("Показать только включённые") else tr("Добавить язык"), color = palette.accent)
                }
                Text(tr("Переключение языка — кнопка с глобусом или свайп по пробелу."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }

        item { SectionTitle(tr("Стиль и тема")) }
        item {
            SBCard {
                SegmentedControl(listOf("system" to tr("Системная"), "light" to tr("Светлая"), "dark" to tr("Тёмная")), themeMode, {
                    themeMode = it; scope.launch { K.setTheme(it) }
                })
                PrimaryButton(tr("Редактор темы"), { nav.push(com.sboard.app.ui.Route.KeyboardTheme) }, icon = Icons.Rounded.Palette)
                Text(tr("Меняйте цвет фона, клавиш, букв, символов, панели и акцента, форму клавиш и размер букв — отдельно для светлой и тёмной темы."),
                    style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                SliderRow(tr("Расстояние между клавишами"), pref(K::keySpacing, 2f), 0f..6f, { "%.1f".format(it) }) { scope.launch { K.setKeySpacing(it) } }
            }
        }

        item { SectionTitle(tr("Размер и раскладка")) }
        item {
            SBCard {
                SliderRow(tr("Высота клавиатуры"), pref(K::heightPercent, 100).toFloat(), 70f..140f, { "${it.toInt()}%" }) { scope.launch { K.setHeightPercent(it.toInt()) } }
                SliderRow(tr("Высота в альбомной ориентации и играх"), pref(K::landscapeHeightPercent, 78).toFloat(), 55f..120f, { "${it.toInt()}%" }) { scope.launch { K.setLandscapeHeightPercent(it.toInt()) } }
                SwitchRow(tr("Ряд цифр"), tr("Отдельный верхний ряд с цифрами"), numberRow, Icons.Rounded.Pin) { numberRow = it; scope.launch { K.setNumberRow(it) } }
                SwitchRow(tr("Символы на клавишах"), tr("Удержание буквы вводит символ над ней, как на Samsung"), symbolHints, Icons.Rounded.Tag) { symbolHints = it; scope.launch { K.setSymbolHints(it) } }
                SwitchRow(tr("Режим одной руки"), null, oneHanded, Icons.Rounded.PanTool) { oneHanded = it; scope.launch { K.setOneHanded(it) } }
            }
        }

        item { SectionTitle(tr("Ввод")) }
        item {
            SBCard {
                SwitchRow(tr("Предиктивный ввод"), tr("Подсказки слов и исправление опечаток (RU/EN, офлайн)"), suggestions, Icons.Rounded.Spellcheck) { suggestions = it; scope.launch { K.setSuggestions(it) } }
                SwitchRow(tr("Непрерывный ввод (свайп)"), tr("Ведите пальцем по буквам — получится слово"), swipe, Icons.Rounded.Gesture) { swipe = it; scope.launch { K.setSwipeTyping(it) } }
                if (swipe) SwitchRow(tr("След пальца"), null, swipeTrail, Icons.Rounded.Timeline) { swipeTrail = it; scope.launch { K.setSwipeTrail(it) } }
                SwitchRow(tr("Заглавная в начале предложения"), null, autoCaps, Icons.Rounded.TextFields) { autoCaps = it; scope.launch { K.setAutoCapitalization(it) } }
                SwitchRow(tr("Точка двойным пробелом"), null, doubleSpace, Icons.Rounded.SpaceBar) { doubleSpace = it; scope.launch { K.setDoubleSpacePeriod(it) } }
                SwitchRow(tr("Пробел после знаков препинания"), null, autoSpace, Icons.Rounded.FormatTextdirectionLToR) { autoSpace = it; scope.launch { K.setAutoSpacePunctuation(it) } }
                SwitchRow(tr("Увеличение клавиши при нажатии"), null, keyPopup, Icons.Rounded.ZoomIn) { keyPopup = it; scope.launch { K.setKeyPopup(it) } }
                SwitchRow(tr("Подсказки эмодзи"), null, emojiSuggest, Icons.Rounded.EmojiEmotions) { emojiSuggest = it; scope.launch { K.setEmojiSuggestions(it) } }
                SliderRow(tr("Задержка удержания"), pref(K::longPressDelay, 300).toFloat(), 150f..800f, { tr("{0} мс", it.toInt()) }) { scope.launch { K.setLongPressDelay(it.toInt()) } }
            }
        }

        item { SectionTitle(tr("Звук и вибрация")) }
        item {
            SBCard {
                SwitchRow(tr("Звук клавиш"), null, sound, Icons.AutoMirrored.Rounded.VolumeUp) { sound = it; scope.launch { K.setSoundEnabled(it) } }
                if (sound) SliderRow(tr("Громкость"), pref(K::soundVolume, 50).toFloat(), 1f..100f, { "${it.toInt()}%" }) { scope.launch { K.setSoundVolume(it.toInt()) } }
                SwitchRow(tr("Вибрация клавиш"), null, vibration, Icons.Rounded.Vibration) { vibration = it; scope.launch { K.setVibrationEnabled(it) } }
            }
        }

        item { SectionTitle(tr("Панель инструментов")) }
        item {
            SBCard {
                SwitchRow(tr("Строка инструментов"), tr("Подсказки и быстрые действия: эмодзи, sBoard Pass, буфер, переводчик, голос, одна рука"), smartbar, Icons.Rounded.ViewDay) { smartbar = it; scope.launch { K.setSmartbar(it) } }
                SwitchRow(tr("sBoard Pass в клавиатуре"), tr("Вставка логинов, email, телефонов, адресов, паролей и OTP из хранилища"), AppSettings.keyboardVaultBar.value, Icons.Rounded.Key) { AppSettings.setKeyboardVaultBar(it) }
                SwitchRow(tr("Биометрия перед вставкой секретов"), tr("Пароли, коды и карты вставляются только после подтверждения"), AppSettings.biometricForSensitive.value, Icons.Rounded.Fingerprint) { AppSettings.setBiometricForSensitive(it) }
                SwitchRow(tr("История буфера обмена"), null, clipHistory, Icons.Rounded.ContentPaste) { clipHistory = it; scope.launch { K.setClipboardHistory(it) } }
                SecondaryButton(tr("Сбросить панель инструментов"), { scope.launch { K.resetToolbar(); message = tr("Порядок кнопок восстановлен") } }, icon = Icons.Rounded.RestartAlt)
                Text(tr("Чтобы изменить порядок кнопок, нажмите «⋯» на панели клавиатуры и выберите «Изменить»."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint)
            }
        }
        item { Text(tr("Клавиатура основана на открытом движке FlorisBoard (Apache-2.0). Набранный текст обрабатывается локально и никуда не отправляется."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
    }
}
