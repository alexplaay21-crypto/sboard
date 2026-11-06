package com.sboard.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sboard.app.core.i18n.tr
import com.sboard.app.core.security.SensitiveClipboard
import com.sboard.app.ui.theme.palette
import kotlinx.coroutines.delay

/** Read-only field with copy and (for secrets) reveal actions. Secrets are masked by default. */
@Composable
fun FieldRow(label: String, value: String, sensitive: Boolean = false, monospace: Boolean = false, onOpen: (() -> Unit)? = null) {
    if (value.isBlank()) return
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
            AnimatedContent(sensitive && !revealed, label = "mask") { masked ->
                Text(
                    if (masked) "•".repeat(value.length.coerceIn(8, 14)) else value,
                    style = MaterialTheme.typography.bodyLarge, color = palette.text,
                    fontFamily = if (monospace || (sensitive && !masked)) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
        if (sensitive) IconButton(onClick = { revealed = !revealed }) {
            Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (revealed) tr("Скрыть") else tr("Показать"), tint = palette.textMuted)
        }
        if (onOpen != null) IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, tr("Открыть"), tint = palette.textMuted) }
        IconButton(onClick = { SensitiveClipboard.copy(context, value, sensitive); copied = true }) {
            Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, tr("Скопировать"), tint = if (copied) palette.success else palette.accent)
        }
    }
}
