package com.sboard.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.SBoardKeyboardSettings

private fun openKeyboardEngineSettings(context: Context, path: String) {
    val intent = Intent(context, dev.patrickgold.florisboard.app.FlorisAppActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("ui://florisboard/$path")
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
fun KeyboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = SystemIntegration.isKeyboardEnabled(context)
            selected = SystemIntegration.isKeyboardSelected(context)
        }
    }
    var theme by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.theme() }.getOrDefault("system")) }
    var height by remember { mutableFloatStateOf(runCatching { SBoardKeyboardSettings.heightPercent() }.getOrDefault(100).toFloat()) }
    var sound by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.soundEnabled() }.getOrDefault(true)) }
    var volume by remember { mutableFloatStateOf(runCatching { SBoardKeyboardSettings.soundVolume() }.getOrDefault(50).toFloat()) }
    var vibration by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.vibrationEnabled() }.getOrDefault(true)) }
    var numberRow by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.numberRow() }.getOrDefault(false)) }
    var suggestions by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.suggestions() }.getOrDefault(true)) }
    var smartbar by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.smartbar() }.getOrDefault(true)) }
    var oneHanded by remember { mutableStateOf(runCatching { SBoardKeyboardSettings.oneHanded() }.getOrDefault(false)) }
    var test by remember { mutableStateOf("") }

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
        item { SectionTitle(tr("Внешний вид")) }
        item {
            SBCard {
                Text(tr("Тема клавиатуры"), style = MaterialTheme.typography.titleSmall, color = palette.text)
                SegmentedControl(listOf("system" to tr("Системная"), "light" to tr("Светлая"), "dark" to tr("Тёмная")), theme, {
                    theme = it; scope.launch { SBoardKeyboardSettings.setTheme(it) }
                })
                Text(tr("Высота клавиатуры: {0}%", height.toInt()), style = MaterialTheme.typography.titleSmall, color = palette.text)
                Slider(height, { height = it }, valueRange = 70f..140f, steps = 13,
                    onValueChangeFinished = { scope.launch { SBoardKeyboardSettings.setHeightPercent(height.toInt()) } },
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
                SwitchRow(tr("Ряд цифр"), tr("Отдельный верхний ряд с цифрами"), numberRow, Icons.Rounded.Pin) { numberRow = it; scope.launch { SBoardKeyboardSettings.setNumberRow(it) } }
                SwitchRow(tr("Режим одной руки"), null, oneHanded, Icons.Rounded.PanTool) { oneHanded = it; scope.launch { SBoardKeyboardSettings.setOneHanded(it) } }
            }
        }
        item { SectionTitle(tr("Звук и вибрация")) }
        item {
            SBCard {
                SwitchRow(tr("Звук клавиш"), null, sound, Icons.AutoMirrored.Rounded.VolumeUp) { sound = it; scope.launch { SBoardKeyboardSettings.setSoundEnabled(it) } }
                if (sound) Slider(volume, { volume = it }, valueRange = 1f..100f,
                    onValueChangeFinished = { scope.launch { SBoardKeyboardSettings.setSoundVolume(volume.toInt()) } },
                    colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
                SwitchRow(tr("Вибрация клавиш"), null, vibration, Icons.Rounded.Vibration) { vibration = it; scope.launch { SBoardKeyboardSettings.setVibrationEnabled(it) } }
            }
        }
        item { SectionTitle(tr("Ввод")) }
        item {
            SBCard {
                SwitchRow(tr("Подсказки и исправления"), tr("Предиктивный ввод и исправление опечаток (RU/EN, офлайн)"), suggestions, Icons.Rounded.Spellcheck) {
                    suggestions = it; scope.launch { SBoardKeyboardSettings.setSuggestions(it) }
                }
                SwitchRow(tr("Строка инструментов"), tr("Панель подсказок и быстрых действий над клавиатурой"), smartbar, Icons.Rounded.ViewDay) {
                    smartbar = it; scope.launch { SBoardKeyboardSettings.setSmartbar(it) }
                }
                SwitchRow(tr("sBoard Pass в клавиатуре"), tr("Вставка логинов, email, телефонов, адресов, паролей и OTP из хранилища"), AppSettings.keyboardVaultBar.value, Icons.Rounded.Key) {
                    AppSettings.setKeyboardVaultBar(it)
                }
                SwitchRow(tr("Биометрия перед вставкой секретов"), tr("Пароли, коды и карты вставляются только после подтверждения"), AppSettings.biometricForSensitive.value, Icons.Rounded.Fingerprint) {
                    AppSettings.setBiometricForSensitive(it)
                }
            }
        }
        item { SectionTitle(tr("Дополнительно")) }
        item {
            SBCard(padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                ListRow(tr("Языки и раскладки"), tr("Русский, английский и другие языки"), icon = Icons.Rounded.Language, onClick = { openKeyboardEngineSettings(context, "settings/localization") })
                ListRow(tr("Панель инструментов"), tr("Порядок и состав быстрых действий"), icon = Icons.Rounded.Tune, onClick = { openKeyboardEngineSettings(context, "settings/smartbar") })
                ListRow(tr("Набор текста"), tr("Автозаглавные, пробелы, словарь"), icon = Icons.Rounded.TextFields, onClick = { openKeyboardEngineSettings(context, "settings/typing") })
                ListRow(tr("Жесты и удержание клавиш"), tr("Свайпы, долгое нажатие и курсор"), icon = Icons.Rounded.Gesture, onClick = { openKeyboardEngineSettings(context, "settings/gestures") })
                ListRow(tr("Эмодзи"), tr("История и тон кожи"), icon = Icons.Rounded.EmojiEmotions, onClick = { openKeyboardEngineSettings(context, "settings/media") })
                ListRow(tr("Буфер обмена клавиатуры"), tr("История и синхронизация буфера"), icon = Icons.Rounded.ContentPaste, onClick = { openKeyboardEngineSettings(context, "settings/clipboard") })
            }
        }
        item { Text(tr("Клавиатура основана на открытом движке FlorisBoard (Apache-2.0). Набранный текст обрабатывается локально и никуда не отправляется."), style = MaterialTheme.typography.bodySmall, color = palette.textFaint) }
    }
}
