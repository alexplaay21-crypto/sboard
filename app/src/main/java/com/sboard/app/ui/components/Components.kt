package com.sboard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sboard.app.core.i18n.tr
import com.sboard.app.data.ItemType
import com.sboard.app.ui.theme.SBoardShapes
import com.sboard.app.ui.theme.palette

/** Press feedback: subtle scale-down spring, used by every tappable surface. */
@Composable
fun Modifier.pressable(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.scale(scale)
}

@Composable
fun SBCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = palette.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = color,
        shape = SBoardShapes.large,
        border = if (palette.isDark) null else BorderStroke(1.dp, palette.outline),
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(interaction) else Modifier)
            .clip(SBoardShapes.large)
            .then(if (onClick != null) Modifier.clickable(interaction, LocalIndication(), onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun LocalIndication() = ripple(color = palette.accent)

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = palette.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
        }
        trailing?.invoke()
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад"), tint = palette.text) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = palette.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp))
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = palette.textMuted,
            letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = palette.accent,
                modifier = Modifier.clip(SBoardShapes.small).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = palette.accent, background: Color = palette.accentSoft, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

fun ItemType.icon(): ImageVector = when (this) {
    ItemType.LOGIN -> Icons.Rounded.Key
    ItemType.CARD -> Icons.Rounded.CreditCard
    ItemType.IDENTITY -> Icons.Rounded.Badge
    ItemType.NOTE -> Icons.AutoMirrored.Rounded.StickyNote2
    ItemType.TOTP -> Icons.Rounded.Timer
    ItemType.PASSKEY -> Icons.Rounded.Fingerprint
}

fun ItemType.label(): String = when (this) {
    ItemType.LOGIN -> tr("Пароль")
    ItemType.CARD -> tr("Банковская карта")
    ItemType.IDENTITY -> tr("Личные данные")
    ItemType.NOTE -> tr("Защищённая заметка")
    ItemType.TOTP -> tr("Код 2FA")
    ItemType.PASSKEY -> tr("Passkey")
}

fun ItemType.pluralLabel(): String = when (this) {
    ItemType.LOGIN -> tr("Пароли")
    ItemType.CARD -> tr("Карты")
    ItemType.IDENTITY -> tr("Личные данные")
    ItemType.NOTE -> tr("Заметки")
    ItemType.TOTP -> tr("Аутентификатор")
    ItemType.PASSKEY -> tr("Passkeys")
}

@Composable
fun ItemBadge(type: ItemType, title: String, size: Dp = 42.dp) {
    val tint = when (type) {
        ItemType.LOGIN -> palette.accent
        ItemType.CARD -> Color(0xFF6FA8DC)
        ItemType.IDENTITY -> Color(0xFF8FBF8F)
        ItemType.NOTE -> Color(0xFFC9A3D9)
        ItemType.TOTP -> Color(0xFFE8A06A)
        ItemType.PASSKEY -> Color(0xFF7FC8C8)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = if (palette.isDark) 0.16f else 0.14f)),
        contentAlignment = Alignment.Center) {
        if (type == ItemType.LOGIN && title.isNotBlank()) {
            Text(title.trim().take(1).uppercase(), color = tint, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        } else {
            Icon(type.icon(), null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = { Icon(Icons.Rounded.ChevronRight, null, tint = palette.textFaint) },
    titleColor: Color = palette.text,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(SBoardShapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(12.dp)) }
            icon != null -> { IconBadge(icon, size = 36.dp); Spacer(Modifier.width(12.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null && onClick != null) trailing() else if (trailing != null && onClick == null) trailing()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title, subtitle = subtitle, icon = icon,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.onAccent, checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.textMuted, uncheckedTrackColor = palette.surfaceHighest,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
        onClick = if (enabled) ({ onChange(!checked) }) else null,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled && !loading, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(50.dp).pressable(interaction), shape = SBoardShapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent,
            disabledContainerColor = palette.surfaceHighest, disabledContentColor = palette.textFaint),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.onAccent)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, danger: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val color = if (danger) palette.danger else palette.text
    FilledTonalButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().height(48.dp).pressable(interaction), shape = SBoardShapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.surfaceHigh, contentColor = color,
            disabledContainerColor = palette.surfaceHigh, disabledContentColor = palette.textFaint),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(19.dp), tint = if (danger) palette.danger else palette.accent); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SBTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    monospace: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, minLines = minLines, isError = isError,
        shape = SBoardShapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (monospace || (password && reveal)) FontFamily.Monospace else FontFamily.Default),
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = if (onDone != null) ImeAction.Done else imeAction,
            autoCorrectEnabled = !password && keyboardType == KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = when {
            trailing != null -> trailing
            password -> ({
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (reveal) tr("Скрыть") else tr("Показать"), tint = palette.textMuted)
                }
            })
            else -> null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.accent, unfocusedBorderColor = palette.outline,
            focusedLabelColor = palette.accent, cursorColor = palette.accent,
            focusedContainerColor = palette.surface, unfocusedContainerColor = palette.surface,
        ),
    )
}

@Composable
fun SegmentedControl(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(SBoardShapes.medium).background(palette.surfaceHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bg by animateColorAsState(if (active) palette.surface else Color.Transparent, tween(180), label = "seg")
            val fg by animateColorAsState(if (active) palette.text else palette.textMuted, tween(180), label = "segfg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg).clickable { onSelect(value) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun FilterChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val (value, label) = options[index]
            val active = value == selected
            val bg by animateColorAsState(if (active) palette.accent else palette.surfaceHigh, tween(180), label = "chip")
            val fg by animateColorAsState(if (active) palette.onAccent else palette.text, tween(180), label = "chipfg")
            Box(Modifier.clip(SBoardShapes.pill).background(bg).clickable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(palette.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = palette.accent, modifier = Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = palette.text, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(action, onAction, Modifier.widthIn(max = 260.dp))
        }
    }
}

enum class Tone { INFO, SUCCESS, WARNING, DANGER }

@Composable
fun Banner(text: String, tone: Tone = Tone.INFO, icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val color = when (tone) { Tone.INFO -> palette.accent; Tone.SUCCESS -> palette.success; Tone.WARNING -> palette.warning; Tone.DANGER -> palette.danger }
    Row(Modifier.fillMaxWidth().clip(SBoardShapes.medium).background(color.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon ?: when (tone) { Tone.SUCCESS -> Icons.Rounded.CheckCircle; Tone.DANGER -> Icons.Rounded.ErrorOutline; Tone.WARNING -> Icons.Rounded.WarningAmber; else -> Icons.Rounded.Info },
            null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = color,
                modifier = Modifier.clip(SBoardShapes.small).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun AnimatedBanner(text: String?, tone: Tone = Tone.INFO) {
    AnimatedVisibility(text != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        if (text != null) Banner(text, tone)
    }
}

@Composable
fun StatusDot(color: Color) { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }

@Composable
fun StatusLine(title: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color); Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = palette.text, textAlign = TextAlign.End)
    }
}

@Composable
fun StrengthBar(score: Int, label: String? = null) {
    val colors = listOf(palette.danger, palette.danger, palette.warning, palette.success, palette.success)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                val active = score > i
                val c by animateColorAsState(if (active) colors[score.coerceIn(0, 4)] else palette.surfaceHighest, tween(250), label = "str")
                Box(Modifier.weight(1f).height(5.dp).clip(SBoardShapes.pill).background(c))
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = colors[score.coerceIn(0, 4)])
    }
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false, dismiss: String = tr("Отмена"),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = SBoardShapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) palette.danger else palette.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = palette.textMuted) } },
    )
}

@Composable
fun Divider() = HorizontalDivider(color = palette.outline, thickness = 0.8.dp)

/** Standard scrollable screen body with comfortable gutters and room for the floating nav bar. */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 120.dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
