package com.sboard.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sboard.app.core.i18n.tr
import com.sboard.app.ui.Navigator
import com.sboard.app.ui.components.*
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.launch
import org.sboard.keyboard.KeyStyle
import org.sboard.keyboard.KeyboardThemeSpec
import org.sboard.keyboard.SBoardKeyboardSettings
import org.sboard.keyboard.SBoardThemeStore

private enum class Part { BACKGROUND, TOOLBAR, TOOLBAR_ICONS, KEYS, KEY_TEXT, HINTS, FUNCTION_KEYS, FUNCTION_TEXT, ACCENT, BORDER }

private fun Part.label() = when (this) {
    Part.BACKGROUND -> tr("Фон"); Part.TOOLBAR -> tr("Панель инструментов"); Part.TOOLBAR_ICONS -> tr("Значки панели")
    Part.KEYS -> tr("Клавиши"); Part.KEY_TEXT -> tr("Буквы"); Part.HINTS -> tr("Символы на клавишах")
    Part.FUNCTION_KEYS -> tr("Служебные клавиши"); Part.FUNCTION_TEXT -> tr("Значки служебных клавиш")
    Part.ACCENT -> tr("Акцент и Enter"); Part.BORDER -> tr("Контур и подсветка")
}

private fun KeyboardThemeSpec.get(p: Part): Long = when (p) {
    Part.BACKGROUND -> background; Part.TOOLBAR -> toolbarBackground; Part.TOOLBAR_ICONS -> toolbarIcons
    Part.KEYS -> keyBackground; Part.KEY_TEXT -> keyText; Part.HINTS -> hintText
    Part.FUNCTION_KEYS -> functionKeyBackground; Part.FUNCTION_TEXT -> functionKeyText; Part.ACCENT -> accent; Part.BORDER -> keyBorder
}

private fun KeyboardThemeSpec.set(p: Part, c: Long): KeyboardThemeSpec = when (p) {
    Part.BACKGROUND -> copy(background = c); Part.TOOLBAR -> copy(toolbarBackground = c); Part.TOOLBAR_ICONS -> copy(toolbarIcons = c)
    Part.KEYS -> copy(keyBackground = c); Part.KEY_TEXT -> copy(keyText = c); Part.HINTS -> copy(hintText = c)
    Part.FUNCTION_KEYS -> copy(functionKeyBackground = c); Part.FUNCTION_TEXT -> copy(functionKeyText = c)
    Part.ACCENT -> copy(accent = c); Part.BORDER -> copy(keyBorder = c)
}

private fun col(c: Long) = Color(c.toInt())

/** Groups of related elements: tapping a region of the preview selects its group. */
private val groups = listOf(
    listOf(Part.KEYS, Part.KEY_TEXT, Part.HINTS),
    listOf(Part.FUNCTION_KEYS, Part.FUNCTION_TEXT),
    listOf(Part.TOOLBAR, Part.TOOLBAR_ICONS),
    listOf(Part.ACCENT), listOf(Part.BORDER), listOf(Part.BACKGROUND),
)
private fun groupOf(p: Part) = groups.first { p in it }

/** Keyboard theme editor in the spirit of Samsung Theme Park: tap any part of the preview and recolour it. */
@Composable
fun KeyboardThemeEditor(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var night by remember { mutableStateOf(systemDark) }
    var spec by remember(night) { mutableStateOf(SBoardThemeStore.load(context, night)) }
    var part by remember { mutableStateOf(Part.KEYS) }
    var tab by remember { mutableStateOf("color") }
    var confirmReset by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    // Changes are applied to the keyboard automatically, shortly after the last edit.
    LaunchedEffect(spec, night) {
        if (!dirty) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        SBoardThemeStore.save(context, night, spec)
        runCatching { SBoardKeyboardSettings.useCustomThemes() }
    }
    fun update(newSpec: KeyboardThemeSpec) { spec = newSpec; dirty = true }

    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Тема клавиатуры"), { nav.pop() }) {
            IconButton(onClick = { confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, tr("Сбросить"), tint = palette.textMuted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedControl(listOf("night" to tr("Тёмная тема"), "day" to tr("Светлая тема")), if (night) "night" else "day", { night = it == "night"; dirty = false })
            KeyboardPreview(spec, part) { tapped -> part = tapped; tab = "color" }
            Text(tr("Нажмите на любой элемент клавиатуры, чтобы изменить его. Изменения применяются сразу."),
                style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Part.entries.toList()) { p ->
                    val active = p == part
                    Row(Modifier.clip(SBoardShapes.pill).background(if (active) palette.accent else palette.surfaceHigh)
                        .clickable { part = p; tab = "color" }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(col(spec.get(p))).border(1.dp, palette.outline, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(p.label(), style = MaterialTheme.typography.labelLarge, color = if (active) palette.onAccent else palette.text)
                    }
                }
            }
        }
        Surface(color = palette.surface, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedControl(listOf("color" to tr("Цвет"), "style" to tr("Стиль клавиш"), "size" to tr("Размеры")), tab, { tab = it })
                when (tab) {
                    "color" -> {
                        val group = groupOf(part)
                        if (group.size > 1) SegmentedControl(group.map { it.name to it.label() }, part.name, { part = Part.valueOf(it) })
                        else Text(part.label(), style = MaterialTheme.typography.titleSmall, color = palette.text)
                        ColorPicker(spec.get(part), { update(spec.set(part, it)) })
                    }
                    "style" -> KeyStylePicker(spec) { update(it) }
                    else -> SizePicker(spec) { update(it) }
                }
            }
        }
    }
    if (confirmReset) ConfirmDialog(tr("Сбросить тему?"), tr("Цвета и стиль вернутся к стандартным."), tr("Сбросить"),
        onConfirm = { update(if (night) KeyboardThemeSpec.NIGHT else KeyboardThemeSpec.DAY); confirmReset = false },
        onDismiss = { confirmReset = false })
}

@Composable
private fun SizePicker(spec: KeyboardThemeSpec, onChange: (KeyboardThemeSpec) -> Unit) {
    val sliderColors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
    Text(tr("Значки панели инструментов: {0} sp", spec.toolbarIconSize), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
    Slider(spec.toolbarIconSize.toFloat(), { onChange(spec.copy(toolbarIconSize = it.toInt())) }, valueRange = 18f..32f, colors = sliderColors)
    Text(tr("Главные клавиши (Shift, ⌫, Enter): {0}%", spec.functionKeyScale), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
    Slider(spec.functionKeyScale.toFloat(), { onChange(spec.copy(functionKeyScale = it.toInt())) }, valueRange = 90f..140f, colors = sliderColors)
    Text(tr("Размер букв: {0}%", spec.fontScale), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
    Slider(spec.fontScale.toFloat(), { onChange(spec.copy(fontScale = it.toInt())) }, valueRange = 70f..130f, colors = sliderColors)
    SegmentedControl(listOf("normal" to tr("Обычный"), "medium" to tr("Средний"), "bold" to tr("Жирный")), spec.fontWeight, { onChange(spec.copy(fontWeight = it)) })
    SwitchRow(tr("Символы на клавишах"), tr("Маленькие подсказки для удержания"), spec.showHints, Icons.Rounded.Tag) { onChange(spec.copy(showHints = it)) }
}

@Composable
private fun KeyStylePicker(spec: KeyboardThemeSpec, onChange: (KeyboardThemeSpec) -> Unit) {
    val styles = listOf(KeyStyle.ROUNDED to tr("Скруглённые"), KeyStyle.SQUARE to tr("Квадратные"), KeyStyle.CAPSULE to tr("Капсулы"),
        KeyStyle.OUTLINE to tr("Контур"), KeyStyle.UNDERLINE to tr("Подсветка"), KeyStyle.FLAT to tr("Без фона"))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(styles) { (style, label) ->
            val active = spec.keyStyle == style
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(spec.copy(keyStyle = style)) }) {
                Box(Modifier.size(64.dp).clip(SBoardShapes.medium).background(col(spec.background))
                    .border(if (active) 2.dp else 1.dp, if (active) palette.accent else palette.outline, SBoardShapes.medium), contentAlignment = Alignment.Center) {
                    PreviewKey("A", spec, style = style, width = 40.dp, height = 40.dp)
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) palette.accent else palette.textMuted)
            }
        }
    }
    if (spec.keyStyle != KeyStyle.SQUARE && spec.keyStyle != KeyStyle.CAPSULE) {
        Text(tr("Скругление: {0} dp", spec.cornerRadius), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
        Slider(spec.cornerRadius.toFloat(), { onChange(spec.copy(cornerRadius = it.toInt())) }, valueRange = 0f..20f,
            colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent))
    }
}

private fun keyShape(spec: KeyboardThemeSpec, style: KeyStyle): Shape = when (style) {
    KeyStyle.SQUARE -> RoundedCornerShape(3.dp)
    KeyStyle.CAPSULE -> RoundedCornerShape(50)
    else -> RoundedCornerShape(spec.cornerRadius.dp)
}

@Composable
private fun PreviewKey(
    label: String, spec: KeyboardThemeSpec, hint: String? = null, function: Boolean = false, accent: Boolean = false,
    icon: ImageVector? = null, style: KeyStyle = spec.keyStyle, width: Dp? = null, height: Dp = 34.dp,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
) {
    val shape = keyShape(spec, style)
    val bg = when {
        accent -> col(spec.accent)
        style == KeyStyle.FLAT && !accent -> Color.Transparent
        function -> col(spec.functionKeyBackground)
        else -> col(spec.keyBackground)
    }
    val fg = when {
        accent -> col(SBoardThemeStore.onColor(spec.accent))
        function -> col(spec.functionKeyText)
        else -> col(spec.keyText)
    }
    val animatedBg by animateColorAsState(bg, label = "kbg")
    Box(
        (if (width != null) modifier.width(width) else modifier).height(height)
            .then(if (style == KeyStyle.UNDERLINE) Modifier.shadow(4.dp, shape, ambientColor = col(spec.keyBorder), spotColor = col(spec.keyBorder)) else Modifier)
            .clip(shape).background(animatedBg)
            .then(if (style == KeyStyle.OUTLINE) Modifier.border(1.dp, col(spec.keyBorder), shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        else Text(label, color = fg, fontSize = (16 * spec.fontScale / 100).sp, maxLines = 1,
            fontWeight = when (spec.fontWeight) { "bold" -> androidx.compose.ui.text.font.FontWeight.Bold; "medium" -> androidx.compose.ui.text.font.FontWeight.Medium; else -> androidx.compose.ui.text.font.FontWeight.Normal })
        if (hint != null && spec.showHints) Text(hint, color = col(spec.hintText), fontSize = 8.sp, modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp, top = 1.dp))
    }
}

/** Interactive preview: every region opens the colour picker for that element. */
@Composable
private fun KeyboardPreview(spec: KeyboardThemeSpec, selected: Part, onSelect: (Part) -> Unit) {
    val glow by animateColorAsState(palette.accent, label = "sel")
    Column(
        Modifier.fillMaxWidth().clip(SBoardShapes.large).background(col(spec.background))
            .border(2.dp, if (selected == Part.BACKGROUND) glow else Color.Transparent, SBoardShapes.large)
            .clickable(remember { MutableInteractionSource() }, null) { onSelect(Part.BACKGROUND) }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(col(spec.toolbarBackground))
            .border(1.5.dp, if (selected == Part.TOOLBAR || selected == Part.TOOLBAR_ICONS) glow else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { onSelect(if (selected in groupOf(Part.TOOLBAR)) selected else Part.TOOLBAR) }.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            listOf(Icons.Rounded.Key, Icons.Rounded.EmojiEmotions, Icons.Rounded.ContentPaste, Icons.Rounded.Translate, Icons.Rounded.Mic, Icons.Rounded.MoreHoriz).forEach {
                Icon(it, null, tint = col(spec.toolbarIcons), modifier = Modifier.size((spec.toolbarIconSize - 4).dp).clickable { onSelect(Part.TOOLBAR_ICONS) })
            }
        }
        val rows = listOf(
            "1234567890".map { it.toString() to null },
            listOf("й" to "+", "ц" to "×", "у" to "÷", "к" to "=", "е" to "ё", "н" to "/", "г" to "_", "ш" to "<", "щ" to ">", "з" to "[", "х" to "]"),
            listOf("ф" to "!", "ы" to "@", "в" to "#", "а" to "₽", "п" to "%", "р" to "^", "о" to "&", "л" to "*", "д" to "(", "ж" to ")", "э" to "~"),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { (l, h) ->
                    PreviewKey(l, spec, hint = h, modifier = Modifier.weight(1f), onClick = { onSelect(if (selected in groupOf(Part.KEYS)) selected else Part.KEYS) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PreviewKey("", spec, function = true, icon = Icons.Rounded.KeyboardCapslock, modifier = Modifier.weight(1.5f), onClick = { onSelect(Part.FUNCTION_KEYS) })
            listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю").forEach { PreviewKey(it, spec, modifier = Modifier.weight(1f), onClick = { onSelect(Part.KEYS) }) }
            PreviewKey("", spec, function = true, icon = Icons.Rounded.Backspace, modifier = Modifier.weight(1.5f), onClick = { onSelect(Part.FUNCTION_TEXT) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PreviewKey("!#1", spec, function = true, modifier = Modifier.weight(1.4f), onClick = { onSelect(Part.FUNCTION_KEYS) })
            PreviewKey("", spec, function = true, icon = Icons.Rounded.Mic, modifier = Modifier.weight(1f), onClick = { onSelect(Part.FUNCTION_TEXT) })
            PreviewKey(",", spec, modifier = Modifier.weight(1f), onClick = { onSelect(Part.KEYS) })
            PreviewKey("Русский", spec, modifier = Modifier.weight(4f), onClick = { onSelect(Part.BORDER) })
            PreviewKey(".", spec, modifier = Modifier.weight(1f), onClick = { onSelect(Part.KEYS) })
            PreviewKey("", spec, accent = true, icon = Icons.AutoMirrored.Rounded.KeyboardReturn, modifier = Modifier.weight(1.4f), onClick = { onSelect(Part.ACCENT) })
        }
    }
}
